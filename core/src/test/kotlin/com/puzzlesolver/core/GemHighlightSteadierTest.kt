package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemColour.BLUE
import com.puzzlesolver.core.puzzle.gems.GemColour.GREEN
import com.puzzlesolver.core.puzzle.gems.GemColour.RED
import com.puzzlesolver.core.puzzle.gems.GemColour.YELLOW
import com.puzzlesolver.core.puzzle.gems.GemHighlightSteadier
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Rings held steady across scans, so a reading that wobbles does not make them blink.
 *
 * On 2026-10-31 the rings flickered on and off in the third stage, over gems that were
 * not changing: a ring per scan, ten scans a second, drawn from whichever way a
 * borderline gem happened to read that time.
 */
class GemHighlightSteadierTest {

    private val pitch = 100f
    private val target = GemPattern(GREEN, RED, BLUE)
    private val match = GemPattern(GREEN, RED, BLUE)
    private val other = GemPattern(GREEN, YELLOW, BLUE)

    /** A 5x4 patch of wall, panned by [dx], with the gem at (2, 1) read as [centre]. */
    private fun scan(dx: Float, centre: GemPattern?, whole: Boolean = true, dropCentre: Boolean = false): GemScanner.Result {
        val gems = ArrayList<GemScanner.Gem>()
        for (row in 0 until 4) for (col in 0 until 5) {
            val isCentre = col == 2 && row == 1
            if (isCentre && dropCentre) continue
            val pattern = if (isCentre) centre!! else GemPattern(RED, RED, RED)
            gems.add(GemScanner.Gem(200f + col * pitch + dx, 200f + row * pitch, 25f, pattern, 0.5f, 0, !isCentre || whole))
        }
        return GemScanner.Result(gems, 0, gems.size, pitch, 0f, "")
    }

    private fun ringedAtCentre(rings: List<GemScanner.Gem>, dx: Float): Int =
        rings.firstOrNull { hypot(it.x - (400f + dx), it.y - 300f) < 1f }?.matchedSlot ?: 0

    @Test
    fun `one stray reading neither lights a gem nor puts one out`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        // Steadily not a match, then one scan that reads as one.
        val seen = listOf(other, other, match, other, other).map { ringedAtCentre(s.update(scan(0f, it), active), 0f) }
        assertEquals(listOf(0, 0, 0, 0, 0), seen)

        s.reset()
        // Steadily a match, with one scan that misreads it.
        val held = listOf(match, match, other, match, match).map { ringedAtCentre(s.update(scan(0f, it), active), 0f) }
        assertEquals("ringed from the second scan on, through the misread", listOf(0, 1, 1, 1, 1), held)
    }

    @Test
    fun `it follows the wall while the camera pans`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        // Thirty pixels a scan -- three hundred a second, a brisk pan at a 100 px pitch.
        val seen = (0 until 6).map { i ->
            val dx = i * 30f
            ringedAtCentre(s.update(scan(dx, if (i == 3) other else match), active), dx)
        }
        assertEquals(listOf(0, 1, 1, 1, 1, 1), seen)
    }

    @Test
    fun `a gem the detector misses for one scan keeps its ring`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        s.update(scan(0f, match), active)
        s.update(scan(10f, match), active)
        val lost = s.update(scan(20f, match, dropCentre = true), active)
        assertEquals("ringed where the wall moved it to", 1, ringedAtCentre(lost, 20f))
        assertEquals(1, ringedAtCentre(s.update(scan(30f, match), active), 30f))
    }

    @Test
    fun `a gem cut off by the frame edge never votes for a match`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        val seen = (0 until 4).map { ringedAtCentre(s.update(scan(0f, match, whole = false), active), 0f) }
        assertEquals(listOf(0, 0, 0, 0), seen)
    }

    @Test
    fun `new targets apply at once to the gems already in view`() {
        val s = GemHighlightSteadier()
        s.update(scan(0f, other), listOf(target))
        s.update(scan(0f, other), listOf(target))
        val rings = s.update(scan(0f, other), listOf(other))
        assertEquals(1, ringedAtCentre(rings, 0f))
        // The red/red/red gems around it match nothing asked for.
        assertTrue(rings.all { hypot(it.x - 400f, it.y - 300f) < 1f })
    }
}
