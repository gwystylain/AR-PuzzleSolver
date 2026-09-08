package com.puzzlesolver.core.solve

/**
 * One observed cell of the puzzle.
 *
 * [value] is adapter-specific: a digit, a colour index, a letter, a wall/floor bit.
 * [confidence] lets the solver treat a shaky reading as provisional and revisit it
 * if the constraint propagation runs into a contradiction.
 */
data class CellObservation(
    val col: Int,
    val row: Int,
    val value: Int,
    val confidence: Float,
) {
    companion object {
        /** Sentinel for "this cell is definitely blank", as distinct from "not yet seen". */
        const val EMPTY = -1
    }
}

/**
 * A batch of newly observed or revised cells, plus how much of the board we have
 * now seen. Solvers are fed deltas, never full boards, so cost scales with what
 * changed rather than with puzzle size.
 */
data class ObservationDelta(
    val cells: List<CellObservation>,
    val revised: List<CellObservation> = emptyList(),
    /** 0..1 fraction of grid cells observed at usable confidence. */
    val coverageFraction: Float,
    val fullyScanned: Boolean,
)

/** What the solver can say after digesting a delta. */
sealed interface SolveOutcome {

    /**
     * The observed clues are consistent but do not yet pin down a unique answer.
     * [remainingAmbiguity] is a coarse hint for the UI -- for a search-based solver,
     * how many distinct completions were found before the cap was hit.
     */
    data class NeedMoreData(val remainingAmbiguity: Int = -1) : SolveOutcome

    /**
     * A unique completion exists given only what we have seen. This is the whole
     * point of the incremental design: for constraint puzzles this can fire long
     * before the scan is finished, and the answer is provably the same one a full
     * scan would produce (see [SolutionPolicy]).
     */
    data class Solved(val solution: PuzzleSolution) : SolveOutcome

    /**
     * The observations contradict each other. Almost always a misread cell rather
     * than a malformed puzzle, so the pipeline responds by lowering confidence on
     * the cells named here and re-observing them, not by giving up.
     */
    data class Contradiction(val reason: String, val suspectCells: List<CellObservation>) : SolveOutcome

    /** Work is still running on the solver thread; the previous outcome still stands. */
    data object Pending : SolveOutcome
}

/**
 * The answer, in grid coordinates. Rendering turns this into canvas-space geometry
 * and then into a camera overlay -- the solver itself knows nothing about pixels.
 */
data class PuzzleSolution(
    val cols: Int,
    val rows: Int,
    /** Row-major values, or [CellObservation.EMPTY] where nothing should be drawn. */
    val values: IntArray,
    /** True for cells that were read from the wall rather than deduced. */
    val given: BooleanArray,
    /** Optional ordered path, for maze/route puzzles. Each entry is row * cols + col. */
    val path: IntArray? = null,
    val label: String = "",
) {
    fun valueAt(col: Int, row: Int): Int = values[row * cols + col]
    fun isGiven(col: Int, row: Int): Boolean = given[row * cols + col]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PuzzleSolution) return false
        return cols == other.cols && rows == other.rows &&
            values.contentEquals(other.values) && given.contentEquals(other.given) &&
            (path?.contentEquals(other.path ?: IntArray(0)) ?: (other.path == null))
    }

    override fun hashCode(): Int =
        (cols * 31 + rows) * 31 + values.contentHashCode()
}

/**
 * How a puzzle type earns the right to answer early.
 *
 * This is the distinction the app is built around, so it is explicit in the type
 * system rather than buried in each adapter.
 */
enum class SolutionPolicy {
    /**
     * The puzzle has a unique solution by construction, so *any* set of clues with
     * exactly one completion determines it. Sudoku, kakuro, and most logic grids
     * are like this: once the seen clues admit one completion, unseen clues cannot
     * disagree without making the puzzle ill-posed. We can answer mid-scan.
     */
    UNIQUE_COMPLETION_SUFFICES,

    /**
     * Every clue is load-bearing and an unseen one can still change the answer.
     * Nonograms, word searches, jigsaw-style puzzles. We must finish the scan.
     */
    REQUIRES_FULL_SCAN,
}

/**
 * A solver that digests observations incrementally.
 *
 * Contract:
 *  - [observe] is called on a background thread, never the render thread.
 *  - Implementations must be interruption-friendly: check [Thread.interrupted] or
 *    the supplied deadline in any search loop, and return [SolveOutcome.Pending]
 *    rather than blocking the pipeline. New observations arriving mid-search should
 *    cancel it, since the search was working from stale data anyway.
 *  - Implementations should be incremental where the puzzle allows it. Re-solving
 *    from scratch on every delta is correct but wasteful; constraint propagation
 *    state is usually cheap to keep.
 */
interface IncrementalSolver {

    val policy: SolutionPolicy

    /**
     * @param deadlineNanos wall-clock deadline from [System.nanoTime]. Return
     *        [SolveOutcome.Pending] if reached, and the pipeline will call again.
     */
    fun observe(delta: ObservationDelta, deadlineNanos: Long): SolveOutcome

    /** Drops all state. Called when the grid model changes incompatibly. */
    fun reset()
}
