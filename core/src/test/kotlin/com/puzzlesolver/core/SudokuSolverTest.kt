package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.sudoku.SudokuSolver
import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.SolveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the claim the app is built on: that a sudoku can be answered from a partial
 * scan, and that the solver knows when it cannot.
 *
 * The 17-clue puzzle below is the lever. Seventeen is the proven minimum number of
 * clues for a uniquely solvable sudoku, so:
 *   - with all 17 read, there is exactly one completion and we must answer;
 *   - with any 16 of them, there are provably at least two completions and we must
 *     refuse to answer.
 * That gives a sharp, mathematically guaranteed boundary to test against, rather
 * than a threshold someone tuned by hand.
 */
class SudokuSolverTest {

    /** A well-known 17-clue puzzle. Zeros are unknowns. */
    private val puzzle17 = listOf(
        "000000010",
        "400000000",
        "020000000",
        "000050407",
        "008000300",
        "001090000",
        "300400200",
        "050100000",
        "000806000",
    )

    private fun cluesOf(rows: List<String>): List<CellObservation> {
        val out = ArrayList<CellObservation>()
        rows.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch != '0') out.add(CellObservation(c, r, ch - '0', 1f))
            }
        }
        return out
    }

    private fun deadline() = System.nanoTime() + 20_000_000_000L    // 20 s, generous for CI

    private fun observe(
        solver: SudokuSolver,
        clues: List<CellObservation>,
        fullyScanned: Boolean = false,
    ): SolveOutcome {
        var outcome: SolveOutcome = SolveOutcome.Pending
        // Retry on Pending so a slow machine cannot turn a correctness test into a
        // timing test.
        repeat(5) {
            outcome = solver.observe(
                ObservationDelta(clues, emptyList(), clues.size / 81f, fullyScanned),
                deadline(),
            )
            if (outcome !is SolveOutcome.Pending) return outcome
        }
        return outcome
    }

    @Test
    fun `seventeen clues are enough to answer before the scan finishes`() {
        val clues = cluesOf(puzzle17)
        assertEquals("puzzle should have 17 clues", 17, clues.size)

        val solver = SudokuSolver(9)
        val outcome = observe(solver, clues, fullyScanned = false)

        assertTrue("expected Solved, got $outcome", outcome is SolveOutcome.Solved)
        val solution = (outcome as SolveOutcome.Solved).solution
        assertValidSudoku(solution.values)
        // Every clue we read must survive into the answer.
        for (c in clues) {
            assertEquals(
                "clue at r${c.row}c${c.col}",
                c.value,
                solution.valueAt(c.col, c.row),
            )
        }
        assertTrue("solved without a full scan should be flagged", solution.label.contains("partial"))
    }

    @Test
    fun `sixteen clues are provably not enough and the solver refuses`() {
        val all = cluesOf(puzzle17)
        // Dropping any single clue from a minimal puzzle must destroy uniqueness.
        // Test several, not just one, so a lucky index cannot hide a bug.
        for (dropIndex in intArrayOf(0, 4, 8, 12, 16)) {
            val subset = all.filterIndexed { i, _ -> i != dropIndex }
            assertEquals(16, subset.size)
            val solver = SudokuSolver(9)
            val outcome = observe(solver, subset, fullyScanned = false)
            assertTrue(
                "16 clues (dropped #$dropIndex) must stay ambiguous, got $outcome",
                outcome is SolveOutcome.NeedMoreData,
            )
        }
    }

    @Test
    fun `clues fed in batches reach the same answer as one batch`() {
        val all = cluesOf(puzzle17)
        val incremental = SudokuSolver(9)
        var last: SolveOutcome = SolveOutcome.Pending
        // Feed a few at a time, the way a pan across the wall would deliver them.
        for (chunk in all.chunked(4)) {
            last = observe(incremental, chunk, fullyScanned = false)
        }
        assertTrue("expected Solved after all batches, got $last", last is SolveOutcome.Solved)

        val atOnce = SudokuSolver(9)
        val reference = observe(atOnce, all, fullyScanned = false)
        assertTrue(reference is SolveOutcome.Solved)
        assertEquals(
            (reference as SolveOutcome.Solved).solution.values.toList(),
            (last as SolveOutcome.Solved).solution.values.toList(),
        )
    }

    @Test
    fun `conflicting reads are reported as a contradiction with suspects`() {
        val solver = SudokuSolver(9)
        val outcome = observe(
            solver,
            listOf(
                CellObservation(0, 0, 5, 0.9f),
                CellObservation(4, 0, 5, 0.4f),      // same row, same digit
            ),
        )
        assertTrue("expected Contradiction, got $outcome", outcome is SolveOutcome.Contradiction)
        val suspects = (outcome as SolveOutcome.Contradiction).suspectCells
        assertTrue("should name the cells to re-read", suspects.isNotEmpty())
    }

    @Test
    fun `blank cells are treated as unknown rather than as a digit`() {
        val solver = SudokuSolver(9)
        val clues = cluesOf(puzzle17) +
            // Explicitly-blank observations must not constrain anything.
            listOf(CellObservation(8, 8, CellObservation.EMPTY, 1f))
        val outcome = observe(solver, clues)
        assertTrue("blanks must not break solving, got $outcome", outcome is SolveOutcome.Solved)
    }

    @Test
    fun `four by four sudoku also works`() {
        val solver = SudokuSolver(4)
        val clues = listOf(
            CellObservation(0, 0, 1, 1f),
            CellObservation(1, 1, 3, 1f),
            CellObservation(2, 2, 2, 1f),
            CellObservation(3, 3, 4, 1f),
            CellObservation(1, 0, 2, 1f),
            CellObservation(0, 2, 3, 1f),
        )
        val outcome = observe(solver, clues)
        if (outcome is SolveOutcome.Solved) {
            assertValidSudoku(outcome.solution.values, 4)
        } else {
            // Not every 4x4 clue set is unique; the point is that it must not crash or
            // claim a wrong answer.
            assertTrue(outcome is SolveOutcome.NeedMoreData)
        }
    }

    private fun assertValidSudoku(values: IntArray, n: Int = 9) {
        val box = Math.sqrt(n.toDouble()).toInt()
        for (i in 0 until n) {
            val row = HashSet<Int>()
            val col = HashSet<Int>()
            for (j in 0 until n) {
                assertTrue("value out of range", values[i * n + j] in 1..n)
                row.add(values[i * n + j])
                col.add(values[j * n + i])
            }
            assertEquals("row $i must hold every digit", n, row.size)
            assertEquals("column $i must hold every digit", n, col.size)
        }
        for (br in 0 until box) {
            for (bc in 0 until box) {
                val seen = HashSet<Int>()
                for (dr in 0 until box) {
                    for (dc in 0 until box) {
                        seen.add(values[(br * box + dr) * n + bc * box + dc])
                    }
                }
                assertEquals("box ($br,$bc) must hold every digit", n, seen.size)
            }
        }
    }
}
