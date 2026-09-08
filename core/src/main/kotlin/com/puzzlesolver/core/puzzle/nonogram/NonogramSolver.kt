package com.puzzlesolver.core.puzzle.nonogram

import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.PuzzleSolution
import com.puzzlesolver.core.solve.SolutionPolicy
import com.puzzlesolver.core.solve.SolveOutcome

/**
 * Nonogram, as the reference implementation of a puzzle that genuinely cannot be
 * answered early.
 *
 * Every clue constrains an entire line, and an unseen clue can flip cells anywhere
 * along it. There is no subset argument like sudoku's: a partially read nonogram is
 * not a well-posed nonogram, it is a different, under-constrained puzzle whose
 * answers usually have nothing to do with the real one. So the policy is
 * [SolutionPolicy.REQUIRES_FULL_SCAN] and the solver refuses to guess until the
 * pipeline reports the whole board covered.
 *
 * Clues arrive as observations on a virtual grid: the pipeline hands us the clue
 * strips as negative row/column indices (see [NonogramAdapter]), which keeps the
 * observation interface uniform across puzzle types.
 */
class NonogramSolver(
    private val cols: Int,
    private val rows: Int,
) : IncrementalSolver {

    /** Per-line clue runs. Null until read. */
    private val rowClues = arrayOfNulls<IntArray>(rows)
    private val colClues = arrayOfNulls<IntArray>(cols)

    /** UNKNOWN / FILLED / BLANK per cell. */
    private val cells = ByteArray(cols * rows)

    private var lastSolution: PuzzleSolution? = null
    private var dirty = true

    override val policy = SolutionPolicy.REQUIRES_FULL_SCAN

    override fun reset() {
        rowClues.fill(null)
        colClues.fill(null)
        cells.fill(UNKNOWN)
        lastSolution = null
        dirty = true
    }

    /**
     * Clue observations use the encoding described on [NonogramAdapter]: rows are
     * addressed with col = -1 and the run values packed into [CellObservation.value]
     * one at a time, in reading order. The pipeline delivers them grouped, so we
     * rebuild the run list from the ordered batch.
     */
    fun setRowClue(row: Int, runs: IntArray) {
        if (row in 0 until rows) {
            rowClues[row] = runs
            dirty = true
        }
    }

    fun setColClue(col: Int, runs: IntArray) {
        if (col in 0 until cols) {
            colClues[col] = runs
            dirty = true
        }
    }

    val cluesComplete: Boolean
        get() = rowClues.all { it != null } && colClues.all { it != null }

    override fun observe(delta: ObservationDelta, deadlineNanos: Long): SolveOutcome {
        // Deliberately refuses to speculate. Reporting the missing count gives the
        // UI something honest to show while the user keeps panning.
        if (!delta.fullyScanned || !cluesComplete) {
            val missing = rowClues.count { it == null } + colClues.count { it == null }
            return SolveOutcome.NeedMoreData(missing)
        }
        if (!dirty) {
            val prev = lastSolution
            if (prev != null) return SolveOutcome.Solved(prev)
        }
        dirty = false

        cells.fill(UNKNOWN)
        if (!propagate(deadlineNanos)) {
            return SolveOutcome.Contradiction("clues are inconsistent", emptyList())
        }
        val solved = search(deadlineNanos)
        return when (solved) {
            SearchResult.TIMEOUT -> SolveOutcome.Pending
            SearchResult.NONE -> SolveOutcome.Contradiction(
                "no picture satisfies the clues as read",
                emptyList(),
            )
            SearchResult.FOUND -> {
                val sol = PuzzleSolution(
                    cols = cols,
                    rows = rows,
                    values = IntArray(cols * rows) { if (cells[it] == FILLED) 1 else 0 },
                    given = BooleanArray(cols * rows),
                    label = "solved",
                )
                lastSolution = sol
                SolveOutcome.Solved(sol)
            }
        }
    }

    private enum class SearchResult { FOUND, NONE, TIMEOUT }

    /**
     * Line-by-line constraint propagation to a fixpoint.
     *
     * For each line we compute, over all placements consistent with the clue and
     * the cells already fixed, which positions are filled in *every* placement and
     * which are blank in every placement. Those become forced. This resolves the
     * large majority of published nonograms without any search at all.
     */
    private fun propagate(deadlineNanos: Long): Boolean {
        var changed = true
        val line = ByteArray(maxOf(cols, rows))
        val forcedFilled = BooleanArray(maxOf(cols, rows))
        val forcedBlank = BooleanArray(maxOf(cols, rows))

        while (changed) {
            if (System.nanoTime() > deadlineNanos) return true   // caller re-enters
            changed = false

            for (r in 0 until rows) {
                val clue = rowClues[r] ?: return false
                for (c in 0 until cols) line[c] = cells[r * cols + c]
                if (!solveLine(line, cols, clue, forcedFilled, forcedBlank)) return false
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (forcedFilled[c] && cells[idx] != FILLED) {
                        if (cells[idx] == BLANK) return false
                        cells[idx] = FILLED
                        changed = true
                    } else if (forcedBlank[c] && cells[idx] != BLANK) {
                        if (cells[idx] == FILLED) return false
                        cells[idx] = BLANK
                        changed = true
                    }
                }
            }

            for (c in 0 until cols) {
                val clue = colClues[c] ?: return false
                for (r in 0 until rows) line[r] = cells[r * cols + c]
                if (!solveLine(line, rows, clue, forcedFilled, forcedBlank)) return false
                for (r in 0 until rows) {
                    val idx = r * cols + c
                    if (forcedFilled[r] && cells[idx] != FILLED) {
                        if (cells[idx] == BLANK) return false
                        cells[idx] = FILLED
                        changed = true
                    } else if (forcedBlank[r] && cells[idx] != BLANK) {
                        if (cells[idx] == FILLED) return false
                        cells[idx] = BLANK
                        changed = true
                    }
                }
            }
        }
        return true
    }

    /**
     * Enumerates placements of [clue] in a line of [len], counting for each position
     * how many placements fill it. Positions filled by all placements, or by none,
     * are forced.
     *
     * Implemented as memoised recursion over (clue index, position), which is O(len
     * * clues) rather than the exponential naive enumeration.
     */
    private fun solveLine(
        line: ByteArray,
        len: Int,
        clue: IntArray,
        outFilled: BooleanArray,
        outBlank: BooleanArray,
    ): Boolean {
        val k = clue.size
        // counts[i] = number of valid placements filling position i
        val counts = LongArray(len)
        var total = 0L

        // memo[c][p] = number of ways to place clues c.. starting at or after p
        val memo = Array(k + 1) { LongArray(len + 1) { -1L } }

        fun canFill(from: Int, length: Int): Boolean {
            if (from + length > len) return false
            for (i in from until from + length) if (line[i] == BLANK) return false
            if (from + length < len && line[from + length] == FILLED) return false
            return true
        }

        fun ways(c: Int, p: Int): Long {
            // p == len + 1 is reachable and legitimate: a run that ends exactly on the
            // last cell leaves the cursor one past the end. That is a valid placement
            // if and only if no clues remain. Returning 0 unconditionally here rejects
            // every clue that fills a line to its edge -- including the whole of a
            // [5]-in-5 row -- and reports the puzzle as inconsistent.
            if (p > len) return if (c == k) 1L else 0L
            memo[c][p].let { if (it >= 0) return it }
            var acc = 0L
            if (c == k) {
                // Everything from p on must be blank-compatible.
                var ok = true
                for (i in p until len) if (line[i] == FILLED) { ok = false; break }
                acc = if (ok) 1L else 0L
            } else {
                var start = p
                while (start + clue[c] <= len) {
                    if (canFill(start, clue[c])) {
                        acc += ways(c + 1, start + clue[c] + 1)
                    }
                    // Cannot skip past a fixed filled cell -- it has to belong to this run.
                    if (line[start] == FILLED) break
                    start++
                }
            }
            memo[c][p] = acc
            return acc
        }

        total = ways(0, 0)
        if (total == 0L) return false

        // Second pass: how many of those placements fill each position.
        //
        // Sweep the clues forward carrying `before[p]` = number of ways to have
        // placed clues 0..c-1 with the next free position at p. Each candidate
        // placement of clue c at `start` then accounts for before[p] * ways(c+1, ..)
        // complete placements, all of which fill [start, start + clue[c]). Recording
        // that on a difference array turns the whole thing into one linear sweep.
        val diff = LongArray(len + 2)
        val before = LongArray(len + 1)
        before[0] = 1
        for (c in 0 until k) {
            val next = LongArray(len + 1)
            for (p in 0..len) {
                val mult = before[p]
                if (mult == 0L) continue
                var start = p
                while (start + clue[c] <= len) {
                    if (canFill(start, clue[c])) {
                        val w = ways(c + 1, start + clue[c] + 1)
                        if (w > 0) {
                            val m = mult * w
                            diff[start] += m
                            diff[start + clue[c]] -= m
                        }
                        val nxt = start + clue[c] + 1
                        if (nxt <= len) next[nxt] += mult
                    }
                    // A fixed filled cell has to belong to this run, so we cannot
                    // slide the run past it.
                    if (line[start] == FILLED) break
                    start++
                }
            }
            System.arraycopy(next, 0, before, 0, len + 1)
        }

        var running = 0L
        for (i in 0 until len) {
            running += diff[i]
            counts[i] = running
        }

        for (i in 0 until len) {
            outFilled[i] = counts[i] == total
            outBlank[i] = counts[i] == 0L
        }
        return true
    }

    /**
     * Falls back to search on the ambiguous cells left by propagation. Nonograms
     * that need this are rare; when they do, branching on the most-constrained
     * unknown cell and re-propagating settles it quickly.
     */
    private fun search(deadlineNanos: Long): SearchResult {
        if (System.nanoTime() > deadlineNanos) return SearchResult.TIMEOUT
        var target = -1
        for (i in cells.indices) {
            if (cells[i] == UNKNOWN) {
                target = i
                break
            }
        }
        if (target < 0) return SearchResult.FOUND

        for (guess in byteArrayOf(FILLED, BLANK)) {
            val snapshot = cells.copyOf()
            cells[target] = guess
            if (propagate(deadlineNanos)) {
                when (val r = search(deadlineNanos)) {
                    SearchResult.FOUND -> return r
                    SearchResult.TIMEOUT -> {
                        System.arraycopy(snapshot, 0, cells, 0, cells.size)
                        return r
                    }
                    SearchResult.NONE -> Unit
                }
            }
            System.arraycopy(snapshot, 0, cells, 0, cells.size)
        }
        return SearchResult.NONE
    }

    companion object {
        const val UNKNOWN: Byte = 0
        const val FILLED: Byte = 1
        const val BLANK: Byte = 2
    }
}
