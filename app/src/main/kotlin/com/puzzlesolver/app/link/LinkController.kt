package com.puzzlesolver.app.link

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.link.LinkColour
import com.puzzlesolver.core.puzzle.link.LinkPairer
import com.puzzlesolver.core.puzzle.link.LinkPairing
import com.puzzlesolver.core.puzzle.link.LinkPlanner
import com.puzzlesolver.core.puzzle.link.LinkScanner
import com.puzzlesolver.core.puzzle.link.LinkWall
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
 * One go at the Link wall: pan it once, end to end, while every panel is lit, and get the
 * pairs and the shortest order to press them in.
 *
 * The wall goes dark the moment the first panel is pressed, so everything has to be read
 * before that, and the answer has to be in the app. Frames arrive on the pipeline's solver
 * thread through [onFrame] and the scanner lives on that thread alone, as with Mines;
 * pairing and planning run on a worker, because the exact plan for sixteen pairs is a few
 * hundred milliseconds on the phone.
 *
 * Each scan is kept under `files/link/<date-time>/` -- the frames, what the scanner made
 * of each (`scan.txt`), and the board and plan (`board.txt`) -- so the first scan of the
 * real room can be replayed at a desk (`LinkReplay`). Nobody has pointed the phone at the
 * room's wall yet.
 */
class LinkController(
    context: Context,
    /** The live camera's dials, when there is a camera. */
    private val tuning: () -> CameraTuning?,
) {
    enum class Phase { AIMING, SCANNING, PLANNING, DONE }

    data class State(
        val phase: Phase = Phase.AIMING,
        val message: String = "Stand at one end of the wall with both rows of panels in view",
        val inView: Int = 0,
        val panels: Int = 0,
        val pairs: Int = 0,
        val leftEnd: Boolean = false,
        val rightEnd: Boolean = false,
        val lostTrack: Boolean = false,
        /** The wall as read: growing while scanning, final once done. */
        val wall: LinkWall? = null,
        val pairing: LinkPairing? = null,
        val path: LinkPlanner.Path? = null,
        /** The pair being pressed now, from 0; the path's length once every pair is done. */
        val step: Int = 0,
        /** What the camera is doing. */
        val camera: String = "",
    )

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Solver thread only.
    private val scanner = LinkScanner(log = ::scanLog)
    private var scanning = false
    private var lastSnapshotNanos = 0L
    private var aimingSinceNanos = 0L

    @Volatile
    private var scanDir: File? = null
    private var scanText: PrintWriter? = null
    private var framesText: PrintWriter? = null
    private var savedFrames = 0
    private var unsavedFrames = 0

    private val startRequested = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var planGeneration = 0

    /**
     * What the scan itself had to say about the wall -- an end not seen, colours named
     * afresh. Kept apart from the pairing's warnings, which each plan works out again, so
     * a correction does not repeat them.
     */
    @Volatile
    private var scanWarnings: List<String> = emptyList()

    private val writer = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(WRITE_QUEUE), ThreadPoolExecutor.DiscardPolicy(),
    )
    private val boardWriter = Executors.newSingleThreadExecutor()

    // --- From the screen ---------------------------------------------------

    fun startScan() {
        if (_state.value.phase != Phase.AIMING) return
        startRequested.set(true)
        _state.update { it.copy(phase = Phase.SCANNING, message = "Pan slowly to the other end") }
        Log.i(TAG, "scan requested")
    }

    /** Ends the scan where it is and plans what was read. */
    fun finishScan() {
        if (_state.value.phase == Phase.SCANNING) finishRequested.set(true)
    }

    /** Back to aiming, whatever was happening. */
    fun rescan() {
        planGeneration++
        cancelRequested.set(true)
        _state.update { State(camera = it.camera) }
        Log.i(TAG, "rescan")
    }

    /** Steps one button of one panel through the five colours, then pairs and plans again. */
    fun correctCell(panel: Int, cell: Int) {
        val s = _state.value
        val wall = s.wall ?: return
        if (s.phase != Phase.DONE || panel !in wall.panels.indices) return
        val current = wall.panels[panel].cells[cell]
        val next = (current + 1).mod(LinkColour.COUNT)
        val corrected = wall.withCell(panel, cell, next)
        Log.i(TAG, "${wall.label(panel)} button ${cell + 1} set by hand: ${LinkColour.name(current)} -> ${LinkColour.name(next)}")
        scanDir?.let { appendBoard(it, "corrected ${wall.label(panel)} button ${cell + 1} to ${LinkColour.name(next)}", corrected, null, null) }
        plan(corrected)
    }

    fun nextStep() = _state.update { s -> s.copy(step = (s.step + 1).coerceAtMost(s.path?.steps?.size ?: 0)) }

    fun previousStep() = _state.update { s -> s.copy(step = (s.step - 1).coerceAtLeast(0)) }

    /** Jumps to the pair [panel] belongs to: for finding the place again after losing count. */
    fun jumpToPanel(panel: Int) = _state.update { s ->
        val k = s.path?.steps?.indexOfFirst { it.first == panel || it.second == panel } ?: -1
        if (k >= 0) s.copy(step = k) else s
    }

    /** Shows [wall] as though it had just been scanned. Kept out of every scan's folder. */
    fun showBoard(wall: LinkWall) {
        cancelRequested.set(true)
        scanDir = null
        scanWarnings = wall.warnings
        Log.i(TAG, "board shown by hand: ${wall.size} panels")
        plan(wall)
    }

    /** One stop either way on the camera, for a room the preset gets wrong. */
    fun nudgeExposure(brighter: Boolean) {
        val t = tuning() ?: return
        if (if (brighter) t.brighter() else t.darker()) Log.i(TAG, "exposure ${if (brighter) "up" else "down"}: ${t.describeRequest()}")
    }

    fun release() {
        scope.cancel()
        writer.shutdown()
        boardWriter.shutdown()
    }

    // --- From the solver thread ----------------------------------------------

    /** One camera frame, upright. The frame is the caller's again once this returns. */
    fun onFrame(frame: RgbImage, timestampNanos: Long) {
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

        val progress = scanner.offer(frame, timestampNanos)
        if (scanning) saveFrame(frame, timestampNanos)

        if (!scanning) {
            _state.update {
                if (it.phase != Phase.AIMING) it else it.copy(
                    message = progress.message,
                    inView = progress.inView,
                    camera = describeCamera(),
                )
            }
            // Started afresh every couple of seconds while aiming, so what it says is about now.
            if (timestampNanos - aimingSinceNanos > AIM_WINDOW_NANOS) {
                scanner.reset()
                aimingSinceNanos = timestampNanos
            }
            return
        }

        val done = progress.phase == LinkScanner.Phase.DONE
        val finish = finishRequested.getAndSet(false)
        val snapshotDue = timestampNanos - lastSnapshotNanos > SNAPSHOT_NANOS
        if (done || finish || snapshotDue) lastSnapshotNanos = timestampNanos
        val wall = if (done || finish || snapshotDue) scanner.result() else null
        _state.update {
            if (it.phase != Phase.SCANNING) it else it.copy(
                message = progress.message,
                inView = progress.inView,
                panels = progress.panels,
                pairs = progress.pairs,
                leftEnd = progress.leftEnd,
                rightEnd = progress.rightEnd,
                lostTrack = progress.phase == LinkScanner.Phase.LOST,
                wall = wall ?: it.wall,
                camera = describeCamera(),
            )
        }
        if (done || finish) {
            scanning = false
            val result = scanner.result()
            if (result == null || result.size < 2) {
                _state.update { it.copy(phase = Phase.AIMING, message = "No panels were read. Aim at the wall and scan again.") }
                closeScan()
                return
            }
            Log.i(TAG, "scan ${if (done) "read the whole wall" else "finished by hand"}: ${result.size} panels")
            scanDir?.let { appendBoard(it, if (done) "scanned end to end" else "finished by hand", result, null, null) }
            closeFrames()
            scanWarnings = result.warnings
            plan(result)
        }
    }

    // --- Planning ----------------------------------------------------------------

    /** Pairs [wall] and works out the shortest walk over the pairs, starting at whichever pair makes it shortest. */
    private fun plan(wall: LinkWall) {
        val generation = ++planGeneration
        _state.update { it.copy(phase = Phase.PLANNING, wall = wall) }
        scope.launch {
            val (pairing, path, millis) = withContext(Dispatchers.Default) {
                val t0 = System.nanoTime()
                val pairing = LinkPairer.pair(wall)
                val path = LinkPlanner.plan(wall, pairing, LinkPlanner.Start.ANYWHERE)
                Triple(pairing, path, (System.nanoTime() - t0) / 1_000_000)
            }
            if (generation != planGeneration) return@launch
            val warnings = buildList {
                addAll(scanWarnings)
                if (pairing.unpaired.isNotEmpty()) {
                    add("No twin for ${pairing.unpaired.joinToString { wall.label(it) }}: the panel count is odd, so a panel was missed or misread.")
                }
                val doubtful = pairing.doubtful
                if (doubtful.isNotEmpty()) {
                    add(
                        "Not exact twins: " + pairing.pairs.filter { pairing.differences[it[0]] > 0 }
                            .joinToString { "${wall.label(it[0])}/${wall.label(it[1])}" } +
                            ". Check them against the wall before the first press.",
                    )
                }
            }
            val summary = "${pairing.pairs.size} pair${if (pairing.pairs.size == 1) "" else "s"}, " +
                "%.0f buttons of walking between them".format(path.between) +
                if (path.optimal) " (shortest)" else " (best found)"
            Log.i(TAG, "plan in ${millis}ms: $summary")
            // Not on the screen -- the board outlines doubtful panels -- so kept here.
            warnings.forEach { Log.w(TAG, it) }
            val warned = wall.withWarnings(warnings)
            scanDir?.let { appendBoard(it, "plan: $summary", warned, pairing, path) }
            _state.update {
                it.copy(
                    phase = Phase.DONE,
                    wall = warned,
                    pairing = pairing,
                    path = path,
                    step = 0,
                )
            }
        }
    }

    // --- Keeping the scan --------------------------------------------------------------

    private fun openScan() {
        closeScan()
        try {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "link/" + STAMP.format(Date())).apply { mkdirs() }
            scanDir = dir
            scanText = PrintWriter(File(dir, "scan.txt").bufferedWriter())
            framesText = PrintWriter(File(dir, "frames.txt").bufferedWriter())
            savedFrames = 0
            unsavedFrames = 0
            scanText?.println("camera: ${describeCamera()}")
        } catch (e: Exception) {
            Log.w(TAG, "could not open the scan log", e)
        }
    }

    private fun scanLog(line: String) {
        if (scanning) scanText?.println(line)
    }

    /** Keeps one frame, full size and JPEG 95, as Mines does, so a replay sees what the phone saw. */
    private fun saveFrame(frame: RgbImage, t: Long) {
        val dir = scanDir ?: return
        if (savedFrames >= MAX_SAVED_FRAMES) return
        if (writer.queue.remainingCapacity() == 0) {
            unsavedFrames++
            return
        }
        val name = "%04d.jpg".format(savedFrames++)
        framesText?.println("$name $t C")
        val copy = RgbImage(frame.width, frame.height, frame.data.copyOf())
        writer.execute {
            try {
                val b = Bitmap.createBitmap(copy.toArgb(), copy.width, copy.height, Bitmap.Config.ARGB_8888)
                File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                b.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "could not save $name", e)
            }
        }
    }

    private fun closeFrames() {
        if (framesText != null) {
            val line = "frames saved: $savedFrames, not saved (writers behind): $unsavedFrames"
            scanText?.println(line)
            Log.i(TAG, line)
        }
        framesText?.close()
        framesText = null
        scanText?.flush()
    }

    private fun closeScan() {
        closeFrames()
        scanText?.close()
        scanText = null
    }

    private fun appendBoard(dir: File, heading: String, wall: LinkWall, pairing: LinkPairing?, path: LinkPlanner.Path?) {
        val text = buildString {
            appendLine("== $heading")
            append(wall.describe())
            wall.warnings.forEach { appendLine("warning: $it") }
            pairing?.pairs?.forEach { (a, b) ->
                appendLine("pair ${wall.label(a)} ${wall.label(b)} ${wall.panels[a].pattern} differs=${pairing.differences[a]}")
            }
            path?.steps?.forEachIndexed { n, s -> appendLine("step ${n + 1}: ${wall.label(s.first)} then ${wall.label(s.second)}") }
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
        return "${t.describeRequest()} (got ${t.reported.describe()})"
    }

    private companion object {
        const val TAG = "LinkController"

        const val SNAPSHOT_NANOS = 400_000_000L
        const val AIM_WINDOW_NANOS = 2_000_000_000L

        /** Half a minute at thirty frames a second; a whole pan is ten or twenty seconds. */
        const val MAX_SAVED_FRAMES = 900
        const val JPEG_QUALITY = 95
        const val WRITE_QUEUE = 12

        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
