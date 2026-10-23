package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPalette
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemTargets
import com.puzzlesolver.core.solve.CellObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rules, which are where a wrong highlight comes from.
 *
 * All of it is arithmetic on small integers, so it is cheap to pin down completely --
 * and worth pinning down completely, because the two asymmetries in [GemPattern.matches]
 * are the kind that look like oversights and get "fixed" into bugs.
 */
class GemMatchingTest {

    private val redYellowBlue = GemPattern(GemColour.RED, GemColour.YELLOW, GemColour.BLUE)

    @Test
    fun `a fully specified target matches only its exact gem`() {
        assertTrue(redYellowBlue.matches(redYellowBlue))
        assertFalse(redYellowBlue.matches(redYellowBlue.copy(centre = GemColour.GREEN)))
        assertFalse(redYellowBlue.matches(redYellowBlue.copy(middle = GemColour.RED)))
        assertFalse(redYellowBlue.matches(redYellowBlue.copy(outer = GemColour.GREEN)))
    }

    @Test
    fun `unset target rings are wildcards`() {
        val outerOnly = GemPattern(outer = GemColour.RED)
        assertTrue(outerOnly.matches(redYellowBlue))
        assertTrue(outerOnly.matches(GemPattern(GemColour.RED, GemColour.GREEN, GemColour.GREEN)))
        assertFalse(outerOnly.matches(GemPattern(GemColour.BLUE, GemColour.YELLOW, GemColour.BLUE)))
    }

    /**
     * The asymmetry that matters. A wildcard on the target means "I do not care"; the
     * same value on an observation means "I could not read this", and those must not
     * cancel out into a match. The user's cost for a wrong highlight is walking to the
     * wrong gem, and the room is on a timer.
     */
    @Test
    fun `an unread ring never satisfies a specified target ring`() {
        val unread = GemPattern(GemColour.RED, GemColour.UNKNOWN, GemColour.BLUE)
        assertFalse(redYellowBlue.matches(unread))
        // ...but a target that does not ask about that ring is still satisfied.
        assertTrue(GemPattern(outer = GemColour.RED).matches(unread))
    }

    @Test
    fun `a blank target is not a filter`() {
        assertTrue(GemPattern.BLANK.isBlank)
        assertEquals(0, GemPattern.BLANK.specifiedZones)
        assertEquals(2, GemPattern(outer = GemColour.RED, centre = GemColour.BLUE).specifiedZones)
    }

    @Test
    fun `packing round-trips every combination`() {
        val values = intArrayOf(
            GemColour.UNKNOWN, GemColour.RED, GemColour.YELLOW,
            GemColour.GREEN, GemColour.BLUE,
        )
        val seen = HashSet<Int>()
        for (o in values) for (m in values) for (c in values) {
            val p = GemPattern(o, m, c)
            val packed = p.pack()
            assertTrue("packed value must be non-negative", packed >= 0)
            assertNotEquals("must not collide with the unlit sentinel", CellObservation.EMPTY, packed)
            assertTrue("packed values must be unique", seen.add(packed))
            assertEquals(p, GemPattern.unpack(packed))
        }
        assertEquals(125, seen.size)
    }

    @Test
    fun `targets are only filters once something is set`() {
        val targets = GemTargets()
        assertEquals(4, targets.size)
        assertTrue(targets.active().isEmpty())

        val before = targets.generation
        targets.set(1, redYellowBlue)
        assertEquals(listOf(redYellowBlue), targets.active())
        assertTrue("editing must bump the generation", targets.generation > before)

        val unchanged = targets.generation
        targets.set(1, redYellowBlue)
        assertEquals("a no-op write must not bump it", unchanged, targets.generation)

        targets.clearAll()
        assertTrue(targets.active().isEmpty())
    }

    @Test
    fun `loading targets replaces every slot in one edit`() {
        val targets = GemTargets()
        val greenGreenRed = GemPattern(GemColour.GREEN, GemColour.GREEN, GemColour.RED)
        targets.set(3, greenGreenRed)
        val before = targets.generation

        // Three loaded: slots 1 to 3, and the fourth -- last round's -- cleared.
        targets.replaceAll(listOf(redYellowBlue, greenGreenRed, redYellowBlue))
        assertEquals(
            listOf(redYellowBlue, greenGreenRed, redYellowBlue, GemPattern.BLANK),
            targets.all(),
        )
        assertEquals("one edit, not four", before + 1, targets.generation)
    }

    @Test
    fun `the reference hues stay far enough apart to be told apart`() {
        // Green and the wall's cyan "blue" are the closest pair now that purple is gone,
        // and the one a clipping bug collapses first, so this guards the margin rather
        // than the values.
        var closest = 360f
        for (i in GemPalette.HUES.indices) {
            for (j in i + 1 until GemPalette.HUES.size) {
                var d = kotlin.math.abs(GemPalette.HUES[i] - GemPalette.HUES[j])
                if (d > 180f) d = 360f - d
                if (d < closest) closest = d
            }
        }
        assertTrue("closest centroids are $closest degrees apart", closest >= 20f)
    }

    @Test
    fun `hue classification lands on the expected colour`() {
        assertEquals(0, GemPalette.nearestColour(355f))     // red, just past the wrap
        assertEquals(0, GemPalette.nearestColour(3f))       // red, just before it
        assertEquals(1, GemPalette.nearestColour(60f))      // yellow
        assertEquals(2, GemPalette.nearestColour(140f))     // green
        assertEquals(3, GemPalette.nearestColour(200f))     // blue, which measures cyan
        // Magenta is a red ring under a cyan glow, not a colour of its own: the arc
        // purple used to own splits between its neighbours at 275 degrees.
        assertEquals(0, GemPalette.nearestColour(325f))     // magenta reads red
        assertEquals(3, GemPalette.nearestColour(265f))     // violet reads blue
    }

}
