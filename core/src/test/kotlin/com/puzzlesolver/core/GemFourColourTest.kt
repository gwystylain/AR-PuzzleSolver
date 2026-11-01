package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The wall in all four colours, which is the third stage of every round.
 *
 * On 2026-10-31 the first two stages of three rounds in a row worked, and the third
 * stage of each struggled: too many gems ringed, and rings blinking on and off. The
 * third stage is the only one that lights yellow, and on the frame here every yellow
 * middle ring under a green outer ring read red -- nine of nine -- and the one under a
 * blue outer ring read blue. A green/yellow/blue gem read as green/red/blue, so a
 * green/red/blue target lit seven gems on this frame where two were right.
 *
 * Yellow light is red light plus green light. The reader takes the glow of the ring
 * outside back out before reading the ring inside, which is what makes a red ring
 * under a green one read red rather than orange; on a yellow ring the same subtraction
 * takes away the ring's own green and leaves red. See GemPalette.OWN_GREEN_MIDDLE.
 */
class GemFourColourTest {

    private fun colourOf(name: String): Int = when (name) {
        "red" -> GemColour.RED
        "yellow" -> GemColour.YELLOW
        "green" -> GemColour.GREEN
        "blue" -> GemColour.BLUE
        else -> GemColour.UNKNOWN
    }

    private fun patternOf(label: GemFixture.Labelled) =
        GemPattern(colourOf(label.outer), colourOf(label.middle), colourOf(label.centre ?: ""))

    /** Each labelled gem paired with what the scanner read there. */
    private fun scanAgainstLabels(targets: GemTargets = GemTargets()): List<Pair<GemFixture.Labelled, GemScanner.Gem>> {
        val result = GemScanner(targets).scan(GemFixture.loadFour())
        return GemFixture.FOUR_LABELLED.mapNotNull { label ->
            val gem = result.gems.minByOrNull { hypot(it.x - label.x, it.y - label.y) }
            if (gem == null || hypot(gem.x - label.x, gem.y - label.y) > 20f) null else label to gem
        }
    }

    @Test
    fun `the scanner finds every labelled gem`() {
        val paired = scanAgainstLabels()
        assertEquals("labelled gems found", GemFixture.FOUR_LABELLED.size, paired.size)
    }

    @Test
    fun `a yellow ring under a green ring is yellow, not red`() {
        val paired = scanAgainstLabels().filter { (l, _) -> l.outer == "green" && l.middle == "yellow" }
        assertTrue("the fixture must hold this case", paired.size >= 9)
        val wrong = paired.filter { (_, g) -> g.pattern.middle != GemColour.YELLOW }
            .map { (l, g) -> "(${l.x}, ${l.y}) read ${g.pattern}" }
        // One of the nine has a yellow ring dim enough that its green barely follows its
        // red; it still reads red. The other eight are the bar.
        assertTrue("${paired.size - wrong.size} of ${paired.size} read yellow: $wrong", wrong.size <= 1)
    }

    @Test
    fun `a yellow ring under a blue ring is yellow, not blue`() {
        val paired = scanAgainstLabels().filter { (l, _) -> l.outer == "blue" && l.middle == "yellow" }
        assertTrue("the fixture must hold this case", paired.isNotEmpty())
        for ((l, g) in paired) assertEquals("(${l.x}, ${l.y}) read ${g.pattern}", GemColour.YELLOW, g.pattern.middle)
    }

    @Test
    fun `the four-colour wall reads as well as the two-colour one`() {
        val paired = scanAgainstLabels()
        val right = IntArray(3)
        val misses = StringBuilder()
        for ((label, gem) in paired) {
            val want = patternOf(label)
            for (zone in 0..2) {
                if (gem.pattern.zone(zone) == want.zone(zone)) right[zone]++
                else misses.append("\n  (${label.x}, ${label.y}) ${GemPattern.zoneName(zone)}: ")
                    .append("expected ${GemColour.name(want.zone(zone))}, read ${GemColour.name(gem.pattern.zone(zone))}")
            }
        }
        val n = paired.size
        assertEquals("outer ring misreads:$misses", n, right[0])
        assertTrue("middle ring ${right[1]} of $n:$misses", right[1] >= n - 2)
        assertTrue("centre dot ${right[2]} of $n:$misses", right[2] >= n - 2)
    }

    /**
     * The highlights that sent the user to the wrong gem. Slot 3 on the day was
     * blue/blue/green, and the gem ringed for it was blue/yellow/green; there is no
     * blue/blue/green gem in this frame. And a green/red/blue target must ring the two
     * gems that are green/red/blue, not the five green/yellow/blue ones as well.
     */
    @Test
    fun `targets ring only the gems that are what they ask for`() {
        val targets = GemTargets()
        targets.set(0, GemPattern(GemColour.BLUE, GemColour.BLUE, GemColour.GREEN))
        val none = GemScanner(targets).scan(GemFixture.loadFour())
        assertEquals("blue/blue/green matches: ${none.matches.map { "(${it.x}, ${it.y}) ${it.pattern}" }}", 0, none.matchCount)

        targets.set(0, GemPattern(GemColour.GREEN, GemColour.RED, GemColour.BLUE))
        val result = GemScanner(targets).scan(GemFixture.loadFour())
        val truth = GemFixture.FOUR_LABELLED.filter { it.outer == "green" && it.middle == "red" && it.centre == "blue" }
        val wrong = result.matches.mapNotNull { gem ->
            val label = GemFixture.FOUR_LABELLED.minByOrNull { hypot(it.x - gem.x, it.y - gem.y) }!!
            if (label in truth) null else "(${gem.x}, ${gem.y}) is ${patternOf(label)}"
        }
        // Five wrong on the day. The one left is the dim yellow ring the reader still
        // reads red -- see `a yellow ring under a green ring is yellow, not red`.
        assertTrue("wrongly ringed: $wrong", wrong.size <= 1)
        assertEquals("found ${result.matchCount - wrong.size} of ${truth.size}", truth.size, result.matchCount - wrong.size)
    }

    /**
     * A gem half out of frame has half its rings, and the reader cannot tell. This one
     * is red/blue/red and read red/blue/blue on the day, which ringed it for slot 2.
     * It is still read and reported -- the next frame will have all of it -- but it is
     * never claimed as a match.
     */
    @Test
    fun `a gem cut off by the edge of the frame is never ringed`() {
        val view = GemFixture.loadFour()
        val targets = GemTargets()
        targets.set(0, GemPattern(outer = GemColour.RED))
        val result = GemScanner(targets).scan(view)
        val cut = GemFixture.FOUR_CUT_OFF
        val gem = result.gems.minByOrNull { hypot(it.x - cut.x, it.y - cut.y) }!!
        assertTrue("the cut-off gem was not found", hypot(gem.x - cut.x, gem.y - cut.y) < 20f)
        assertFalse("the cut-off gem was claimed whole", gem.whole)
        assertEquals("the cut-off gem was ringed", 0, gem.matchedSlot)
        assertTrue("red-outer gems still ring", result.matchCount >= 10)
        for (m in result.matches) {
            assertTrue(
                "ringed a gem at (${m.x}, ${m.y}) whose disc runs off the frame",
                m.x >= m.radius && m.y >= m.radius &&
                    m.x <= view.width - 1 - m.radius && m.y <= view.height - 1 - m.radius,
            )
        }
    }
}
