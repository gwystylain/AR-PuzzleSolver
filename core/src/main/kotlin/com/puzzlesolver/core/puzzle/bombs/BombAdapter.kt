package com.puzzlesolver.core.puzzle.bombs

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.OverlayModel
import com.puzzlesolver.core.puzzle.PuzzleAdapter
import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.PuzzleSolution

/**
 * The wall of RGB buttons.
 *
 * Almost nothing about this puzzle fits the shape the rest of the app assumes, and each
 * departure is here rather than bolted onto shared code:
 *
 *  - **The grid has no lines.** `GridDetector` reports a pitch an order of magnitude too
 *    fine at high confidence, so [detectGrid] supplies [ButtonLatticeDetector] instead.
 *  - **Cells carry no glyph.** There is nothing to classify, only a colour, so
 *    [GlyphClassifier] is not involved at all.
 *  - **The colour is not in the middle of the cell.** A lit button clips its own face to
 *    white, so the reading is taken from the glow around it. See [ButtonPalette].
 *  - **Luma alone is useless.** A red hazard and a white target are the same white once
 *    clipped, so this adapter declines to read anything without chroma.
 */
class BombAdapter : PuzzleAdapter {

    override val id = ID
    override val displayName = "Mines"

    override val requiresColour = true

    private var detector: ButtonLatticeDetector? = null
    private var stats = ""

    override val lastReadStats: String get() = stats

    override val lastGridReport: String get() = detector?.lastReport ?: "no detector yet"

    override fun detectGrid(view: CanvasView, coverage: CoverageMap, spec: CanvasSpec): GridModel? {
        val d = detector?.takeIf { it.specMatches(spec) } ?: ButtonLatticeDetector(spec).also { detector = it }
        return d.detect(view.luma, coverage)
    }

    /**
     * How much this canvas looks like a wall of buttons.
     *
     * The evidence is deliberately about *shape* rather than colour. Colour is what the
     * solver reads, so scoring on it too would let one bad frame of white balance both
     * misread the board and claim the board harder. Square cells, a plausible physical
     * pitch, and a lattice the detector actually fitted are all independent of it.
     */
    override fun identify(
        view: CanvasView,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): Float {
        if (!view.hasColour) return 0f
        val size = grid.cellSizeMetres(spec)
        // Arcade buttons on a wall, not a printed puzzle: a few centimetres apart.
        if (size[0] < 0.03f || size[0] > 0.40f) return 0f
        // The lattice is square. A ruled puzzle photographed off-axis is not.
        val squareness = 1f - (kotlin.math.abs(size[0] - size[1]) / maxOf(size[0], size[1]))
        if (squareness < 0.85f) return 0f

        val lit = countLitButtons(view, grid, coverage)
        val cells = grid.cellCount.coerceAtLeast(1)
        val litFraction = lit.toFloat() / cells
        // A board with nothing lit is not this puzzle; one that is entirely lit is not
        // either, since mines go on unlit buttons.
        if (litFraction < 0.02f || litFraction > 0.75f) return 0f

        return (squareness * grid.confidence).coerceIn(0f, 1f)
    }

    override fun readCells(
        view: CanvasView,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
        alreadyRead: BooleanArray,
    ): List<CellObservation> {
        if (!view.hasColour) {
            stats = "no chroma: refusing to read colour off brightness"
            return emptyList()
        }
        val out = ArrayList<CellObservation>()
        val halo = FloatArray(3)
        val ambient = FloatArray(3)
        val scratch = FloatArray(3)

        var uncovered = 0
        var unlit = 0
        var lit = 0
        var unnamed = 0

        val pitch = grid.pitchX
        val inner = pitch * ButtonPalette.HALO_INNER_FRACTION
        val outer = pitch * ButtonPalette.HALO_OUTER_FRACTION
        // Between the buttons, far enough out to be panel rather than glow.
        val ambientInner = pitch * 0.42f
        val ambientOuter = pitch * 0.50f

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val idx = row * grid.cols + col
                if (idx < alreadyRead.size && alreadyRead[idx]) continue

                val b = grid.cellBounds(col, row)
                if (coverage.coverageOfRect(b[0], b[1], b[2], b[3]) < MIN_COVERAGE) {
                    uncovered++
                    continue
                }

                val c = grid.cellCentre(col, row)
                val ambientN = view.meanRgbInAnnulus(
                    c[0], c[1], ambientInner, ambientOuter, ambient, scratch = scratch,
                )
                if (ambientN < 8) {
                    uncovered++
                    continue
                }
                val haloN = view.meanRgbInAnnulus(
                    c[0], c[1], inner, outer, halo, scratch = scratch,
                )
                if (haloN < 8) {
                    uncovered++
                    continue
                }

                val reading = ButtonPalette.classify(halo, ambient)
                when (reading.button) {
                    Button.EMPTY -> unlit++
                    Button.OPAQUE -> unnamed++
                    else -> lit++
                }
                out.add(CellObservation(col, row, reading.button, reading.confidence))
            }
        }
        stats = "unlit=$unlit lit=$lit unnamed=$unnamed uncovered=$uncovered"
        return out
    }

    private fun countLitButtons(view: CanvasView, grid: GridModel, coverage: CoverageMap): Int {
        val halo = FloatArray(3)
        val ambient = FloatArray(3)
        val scratch = FloatArray(3)
        val pitch = grid.pitchX
        var lit = 0
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val b = grid.cellBounds(col, row)
                if (coverage.coverageOfRect(b[0], b[1], b[2], b[3]) < MIN_COVERAGE) continue
                val c = grid.cellCentre(col, row)
                if (view.meanRgbInAnnulus(
                        c[0], c[1], pitch * 0.42f, pitch * 0.50f, ambient, scratch = scratch,
                    ) < 8
                ) continue
                if (view.meanRgbInAnnulus(
                        c[0], c[1],
                        pitch * ButtonPalette.HALO_INNER_FRACTION,
                        pitch * ButtonPalette.HALO_OUTER_FRACTION,
                        halo, scratch = scratch,
                    ) < 8
                ) continue
                if (ButtonPalette.classify(halo, ambient).button != Button.EMPTY) lit++
            }
        }
        return lit
    }

    override fun createSolver(grid: GridModel): IncrementalSolver =
        BombSolver(grid.cols, grid.rows)

    /**
     * Draws the buttons to press.
     *
     * Only the first detonation is shown solid. The rest is a preview, and deliberately
     * looks like one: the board is re-scanned after every detonation and the plan
     * re-derived from what is actually on the wall, so later rounds are a forecast that
     * the next sweep will replace rather than instructions to follow blind.
     */
    override fun overlayFor(solution: PuzzleSolution, grid: GridModel): OverlayModel {
        val glyphs = ArrayList<OverlayModel.Glyph>()
        val highlights = ArrayList<OverlayModel.Highlight>()

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val mask = solution.valueAt(col, row)
                if (mask <= 0) continue
                val first = Integer.numberOfTrailingZeros(mask) + 1
                val label = (1..MAX_LABELLED_ROUNDS)
                    .filter { mask and (1 shl (it - 1)) != 0 }
                    .joinToString(",")
                highlights.add(
                    OverlayModel.Highlight(col, row, if (first == 1) COLOR_NOW else COLOR_LATER)
                )
                glyphs.add(
                    OverlayModel.Glyph(
                        col = col,
                        row = row,
                        text = label,
                        colorRgba = if (first == 1) COLOR_NOW_TEXT else COLOR_LATER_TEXT,
                        scale = if (label.length > 1) 0.5f else 0.62f,
                    )
                )
            }
        }
        return OverlayModel(glyphs = glyphs, highlights = highlights)
    }

    // The luma-only entry points are unreachable in practice -- the engine calls the
    // CanvasView versions and this adapter refuses without chroma -- but the interface
    // requires them.
    override fun identify(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): Float = 0f

    override fun readCells(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
        alreadyRead: BooleanArray,
    ): List<CellObservation> = emptyList()

    companion object {
        /** Registry id, also used by the UI to spot a wall that lights itself. */
        const val ID = "bombs"

        private const val MIN_COVERAGE = 0.90f
        private const val MAX_LABELLED_ROUNDS = 6
        private const val COLOR_NOW = 0x30FF6600
        private const val COLOR_NOW_TEXT = 0xFFFF8800.toInt()
        private const val COLOR_LATER = 0x181166FF
        private const val COLOR_LATER_TEXT = 0x996699FF.toInt()
    }
}
