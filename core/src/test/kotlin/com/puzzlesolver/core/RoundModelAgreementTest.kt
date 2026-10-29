package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombRules
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import com.puzzlesolver.core.puzzle.bombs.LineOfSight
import com.puzzlesolver.core.puzzle.bombs.RoundModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The load-bearing test of the solver.
 *
 * [Detonation] is the definition of what the room does, a cell at a time. [RoundModel]
 * is what the search runs on instead, and it makes a different claim under each rule
 * set:
 *
 *  - **Under the practice game's rules** (the defaults) it is a lower bound: every ray
 *    credited to the first button in its path, where the game lets a ray on through a
 *    button that has already gone down. So it may miss damage, and must never invent
 *    any: whatever it calls cleared, the game clears.
 *  - **Under [BombRules.ROOM_AS_FIRST_ASSUMED]** it is exact, by a closed form for
 *    annihilation -- a ray dies exactly when another mine stands along it with clear
 *    air between.
 *
 * Both are arguments, not observations, so they are worth attacking. These cases fire
 * thousands of random boards through both and hold each claim to the cell.
 *
 * The boards are generated small and dense on purpose: short lines and many mines
 * per line are where three-in-a-row cancellation, odd-and-even parity meetings, and
 * buttons-absorbing-rays-before-they-meet all collide.
 */
class RoundModelAgreementTest {

    private val sim = Detonation()

    @Test
    fun `under the practice game's rules the model never claims damage the game does not deal`() {
        val rng = Random(20261028)
        var tested = 0
        var gameDealtMore = 0
        repeat(4000) {
            val board = randomBoard(rng.nextInt(3, 13), rng.nextInt(2, 8), rng, BombRules())
            val placeable = (0 until board.cellCount).filter { board.isPlaceable(it) }
            if (placeable.isEmpty()) return@repeat
            val mines = placeable.shuffled(rng).take(rng.nextInt(1, minOf(placeable.size, 6) + 1)).toIntArray()

            val model = RoundModel(board, LineOfSight(board))
            for (m in mines) model.choose(m)
            val truth = sim.fire(board, mines)
            tested++

            var more = false
            for (t in board.liveTargets()) {
                val claimed = minOf(model.hits[t], board.hp[t])
                assertTrue(
                    "model claims $claimed on $t, the game deals ${truth.damage[t]}:\n$board mines=${mines.toList()}",
                    claimed <= truth.damage[t],
                )
                if (truth.damage[t] > claimed) more = true
            }
            if (model.shortfall == 0) {
                assertTrue("model calls it cleared and the game does not", board.liveTargets().all { truth.damage[it] >= board.hp[it] })
            }
            if (more) gameDealtMore++
        }
        assertTrue(tested > 3000)
        // Otherwise this would be passing on boards where the two never differ.
        assertTrue("rays let through fallen buttons should show up, saw $gameDealtMore", gameDealtMore > 200)
    }

    @Test
    fun `closed form matches the tick simulation on random boards, under the old rules`() {
        val rng = Random(20260817)
        var boardsTested = 0
        var boardsWithCancellation = 0
        var boardsWithHazardHits = 0

        repeat(4000) {
            val cols = rng.nextInt(3, 13)
            val rows = rng.nextInt(2, 8)
            val board = randomBoard(cols, rows, rng, BombRules.ROOM_AS_FIRST_ASSUMED)
            val placeable = (0 until board.cellCount).filter { board.isPlaceable(it) }
            if (placeable.isEmpty()) return@repeat

            val mineCount = rng.nextInt(1, minOf(placeable.size, 6) + 1)
            val mines = placeable.shuffled(rng).take(mineCount).toIntArray()

            val model = RoundModel(board, LineOfSight(board))
            for (m in mines) model.choose(m)

            val truth = sim.fire(board, mines)
            if (truth.annihilated > 0) boardsWithCancellation++
            boardsTested++

            assertArrayEquals(
                "board:\n$board mines=${mines.toList()}",
                truth.damage,
                model.hits,
            )
            // Hazards decide whether a plan loses the room outright, so the two models
            // have to agree on those too. A closed form that under-counts them would
            // quietly hand out losing plans, which is the worst thing this can do.
            assertEquals(
                "hazard hits disagree on board:\n$board mines=${mines.toList()}",
                truth.hazardHits,
                model.hazardHits,
            )
            if (truth.hazardHits > 0) boardsWithHazardHits++
        }

        assertTrue("generated boards should exercise cancellation", boardsWithCancellation > 200)
        assertTrue("and should fire on hazards sometimes", boardsWithHazardHits > 100)
        assertTrue(boardsTested > 3000)
    }

    @Test
    fun `placing and lifting mines in any order returns to a clean slate`() {
        // The search backtracks constantly, so an asymmetry between choose and
        // unchoose would corrupt the model without ever throwing. This checks the
        // round trip is exact, including the case where a mine that was shadowing a
        // neighbour is lifted and the neighbour gets its ray back.
        val rng = Random(4242)
        repeat(2000) {
            // Old rules for half, where a lifted mine hands a neighbour its ray back.
            val rules = if (it % 2 == 0) BombRules.ROOM_AS_FIRST_ASSUMED else BombRules()
            val board = randomBoard(rng.nextInt(3, 11), rng.nextInt(2, 8), rng, rules)
            val placeable = (0 until board.cellCount).filter { board.isPlaceable(it) }
            if (placeable.size < 2) return@repeat

            val model = RoundModel(board, LineOfSight(board))
            val startShortfall = model.shortfall
            val mines = placeable.shuffled(rng).take(rng.nextInt(1, minOf(placeable.size, 5) + 1))
            val tokens = LongArray(mines.size)

            for ((i, m) in mines.withIndex()) tokens[i] = model.choose(m)

            // Mid-way, the model must still agree with the simulator, where it is exact.
            if (rules.opposingRaysAnnihilate) {
                assertArrayEquals(sim.fire(board, mines.toIntArray()).damage, model.hits)
            }

            for (i in mines.indices.reversed()) model.unchoose(mines[i], tokens[i])

            assertEquals(0, model.chosenCount)
            assertEquals(startShortfall, model.shortfall)
            assertArrayEquals(IntArray(board.cellCount), model.hits)
        }
    }

    @Test
    fun `shortfall never counts damage past a button's hit points`() {
        // Four mines all sighting one white button. Only the first hit is progress;
        // a search that scored the other three would happily buy them.
        val board = BombBoard.parse(
            """
            X.X
            .W.
            X.X
            """
        )
        val model = RoundModel(board, LineOfSight(board))
        val mines = board.placedMines()
        // Parse marks them already-placed, so rebuild as a clean board plus choices.
        val clean = BombBoard.parse(
            """
            ...
            .W.
            ...
            """
        )
        val m2 = RoundModel(clean, LineOfSight(clean))
        assertEquals(1, m2.shortfall)
        for (m in mines) m2.choose(m)
        assertEquals("the button is cleared, not cleared four times over", 0, m2.shortfall)
        assertEquals(4, m2.hits[clean.index(1, 1)])
        assertEquals(4, board.countOf(Button.MINE))
    }
}

/** A dense mix of everything the solver has to cope with, including unknown colours. */
private fun randomBoard(cols: Int, rows: Int, rng: Random, rules: BombRules): BombBoard {
    val kinds = ByteArray(cols * rows) {
        when (rng.nextInt(100)) {
            in 0..52 -> Button.EMPTY
            in 53..71 -> Button.WHITE
            in 72..83 -> Button.BLUE
            in 84..89 -> Button.GREEN
            in 90..95 -> Button.HAZARD
            else -> Button.OPAQUE
        }.toByte()
    }
    return BombBoard.of(cols, rows, kinds, rules)
}
