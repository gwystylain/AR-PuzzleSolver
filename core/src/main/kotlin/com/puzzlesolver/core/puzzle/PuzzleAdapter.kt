package com.puzzlesolver.core.puzzle

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.PuzzleSolution

/**
 * Everything that is specific to one kind of puzzle, behind one interface.
 *
 * Adding a puzzle type means writing one of these and registering it. Nothing in
 * the capture, geometry, canvas, or rendering layers needs to know it exists.
 */
interface PuzzleAdapter {

    val id: String
    val displayName: String

    /**
     * How confident this adapter is that the canvas holds *its* kind of puzzle,
     * 0..1. The registry runs every adapter and takes the winner, so this needs to
     * be honest about negatives: return near zero when the evidence is absent
     * rather than when it merely disagrees.
     */
    fun identify(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): Float

    /**
     * Reads the cells that have become newly readable.
     *
     * @param alreadyRead cells the pipeline has already accepted, so the adapter can
     *        skip them. Cells whose canvas confidence has *improved* materially are
     *        cleared from this set by the caller and so will be re-read.
     * @return observations for cells readable at usable confidence. Cells that are
     *         visible but genuinely blank must be reported with
     *         [CellObservation.EMPTY], not omitted -- "blank" is a clue.
     */
    fun readCells(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
        alreadyRead: BooleanArray,
    ): List<CellObservation>

    fun createSolver(grid: GridModel): IncrementalSolver

    /**
     * Colour-aware entry points. Everything the engine calls goes through these, and
     * they default to the single-channel versions above, so an adapter that only wants
     * luma implements nothing extra.
     *
     * They exist because one puzzle type -- a wall of RGB buttons -- has colour as its
     * entire state, and no amount of care with luma recovers it: a red hazard and a
     * white target are both clipped to the same white by the time the sensor sees them.
     */
    fun identify(view: CanvasView, grid: GridModel, coverage: CoverageMap, spec: CanvasSpec): Float =
        identify(view.luma, grid, coverage, spec)

    fun readCells(
        view: CanvasView,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
        alreadyRead: BooleanArray,
    ): List<CellObservation> = readCells(view.luma, grid, coverage, spec, alreadyRead)

    /** True when this adapter cannot work without chroma being streamed. */
    val requiresColour: Boolean get() = false

    /**
     * Why this adapter's own grid detector last declined, for the log.
     *
     * "No grid" has several causes that look identical from outside -- nothing
     * observed, too few buttons, a pitch outside the believable range, blobs that do
     * not share a lattice -- and each one implies a different fix. On a wall, in a
     * room, with one attempt at a scan, guessing between them is expensive.
     */
    val lastGridReport: String get() = ""

    /**
     * An adapter's own grid detector, for puzzles the shared one cannot see.
     *
     * Returning null -- the default -- means "use `GridDetector`", which is right for
     * anything ruled. It is not right for a lattice of buttons with no lines drawn
     * between them: handed one of those, `GridDetector` does not fail, it reports a
     * pitch an order of magnitude too fine at high confidence, and everything
     * downstream then reads cells that are not where it thinks they are.
     *
     * A grid returned here is only adopted if this adapter then also identifies the
     * canvas as its own, so a specialised detector firing on the wrong puzzle costs a
     * little work rather than a wrong answer.
     */
    fun detectGrid(view: CanvasView, coverage: CoverageMap, spec: CanvasSpec): GridModel? = null

    /**
     * Tally of what happened during the last [readCells], for diagnostics.
     *
     * Cell reading declines for several unrelated reasons -- not covered yet, too little
     * contrast, ambiguous glyph -- and they are indistinguishable from the outside while
     * looking identical in the UI: the count simply stops rising. Each one implies a
     * different fix, so each needs naming.
     */
    val lastReadStats: String get() = ""

    /**
     * Turns a solution into draw instructions in *grid* coordinates. Keeping this
     * on the adapter means a maze can draw a path while a sudoku draws digits,
     * without the renderer growing a switch statement.
     */
    fun overlayFor(solution: PuzzleSolution, grid: GridModel): OverlayModel
}

/**
 * Renderer-agnostic description of what to draw over the puzzle. The GL layer
 * converts these to canvas texels via [GridModel], then to screen space via the
 * wall surface and the current camera pose.
 */
data class OverlayModel(
    val glyphs: List<Glyph> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
) {
    /** A character or short string centred in a cell. */
    data class Glyph(
        val col: Int,
        val row: Int,
        val text: String,
        val colorRgba: Int,
        /** Relative to cell size. */
        val scale: Float = 0.7f,
    )

    /** A polyline through fractional cell coordinates; used for routes and paths. */
    data class Stroke(
        val points: FloatArray,
        val colorRgba: Int,
        /** Relative to cell size. */
        val widthCells: Float = 0.15f,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Stroke && points.contentEquals(other.points) &&
                colorRgba == other.colorRgba && widthCells == other.widthCells

        override fun hashCode(): Int = points.contentHashCode() * 31 + colorRgba
    }

    /** A filled cell, used for shading and for flagging low-confidence reads. */
    data class Highlight(
        val col: Int,
        val row: Int,
        val colorRgba: Int,
    )

    companion object {
        val EMPTY = OverlayModel()
    }
}

/**
 * Picks the adapter that best explains the canvas.
 *
 * Identification is sticky on purpose: once we have committed to a puzzle type and
 * started accumulating observations, a momentarily better score from a rival
 * adapter should not throw that work away. Only a sustained, clearly better score
 * switches us.
 */
class PuzzleRegistry(private val adapters: List<PuzzleAdapter>) {

    private var committed: PuzzleAdapter? = null
    private var committedScore = 0f
    private var rivalStreak = 0

    fun all(): List<PuzzleAdapter> = adapters

    fun byId(id: String): PuzzleAdapter? = adapters.firstOrNull { it.id == id }

    /** Forces a specific adapter, e.g. from a debug menu or a replay config. */
    fun pin(id: String): PuzzleAdapter? = byId(id)?.also {
        committed = it
        committedScore = 1f
        rivalStreak = 0
        pinned = true
    }

    fun reset() {
        committed = null
        committedScore = 0f
        rivalStreak = 0
        pinned = false
    }

    /**
     * Whether the committed adapter was chosen by the user rather than by evidence.
     *
     * The distinction matters to the UI: "I am reading this as a sudoku because it
     * looks like one" and "I am reading this as a sudoku because you said so" fail in
     * different ways and want different fixes.
     */
    var pinned: Boolean = false
        private set

    val committedId: String? get() = committed?.id

    /** Hands identification back to the evidence. */
    fun unpin() {
        pinned = false
        committed = null
        committedScore = 0f
        rivalStreak = 0
    }

    fun select(
        view: CanvasView,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): PuzzleAdapter? {
        var best: PuzzleAdapter? = null
        var bestScore = 0f
        for (a in adapters) {
            val s = a.identify(view, grid, coverage, spec)
            if (s > bestScore) {
                bestScore = s
                best = a
            }
        }
        if (pinned) return committed
        if (bestScore < MIN_IDENTIFY_SCORE) return committed

        val current = committed
        if (current == null) {
            committed = best
            committedScore = bestScore
            return best
        }
        if (best === current) {
            committedScore = maxOf(committedScore, bestScore)
            rivalStreak = 0
            return current
        }
        // A rival has to beat us clearly, several detections running.
        if (bestScore > committedScore * SWITCH_MARGIN) {
            rivalStreak++
            if (rivalStreak >= SWITCH_STREAK) {
                committed = best
                committedScore = bestScore
                rivalStreak = 0
                return best
            }
        } else {
            rivalStreak = 0
        }
        return current
    }

    private companion object {
        const val MIN_IDENTIFY_SCORE = 0.35f
        const val SWITCH_MARGIN = 1.4f
        const val SWITCH_STREAK = 3
    }
}
