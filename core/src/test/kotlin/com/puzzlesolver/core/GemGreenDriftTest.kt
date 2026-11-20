package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Green where the locked white balance left it on 2026-11-19: 170 degrees, and 172 on
 * the centre dots, which was the green/blue boundary when blue's centroid sat at 198.
 *
 * Stage 1 is red and green only, so every blue on this wall is a misreading. On the
 * phone a third of the green centres read blue, and the red/green/green target read
 * red/green/blue in two frames and matched at 0.11 in a third -- never steadily enough
 * for the two-scans-in-three ring. The user saw the app pan straight past it.
 * See GemPalette.HUES.
 */
class GemGreenDriftTest {

    private fun colourOf(name: String): Int = when (name) {
        "red" -> GemColour.RED
        "yellow" -> GemColour.YELLOW
        "green" -> GemColour.GREEN
        "blue" -> GemColour.BLUE
        else -> GemColour.UNKNOWN
    }

    private fun patternOf(text: String): GemPattern {
        val p = text.split('/').map { colourOf(it) }
        return GemPattern(p[0], p[1], p[2])
    }

    private fun scan(targets: GemTargets = GemTargets()) = GemScanner(targets).scan(GemFixture.loadDrift())

    /** Each labelled gem paired with what the scanner read there. */
    private fun scanAgainstLabels(targets: GemTargets = GemTargets()): List<Pair<GemFixture.Labelled, GemScanner.Gem>> {
        val result = scan(targets)
        return GemFixture.DRIFT_LABELLED.mapNotNull { label ->
            val gem = result.gems.minByOrNull { hypot(it.x - label.x, it.y - label.y) }
            if (gem == null || hypot(gem.x - label.x, gem.y - label.y) > 20f) null else label to gem
        }
    }

    @Test
    fun `the scanner finds every labelled gem`() {
        assertEquals("labelled gems found", GemFixture.DRIFT_LABELLED.size, scanAgainstLabels().size)
    }

    @Test
    fun `a two-colour wall reads no blue`() {
        val blue = scan().gems.filter { it.whole }.filter {
            it.pattern.outer == GemColour.BLUE || it.pattern.middle == GemColour.BLUE || it.pattern.centre == GemColour.BLUE
        }.map { "(${it.x}, ${it.y}) read ${it.pattern}" }
        assertTrue("${blue.size} gems read blue on a red and green wall: $blue", blue.isEmpty())
    }

    @Test
    fun `every ring reads right`() {
        val wrong = scanAgainstLabels().flatMap { (l, g) ->
            val want = listOf(l.outer, l.middle, l.centre).map { colourOf(it ?: "") }
            val got = listOf(g.pattern.outer, g.pattern.middle, g.pattern.centre)
            (0..2).filter { want[it] != got[it] }.map { "(${l.x}, ${l.y}) ${GemPattern.zoneName(it)} read ${GemColour.name(got[it])}" }
        }
        assertTrue("${wrong.size} of ${GemFixture.DRIFT_LABELLED.size * 3} rings wrong: $wrong", wrong.isEmpty())
    }

    @Test
    fun `the red-green-green target is the one gem ringed for the day's targets`() {
        val targets = GemTargets()
        GemFixture.DRIFT_TARGETS.forEachIndexed { i, t -> targets.set(i, patternOf(t)) }
        val matches = scan(targets).matches
        assertEquals("matches: ${matches.map { "(${it.x}, ${it.y}) ${it.pattern} slot ${it.matchedSlot}" }}", 1, matches.size)
        val gem = matches.single()
        assertTrue("matched at (${gem.x}, ${gem.y})", hypot(gem.x - 667.8f, gem.y - 408.4f) < 20f)
        assertEquals(1, gem.matchedSlot)
        // Read with a margin, not by a coin flip: on the phone this gem's confidence was
        // 0.06, and the next scan could as easily have read the centre blue.
        assertTrue("confidence ${gem.confidence}", gem.confidence >= 0.5f)
    }
}
