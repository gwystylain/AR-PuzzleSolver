package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.nonogram.NonogramSolver
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.SolutionPolicy
import com.puzzlesolver.core.solve.SolveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counterpart to [SudokuSolverTest]: a puzzle type that must *not* answer early,
 * and must say so honestly while it waits.
 */
class NonogramSolverTest {

    private fun deadline() = System.nanoTime() + 20_000_000_000L

    private fun step(solver: NonogramSolver, fullyScanned: Boolean): SolveOutcome {
        var outcome: SolveOutcome = SolveOutcome.Pending
        repeat(5) {
            outcome = solver.observe(
                ObservationDelta(emptyList(), emptyList(), 1f, fullyScanned),
                deadline(),
            )
            if (outcome !is SolveOutcome.Pending) return outcome
        }
        return outcome
    }

    /** A plus sign on a 5x5 board. Uniquely determined by its clues. */
    private fun plusSign(solver: NonogramSolver) {
        solver.setRowClue(0, intArrayOf(1))
        solver.setRowClue(1, intArrayOf(1))
        solver.setRowClue(2, intArrayOf(5))
        solver.setRowClue(3, intArrayOf(1))
        solver.setRowClue(4, intArrayOf(1))
        solver.setColClue(0, intArrayOf(1))
        solver.setColClue(1, intArrayOf(1))
        solver.setColClue(2, intArrayOf(5))
        solver.setColClue(3, intArrayOf(1))
        solver.setColClue(4, intArrayOf(1))
    }

    @Test
    fun `policy declares that a full scan is required`() {
        assertEquals(SolutionPolicy.REQUIRES_FULL_SCAN, NonogramSolver(5, 5).policy)
    }

    @Test
    fun `refuses to answer while the scan is incomplete`() {
        val solver = NonogramSolver(5, 5)
        plusSign(solver)
        // Every clue is in hand, but the pipeline has not declared the scan complete.
        // A nonogram solver must still refuse: it has no way to know an unread clue
        // would not contradict what it has.
        val outcome = step(solver, fullyScanned = false)
        assertTrue("must not answer early, got $outcome", outcome is SolveOutcome.NeedMoreData)
    }

    @Test
    fun `reports how many clue lines are still missing`() {
        val solver = NonogramSolver(5, 5)
        solver.setRowClue(0, intArrayOf(1))
        solver.setColClue(0, intArrayOf(1))
        val outcome = step(solver, fullyScanned = true)
        assertTrue(outcome is SolveOutcome.NeedMoreData)
        // 4 rows + 4 columns unread.
        assertEquals(8, (outcome as SolveOutcome.NeedMoreData).remainingAmbiguity)
    }

    @Test
    fun `solves the plus sign once the scan is complete`() {
        val solver = NonogramSolver(5, 5)
        plusSign(solver)
        val outcome = step(solver, fullyScanned = true)
        assertTrue("expected Solved, got $outcome", outcome is SolveOutcome.Solved)

        val expected = intArrayOf(
            0, 0, 1, 0, 0,
            0, 0, 1, 0, 0,
            1, 1, 1, 1, 1,
            0, 0, 1, 0, 0,
            0, 0, 1, 0, 0,
        )
        assertEquals(
            expected.toList(),
            (outcome as SolveOutcome.Solved).solution.values.toList(),
        )
    }

    @Test
    fun `solves a board that needs more than one run per line`() {
        // 5x5:
        //   X X . X X
        //   . X X X .
        //   X . X . X
        //   . X X X .
        //   X X . X X
        val solver = NonogramSolver(5, 5)
        // Rows, top to bottom.
        solver.setRowClue(0, intArrayOf(2, 2))       // X X . X X
        solver.setRowClue(1, intArrayOf(3))          // . X X X .
        solver.setRowClue(2, intArrayOf(1, 1, 1))    // X . X . X
        solver.setRowClue(3, intArrayOf(3))          // . X X X .
        solver.setRowClue(4, intArrayOf(2, 2))       // X X . X X
        // Columns, top to bottom. Read off the grid above -- this board is not
        // transpose-symmetric, so these do not mirror the row clues.
        solver.setColClue(0, intArrayOf(1, 1, 1))    // X . X . X
        solver.setColClue(1, intArrayOf(2, 2))       // X X . X X
        solver.setColClue(2, intArrayOf(3))          // . X X X .
        solver.setColClue(3, intArrayOf(2, 2))       // X X . X X
        solver.setColClue(4, intArrayOf(1, 1, 1))    // X . X . X

        val outcome = step(solver, fullyScanned = true)
        assertTrue("expected Solved, got $outcome", outcome is SolveOutcome.Solved)
        val expected = intArrayOf(
            1, 1, 0, 1, 1,
            0, 1, 1, 1, 0,
            1, 0, 1, 0, 1,
            0, 1, 1, 1, 0,
            1, 1, 0, 1, 1,
        )
        assertEquals(expected.toList(), (outcome as SolveOutcome.Solved).solution.values.toList())
    }

    @Test
    fun `inconsistent clues are reported rather than guessed at`() {
        val solver = NonogramSolver(5, 5)
        // Row sums demand 5 filled cells, column sums demand none.
        for (r in 0 until 5) solver.setRowClue(r, intArrayOf(1))
        for (c in 0 until 5) solver.setColClue(c, IntArray(0))
        val outcome = step(solver, fullyScanned = true)
        assertTrue("expected Contradiction, got $outcome", outcome is SolveOutcome.Contradiction)
    }

    @Test
    fun `an empty board is a legal solution`() {
        val solver = NonogramSolver(4, 4)
        for (i in 0 until 4) {
            solver.setRowClue(i, IntArray(0))
            solver.setColClue(i, IntArray(0))
        }
        val outcome = step(solver, fullyScanned = true)
        assertTrue("expected Solved, got $outcome", outcome is SolveOutcome.Solved)
        assertTrue(
            (outcome as SolveOutcome.Solved).solution.values.all { it == 0 },
        )
    }
}
