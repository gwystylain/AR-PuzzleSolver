package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
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
 * [Detonation] is the definition of what the room does: rays advance a cell at a
 * time and cancel when they meet. [RoundModel] is the claim that the same answer
 * follows from a closed form -- a ray dies exactly when another mine stands along
 * it with clear air between -- which is what lets the search evaluate a placement
 * without running the tick loop.
 *
 * The claim is an argument, not an observation, so it is worth attacking. These
 * cases fire thousands of random boards through both and demand identical damage,
 * cell for cell. A disagreement means the closed form has missed an interaction and
 * the search is optimising against a fiction.
 *
 * The boards are generated small and dense on purpose: short lines and many mines
 * per line are where three-in-a-row cancellation, odd-and-even parity meetings, and
 * buttons-absorbing-rays-before-they-meet all collide.
 */
class RoundModelAgreementTest {

    private val sim = Detonation()

    @Test
    fun `closed form matches the tick simulation on random boards`() {
        val rng = Random(20260817)
        var boardsTested = 0
        var boardsWithCancellation = 0
        var boardsWithHazardHits = 0

        repeat(4000) {
            val cols = rng.nextInt(3, 13)
            val rows = rng.nextInt(2, 8)
            val board = randomBoard(cols, rows, rng)
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
            val board = randomBoard(rng.nextInt(3, 11), rng.nextInt(2, 8), rng)
            val placeable = (0 until board.cellCount).filter { board.isPlaceable(it) }
            if (placeable.size < 2) return@repeat

            val model = RoundModel(board, LineOfSight(board))
            val startShortfall = model.shortfall
            val mines = placeable.shuffled(rng).take(rng.nextInt(1, minOf(placeable.size, 5) + 1))
            val tokens = LongArray(mines.size)

            for ((i, m) in mines.withIndex()) tokens[i] = model.choose(m)

            // Mid-way, the model must still agree with the simulator.
            assertArrayEquals(sim.fire(board, mines.toIntArray()).damage, model.hits)

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
private fun randomBoard(cols: Int, rows: Int, rng: Random): BombBoard {
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
    return BombBoard.of(cols, rows, kinds)
}
