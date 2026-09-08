package com.puzzlesolver.core.puzzle.sudoku

import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.PuzzleSolution
import com.puzzlesolver.core.solve.SolutionPolicy
import com.puzzlesolver.core.solve.SolveOutcome
import kotlin.math.sqrt

/**
 * Sudoku, as the reference implementation of a puzzle that can be answered from a
 * partial scan.
 *
 * The early-answer argument, since it is the crux of the app:
 *
 *   A well-posed sudoku has exactly one completion. Suppose we have seen only some
 *   of its givens and that subset already admits exactly one completion C. Every
 *   unseen given belongs to the true puzzle, whose completion is also a completion
 *   of the subset. Since the subset has only C as a completion, the true answer is
 *   C. So the unseen clues cannot change anything, and we can draw the answer the
 *   instant the seen subset becomes uniquely completable.
 *
 * The premise that matters is "well-posed". If the wall holds a puzzle with
 * multiple valid answers, an early answer is one of them rather than the one the
 * setter intended. That is a property of the puzzle, not a bug here, and the
 * pipeline surfaces it: [SolveOutcome.Solved] arriving before a full scan is
 * reported to the UI as such.
 *
 * Supports 4x4, 9x9 and 16x16 (any perfect square up to 16, bounded by the Int
 * candidate mask).
 */
class SudokuSolver(val n: Int = 9) : IncrementalSolver {

    private val box = sqrt(n.toDouble()).toInt()

    init {
        require(box * box == n) { "sudoku order must be a perfect square, got $n" }
        require(n <= 16) { "candidate masks are Ints; order capped at 16" }
    }

    private val cellCount = n * n
    private val fullMask = (1 shl n) - 1

    /** -1 where unknown; otherwise a 0-based digit. */
    private val given = IntArray(cellCount) { -1 }
    private val givenConfidence = FloatArray(cellCount)

    /** Scratch board for search; candidates as bitmasks. */
    private val candidates = IntArray(cellCount)
    private val board = IntArray(cellCount)

    private var lastSolution: PuzzleSolution? = null
    private var dirty = true

    override val policy = SolutionPolicy.UNIQUE_COMPLETION_SUFFICES

    override fun reset() {
        given.fill(-1)
        givenConfidence.fill(0f)
        lastSolution = null
        dirty = true
    }

    override fun observe(delta: ObservationDelta, deadlineNanos: Long): SolveOutcome {
        for (c in delta.cells + delta.revised) {
            val idx = c.row * n + c.col
            if (idx !in 0 until cellCount) continue
            val v = if (c.value == CellObservation.EMPTY) -1 else c.value - 1
            if (given[idx] != v) dirty = true
            given[idx] = v
            givenConfidence[idx] = c.confidence
        }
        if (!dirty) {
            val prev = lastSolution
            return if (prev != null) SolveOutcome.Solved(prev) else SolveOutcome.NeedMoreData()
        }
        dirty = false

        // Seed candidates from the givens and propagate.
        candidates.fill(fullMask)
        board.fill(-1)
        for (i in 0 until cellCount) {
            val g = given[i]
            if (g >= 0 && !assign(i, g)) {
                return SolveOutcome.Contradiction(
                    "given digits conflict at r${i / n + 1}c${i % n + 1}",
                    suspects(i),
                )
            }
        }

        val result = countSolutions(deadlineNanos, limit = 2)
        return when {
            result.timedOut -> SolveOutcome.Pending
            result.count == 0 -> SolveOutcome.Contradiction(
                "no completion exists for the digits read so far",
                lowestConfidenceGivens(),
            )
            result.count == 1 -> {
                val sol = PuzzleSolution(
                    cols = n,
                    rows = n,
                    values = IntArray(cellCount) { result.first[it] + 1 },
                    given = BooleanArray(cellCount) { given[it] >= 0 },
                    label = if (delta.fullyScanned) "solved" else "solved from partial scan",
                )
                lastSolution = sol
                SolveOutcome.Solved(sol)
            }
            else -> SolveOutcome.NeedMoreData(result.count)
        }
    }

    // -----------------------------------------------------------------------
    // Constraint propagation. Standard Norvig-style: assigning a digit eliminates
    // it from peers, and elimination cascades through naked and hidden singles.
    // Fast enough that the search below rarely has to guess more than once or twice.

    private fun assign(idx: Int, digit: Int): Boolean {
        val others = candidates[idx] and (1 shl digit).inv()
        var ok = true
        var m = others
        while (m != 0 && ok) {
            val d = Integer.numberOfTrailingZeros(m)
            m = m and (m - 1)
            ok = eliminate(idx, d)
        }
        return ok
    }

    private fun eliminate(idx: Int, digit: Int): Boolean {
        val bit = 1 shl digit
        if (candidates[idx] and bit == 0) return true          // already gone
        candidates[idx] = candidates[idx] and bit.inv()
        val remaining = candidates[idx]

        if (remaining == 0) return false                        // naked contradiction

        // Naked single: one candidate left, so peers cannot use it.
        if (Integer.bitCount(remaining) == 1) {
            val d = Integer.numberOfTrailingZeros(remaining)
            board[idx] = d
            forEachPeer(idx) { p ->
                if (!eliminate(p, d)) return false
            }
        }

        // Hidden single: this digit now fits in only one cell of some unit.
        val r = idx / n
        val c = idx % n
        if (!hiddenSingleInUnit(digit) { i -> r * n + i }) return false
        if (!hiddenSingleInUnit(digit) { i -> i * n + c }) return false
        val br = (r / box) * box
        val bc = (c / box) * box
        if (!hiddenSingleInUnit(digit) { i -> (br + i / box) * n + bc + i % box }) return false
        return true
    }

    private inline fun hiddenSingleInUnit(digit: Int, cellAt: (Int) -> Int): Boolean {
        val bit = 1 shl digit
        var place = -1
        var count = 0
        for (i in 0 until n) {
            val idx = cellAt(i)
            if (candidates[idx] and bit != 0) {
                count++
                if (count > 1) return true                       // not hidden, nothing to do
                place = idx
            }
        }
        if (count == 0) return false                             // digit has nowhere to go
        return assign(place, digit)
    }

    private inline fun forEachPeer(idx: Int, action: (Int) -> Unit) {
        val r = idx / n
        val c = idx % n
        for (i in 0 until n) {
            val a = r * n + i
            if (a != idx) action(a)
            val b = i * n + c
            if (b != idx) action(b)
        }
        val br = (r / box) * box
        val bc = (c / box) * box
        for (dr in 0 until box) {
            for (dc in 0 until box) {
                val p = (br + dr) * n + bc + dc
                if (p != idx) action(p)
            }
        }
    }

    // -----------------------------------------------------------------------

    private class CountResult(
        val count: Int,
        val first: IntArray,
        val timedOut: Boolean,
    )

    /**
     * Counts completions up to [limit].
     *
     * Stopping at two is the whole trick: we never need the exact number of
     * solutions, only whether it is one. Finding a second completion is usually far
     * cheaper than exhausting the space, so "still ambiguous" is answered quickly
     * and the loop can go back to waiting for more of the wall.
     */
    private fun countSolutions(deadlineNanos: Long, limit: Int): CountResult {
        val snapshot = candidates.copyOf()
        var found = 0
        val first = IntArray(cellCount)
        var timedOut = false

        fun search(cand: IntArray): Boolean {
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true
                return true                                       // unwind
            }
            // Most-constrained cell first.
            var target = -1
            var bestCount = Int.MAX_VALUE
            for (i in 0 until cellCount) {
                val bc = Integer.bitCount(cand[i])
                if (bc == 0) return false
                if (bc > 1 && bc < bestCount) {
                    bestCount = bc
                    target = i
                }
            }
            if (target < 0) {
                if (found == 0) for (i in 0 until cellCount) first[i] = Integer.numberOfTrailingZeros(cand[i])
                found++
                return found >= limit
            }
            var m = cand[target]
            while (m != 0) {
                val d = Integer.numberOfTrailingZeros(m)
                m = m and (m - 1)
                val saved = candidates.copyOf()
                System.arraycopy(cand, 0, candidates, 0, cellCount)
                val ok = assign(target, d)
                val next = candidates.copyOf()
                System.arraycopy(saved, 0, candidates, 0, cellCount)
                if (ok && search(next)) return true
            }
            return false
        }

        search(snapshot)
        System.arraycopy(snapshot, 0, candidates, 0, cellCount)
        return CountResult(found, first, timedOut)
    }

    /**
     * When the board contradicts itself the culprit is nearly always a misread
     * glyph, so we hand back the shakiest readings for re-observation rather than
     * declaring the puzzle unsolvable.
     */
    private fun lowestConfidenceGivens(): List<CellObservation> =
        (0 until cellCount)
            .filter { given[it] >= 0 }
            .sortedBy { givenConfidence[it] }
            .take(5)
            .map { CellObservation(it % n, it / n, given[it] + 1, givenConfidence[it]) }

    private fun suspects(idx: Int): List<CellObservation> {
        val out = ArrayList<CellObservation>(4)
        out.add(CellObservation(idx % n, idx / n, given[idx] + 1, givenConfidence[idx]))
        forEachPeer(idx) { p ->
            if (given[p] == given[idx] && out.size < 5) {
                out.add(CellObservation(p % n, p / n, given[p] + 1, givenConfidence[p]))
            }
        }
        return out
    }
}
