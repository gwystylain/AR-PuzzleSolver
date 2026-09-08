package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombSolver
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.SolutionPolicy
import com.puzzlesolver.core.solve.SolveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The solver's contract with the pipeline, as distinct from whether the plan is any
 * good -- that is [BombPlannerTest]'s job.
 *
 * The case worth stating plainly is `refuses to answer from a partial scan`. Every
 * other puzzle in the app that can answer early does so because its clues only ever
 * remove possibilities. Here a single unread button changes where every mine goes, so
 * the early answer is not merely unavailable, it is wrong, and the test builds a board
 * that demonstrates it rather than asserting the enum.
 */
class BombSolverTest {

    private fun observationsFor(board: BombBoard, skip: Set<Int> = emptySet()): List<CellObservation> =
        (0 until board.cellCount)
            .filter { it !in skip }
            .map { CellObservation(it % board.cols, it / board.cols, board.kindAt(it), 0.95f) }

    private fun solve(board: BombBoard): SolveOutcome {
        val solver = BombSolver(board.cols, board.rows)
        try {
            val delta = ObservationDelta(observationsFor(board), coverageFraction = 1f, fullyScanned = true)
            // Planning runs on a worker, so the first calls legitimately return
            // Pending. Poll the way the pipeline does rather than sleeping blindly.
            repeat(400) {
                when (val outcome = solver.observe(delta, System.nanoTime() + 12_000_000L)) {
                    is SolveOutcome.Pending -> Thread.sleep(5)
                    else -> return outcome
                }
            }
            return SolveOutcome.Pending
        } finally {
            solver.close()
        }
    }

    @Test
    fun `the policy is full scan and the reason is demonstrable`() {
        val solver = BombSolver(4, 1)
        assertEquals(SolutionPolicy.REQUIRES_FULL_SCAN, solver.policy)
        solver.close()

        // Two boards agreeing on everything except the last button, whose colour flips
        // the right answer from one mine to two. Any solver that committed after
        // reading the first three cells would be wrong on one of them.
        val a = BombBoard.parse("W..W")
        val b = BombBoard.parse("W..B")
        val one = (solve(a) as SolveOutcome.Solved).solution
        val two = (solve(b) as SolveOutcome.Solved).solution
        assertEquals(1, BombSolver.totalMines(one))
        assertEquals(2, BombSolver.totalMines(two))
    }

    @Test
    fun `refuses to answer while any button is unseen`() {
        val board = BombBoard.parse("W..W")
        val solver = BombSolver(board.cols, board.rows)
        val partial = ObservationDelta(
            observationsFor(board, skip = setOf(3)),
            coverageFraction = 0.75f,
            fullyScanned = false,
        )
        val outcome = solver.observe(partial, System.nanoTime() + 12_000_000L)
        assertTrue("expected NeedMoreData, got $outcome", outcome is SolveOutcome.NeedMoreData)
        assertEquals(1, (outcome as SolveOutcome.NeedMoreData).remainingAmbiguity)
        solver.close()
    }

    @Test
    fun `refuses while the pipeline says the scan is incomplete, even with every cell read`() {
        val board = BombBoard.parse("W..W")
        val solver = BombSolver(board.cols, board.rows)
        val outcome = solver.observe(
            ObservationDelta(observationsFor(board), coverageFraction = 1f, fullyScanned = false),
            System.nanoTime() + 12_000_000L,
        )
        assertTrue(outcome is SolveOutcome.NeedMoreData)
        solver.close()
    }

    @Test
    fun `the solution says which detonation each button belongs to`() {
        val board = BombBoard.parse(".WWW")
        val solution = (solve(board) as SolveOutcome.Solved).solution

        assertEquals("three presses", 3, BombSolver.totalMines(solution))
        assertEquals("one per detonation", 3, BombSolver.roundCount(solution))
        assertTrue(solution.label.contains("3 mines in 3 detonations"))

        // Replay it: press the cell marked 1, detonate, then the cell marked 2, and so
        // on. Order is the whole point -- each round is planned against the board the
        // one before it leaves behind.
        var live = board
        val sim = Detonation()
        for (round in 1..3) {
            val mines = BombSolver.minesForRound(solution, round)
            for (m in mines) assertTrue("round $round presses a lit button", live.isPlaceable(m))
            live = live.afterDetonation(sim.fire(live, mines).damage, mines)
        }
        assertTrue("board should be clear:\n$live", live.isCleared)
    }

    @Test
    fun `a board that cannot be solved is reported as a contradiction with suspects named`() {
        // A white button walled in on all eight sides by colours we could not name, so
        // nothing can ever reach it. The room always admits a solution, which makes a
        // board like this evidence of a misread rather than of an unsolvable puzzle --
        // so the solver blames the reading and names cells for the pipeline to look at
        // again. An empty suspect list would leave it contradicting forever.
        val outcome = solve(
            BombBoard.parse(
                """
                .....
                .###.
                .#W#.
                .###.
                .....
                """
            )
        )
        assertTrue("expected Contradiction, got $outcome", outcome is SolveOutcome.Contradiction)
        assertTrue((outcome as SolveOutcome.Contradiction).suspectCells.isNotEmpty())
    }

    @Test
    fun `re-reading a cell supersedes the plan built from the old colour`() {
        val board = BombBoard.parse("W..W")
        val solver = BombSolver(board.cols, board.rows)
        try {
            val first = ObservationDelta(observationsFor(board), coverageFraction = 1f, fullyScanned = true)
            var solved: SolveOutcome = SolveOutcome.Pending
            repeat(400) {
                solved = solver.observe(first, System.nanoTime() + 12_000_000L)
                if (solved !is SolveOutcome.Pending) return@repeat
                Thread.sleep(5)
            }
            assertEquals(1, BombSolver.totalMines((solved as SolveOutcome.Solved).solution))

            // The far button turns out to be blue after a better look.
            val revised = ObservationDelta(
                cells = emptyList(),
                revised = listOf(CellObservation(3, 0, Button.BLUE, 0.99f)),
                coverageFraction = 1f,
                fullyScanned = true,
            )
            var again: SolveOutcome = SolveOutcome.Pending
            repeat(400) {
                again = solver.observe(revised, System.nanoTime() + 12_000_000L)
                if (again !is SolveOutcome.Pending) return@repeat
                Thread.sleep(5)
            }
            assertEquals(
                "the stale one-mine plan must not survive the re-read",
                2,
                BombSolver.totalMines((again as SolveOutcome.Solved).solution),
            )
        } finally {
            solver.close()
        }
    }
}
