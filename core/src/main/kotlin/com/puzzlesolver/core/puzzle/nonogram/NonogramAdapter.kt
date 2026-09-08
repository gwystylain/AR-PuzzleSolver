package com.puzzlesolver.core.puzzle.nonogram

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

/**
 * Nonogram (griddler / picross) on a wall.
 *
 * Layout assumption: clue strips occupy the top rows and left columns of the
 * detected grid, and the play area is the rectangle below and right of them. The
 * split is found by looking for the heavy rule that separates clues from board,
 * falling back to the widest empty gutter.
 *
 * Clue cells are addressed through the same [CellObservation] interface as any
 * other cell, using the detected grid's own coordinates. The adapter, not the
 * pipeline, knows which of those coordinates are clues -- that is what keeps the
 * pipeline free of puzzle-specific special cases.
 */
class NonogramAdapter(
    private val classifier: GlyphClassifier,
) : PuzzleAdapter {

    override val id = "nonogram"
    override val displayName = "Nonogram"

    private val reader = CellReader()

    /** Cached layout for the current grid; recomputed when the grid changes shape. */
    private var layout: Layout? = null
    private var layoutForGrid: GridModel? = null

    /**
     * @param clueCols number of clue columns on the left
     * @param clueRows number of clue rows on the top
     */
    data class Layout(val clueCols: Int, val clueRows: Int, val boardCols: Int, val boardRows: Int)

    private fun layoutOf(canvas: GrayImage, grid: GridModel): Layout {
        val cached = layout
        if (cached != null && layoutForGrid?.isCompatibleWith(grid) == true) return cached

        // The separator is the internal grid line with the most ink. Scan the first
        // 40% of each axis, which is where a clue strip can plausibly end.
        val maxClueCols = (grid.cols * 0.4f).toInt().coerceAtLeast(1)
        val maxClueRows = (grid.rows * 0.4f).toInt().coerceAtLeast(1)

        var bestCol = 1
        var bestColInk = -1.0
        for (i in 1..maxClueCols) {
            val ink = verticalLineInk(canvas, grid, i)
            if (ink > bestColInk) {
                bestColInk = ink
                bestCol = i
            }
        }
        var bestRow = 1
        var bestRowInk = -1.0
        for (i in 1..maxClueRows) {
            val ink = horizontalLineInk(canvas, grid, i)
            if (ink > bestRowInk) {
                bestRowInk = ink
                bestRow = i
            }
        }

        val l = Layout(bestCol, bestRow, grid.cols - bestCol, grid.rows - bestRow)
        layout = l
        layoutForGrid = grid
        return l
    }

    private fun verticalLineInk(canvas: GrayImage, grid: GridModel, colLine: Int): Double {
        var acc = 0.0
        val samples = 96
        for (k in 0 until samples) {
            val c = grid.cellToCanvas(colLine.toFloat(), grid.rows * (k + 0.5f) / samples)
            acc += 255.0 - canvas.sampleBilinear(c[0], c[1])
        }
        return acc / samples
    }

    private fun horizontalLineInk(canvas: GrayImage, grid: GridModel, rowLine: Int): Double {
        var acc = 0.0
        val samples = 96
        for (k in 0 until samples) {
            val c = grid.cellToCanvas(grid.cols * (k + 0.5f) / samples, rowLine.toFloat())
            acc += 255.0 - canvas.sampleBilinear(c[0], c[1])
        }
        return acc / samples
    }

    override fun identify(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): Float {
        if (grid.cols < 8 || grid.rows < 8) return 0f
        val l = layoutOf(canvasLuma, grid)
        if (l.boardCols < 5 || l.boardRows < 5) return 0f

        // Signature of a nonogram: the clue strips carry glyphs, the board is mostly
        // blank or solid, and the two regions look statistically different.
        val clueInk = meanInkOverRegion(canvasLuma, grid, coverage, 0, 0, l.clueCols, grid.rows)
        val boardInk = meanInkOverRegion(
            canvasLuma, grid, coverage, l.clueCols, l.clueRows, grid.cols, grid.rows,
        )
        if (clueInk < 0f || boardInk < 0f) return 0f

        // Clue strips should have moderate ink (digits); an empty board should have
        // very little. A big ratio is the discriminator.
        val ratio = (clueInk + 1e-3f) / (boardInk + 1e-3f)
        val ratioScore = ((ratio - 1.2f) / 2.0f).coerceIn(0f, 1f)
        return ratioScore * grid.confidence
    }

    private fun meanInkOverRegion(
        canvas: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        c0: Int, r0: Int, c1: Int, r1: Int,
    ): Float {
        var acc = 0f
        var n = 0
        for (row in r0 until r1) {
            for (col in c0 until c1) {
                val b = grid.cellBounds(col, row)
                if (coverage.coverageOfRect(b[0], b[1], b[2], b[3]) < 0.9f) continue
                acc += reader.inkStats(reader.samplePatch(canvas, grid, col, row))[0]
                n++
            }
        }
        return if (n < 4) -1f else acc / n
    }

    override fun readCells(
        canvasLuma: GrayImage,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
        alreadyRead: BooleanArray,
    ): List<CellObservation> {
        val l = layoutOf(canvasLuma, grid)
        val out = ArrayList<CellObservation>()
        // Only clue cells carry information; the board itself is what we are solving
        // for, so reading it would be pointless (and on an unsolved wall puzzle it is
        // blank anyway).
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val isClue = col < l.clueCols || row < l.clueRows
                if (!isClue) continue
                if (col < l.clueCols && row < l.clueRows) continue   // dead corner
                val idx = row * grid.cols + col
                if (idx < alreadyRead.size && alreadyRead[idx]) continue

                val b = grid.cellBounds(col, row)
                if (coverage.coverageOfRect(b[0], b[1], b[2], b[3]) < 0.95f) continue

                val patch = reader.samplePatch(canvasLuma, grid, col, row)
                val stats = reader.inkStats(patch)
                if (stats[4] < 0.12f) continue
                if (stats[0] < 0.02f) {
                    out.add(CellObservation(col, row, CellObservation.EMPTY, 1f))
                    continue
                }
                val norm = reader.normalize(patch, stats[1].toInt())
                val (label, conf) = classifier.classify(norm)
                if (label >= 0 && conf >= 0.55f) {
                    out.add(CellObservation(col, row, label, conf))
                }
            }
        }
        return out
    }

    override fun createSolver(grid: GridModel): IncrementalSolver {
        val l = layout ?: Layout(1, 1, grid.cols - 1, grid.rows - 1)
        return NonogramClueSolver(l, grid.cols)
    }

    override fun overlayFor(solution: PuzzleSolution, grid: GridModel): OverlayModel {
        val l = layout ?: return OverlayModel.EMPTY
        val fills = ArrayList<OverlayModel.Highlight>()
        for (row in 0 until solution.rows) {
            for (col in 0 until solution.cols) {
                if (solution.valueAt(col, row) == 1) {
                    fills.add(
                        OverlayModel.Highlight(
                            col = col + l.clueCols,
                            row = row + l.clueRows,
                            colorRgba = FILL_COLOR,
                        )
                    )
                }
            }
        }
        return OverlayModel(highlights = fills)
    }

    /**
     * Bridges grid-space clue observations to [NonogramSolver]'s per-line run lists.
     *
     * Clue digits sit one per cell, read outward from the board, so a row's runs are
     * the non-blank clue cells left-to-right and a column's are top-to-bottom.
     * Multi-digit clues that share a cell are not handled here -- see
     * docs/PUZZLE_PLUGINS.md for how to extend the reader when your puzzles use them.
     */
    private class NonogramClueSolver(
        private val layout: Layout,
        private val gridCols: Int,
    ) : IncrementalSolver {

        private val inner = NonogramSolver(layout.boardCols, layout.boardRows)
        private val rowCells = Array(layout.boardRows) { IntArray(layout.clueCols) { -1 } }
        private val colCells = Array(layout.boardCols) { IntArray(layout.clueRows) { -1 } }

        override val policy get() = inner.policy

        override fun reset() {
            inner.reset()
            rowCells.forEach { it.fill(-1) }
            colCells.forEach { it.fill(-1) }
        }

        override fun observe(
            delta: com.puzzlesolver.core.solve.ObservationDelta,
            deadlineNanos: Long,
        ): com.puzzlesolver.core.solve.SolveOutcome {
            for (o in delta.cells + delta.revised) {
                val v = if (o.value == CellObservation.EMPTY) -1 else o.value
                if (o.col < layout.clueCols && o.row >= layout.clueRows) {
                    val r = o.row - layout.clueRows
                    if (r in rowCells.indices) {
                        rowCells[r][o.col] = v
                        inner.setRowClue(r, rowCells[r].filter { it > 0 }.toIntArray())
                    }
                } else if (o.row < layout.clueRows && o.col >= layout.clueCols) {
                    val c = o.col - layout.clueCols
                    if (c in colCells.indices) {
                        colCells[c][o.row] = v
                        inner.setColClue(c, colCells[c].filter { it > 0 }.toIntArray())
                    }
                }
            }
            return inner.observe(delta, deadlineNanos)
        }
    }

    private companion object {
        const val FILL_COLOR = 0xAA2244FF.toInt()
    }
}
