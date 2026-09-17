package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemReader
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading rings through their neighbours' glow, at the exposure the app runs at.
 *
 * The run of 2026-09-04 was the first at the 1/250 s preset in the room, and it worked
 * up to a point: every gem read, nothing clipped, the frame measured 1-2% washed out,
 * and one of three targets was found. The other two were not, and both had the same
 * shape -- a centre dot of a different colour from the ring around it.
 *
 * At this exposure the LEDs resolve as individual dots and the panel between them is
 * black, but each ring still throws a glow over the ring inside it, and light adds. A
 * red dot under a green ring is not red in the image, it is red plus green, which is
 * orange: measured at 26-28 degrees, against a red/yellow boundary at 29. Every red ring
 * under a green one was a coin flip and read with a confidence of 0.03; every green
 * centre under a red ring read yellow; the one gem on the wall with a genuinely yellow
 * ring, under a red one, read red. Against 56 gems labelled by eye the reader scored 79%,
 * with the outer ring perfect and every single miss a mixture of two neighbours.
 *
 * The fix is to take the glow back out before reading: the gap between two rings has
 * no LEDs in it, so its colour is the spill, and subtracting a measured fraction of it
 * from the ring inside lifts the same frame to 97%. The subtraction is capped so no
 * channel can lose more than half of itself, which is what keeps a colour that shares a
 * channel with the glow over it -- this wall's cyan under purple -- from being unmixed
 * into something else.
 */
class GemWashTest {

    private fun colourOf(name: String): Int = when (name) {
        "red" -> GemColour.RED
        "yellow" -> GemColour.YELLOW
        "green" -> GemColour.GREEN
        "blue" -> GemColour.BLUE
        "purple" -> GemColour.PURPLE
        else -> GemColour.UNKNOWN
    }

    private class Tally(var right: Int = 0, var total: Int = 0) {
        val accuracy: Float get() = if (total == 0) 0f else right.toFloat() / total
    }

    private fun score(): Triple<Tally, Tally, Tally> {
        val view = GemFixture.loadPreset()
        val reader = GemReader()
        val outer = Tally()
        val middle = Tally()
        val centre = Tally()
        for (gem in GemFixture.PRESET_LABELLED) {
            val read = reader.readAt(view, gem.x, gem.y, GemFixture.PRESET_PITCH).pattern
            outer.total++; if (read.outer == colourOf(gem.outer)) outer.right++
            middle.total++; if (read.middle == colourOf(gem.middle)) middle.right++
            gem.centre?.let { centre.total++; if (read.centre == colourOf(it)) centre.right++ }
        }
        return Triple(outer, middle, centre)
    }

    @Test
    fun `the outer ring is read exactly, as it always was`() {
        val (outer, _, _) = score()
        assertEquals("outer ring misreads", outer.total, outer.right)
    }

    /**
     * Before the glow was subtracted these two rings read 43 of 56 and 33 of 56 on the
     * full frame. The bar is set below the measured figure so that a change which trades
     * a ring here for something elsewhere is a judgement rather than a red build.
     */
    @Test
    fun `the inner rings are read through the glow of the ring outside them`() {
        val (_, middle, centre) = score()
        assertTrue(
            "middle ring: ${middle.right} of ${middle.total} (${(middle.accuracy * 100).toInt()}%)",
            middle.accuracy >= 0.92f,
        )
        assertTrue(
            "centre dot: ${centre.right} of ${centre.total} (${(centre.accuracy * 100).toInt()}%)",
            centre.accuracy >= 0.85f,
        )
    }

    /**
     * The three misreads that stopped targets matching, stated as the gems that had them.
     * Each of these is a pattern that appears on this wall and that the two unmatched
     * targets -- red/red/green and red/green/red -- are built from.
     */
    @Test
    fun `a red dot under a green ring is red, not yellow`() {
        val view = GemFixture.loadPreset()
        val reader = GemReader()
        var seen = 0
        var right = 0
        for (gem in GemFixture.PRESET_LABELLED) {
            if (gem.middle != "green" || gem.centre != "red") continue
            seen++
            val read = reader.readAt(view, gem.x, gem.y, GemFixture.PRESET_PITCH).pattern
            if (read.centre == GemColour.RED) right++
        }
        assertTrue("the fixture must hold this case", seen >= 5)
        assertTrue("$right of $seen red-under-green centres read red", right >= seen - 1)
    }

    @Test
    fun `a red ring under a green ring is red, not yellow`() {
        val view = GemFixture.loadPreset()
        val reader = GemReader()
        var seen = 0
        var right = 0
        for (gem in GemFixture.PRESET_LABELLED) {
            if (gem.outer != "green" || gem.middle != "red") continue
            seen++
            val read = reader.readAt(view, gem.x, gem.y, GemFixture.PRESET_PITCH).pattern
            if (read.middle == GemColour.RED) right++
        }
        assertTrue("the fixture must hold this case", seen >= 8)
        assertEquals("$right of $seen red-under-green middle rings read red", seen, right)
    }

    @Test
    fun `a green dot under a red ring is green, not yellow`() {
        val view = GemFixture.loadPreset()
        val reader = GemReader()
        var seen = 0
        var right = 0
        for (gem in GemFixture.PRESET_LABELLED) {
            if (gem.middle != "red" || gem.centre != "green") continue
            seen++
            val read = reader.readAt(view, gem.x, gem.y, GemFixture.PRESET_PITCH).pattern
            if (read.centre == GemColour.GREEN) right++
        }
        assertTrue("the fixture must hold this case", seen >= 5)
        assertTrue("$right of $seen green-under-red centres read green", right >= seen - 1)
    }

    /**
     * The whole scanner over the frame, and the thing the run was actually for: the one
     * gem that matched must still match, and the reading must not have swung to a new
     * kind of wrong. Before the fix the middle ring came back yellow for 17% of the wall
     * and the centre for 35%; there is no yellow ring on this wall bar one.
     */
    @Test
    fun `the scanner no longer sees a wall of yellow`() {
        val targets = GemTargets()
        targets.set(0, GemPattern(GemColour.RED, GemColour.GREEN, GemColour.GREEN))
        val result = GemScanner(targets).scan(GemFixture.loadPreset())
        assertTrue("found ${result.blobCount} gems", result.blobCount >= 30)
        val yellowMiddles = result.gems.count { it.pattern.middle == GemColour.YELLOW }
        val yellowCentres = result.gems.count { it.pattern.centre == GemColour.YELLOW }
        assertTrue("$yellowMiddles yellow middle rings of ${result.gems.size}", yellowMiddles <= 2)
        assertTrue("$yellowCentres yellow centres of ${result.gems.size}", yellowCentres <= 3)
        assertEquals("the red/green/green gem must still match target 1", 1, result.matchCount)
    }
}
