package com.puzzlesolver.core.puzzle.terminal

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
 * Terminal, as far as the registry is concerned. The actual work is in
 * [TerminalScanner].
 *
 * A name in a menu and nothing else, exactly like
 * [com.puzzlesolver.core.puzzle.gems.GemAdapter], and for one of the same two reasons:
 * this puzzle is read from the live camera frame rather than from the accumulated
 * canvas. The other reason does not apply -- Terminal has no quarrel with ARCore's
 * exposure -- but the first is enough on its own. The wall is played *while* it is being
 * read: the lowest number is hit and its display clears every few seconds, and a mosaic
 * accumulated over a pan would hold a mixture of numbers that were on the wall at
 * different times, which is worse than useless for finding the smallest one now.
 *
 * So it declines the grid, scores zero on identification and never builds a solver;
 * choosing Terminal costs the AR pipeline nothing, and the activity swaps the frame
 * source instead.
 */
class TerminalAdapter : PuzzleAdapter {

    override val id = ID
    override val displayName = "Terminal"

    /** Never adopts a grid: the displays are not on a lattice and not on the canvas. */
    override fun detectGrid(view: CanvasView, coverage: CoverageMap, spec: CanvasSpec): GridModel? = null

    override fun identify(
        view: CanvasView,
        grid: GridModel,
        coverage: CoverageMap,
        spec: CanvasSpec,
    ): Float = 0f

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

    /**
     * Unreachable: [identify] returns zero, so the engine never selects this adapter and
     * never asks it for a solver. Throwing rather than returning a stub, because a stub
     * would be a second and silent implementation of a mode that already has one.
     */
    override fun createSolver(grid: GridModel): IncrementalSolver =
        throw UnsupportedOperationException("Terminal runs on the live camera, not the canvas")

    override fun overlayFor(solution: PuzzleSolution, grid: GridModel): OverlayModel =
        OverlayModel.EMPTY

    companion object {
        /**
         * Registry id. Also what the activity watches for, since choosing this mode
         * swaps the whole frame source rather than just the solver.
         */
        const val ID = "terminal"
    }
}
