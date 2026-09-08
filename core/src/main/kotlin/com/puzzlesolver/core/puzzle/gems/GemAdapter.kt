package com.puzzlesolver.core.puzzle.gems

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
 * Gems, as far as the registry is concerned. The actual work is in [GemScanner].
 *
 * This is a name in a menu and nothing else, which is unusual enough to be worth
 * explaining. Every other adapter reads its puzzle off the accumulated canvas, which
 * needs a camera pose, which means ARCore — and ARCore will not pass an exposure request
 * through to the sensor, so on a wall of bright LEDs in a dark room the gem rings are
 * not in the image at all. See docs/CAMERA_CONTROL.md for the measurements.
 *
 * So Gems reads frames straight from its own camera, with no pose, no wall fit and no
 * canvas: `Camera2FrameSource` supplies them and [GemScanner] does the rest. There was a
 * full canvas-based implementation here and it has been deleted rather than left behind
 * a flag, because it could never run — and a lattice detector running every detection
 * pass for a mode that will never use its answer is 21 ms a pass spent on nothing.
 *
 * What remains is deliberately inert. It declines the grid, scores zero on
 * identification and never builds a solver, so choosing Gems costs the AR pipeline
 * nothing; the activity swaps the frame source instead.
 */
class GemAdapter : PuzzleAdapter {

    override val id = ID
    override val displayName = "Gems"

    /**
     * Never adopts a grid. The canvas is not where this puzzle is read from, and a
     * detector firing here would only take work from the ones that mean it.
     */
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
        throw UnsupportedOperationException("Gems runs on the live camera, not the canvas")

    override fun overlayFor(solution: PuzzleSolution, grid: GridModel): OverlayModel =
        OverlayModel.EMPTY

    companion object {
        /**
         * Registry id. Also what the activity watches for, since choosing this mode
         * swaps the whole frame source rather than just the solver.
         */
        const val ID = "gems"
    }
}
