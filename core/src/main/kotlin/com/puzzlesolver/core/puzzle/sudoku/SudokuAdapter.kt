package com.puzzlesolver.core.puzzle.sudoku

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.CellReader
import com.puzzlesolver.core.puzzle.GlyphClassifier
import com.puzzlesolver.core.puzzle.OverlayModel
import com.puzzlesolver.core.puzzle.PuzzleAdapter
import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.PuzzleSolution
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sudoku on a wall.
 *
 * @param classifier supplied by the platform layer, because good glyph templates
 *        come from rendering the digits with the device font engine rather than
 *        from bitmaps baked into :core.
 */
class SudokuAdapter(
    private val classifier: GlyphClassifier,
) : PuzzleAdapter {

    override val id = "sudoku"
    override val displayName = "Sudoku"

    private val reader = CellReader()

    override fun identify(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): Float {
        // Square, and an order that is a perfect square.
        if (grid.cols != grid.rows) return 0f
        val order = grid.cols
        val boxSize = sqrt(order.toDouble()).toInt()
        if (boxSize * boxSize != order) return 0f
        if (order !in intArrayOf(4, 9, 16)) return 0f

        // Cells must be roughly square in metres. A grid whose cells are 3:1 is a
        // crossword or a table, not a sudoku.
        val (cw, ch) = grid.cellSizeMetres(spec).let { it[0] to it[1] }
        if (abs(cw - ch) / maxOf(cw, ch) > 0.25f) return 0f

        // The clincher: sudoku has heavier rules on box boundaries. Look for extra
        // ink on every boxSize-th grid line.
        val boxLineScore = boxLineEvidence(canvasLuma, grid, boxSize)

        // And a plausible fraction of filled cells among those we can see.
        var seen = 0
        var filled = 0
        for (row in 0 until order) {
            for (col in 0 until order) {
                val b = grid.cellBounds(col, row)
                if (coverage.coverageOfRect(b[0], b[1], b[2], b[3]) < 0.9f) continue
                seen++
                val stats = reader.inkStats(reader.samplePatch(canvasLuma, grid, col, row))
                if (stats[0] > MIN_INK_FRACTION) filled++
            }
        }
        if (seen < 4) return 0f
        val fillRatio = filled.toFloat() / seen
        // Published sudoku sit around 25-45% givens; be generous with the window.
        val fillScore = if (fillRatio in 0.10f..0.75f) 1f else 0.2f

        return (0.6f * boxLineScore + 0.4f * fillScore) * grid.confidence
    }

    /**
     * Compares ink density on box-boundary lines against ordinary cell lines. In a
     * printed sudoku the box rules are drawn heavier, which is a cheap and very
     * discriminative signal.
     */
    private fun boxLineEvidence(canvas: GrayImage, grid: GridModel, boxSize: Int): Float {
        var boxSum = 0.0
        var boxN = 0
        var thinSum = 0.0
        var thinN = 0
        for (i in 1 until grid.cols) {
            val density = lineDensity(canvas, grid, i)
            if (i % boxSize == 0) {
                boxSum += density
                boxN++
            } else {
                thinSum += density
                thinN++
            }
        }
        if (boxN == 0 || thinN == 0) return 0.5f
        val boxMean = boxSum / boxN
        val thinMean = (thinSum / thinN).coerceAtLeast(1e-3)
        val ratio = boxMean / thinMean
        // 1.0 means no difference, 2.0+ means clearly heavier rules.
        return ((ratio - 1.0) / 1.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun lineDensity(canvas: GrayImage, grid: GridModel, colLine: Int): Double {
        var acc = 0.0
        var n = 0
        val samples = 64
        for (k in 0 until samples) {
            val row = grid.rows * (k + 0.5f) / samples
            val c = grid.cellToCanvas(colLine.toFloat(), row)
            acc += 255.0 - canvas.sampleBilinear(c[0], c[1])
            n++
        }
        return if (n == 0) 0.0 else acc / n
    }

    private var readStats: String = "no read yet"
    override val lastReadStats: String get() = readStats

    override fun readCells(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
        alreadyRead: BooleanArray,
    ): List<CellObservation> {
        val out = ArrayList<CellObservation>()
        var done = 0
        var uncovered = 0
        var washedOut = 0
        var blank = 0
        var smudged = 0
        var unrecognised = 0
        var bestRejectedConfidence = 0f

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val idx = row * grid.cols + col
                if (idx < alreadyRead.size && alreadyRead[idx]) {
                    done++
                    continue
                }
                val b = grid.cellBounds(col, row)
                // Demand near-total coverage of the cell. A half-seen digit is worse
                // than no digit: it feeds the solver a confident wrong given.
                if (coverage.coverageOfRect(b[0], b[1], b[2], b[3]) < 0.95f) {
                    uncovered++
                    continue
                }

                val patch = reader.samplePatch(canvasLuma, grid, col, row)
                val stats = reader.inkStats(patch)
                val inkFraction = stats[0]
                val threshold = stats[1].toInt()
                val contrast = stats[4]

                // Blank first, contrast second. An empty cell has nothing to contrast
                // against, so a contrast gate placed ahead of this discards it as
                // unreadable -- and an empty cell is a clue, not a missing reading. Only a
                // cell that *has* ink needs to prove the ink is legible.
                if (inkFraction < MIN_INK_FRACTION) {
                    blank++
                    out.add(CellObservation(col, row, CellObservation.EMPTY, 1f - inkFraction))
                    continue
                }
                if (contrast < MIN_CONTRAST) {
                    washedOut++
                    continue
                }
                if (inkFraction > MAX_INK_FRACTION) {
                    smudged++
                    continue
                }

                val norm = reader.normalize(patch, threshold)
                val (label, conf) = classifier.classify(norm)
                if (label in 1..grid.cols && conf >= MIN_GLYPH_CONFIDENCE) {
                    out.add(CellObservation(col, row, label, conf))
                } else {
                    unrecognised++
                    if (conf > bestRejectedConfidence) bestRejectedConfidence = conf
                }
            }
        }

        readStats = "done=$done uncovered=$uncovered washed=$washedOut blank=$blank " +
            "smudge=$smudged unread=$unrecognised bestConf=${"%.2f".format(bestRejectedConfidence)}"
        return out
    }

    override fun createSolver(grid: GridModel): IncrementalSolver = SudokuSolver(grid.cols)

    override fun overlayFor(solution: PuzzleSolution, grid: GridModel): OverlayModel {
        val glyphs = ArrayList<OverlayModel.Glyph>(solution.cols * solution.rows)
        for (row in 0 until solution.rows) {
            for (col in 0 until solution.cols) {
                if (solution.isGiven(col, row)) continue     // already on the wall
                glyphs.add(
                    OverlayModel.Glyph(
                        col = col,
                        row = row,
                        text = solution.valueAt(col, row).toString(),
                        colorRgba = DEDUCED_COLOR,
                        scale = 0.62f,
                    )
                )
            }
        }
        return OverlayModel(glyphs = glyphs)
    }

    private companion object {
        const val MIN_INK_FRACTION = 0.02f
        const val MAX_INK_FRACTION = 0.55f
        const val MIN_CONTRAST = 0.12f
        const val MIN_GLYPH_CONFIDENCE = 0.55f
        const val DEDUCED_COLOR = 0x33DD77FF.toInt()
    }
}
