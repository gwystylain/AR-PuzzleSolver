package com.puzzlesolver.app.mines

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.bombs.BombPlan
import com.puzzlesolver.core.puzzle.bombs.PlanOutcome
import com.puzzlesolver.core.puzzle.bombs.ScannedWall
import com.puzzlesolver.core.puzzle.bombs.WallScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One go at the mines wall: aim at the green button, pan the wall, get the answer.
 *
 * The answer used to be drawn on the wall through ARCore, which meant reading the wall at
 * the exposure ARCore chose and holding the phone up to it while pressing buttons. Now
 * the wall is read once, in one pan, into a board of the app's own, and the plan is drawn
 * on that board. See [WallScanner] for the pan and docs/BOMB_PUZZLE.md for the why.
 *
 * Frames arrive on the pipeline's solver thread through [onFrame], and the scanner lives
 * on that thread alone. Everything the screen asks for -- start, finish, cancel, a cell
 * corrected by hand -- is either a flag that thread picks up on its next frame, or work
 * that never touches the scanner at all. Planning runs on its own worker: a whole wall is
 * a few hundred milliseconds of search, and the camera should not stop for it.
 *
 * Each scan is written to the phone under `files/mines/<date-time>/`: the frames it read
 * (`frames.txt` says which was which), what the scanner made of each (`scan.txt`), and
 * the board and plan (`board.txt`). Not for the user; for replaying a scan at a desk
 * when one comes out wrong (`MinesReplay`).
 */
class MinesController(
    context: Context,
    /** The live camera's dials, when there is a camera. */
    private val tuning: () -> CameraTuning?,
) {
    enum class Phase { AIMING, SCANNING, PLANNING, DONE }

    data class State(
        val phase: Phase = Phase.AIMING,
        val message: String = "Point the camera at the green button, all seven rows in view",
        val columns: Int = 0,
        val expectedColumns: Int = WallScanner.COLUMNS,
        val wallInView: Boolean = false,
        val greenFound: Boolean = false,
        /** The wall as read: growing while scanning, final once done. */
        val wall: ScannedWall? = null,
        val plan: BombPlan? = null,
        /** One line on the plan, or why there is none. */
        val planSummary: String? = null,
        /** What the camera is doing for both kinds of frame. */
        val camera: String = "",
        val lostTrack: Boolean = false,
    )

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Solver thread only.
    private val scanner = WallScanner(log = ::scanLog)
    private var scanning = false
    private var lastSnapshotNanos = 0L
    private var lastCameraChangeNanos = 0L
    private var latticeSteps = 0
    private var steeredTo: CameraTuning.Lattice? = null
    private var limitLogged = false
    private var aimingSinceNanos = 0L

    @Volatile
    private var scanDir: File? = null
    private var scanText: PrintWriter? = null
    private var framesText: PrintWriter? = null
    private var savedFrames = 0

    // Set from the main thread, taken by the solver thread on its next frame.
    private val startRequested = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var autoLattice = true

    /**
     * Bumped whenever the board being planned changes or is thrown away. A plan that
     * finishes for an older one is dropped rather than shown against the wrong board.
     */
    @Volatile
    private var planGeneration = 0

    /** Frames are written off the solver thread; a full queue drops the frame, never waits. */
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8), ThreadPoolExecutor.DiscardPolicy(),
    )

    /** The board and plan, which are never dropped. */
    private val boardWriter = Executors.newSingleThreadExecutor()

    // --- From the screen ---------------------------------------------------

    fun startScan() {
        if (_state.value.phase != Phase.AIMING) return
        startRequested.set(true)
        _state.update { it.copy(phase = Phase.SCANNING, message = "Hold on the green button, then pan right slowly") }
        Log.i(TAG, "scan requested")
    }

    /** Ends the scan where it is and plans what was read. */
    fun finishScan() {
        if (_state.value.phase == Phase.SCANNING) finishRequested.set(true)
    }

    /** Back to aiming, whatever was happening: a scan, a plan, or the answer. */
    fun rescan() {
        planGeneration++
        cancelRequested.set(true)
        _state.update { State(camera = it.camera) }
        Log.i(TAG, "rescan")
    }

    /**
     * Cycles one cell through the colours a button can be, for a cell the scan got
     * wrong, and plans again. The green is the anchor and is left alone.
     */
    fun correctCell(col: Int, row: Int) {
        val s = _state.value
        val wall = s.wall ?: return
        if (s.phase != Phase.DONE && s.phase != Phase.PLANNING) return
        val current = wall.kindAt(col, row)
        if (current == com.puzzlesolver.core.puzzle.bombs.Button.GREEN) return
        val next = CYCLE[(CYCLE.indexOf(current) + 1) % CYCLE.size]
        val corrected = wall.withKind(col, row, next)
        Log.i(TAG, "cell ${col + 1},${row + 1} set by hand: ${name(current)} -> ${name(next)}")
        scanDir?.let { dir -> appendBoard(dir, "corrected ${col + 1},${row + 1} to ${name(next)}", corrected, null) }
        plan(corrected)
    }

    /** Hands the lattice exposure to the user, or back to the scan. */
    fun nudgeLattice(brighter: Boolean) {
        autoLattice = false
        tuning()?.nudgeLattice(brighter)
    }

    fun nudgeColour(brighter: Boolean) {
        val t = tuning() ?: return
        if (brighter) t.brighter() else t.darker()
    }

    fun release() {
        scope.cancel()
        writer.shutdown()
        boardWriter.shutdown()
    }

    // --- From the solver thread ----------------------------------------------

    /**
     * One camera frame, upright. [lattice] says which of the alternating pair it is;
     * a camera that is not alternating sends every frame as a lattice frame.
     *
     * The frame is the caller's again once this returns, so nothing here keeps it.
     */
    fun onFrame(frame: RgbImage, lattice: Boolean, timestampNanos: Long) {
        if (cancelRequested.getAndSet(false)) {
            closeScan()
            scanning = false
            scanner.reset()
        }
        if (startRequested.getAndSet(false)) {
            scanner.reset()
            scanning = true
            openScan()
        }

        val exposure = if (lattice) WallScanner.Exposure.LATTICE else WallScanner.Exposure.COLOUR
        val progress = scanner.offer(frame, exposure, timestampNanos)
        if (scanning) saveFrame(frame, lattice, timestampNanos)

        if (!scanning) {
            // Aiming: the scanner runs for the feedback -- is the wall in view, is the
            // green -- and is reset the moment the scan starts. Its count means nothing yet.
            if (lattice) steerLattice(progress, timestampNanos)
            _state.update {
                if (it.phase != Phase.AIMING) it else it.copy(
                    message = progress.message,
                    wallInView = progress.wallInView,
                    greenFound = progress.phase != WallScanner.Phase.FINDING_GREEN,
                    camera = describeCamera(),
                )
            }
            // Started afresh every couple of seconds, so "green in view" means now and not
            // at some point since the mode was picked. Nothing from before the scan carries
            // into it either way: it starts with a reset of its own.
            if (timestampNanos - aimingSinceNanos > AIM_WINDOW_NANOS) {
                scanner.reset()
                aimingSinceNanos = timestampNanos
            }
            return
        }

        val done = progress.phase == WallScanner.Phase.DONE
        val finish = finishRequested.getAndSet(false)
        val snapshotDue = timestampNanos - lastSnapshotNanos > SNAPSHOT_NANOS
        if (done || finish || snapshotDue) lastSnapshotNanos = timestampNanos
        val wall = if (done || finish || snapshotDue) scanner.result() else null
        _state.update {
            if (it.phase != Phase.SCANNING) it else it.copy(
                message = progress.message,
                columns = progress.columns,
                expectedColumns = progress.expectedColumns,
                wallInView = progress.wallInView,
                greenFound = progress.phase != WallScanner.Phase.FINDING_GREEN,
                lostTrack = progress.phase == WallScanner.Phase.LOST,
                wall = wall ?: it.wall,
                camera = describeCamera(),
            )
        }
        if (done || finish) {
            scanning = false
            val result = scanner.result()
            if (result == null) {
                // Finished before the green was ever seen: nothing to count from.
                _state.update {
                    it.copy(phase = Phase.AIMING, message = "The green button was never found. Start on it and scan again.")
                }
                closeScan()
                return
            }
            Log.i(TAG, "scan ${if (done) "reached the end" else "finished by hand"}: ${result.cols} columns")
            scanDir?.let { appendBoard(it, if (done) "scanned to the end" else "finished by hand", result, null) }
            closeFrames()
            plan(result)
        }
    }

    /**
     * Keeps the lattice frames bright enough to see the unlit buttons, while aiming only.
     *
     * One stop at a time, a second and a half apart so each change is measured before
     * the next. Up to three stops brighter and two darker than the default, which was set
     * off reference footage of a brighter room than the one on 2026-10-27: there, two
     * stops up (1/125 s at ISO 6400) left the unlit buttons at 15-30 and the wall unseen,
     * and replaying that pan at twice the light found it in 145 frames of 147. The third
     * stop is that doubling -- 1/62 s at ISO 6400, as long as a lattice frame may run
     * before a pan smears it -- and the scanner lifts a dim frame for the rest (see
     * WallScanner.liftDim). Never during a scan, because a retune stops the camera for a
     * moment and the count cannot afford a gap.
     */
    private fun steerLattice(progress: WallScanner.Progress, now: Long) {
        if (!autoLattice) return
        val t = tuning() ?: return
        val current = t.settings.lattice ?: return
        // Counted from wherever the lattice frames last started. Leaving Mines turns them
        // off and coming back starts them at the default again; a count carried across
        // that would hold a second visit at the default, already "three stops up".
        if (current != steeredTo) {
            latticeSteps = 0
            limitLogged = false
        }
        if (now - lastCameraChangeNanos < STEER_INTERVAL_NANOS) return
        val brighter = when {
            progress.wallInView && progress.buttonBrightness in 0f..DIM_BUTTONS -> true
            progress.wallInView && progress.buttonBrightness > BRIGHT_BUTTONS -> false
            // Black, but with lit buttons in the colour frames: on the wall, too dark to
            // see it. Black with nothing lit is a phone pointed at the floor, and must not
            // be answered by brightening -- that is the trap docs/CAMERA_CONTROL.md
            // describes the gem loop falling into.
            !progress.wallInView && progress.frameBrightness in 0f..BLACK_FRAME &&
                progress.litInView >= MIN_LIT_FOR_DARK -> true
            else -> return
        }
        val atLimit = if (brighter) latticeSteps >= MAX_BRIGHTER_STEPS else latticeSteps <= -MAX_DARKER_STEPS
        if (atLimit) {
            // Said once per stretch at the limit. Without it a room too dark even here
            // leaves nothing in the log but the last change -- which is how 2026-10-27
            // had to be worked out from the camera's retunes.
            if (!limitLogged) {
                limitLogged = true
                Log.w(
                    TAG,
                    "lattice wants ${if (brighter) "brighter" else "darker"} but is at its limit: " +
                        "buttons=%.0f frame=%.0f wall=${progress.wallInView} lit=${progress.litInView} at %s".format(
                            progress.buttonBrightness, progress.frameBrightness, current.describe(),
                        ),
                )
            }
            return
        }
        if (t.nudgeLattice(brighter)) {
            latticeSteps += if (brighter) 1 else -1
            steeredTo = t.settings.lattice
            limitLogged = false
            lastCameraChangeNanos = now
            Log.i(
                TAG,
                "lattice ${if (brighter) "brighter" else "darker"}: buttons=%.0f frame=%.0f -> %s".format(
                    progress.buttonBrightness, progress.frameBrightness, t.settings.lattice?.describe(),
                ),
            )
        }
    }

    // --- Planning ----------------------------------------------------------------

    private fun plan(wall: ScannedWall) {
        val generation = ++planGeneration
        _state.update { it.copy(phase = Phase.PLANNING, wall = wall, plan = null, planSummary = "Working out the fewest mines...") }
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                wall.plan(System.nanoTime() + PLAN_BUDGET_NANOS)
            }
            if (generation != planGeneration) return@launch
            val summary = when (outcome) {
                is PlanOutcome.Found -> {
                    val p = outcome.plan
                    "${p.totalMines} mine${if (p.totalMines == 1) "" else "s"} in ${p.roundCount} " +
                        "detonation${if (p.roundCount == 1) "" else "s"}" +
                        if (p.provenMinimal) " (fewest possible)" else " (fewest found)"
                }
                is PlanOutcome.Impossible -> "No plan: ${outcome.reason}. Check the board against the wall."
                is PlanOutcome.Unfinished -> "No plan yet: ${outcome.reason}."
            }
            val plan = (outcome as? PlanOutcome.Found)?.plan
            Log.i(TAG, "plan: $summary")
            scanDir?.let { dir -> appendBoard(dir, "plan: $summary", wall, plan) }
            _state.update { it.copy(phase = Phase.DONE, wall = wall, plan = plan, planSummary = summary) }
        }
    }

    // --- Keeping the scan --------------------------------------------------------------

    private fun openScan() {
        closeScan()
        try {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "mines/" + STAMP.format(Date())).apply { mkdirs() }
            scanDir = dir
            scanText = PrintWriter(File(dir, "scan.txt").bufferedWriter())
            framesText = PrintWriter(File(dir, "frames.txt").bufferedWriter())
            savedFrames = 0
            scanText?.println("camera: ${describeCamera()}")
        } catch (e: Exception) {
            // Losing the copy costs a replay, never the scan.
            Log.w(TAG, "could not open the scan log", e)
        }
    }

    private fun scanLog(line: String) {
        if (scanning) scanText?.println(line)
    }

    private fun saveFrame(frame: RgbImage, lattice: Boolean, t: Long) {
        val dir = scanDir ?: return
        if (savedFrames >= MAX_SAVED_FRAMES) return
        val name = "%04d.jpg".format(savedFrames++)
        framesText?.println("$name $t ${if (lattice) "L" else "C"}")
        // Half size, the size the replay fixture is: every look the scanner needs, at a
        // quarter of the bytes. Copied here because the frame's buffer is reused for the
        // next one; a straight byte copy, so it costs about a millisecond.
        val small = RgbImage(frame.width / 2, frame.height / 2)
        var o = 0
        for (y in 0 until small.height) {
            var i = (y * 2 * frame.width) * 3
            for (x in 0 until small.width) {
                small.data[o] = frame.data[i]
                small.data[o + 1] = frame.data[i + 1]
                small.data[o + 2] = frame.data[i + 2]
                o += 3
                i += 6
            }
        }
        writer.execute {
            try {
                val b = Bitmap.createBitmap(small.toArgb(), small.width, small.height, Bitmap.Config.ARGB_8888)
                File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                b.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "could not save $name", e)
            }
        }
    }

    private fun closeFrames() {
        framesText?.close()
        framesText = null
        scanText?.flush()
    }

    private fun closeScan() {
        closeFrames()
        scanText?.close()
        scanText = null
    }

    private fun appendBoard(dir: File, heading: String, wall: ScannedWall, plan: BombPlan?) {
        val text = buildString {
            appendLine("== $heading")
            append(wall.describe())
            wall.warnings.forEach { appendLine("warning: $it") }
            plan?.rounds?.forEachIndexed { n, round ->
                appendLine(
                    "round ${n + 1}: " + round.mines.sorted().joinToString { m ->
                        "${m % wall.cols + 1},${m / wall.cols + 1}"
                    }
                )
            }
            appendLine()
        }
        boardWriter.execute {
            try {
                File(dir, "board.txt").appendText(text)
            } catch (e: Exception) {
                Log.w(TAG, "could not write the board", e)
            }
        }
    }

    private fun describeCamera(): String {
        val t = tuning() ?: return "no camera"
        val lattice = t.settings.lattice?.let { "lattice ${it.describe()} (got ${t.latticeReported.describe()})" }
            ?: "no lattice frames"
        return "colour ${t.describeRequest().substringBefore(" + lattice")} (got ${t.reported.describe()})  ·  $lattice"
    }

    private fun name(button: Int): String = com.puzzlesolver.core.puzzle.bombs.Button.name(button)

    private companion object {
        const val TAG = "MinesController"

        /** What a tap on a cell steps through. Green is the anchor and not on it. */
        val CYCLE = intArrayOf(
            com.puzzlesolver.core.puzzle.bombs.Button.EMPTY,
            com.puzzlesolver.core.puzzle.bombs.Button.WHITE,
            com.puzzlesolver.core.puzzle.bombs.Button.BLUE,
            com.puzzlesolver.core.puzzle.bombs.Button.HAZARD,
            com.puzzlesolver.core.puzzle.bombs.Button.MINE,
        )

        /** How often the growing board is handed to the screen while scanning. */
        const val SNAPSHOT_NANOS = 400_000_000L

        /** Long enough for the exact pass on a whole wall; the greedy answer is there in milliseconds. */
        const val PLAN_BUDGET_NANOS = 6_000_000_000L

        const val STEER_INTERVAL_NANOS = 1_500_000_000L
        const val AIM_WINDOW_NANOS = 2_000_000_000L
        const val DIM_BUTTONS = 45f
        const val BRIGHT_BUTTONS = 205f
        const val BLACK_FRAME = 12f
        const val MIN_LIT_FOR_DARK = 2
        const val MAX_BRIGHTER_STEPS = 3
        const val MAX_DARKER_STEPS = 2

        /** A minute at twenty frames a second, both kinds together. */
        const val MAX_SAVED_FRAMES = 1200
        const val JPEG_QUALITY = 88

        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
