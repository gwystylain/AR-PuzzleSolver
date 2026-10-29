package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombRules
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Detonation] against the practice game's own detonation, on a thousand boards.
 *
 * Both sides build the same boards from the same integer RNG: a 16x7 stretch of the
 * game's grid, a dense scatter of whites and blues, and on every other board a few kill
 * squares; then one or two detonations of one to four bombs on random free cells,
 * clicked in the order drawn. The game ran them through its real `detonate` on
 * 2026-10-28 (with only its 36 ms animation step taken out) and recorded, per board,
 * either that the game was lost or every mine's hit points after each detonation.
 * Here they run through the simulator under the default rules, and the two are
 * compared by hash, in blocks of fifty.
 *
 * The game breaks same-step ties by click order, and so does the simulator's forward
 * run, so the match is expected to be exact, ties included.
 *
 * To redo the game's half, paste `tools/mines/web-game-crosscheck.js` into the browser
 * console on the practice game's page; it prints the numbers held here.
 */
class WebGameCrossCheckTest {

    private val sim = Detonation()

    @Test
    fun `the simulator does what the practice game does on a thousand boards`() {
        val run = run(BombRules())
        println("cross-check: ${run.lost} lost, ${run.secondRounds} second detonations, " +
            "${run.contested} contested rays, ${run.passedThrough} hits behind a button")
        assertEquals("lost", 331, run.lost)
        assertEquals("second detonations", 366, run.secondRounds)
        for ((k, expected) in GAME_BLOCKS.withIndex()) {
            assertEquals("boards ${k * 50}..${k * 50 + 49} differ from the game", expected, run.blocks[k])
        }
        assertEquals(GAME_ALL, run.all)
        // Not a vacuous match: rays carried on through fallen buttons, and ties came up.
        assertTrue(run.passedThrough > 50)
        assertTrue(run.contested > 0)
    }

    @Test
    fun `the rules this app used to assume do not`() {
        // The same thousand boards under the old model -- head-on rays vaporising, a
        // fallen button blocking to the end. Most blocks disagree with the game, which
        // is the measure of how far off the old plans could have been.
        val run = run(BombRules.ROOM_AS_FIRST_ASSUMED)
        val agreeing = GAME_BLOCKS.indices.count { run.blocks[it] == GAME_BLOCKS[it] }
        println("old rules: ${run.lost} lost against the game's 331; $agreeing of 20 blocks agree")
        assertTrue(run.all != GAME_ALL)
        assertTrue("at most a few blocks can agree by chance, saw $agreeing", agreeing <= 2)
    }

    private class Run(
        val blocks: List<String>,
        val all: String,
        val lost: Int,
        val secondRounds: Int,
        val contested: Int,
        val passedThrough: Int,
    )

    private fun run(rules: BombRules): Run {
        val hashes = ArrayList<String>()
        var lost = 0
        var secondRounds = 0
        var contested = 0
        var passedThrough = 0
        for (i in 0 until SCENARIOS) {
            val g = XorShift(1000 + i)
            val killPer200 = if (i % 2 == 0) 0 else 4
            val kinds = ByteArray(WIDTH * ROWS) { Button.EMPTY.toByte() }
            val wasMine = BooleanArray(WIDTH * ROWS)
            for (r in 0 until ROWS) for (c in 0 until WIDTH) {
                val v = g.int(200)
                val idx = r * WIDTH + c
                kinds[idx] = when {
                    v < 28 -> Button.WHITE
                    v < 40 -> Button.BLUE
                    v < 40 + killPer200 -> Button.HAZARD
                    else -> Button.EMPTY
                }.toByte()
                wasMine[idx] = v < 40
            }
            val rounds = 1 + g.int(2)
            var board = BombBoard.of(WIDTH, ROWS, kinds, rules)
            val sig = ArrayList<String>()
            for (round in 0 until rounds) {
                if (board.remainingHitPoints == 0) break
                val cand = (0 until board.cellCount).filter { board.isPlaceable(it) }.toMutableList()
                if (cand.isEmpty()) break
                val picks = ArrayList<Int>()
                val n = 1 + g.int(4)
                for (b in 0 until n) {
                    if (cand.isEmpty()) break
                    picks.add(cand.removeAt(g.int(cand.size)))
                }
                val mines = picks.toIntArray()
                val result = sim.fire(board, mines)
                if (round == 1) secondRounds++
                contested += result.contested
                if (result.losesGame) {
                    sig.add("L")
                    lost++
                    break
                }
                for (t in board.liveTargets()) if (result.damage[t] > 0 && blockedBefore(board, mines, t)) passedThrough++
                board = board.afterDetonation(result.damage, mines)
                sig.add(buildString { for (idx in 0 until WIDTH * ROWS) append(if (wasMine[idx]) ('0' + board.hp[idx]) else '.') })
            }
            hashes.add(fnv(sig.joinToString("|")))
        }
        val blocks = (0 until SCENARIOS / 50).map { k -> fnv(hashes.subList(k * 50, k * 50 + 50).joinToString(" ")) }
        return Run(blocks, fnv(hashes.joinToString(" ")), lost, secondRounds, contested, passedThrough)
    }

    /** Whether some hit on [t] can only have come through another button first. */
    private fun blockedBefore(board: BombBoard, mines: IntArray, t: Int): Boolean {
        val tc = board.colOf(t)
        val tr = board.rowOf(t)
        for (m in mines) {
            val dc = Integer.signum(tc - board.colOf(m))
            val dr = Integer.signum(tr - board.rowOf(m))
            val steps = maxOf(Math.abs(tc - board.colOf(m)), Math.abs(tr - board.rowOf(m)))
            if (steps == 0 || board.colOf(m) + dc * steps != tc || board.rowOf(m) + dr * steps != tr) continue
            for (s in 1 until steps) {
                val p = board.index(board.colOf(m) + dc * s, board.rowOf(m) + dr * s)
                if (board.isLiveTarget(p)) return true
            }
        }
        return false
    }

    /** The game harness's RNG: xorshift32, bit for bit as JavaScript does it. */
    private class XorShift(seed: Int) {
        private var s = seed

        fun int(n: Int): Int {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return ((s.toLong() and 0xFFFFFFFFL) % n).toInt()
        }
    }

    private fun fnv(text: String): String {
        var h = 0x811c9dc5.toInt()
        for (ch in text) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }

    private companion object {
        const val SCENARIOS = 1000
        const val WIDTH = 16
        const val ROWS = 7

        /** What the practice game did, block by block, on 2026-10-28. */
        val GAME_BLOCKS = listOf(
            "2bc8032c", "03fc5c17", "73ebfdad", "42d6d3c3", "06fb018d",
            "fbcb602c", "4534081e", "8b401056", "3e4c9806", "cb337454",
            "38c1d971", "dcc45d94", "a5eba80f", "b086fc33", "b2b9970b",
            "ae7a2e98", "721c7e32", "1d2243af", "68ade981", "57284920",
        )
        const val GAME_ALL = "cbbb1def"
    }
}
