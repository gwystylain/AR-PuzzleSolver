package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargetStrip
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loading the targets off the wall, against the frame where the user typed them in.
 *
 * The real-frame half runs the whole thing the phone does: scan the frame, turn it
 * upright the way a portrait phone does, keep what lands in the slit. The ground truth is
 * what the user entered by hand that round, which found every gem -- so a load that
 * agrees with it is a load that would have found every gem too.
 *
 * The synthetic half pins the refusals, each of which stands between a slit aimed a
 * little wrong and a round spent hunting for gems that are not the targets.
 */
class GemTargetStripTest {

    private fun colourOf(name: String): Int = when (name) {
        "red" -> GemColour.RED
        "yellow" -> GemColour.YELLOW
        "green" -> GemColour.GREEN
        "blue" -> GemColour.BLUE
        else -> GemColour.UNKNOWN
    }

    private fun pattern(spec: String): GemPattern {
        val (o, m, c) = spec.split('/').map { colourOf(it) }
        return GemPattern(o, m, c)
    }

    private class Scan(val spots: List<GemTargetStrip.Spot>, val pitch: Float, val screenWidth: Float)

    /**
     * Scans the fixture and puts every gem where a portrait phone would show it: a
     * quarter turn clockwise, so the image's left edge becomes the top of the screen and
     * its bottom edge the left. At unit scale, which keeps every ratio to the pitch.
     */
    private fun scanUpright(view: CanvasView): Scan {
        val result = GemScanner(GemTargets()).scan(view)
        val h = view.height
        val spots = result.gems.map { GemTargetStrip.Spot(h - 1 - it.y, it.x, it.pattern) }
        return Scan(spots, result.pitch, h.toFloat())
    }

    /** A slit across the whole screen, centred on screen row [y]. */
    private fun slitAt(y: Float, height: Float = 90f, width: Float = 1080f) =
        GemTargetStrip.Box(0f, y - height / 2f, width, y + height / 2f)

    @Test
    fun `it loads the lit targets left to right, as the user typed them`() {
        val scan = scanUpright(GemFixture.loadTargets())
        assertTrue("the wall must be in view to give a pitch", scan.pitch > 0f)
        val strip = GemTargetStrip()
        val box = slitAt(GemFixture.TARGETS_ROW_X)

        val first = strip.offer(scan.spots, box, scan.pitch)
        assertNull("one scan is not enough to load", first.targets)
        assertEquals("reading 3 targets (1 of 3)", first.status)
        assertNull(strip.offer(scan.spots, box, scan.pitch).targets)

        val loaded = strip.offer(scan.spots, box, scan.pitch)
        assertNotNull(loaded.status, loaded.targets)
        assertEquals(GemFixture.TARGETS_TYPED.map { pattern(it) }, loaded.targets)
        assertEquals("loaded 3 targets", loaded.status)
    }

    @Test
    fun `the dark fourth button is simply absent`() {
        // Not loaded as a blank and not read as anything: an unlit button is not a blob.
        // Three lit buttons fill slots 1 to 3 and the fourth slot is left for the caller
        // to clear, rather than shifting anything along.
        val scan = scanUpright(GemFixture.loadTargets())
        val inBox = scan.spots.filter { slitAt(GemFixture.TARGETS_ROW_X).contains(it.x, it.y) }
        assertEquals(3, inBox.size)
    }

    @Test
    fun `a slit over the wall itself refuses`() {
        val scan = scanUpright(GemFixture.loadTargets())
        val strip = GemTargetStrip()
        val verdict = strip.offer(scan.spots, slitAt(GemFixture.TARGETS_WALL_X), scan.pitch)
        assertNull(verdict.targets)
        assertTrue(verdict.status, verdict.status.contains("only the target row"))
    }

    @Test
    fun `a slit over the end of a wall row refuses even with four gems or fewer`() {
        // The case the spacing check exists for: one row, few enough gems, and every one
        // of them a wall gem.
        val scan = scanUpright(GemFixture.loadTargets())
        val wallRow = scan.spots
            .filter { kotlin.math.abs(it.y - GemFixture.TARGETS_WALL_X) < 40f }
            .sortedBy { it.x }
        assertTrue("fixture should show a wall row", wallRow.size >= 4)
        val right = (wallRow[2].x + wallRow[3].x) / 2f
        val box = slitAt(GemFixture.TARGETS_WALL_X, width = right)
        val verdict = GemTargetStrip().offer(scan.spots, box, scan.pitch)
        assertNull(verdict.targets)
        assertTrue(verdict.status, verdict.status.contains("too close together"))
    }

    @Test
    fun `a slit straddling the targets and the wall refuses`() {
        val scan = scanUpright(GemFixture.loadTargets())
        // Tall enough to take in both rows, narrow enough to hold four gems or fewer, so
        // it is the row check and not the count that has to catch it: a target and the
        // wall gem nearest it, and only just.
        val target = scan.spots.filter { kotlin.math.abs(it.y - GemFixture.TARGETS_ROW_X) < 40f }
            .minBy { it.x }
        val wall = scan.spots.filter { kotlin.math.abs(it.y - GemFixture.TARGETS_WALL_X) < 40f }
            .minBy { kotlin.math.abs(it.x - target.x) }
        val box = GemTargetStrip.Box(
            minOf(target.x, wall.x) - 10f, target.y - 10f,
            maxOf(target.x, wall.x) + 10f, wall.y + 10f,
        )
        val inBox = scan.spots.count { box.contains(it.x, it.y) }
        assertTrue("the box should hold a target and a wall gem, held $inBox", inBox in 2..4)
        val verdict = GemTargetStrip().offer(scan.spots, box, scan.pitch)
        assertNull(verdict.targets)
        assertTrue(verdict.status, verdict.status.contains("two rows"))
    }

    private val redGreenBlue = GemPattern(GemColour.RED, GemColour.GREEN, GemColour.BLUE)
    private val greenGreenRed = GemPattern(GemColour.GREEN, GemColour.GREEN, GemColour.RED)
    private val box = GemTargetStrip.Box(0f, 100f, 1000f, 200f)

    private fun spots(vararg patterns: GemPattern) =
        patterns.mapIndexed { i, p -> GemTargetStrip.Spot(100f + i * 220f, 150f, p) }

    @Test
    fun `a scan that disagrees starts the count again`() {
        val strip = GemTargetStrip()
        strip.offer(spots(redGreenBlue, greenGreenRed), box, 110f)
        strip.offer(spots(redGreenBlue, greenGreenRed), box, 110f)
        // One frame reads the second target differently: the two before it no longer count.
        val odd = strip.offer(spots(redGreenBlue, redGreenBlue), box, 110f)
        assertNull(odd.targets)
        assertEquals("reading 2 targets (1 of 3)", odd.status)
        assertNull(strip.offer(spots(redGreenBlue, greenGreenRed), box, 110f).targets)
        assertNull(strip.offer(spots(redGreenBlue, greenGreenRed), box, 110f).targets)
        assertEquals(
            listOf(redGreenBlue, greenGreenRed),
            strip.offer(spots(redGreenBlue, greenGreenRed), box, 110f).targets,
        )
    }

    @Test
    fun `a ring it could not read is never loaded as a wildcard`() {
        val strip = GemTargetStrip()
        val half = GemPattern(GemColour.RED, GemColour.GREEN, GemColour.UNKNOWN)
        repeat(5) {
            val verdict = strip.offer(spots(redGreenBlue, half), box, 110f)
            assertNull(verdict.targets)
            assertTrue(verdict.status, verdict.status.contains("target 2 would not read"))
        }
    }

    @Test
    fun `without the wall in view there is no scale and no load`() {
        val verdict = GemTargetStrip().offer(spots(redGreenBlue), box, -1f)
        assertNull(verdict.targets)
        assertTrue(verdict.status, verdict.status.contains("wall in view"))
    }

    @Test
    fun `gems outside the slit are ignored, and the order is the screen's`() {
        val strip = GemTargetStrip()
        val outside = GemTargetStrip.Spot(500f, 400f, GemPattern(GemColour.BLUE, GemColour.BLUE, GemColour.BLUE))
        // Handed over right to left: the slots follow the screen, not the scanner's order.
        val given = spots(redGreenBlue, greenGreenRed).reversed() + outside
        repeat(GemTargetStrip.AGREEING_SCANS - 1) { strip.offer(given, box, 110f) }
        assertEquals(listOf(redGreenBlue, greenGreenRed), strip.offer(given, box, 110f).targets)
    }
}
