package com.puzzlesolver.core

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridDetector
import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.OverlayModel
import com.puzzlesolver.core.puzzle.PuzzleAdapter
import com.puzzlesolver.core.puzzle.PuzzleRegistry
import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.SolutionPolicy
import com.puzzlesolver.core.solve.SolveOutcome

/**
 * Drives detect -> read -> solve over the accumulated canvas.
 *
 * Deliberately platform-free and synchronous: the caller decides what thread this
 * runs on and how often. On device that is a dedicated pipeline thread fed by the
 * GPU readback; in tests and video replay it is the test thread, called once per
 * decoded frame. Identical code path, which is the point -- a bug reproduced from
 * a recording is the same bug.
 */
class PuzzleEngine(
    private val spec: CanvasSpec,
    private val registry: PuzzleRegistry,
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * Time budget per [step] call. The solver returns
         * [SolveOutcome.Pending] rather than overrunning it, and picks up where it
         * left off next call, so a hard puzzle never stalls the capture loop.
         */
        val solveBudgetNanos: Long = 12_000_000L,      // 12 ms
        /** Re-run grid detection when coverage has grown by this fraction. */
        val redetectCoverageDelta: Float = 0.02f,
        /**
         * A cell already read is re-read when its canvas confidence improves by this
         * much, since a better look can correct a bad glyph call.
         */
        val recheckConfidenceGain: Float = 0.2f,
    )

    /** Everything the UI needs, in one immutable snapshot. */
    data class State(
        val grid: GridModel? = null,
        val adapterId: String? = null,
        val adapterName: String? = null,
        val policy: SolutionPolicy? = null,
        val outcome: SolveOutcome = SolveOutcome.NeedMoreData(),
        val overlay: OverlayModel = OverlayModel.EMPTY,
        /** 0..1 fraction of grid cells read. */
        val cellsReadFraction: Float = 0f,
        val cellsRead: Int = 0,
        val cellCount: Int = 0,
        /** True when the answer was reached before the whole board had been seen. */
        val solvedEarly: Boolean = false,
        val lastStepMillis: Float = 0f,
        /** Why no grid was found, when there is none. */
        val gridRejection: String? = null,
        /** Adapter's tally of the last cell-reading pass. */
        val readStats: String? = null,
        /**
         * An adapter that needs chroma likes the look of this canvas, but cannot be
         * confirmed until the capture layer streams colour.
         *
         * This exists to break a circle. Colour costs bandwidth and only one puzzle
         * needs it, so it is off by default -- but that puzzle cannot be identified
         * without it. Its *grid* detector runs on luma alone, though, so a lattice of
         * buttons is recognisable before any colour arrives. That is enough to justify
         * turning chroma on, and the identification is settled a frame or two later.
         */
        val colourRequested: Boolean = false,
    )

    private val detector = GridDetector(spec)

    private var grid: GridModel? = null
    private var adapter: PuzzleAdapter? = null
    private var solver: IncrementalSolver? = null

    private var cellsRead = BooleanArray(0)
    private var cellConfidence = FloatArray(0)
    private var readCount = 0

    private var lastDetectCoverage = -1f
    private var pendingOutcome: SolveOutcome = SolveOutcome.NeedMoreData()
    private var overlay: OverlayModel = OverlayModel.EMPTY
    private var solvedEarly = false

    fun reset() {
        grid = null
        adapter = null
        preferredAdapter = null
        solver = null
        cellsRead = BooleanArray(0)
        cellConfidence = FloatArray(0)
        readCount = 0
        lastDetectCoverage = -1f
        pendingOutcome = SolveOutcome.NeedMoreData()
        overlay = OverlayModel.EMPTY
        solvedEarly = false
        registry.reset()
    }

    /**
     * Tells the detector how many cells per axis to expect, or null to infer it.
     * Changing this invalidates the grid, so everything downstream restarts.
     */
    fun setExpectedCells(cells: Int?) {
        if (detector.expectedCells == cells) return
        detector.expectedCells = cells
        reset()
    }

    /** Forces a puzzle type, bypassing identification. Used by the debug menu. */
    /**
     * Returns identification to the evidence after a manual choice.
     *
     * Everything read under the old adapter goes with it. Cell values are
     * adapter-specific -- a digit to one, a colour index to another -- so carrying
     * them across would feed the new solver numbers that mean something else entirely.
     */
    fun autoDetectPuzzle() {
        registry.unpin()
        adapter = null
        preferredAdapter = null
        solver = null
        cellsRead.fill(false)
        cellConfidence.fill(0f)
        readCount = 0
        overlay = OverlayModel.EMPTY
        solvedEarly = false
        pendingOutcome = SolveOutcome.NeedMoreData()
    }

    /**
     * Rebuilds the solver against the current adapter and grid, keeping both.
     *
     * For settings that change the *answer* without changing what is on the wall -- the
     * mine budget is the only one so far. A full [reset] would do the job too, but it
     * also drops the adapter and any mode the user pinned, so setting a budget would
     * silently undo the mode they had just chosen.
     *
     * Cells are marked unread rather than carried over. The solver owns the observed
     * values, so a new one starts empty; the canvas mirror still holds the pixels, and
     * they are re-read on the next pass at no real cost.
     */
    fun reconfigureSolver() {
        val a = adapter ?: return
        val g = grid ?: return
        solver = createSolverOrNull(a, g)
        cellsRead.fill(false)
        cellConfidence.fill(0f)
        readCount = 0
        overlay = OverlayModel.EMPTY
        solvedEarly = false
        pendingOutcome = SolveOutcome.NeedMoreData()
    }

    /** Id of the adapter in use, and whether the user chose it. */
    fun activeAdapterId(): String? = adapter?.id

    fun isPinned(): Boolean = registry.pinned

    /** Every puzzle type this build can solve, for the mode menu. */
    fun availableAdapters(): List<PuzzleAdapter> = registry.all()

    fun pinAdapter(id: String) {
        registry.byId(id)?.let { if (it.requiresColour) colourRequested = true }
        registry.pin(id)?.let {
            if (adapter !== it) {
                adapter = it
                solver = grid?.let { g -> createSolverOrNull(it, g) }
                cellsRead.fill(false)
                cellConfidence.fill(0f)
                readCount = 0
            }
        }
    }

    /**
     * Builds a solver, tolerating adapters that reject the detected grid.
     *
     * Necessary because pinning a puzzle type bypasses `identify`, which is normally
     * what guarantees the grid suits the adapter. Forcing sudoku onto an 11x12 grid
     * reaches `SudokuSolver`'s perfect-square requirement and throws -- and since this
     * runs on the solver thread, an uncaught throw there takes the thread down and the
     * app silently stops solving for the rest of the session.
     */
    private fun createSolverOrNull(a: PuzzleAdapter, g: GridModel): IncrementalSolver? =
        try {
            a.createSolver(g)
        } catch (e: Exception) {
            lastSolverError = "${a.id} cannot handle a ${g.cols}x${g.rows} grid: ${e.message}"
            null
        }

    /** Set when an adapter refused the detected grid; surfaced for diagnostics. */
    var lastSolverError: String? = null
        private set

    /**
     * One pass over the current canvas.
     *
     * @param canvasLuma       the accumulated mosaic, single channel
     * @param coverage         which parts of it we have seen and how well
     * @param scanConsideredComplete set by the capture layer when the user has
     *        covered the whole detected board. Puzzles with
     *        [SolutionPolicy.REQUIRES_FULL_SCAN] will not answer before this.
     */
    fun step(
        canvasLuma: GrayImage,
        coverage: CoverageMap,
        scanConsideredComplete: Boolean,
    ): State = step(CanvasView(canvasLuma), coverage, scanConsideredComplete)

    fun step(
        view: CanvasView,
        coverage: CoverageMap,
        scanConsideredComplete: Boolean,
    ): State {
        val started = System.nanoTime()
        val coverageFraction = coverage.observedCells.toFloat() /
            (coverage.cols * coverage.rows).coerceAtLeast(1)

        // 1. Grid. Cheap to skip, expensive to redo, so only when coverage moved.
        if (grid == null || coverageFraction - lastDetectCoverage > config.redetectCoverageDelta) {
            lastDetectCoverage = coverageFraction
            val found = detectGrid(view, coverage)
            if (found != null) {
                val old = grid
                if (old == null || !old.isCompatibleWith(found)) {
                    // Shape changed: everything downstream is invalid.
                    grid = found
                    adapter = null
                    solver = null
                    cellsRead = BooleanArray(found.cellCount)
                    cellConfidence = FloatArray(found.cellCount)
                    readCount = 0
                    overlay = OverlayModel.EMPTY
                    solvedEarly = false
                } else {
                    // Same grid, refined estimate. Keep the observations.
                    grid = found
                }
            }
        }
        val g = grid ?: return snapshot(started, coverageFraction)

        // 2. Puzzle type.
        if (adapter == null) {
            adapter = preferredAdapter?.takeIf { it.identify(view, g, coverage, spec) >= MIN_OWN_IDENTIFY }
                ?: registry.select(view, g, coverage, spec)
            adapter?.let { solver = createSolverOrNull(it, g) }
        }
        val a = adapter ?: return snapshot(started, coverageFraction)
        val s = solver ?: return snapshot(started, coverageFraction)

        // 3. Re-open cells whose view has materially improved.
        //
        // Clearing the read flag is all that is needed: readCells picks them up in the
        // same pass, so they arrive as ordinary observations and the solver overwrites
        // whatever it had. Tracking them separately as "revised" would duplicate every
        // one of them in the delta.
        var reopened = 0
        for (row in 0 until g.rows) {
            for (col in 0 until g.cols) {
                val idx = row * g.cols + col
                if (!cellsRead[idx]) continue
                val b = g.cellBounds(col, row)
                val cov = coverage.coverageOfRect(b[0], b[1], b[2], b[3])
                if (cov > cellConfidence[idx] + config.recheckConfidenceGain) {
                    cellsRead[idx] = false
                    readCount--
                    reopened++
                }
            }
        }

        // 4. Read newly readable cells.
        val fresh = a.readCells(view, g, coverage, spec, cellsRead)
        for (o in fresh) {
            val idx = o.row * g.cols + o.col
            if (idx < 0 || idx >= cellsRead.size) continue
            if (!cellsRead[idx]) {
                cellsRead[idx] = true
                readCount++
            }
            val b = g.cellBounds(o.col, o.row)
            cellConfidence[idx] = coverage.coverageOfRect(b[0], b[1], b[2], b[3])
        }

        // 5. Solve, but only when there is something new or work left over.
        val readFraction = readCount.toFloat() / g.cellCount.coerceAtLeast(1)
        val boardFullySeen = scanConsideredComplete && readFraction > 0.995f
        // Nothing new and no work left over means the solver would reach the same
        // conclusion it already reached. Skipping the call is what keeps the pipeline
        // idle-cheap once a board is fully read.
        if (fresh.isEmpty() && reopened == 0 && pendingOutcome !is SolveOutcome.Pending) {
            return snapshot(started, coverageFraction)
        }

        val delta = ObservationDelta(
            cells = fresh,
            coverageFraction = readFraction,
            fullyScanned = boardFullySeen,
        )
        val deadline = started + config.solveBudgetNanos
        val outcome = s.observe(delta, deadline)

        when (outcome) {
            is SolveOutcome.Solved -> {
                overlay = a.overlayFor(outcome.solution, g)
                // The headline claim of the app: record whether we beat the scan.
                solvedEarly = !boardFullySeen
                pendingOutcome = outcome
            }
            is SolveOutcome.Contradiction -> {
                // A misread is far more likely than a bad puzzle, so drop the named
                // cells and let the next pass re-read them from better pixels.
                for (c in outcome.suspectCells) {
                    val idx = c.row * g.cols + c.col
                    if (idx in cellsRead.indices && cellsRead[idx]) {
                        cellsRead[idx] = false
                        cellConfidence[idx] = 0f
                        readCount--
                    }
                }
                overlay = OverlayModel.EMPTY
                pendingOutcome = outcome
            }
            else -> pendingOutcome = outcome
        }

        return snapshot(started, coverageFraction)
    }

    /**
     * Which adapter, if any, supplied the current grid. Kept so the engine can offer
     * that adapter first when identifying: a specialised detector firing is strong
     * evidence, and it saves asking every adapter about a canvas only one can read.
     */
    private var preferredAdapter: PuzzleAdapter? = null

    /** See [State.colourRequested]. Latches: once colour is worth streaming, it stays worth it. */
    private var colourRequested = false

    /** What the adapters' own grid detectors said last time, for diagnostics. */
    private var adapterGridReport: String? = null

    /**
     * Finds the grid, letting an adapter with its own detector go first.
     *
     * Order matters. `GridDetector` looks for ruled lines and, on a canvas that has
     * none, settles for noise and reports a confident wrong pitch rather than nothing.
     * So a specialised detector that says "this is mine, and here is the lattice" is
     * asked before the general one, and its answer only stands if the same adapter then
     * identifies the canvas too.
     */
    private fun detectGrid(view: CanvasView, coverage: CoverageMap): GridModel? {
        var reports: StringBuilder? = null
        for (a in registry.all()) {
            val candidate = a.detectGrid(view, coverage, spec)
            a.lastGridReport.takeIf { it.isNotEmpty() }?.let {
                (reports ?: StringBuilder().also { sb -> reports = sb })
                    .append(if (reports!!.isEmpty()) "" else "; ").append(a.id).append(": ").append(it)
            }
            if (candidate == null) continue
            if (candidate.confidence < MIN_OWN_GRID_CONFIDENCE) continue

            if (a.requiresColour && !view.hasColour) {
                // Its detector recognises this canvas, but confirming that costs colour
                // we are not yet streaming. Ask for it and leave the grid alone -- if
                // this were adopted unconfirmed, a lattice detector misfiring on some
                // other puzzle would lock the engine onto a grid it could never verify.
                colourRequested = true
                continue
            }
            if (a.identify(view, candidate, coverage, spec) < MIN_OWN_IDENTIFY) continue
            preferredAdapter = a
            adapterGridReport = reports?.toString()
            return candidate
        }
        preferredAdapter = null
        adapterGridReport = reports?.toString()
        return detector.detect(view.luma, coverage)
    }

    private fun snapshot(startedNanos: Long, coverageFraction: Float): State {
        val g = grid
        return State(
            grid = g,
            adapterId = adapter?.id,
            adapterName = adapter?.displayName,
            policy = solver?.policy,
            outcome = pendingOutcome,
            overlay = overlay,
            cellsReadFraction = if (g == null) coverageFraction else readCount.toFloat() / g.cellCount.coerceAtLeast(1),
            cellsRead = readCount,
            cellCount = g?.cellCount ?: 0,
            solvedEarly = solvedEarly,
            // Prefer whichever detector actually looked. When an adapter supplies its
            // own, GridDetector's complaint is about a puzzle nobody is solving.
            gridRejection = adapterGridReport ?: detector.lastRejection,
            readStats = adapter?.lastReadStats,
            colourRequested = colourRequested || adapter?.requiresColour == true,
            lastStepMillis = (System.nanoTime() - startedNanos) / 1e6f,
        )
    }

    private companion object {
        /** A specialised detector has to be sure before it displaces the general one. */
        const val MIN_OWN_GRID_CONFIDENCE = 0.5f

        /** And the same adapter has to recognise the canvas on that grid. */
        const val MIN_OWN_IDENTIFY = 0.5f
    }
}