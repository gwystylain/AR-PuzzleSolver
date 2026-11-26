package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.link.LinkColour
import com.puzzlesolver.core.puzzle.link.LinkPairer
import com.puzzlesolver.core.puzzle.link.LinkPlanner
import com.puzzlesolver.core.puzzle.link.LinkWall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The walk between pairs, checked against every possible order on boards small enough to
 * try them all, and the board the user sent from the practice game paired and planned.
 */
class LinkPlannerTest {

    /** The practice game, level 9 or 10, as in the screenshot: 16 pairs in two bands. */
    private val practice = LinkWall.parse(
        """
        BRG.RRB.PPP.GYP.GRR.BRG.RRG.BRB.BGG.GRR.YGB.GYP.RBP.RBY.BBB.RBY
        PRP.RGY.YPG.BYP.GBB.PRP.BPB.BRY.GBB.GBB.RPR.BYP.RPR.RBY.GRG.RBY
        RRG.GGY.YPY.GRR.RYB.RRG.PPY.PPP.GBY.RYB.BBB.GRR.RGY.RBG.RGP.RBG
        ...............................................................
        YPB.RBP.BBB.GRR.RRB.RBP.GRR.PPP.BRB.YGB.GBY.RBP.BGG.RRG.YPB.GBY
        BYY.RPR.GRG.YPG.RGY.YYP.YPG.YPG.BRY.RPR.PBG.YYP.GBB.BPB.BYY.PBG
        YBR.RGY.RGP.YPP.GGY.YGR.YPP.YPY.PPP.BBB.BBP.YGR.GBY.PPY.YBR.BBP
        """
    )

    @Test
    fun `the practice board pairs exactly and names its panels as the game does`() {
        assertEquals(32, practice.size)
        assertEquals(4, practice.slotSpacing)
        assertEquals(16, practice.slotCount)
        val pairing = LinkPairer.pair(practice)
        assertTrue(pairing.complete)
        assertEquals(16, pairing.exactPairs)
        val byLabel = pairing.pairs.map { (a, b) -> setOf(practice.label(a), practice.label(b)) }.toSet()
        // Read off the screenshot by eye, panel against panel.
        for (pair in listOf(
            setOf("T1", "T6"), setOf("T2", "B5"), setOf("T3", "B8"), setOf("T4", "T12"), setOf("T5", "T10"),
            setOf("T7", "B14"), setOf("T8", "B9"), setOf("T9", "B13"), setOf("T11", "B10"), setOf("T13", "B2"),
            setOf("T14", "T16"), setOf("T15", "B3"), setOf("B1", "B15"), setOf("B4", "B7"), setOf("B6", "B12"),
            setOf("B11", "B16"),
        )) {
            assertTrue("$pair", pair in byLabel)
        }
    }

    @Test
    fun `the practice board's path is the shortest of every order and visits each pair back to back`() {
        val pairing = LinkPairer.pair(practice)
        val started = System.nanoTime()
        val path = LinkPlanner.plan(practice, pairing)
        val millis = (System.nanoTime() - started) / 1e6
        println("practice board: ${"%.1f".format(path.length)} buttons, ${"%.1f".format(path.between)} between pairs, ${"%.0f".format(millis)} ms")
        println(path.steps.joinToString(" ") { "${practice.label(it.first)}>${practice.label(it.second)}" })
        assertTrue(path.optimal)
        assertEquals(16, path.steps.size)
        assertEquals((0 until 32).toSet(), path.presses.toSet())
        for (s in path.steps) assertEquals(s.second, pairing.partner[s.first])
        // A greedy walk is never shorter than the exact one.
        val xs = FloatArray(practice.size) { practice.panels[it].centreX }
        val ys = FloatArray(practice.size) { practice.panels[it].centreY }
        assertTrue(path.between <= greedyBetween(xs, ys, pairing.pairs) + 1e-3f)
    }

    @Test
    fun `exact plans match brute force over every order and direction`() {
        val random = Random(7)
        repeat(60) { trial ->
            val pairsCount = 1 + trial % 6
            val xs = FloatArray(pairsCount * 2) { random.nextInt(0, 64).toFloat() }
            val ys = FloatArray(pairsCount * 2) { if (random.nextBoolean()) 1f else 5f }
            val pairs = (0 until pairsCount).map { intArrayOf(2 * it, 2 * it + 1) }
            for (start in LinkPlanner.Start.entries) {
                val path = LinkPlanner.plan(xs, ys, pairs, start)
                val best = bruteForce(xs, ys, pairs, start)
                assertEquals("trial $trial $start", best, path.between, 1e-3f)
                assertEquals(pairsCount, path.steps.size)
            }
        }
    }

    @Test
    fun `a start at either end walks from that end`() {
        val pairing = LinkPairer.pair(practice)
        val left = LinkPlanner.plan(practice, pairing, LinkPlanner.Start.LEFT)
        val right = LinkPlanner.plan(practice, pairing, LinkPlanner.Start.RIGHT)
        val anywhere = LinkPlanner.plan(practice, pairing, LinkPlanner.Start.ANYWHERE)
        assertTrue(anywhere.between <= left.between + 1e-3f)
        assertTrue(anywhere.between <= right.between + 1e-3f)
        // The first press from the left end is in the left half of the wall, and the
        // reverse from the right.
        assertTrue(practice.panels[left.steps.first().first].col < practice.cols / 2)
        assertTrue(practice.panels[right.steps.first().first].col > practice.cols / 2)
    }

    @Test
    fun `fewer pairs, and a sparse wall, plan just the same`() {
        // Eight pairs in a level that leaves slots dark: two bands of eight slots, with
        // a gap of two slots in the middle.
        val sparse = LinkWall.parse(
            """
            RRR.GGG.BBB.YYY.........PPP.RGB.YGP.BPR
            RGR.GBG.BRB.YPY.........PGP.RGB.YGP.BPR
            RRR.GGG.BBB.YYY.........PPP.RGB.YGP.BPR
            .......................................
            YGP.PPP.RRR.........BBB.BPR.GGG.YYY.RGB
            YGP.PGP.RGR.........BRB.BPR.GBG.YPY.RGB
            YGP.PPP.RRR.........BBB.BPR.GGG.YYY.RGB
            """
        )
        assertEquals(16, sparse.size)
        assertEquals(4, sparse.slotSpacing)
        assertEquals(10, sparse.slotCount)
        val pairing = LinkPairer.pair(sparse)
        assertTrue(pairing.complete)
        val path = LinkPlanner.plan(sparse, pairing)
        assertEquals(8, path.steps.size)
        assertEquals(sparse.panels.indices.toSet(), path.presses.toSet())
    }

    @Test
    fun `more pairs than the exact search takes still gets a full walk`() {
        val random = Random(3)
        val pairsCount = LinkPlanner.MAX_EXACT_PAIRS + 2
        val xs = FloatArray(pairsCount * 2) { random.nextInt(0, 80).toFloat() }
        val ys = FloatArray(pairsCount * 2) { if (random.nextBoolean()) 1f else 5f }
        val pairs = (0 until pairsCount).map { intArrayOf(2 * it, 2 * it + 1) }
        val path = LinkPlanner.plan(xs, ys, pairs)
        assertEquals(false, path.optimal)
        assertEquals((0 until pairsCount * 2).toSet(), path.presses.toSet())
        assertTrue(path.between <= greedyBetween(xs, ys, pairs) + 1e-3f)
    }

    @Test
    fun `misread cells still pair each panel with its twin, and say which`() {
        // Two cells misread: T1's middle and B16's corner.
        val misread = practice.withCell(0, 4, LinkColour.GREEN).withCell(practice.size - 1, 8, LinkColour.UNKNOWN)
        val pairing = LinkPairer.pair(misread)
        val exact = LinkPairer.pair(practice)
        assertEquals(exact.partner.toList(), pairing.partner.toList())
        assertEquals(14, pairing.exactPairs)
        assertEquals(4, pairing.doubtful.size)
        assertEquals(false, pairing.complete)
    }

    @Test
    fun `an odd panel out is left unpaired`() {
        val wall = LinkWall.parse(
            """
            RRR.GGG.RRR
            RGR.GRG.RGR
            RRR.GGG.RRR
            """
        )
        val pairing = LinkPairer.pair(wall)
        assertEquals(1, pairing.pairs.size)
        assertEquals(listOf(1), pairing.unpaired)
        assertEquals(1, LinkPlanner.plan(wall, pairing).steps.size)
    }

    @Test
    fun `a wall drawn as text reads back the same`() {
        val again = LinkWall.parse(practice.describe())
        assertEquals(practice.describe(), again.describe())
        assertEquals("T1", practice.label(0))
        assertEquals("B1", practice.label(1))
        assertEquals("B16", practice.label(practice.size - 1))
    }

    private fun bruteForce(xs: FloatArray, ys: FloatArray, pairs: List<IntArray>, start: LinkPlanner.Start): Float {
        val startPoint = when (start) {
            LinkPlanner.Start.ANYWHERE -> null
            LinkPlanner.Start.LEFT -> floatArrayOf(xs.min(), (ys.min() + ys.max()) / 2f)
            LinkPlanner.Start.RIGHT -> floatArrayOf(xs.max(), (ys.min() + ys.max()) / 2f)
        }
        var best = Float.MAX_VALUE
        fun d(i: Int, j: Int) = hypot(xs[i] - xs[j], ys[i] - ys[j])
        fun go(used: BooleanArray, last: Int, cost: Float, depth: Int) {
            if (depth == pairs.size) {
                best = minOf(best, cost)
                return
            }
            for (p in pairs.indices) {
                if (used[p]) continue
                used[p] = true
                for (s in 0..1) {
                    val first = pairs[p][s]
                    val second = pairs[p][1 - s]
                    val step = when {
                        last >= 0 -> d(last, first)
                        startPoint != null -> hypot(xs[first] - startPoint[0], ys[first] - startPoint[1])
                        else -> 0f
                    }
                    go(used, second, cost + step, depth + 1)
                }
                used[p] = false
            }
        }
        go(BooleanArray(pairs.size), -1, 0f, 0)
        return best
    }

    private fun greedyBetween(xs: FloatArray, ys: FloatArray, pairs: List<IntArray>): Float {
        var best = Float.MAX_VALUE
        for (startPair in pairs.indices) for (s in 0..1) {
            val used = BooleanArray(pairs.size)
            used[startPair] = true
            var last = pairs[startPair][1 - s]
            var cost = 0f
            repeat(pairs.size - 1) {
                var pick = -1
                var pickS = 0
                var pickD = Float.MAX_VALUE
                for (p in pairs.indices) if (!used[p]) for (t in 0..1) {
                    val dd = hypot(xs[last] - xs[pairs[p][t]], ys[last] - ys[pairs[p][t]])
                    if (dd < pickD) {
                        pickD = dd
                        pick = p
                        pickS = t
                    }
                }
                used[pick] = true
                cost += pickD
                last = pairs[pick][1 - pickS]
            }
            best = minOf(best, cost)
        }
        return best
    }
}
