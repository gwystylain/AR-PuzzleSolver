package com.puzzlesolver.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException
import com.puzzlesolver.app.frame.ArCoreFrameSource
import com.puzzlesolver.app.frame.Camera2FrameSource
import com.puzzlesolver.app.frame.CameraProbe
import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.app.frame.FrameSource
import com.puzzlesolver.app.frame.VideoFrameSource
import com.puzzlesolver.app.pipeline.ScanPipeline
import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.puzzle.gems.GemAdapter
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.terminal.TerminalAdapter
import com.puzzlesolver.app.record.LogCapture
import com.puzzlesolver.app.record.SessionBundle
import com.puzzlesolver.app.record.SessionStore
import com.puzzlesolver.app.ui.PuzzleSolverTheme
import com.puzzlesolver.app.ui.ScanScreen

/**
 * Hosts the GL surface and the Compose overlay.
 *
 * Kept deliberately thin. All the interesting state lives in [ScanPipeline], which
 * knows nothing about Android lifecycle, so it can also be driven from a test.
 */
class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var pipeline: ScanPipeline
    private lateinit var sessionStore: SessionStore

    private var arSource: ArCoreFrameSource? = null
    private var sessionResumed = false

    /**
     * The run's log, written to the phone rather than to a laptop's logcat.
     *
     * Created eagerly because it is the artifact whose absence cannot be repaired: a
     * capture can be retaken while still at the wall, and a log of a run that already
     * happened cannot be.
     */
    private val logCapture by lazy { LogCapture(java.io.File(getExternalFilesDir(null), "logs")) }

    /**
     * Set when the user stops the log by hand, so the auto-start does not undo it.
     *
     * Without this the button would appear not to work: Gems is still the active mode a
     * fiftieth of a second later, and the poll below would start a fresh log on the very
     * next tick.
     */
    private var loggingSuppressed = false

    private var isLogging by mutableStateOf(false)
    private var logSummary by mutableStateOf<String?>(null)

    /** Recomposed from the pipeline's published state each frame. */
    private var uiState by mutableStateOf(ScanPipeline.UiState())
    private var recording by mutableStateOf(false)
    private var replayName by mutableStateOf<String?>(null)

    /** Mirror of the pipeline's gem targets, so Compose recomposes when they change. */
    private var gemTargets by mutableStateOf<List<GemPattern>>(emptyList())

    /**
     * Whether to take the camera over from ARCore, so exposure can be set.
     *
     * **Off by default, on measurement rather than principle.** On a CPH2655 with ARCore
     * 1.48, shared camera mode discards the app's capture request entirely: 1/250 s at
     * ISO 100 read back as 1/100 s at ISO 2008, before ARCore's first resume, after it,
     * and re-asserted a beat later; even `CONTROL_EFFECT_MODE_MONO` left the preview in
     * colour. The same request through plain Camera2 with no ARCore present is honoured
     * exactly -- `--ez camprobe true` re-runs that check in one second.
     *
     * So it buys nothing here, and it is not free: ARCore cannot use a hardware depth
     * sensor while sharing, and the wall fit converges faster with one. Zero benefit
     * against a real cost is an easy default.
     *
     * `--ez sharedcam true` turns it back on, which is worth doing on a new ARCore
     * release or a different phone -- the HUD's "asked" versus "actual" lines say within
     * seconds whether it took.
     */
    private var useSharedCamera = false

    /**
     * Whether to pick the camera config with the largest image rather than the highest
     * frame rate. Also launch-time, for the same reason.
     */
    private var preferDetail = false

    private var launchShutterMicros = 0L
    private var launchIso = 0

    /**
     * Standalone Camera2 check, run instead of the AR session when asked for.
     *
     * Instead of, not alongside: only one thing can hold the camera, and the whole point
     * of the probe is that ARCore is nowhere near it.
     */
    private var probe: CameraProbe? = null

    /**
     * Lets the debug controls be driven from adb instead of by tapping.
     *
     *   adb shell am broadcast -a com.puzzlesolver.app.DEBUG \
     *       --ef flat 0.5 --ei expect 9 --es pin sudoku --ez rescan true
     *
     * Worth having beyond convenience: tapping requires knowing where the buttons are,
     * and Compose does not publish its nodes to uiautomator, so the alternative is
     * screenshotting the live camera feed to hunt for coordinates. This is also the only
     * way to script a repeatable sequence of debug states.
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.hasExtra("flat")) {
                val d = intent.getFloatExtra("flat", 0f)
                pipeline.setForcedFlatWall(d)
                Log.i(TAG, "debug: forced flat wall = ${d}m")
            }
            if (intent.hasExtra("expect")) {
                val n = intent.getIntExtra("expect", 0)
                pipeline.setExpectedCells(if (n > 0) n else null)
                Log.i(TAG, "debug: expected cells = $n")
            }
            intent.getStringExtra("pin")?.let {
                selectPuzzleMode(it)
                Log.i(TAG, "debug: pinned puzzle = $it")
            }
            intent.getStringExtra("gem")?.let { spec ->
                // 1-based on the wire to match the digits drawn on the wall and the
                // labels on the target buttons; nothing else in the app is 1-based, so
                // converting here keeps that oddity at the boundary.
                val slot = intent.getIntExtra("gemslot", 1) - 1
                val pattern = parseGemPattern(spec)
                if (pattern == null) {
                    Log.w(TAG, "debug: unrecognised gem pattern '$spec'")
                } else {
                    pipeline.setGemTarget(slot, pattern)
                    gemTargets = pipeline.gemTargets.all()
                    Log.i(TAG, "debug: gem target ${slot + 1} = $pattern")
                }
            }
            if (intent.getBooleanExtra("gemclear", false)) {
                pipeline.clearGemTargets()
                gemTargets = pipeline.gemTargets.all()
                Log.i(TAG, "debug: gem targets cleared")
            }
            if (intent.getBooleanExtra("dump", false)) {
                val f = java.io.File(getExternalFilesDir(null), "canvas-dump.png")
                pipeline.requestMirrorDump(f)
                Log.i(TAG, "debug: canvas dump requested -> $f")
            }
            if (intent.getBooleanExtra("gemdump", false)) {
                startGemCapture(
                    intent.getIntExtra("gemframes", GEM_CAPTURE_FRAMES),
                    intent.getIntExtra("gemevery", GEM_CAPTURE_INTERVAL_MS.toInt()).toLong(),
                )
            }
            intent.getStringExtra("cam")?.let { applyCameraCommand(it) }
            if (intent.hasExtra("ev")) {
                val ev = intent.getIntExtra("ev", 0)
                pipeline.tuning()?.update { it.copy(evSteps = ev) }
                pipeline.setAutoExposureEnabled(false)
                Log.i(TAG, "debug: exposure compensation = $ev steps")
            }
            if (intent.hasExtra("shutter")) {
                // Microseconds on the wire; `am broadcast` has no long extra that reads
                // nicely, and a shutter in microseconds is a number a person can type.
                val micros = intent.getIntExtra("shutter", 0).toLong()
                if (micros > 0) {
                    pipeline.tuning()?.update {
                        it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = micros * 1000L)
                    }
                    pipeline.setAutoExposureEnabled(false)
                    Log.i(TAG, "debug: shutter = ${micros}us")
                }
            }
            if (intent.hasExtra("iso")) {
                val iso = intent.getIntExtra("iso", 0)
                if (iso > 0) {
                    pipeline.tuning()?.update { it.copy(mode = CameraTuning.Mode.MANUAL, iso = iso) }
                    pipeline.setAutoExposureEnabled(false)
                    Log.i(TAG, "debug: iso = $iso")
                }
            }
            if (intent.hasExtra("sharedcam")) {
                useSharedCamera = intent.getBooleanExtra("sharedcam", false)
                Log.i(TAG, "debug: sharedCamera = $useSharedCamera; restarting the camera")
                startLiveSource()
            }
            if (intent.hasExtra("detail")) {
                preferDetail = intent.getBooleanExtra("detail", false)
                Log.i(TAG, "debug: preferDetail = $preferDetail; restarting the camera")
                startLiveSource()
            }
            if (intent.getBooleanExtra("rescan", false)) {
                pipeline.restartScan()
                Log.i(TAG, "debug: rescan")
            }
            if (intent.getBooleanExtra("newwall", false)) {
                pipeline.restartEverything()
                Log.i(TAG, "debug: restart everything")
            }
        }
    }

    /**
     * Parses `outer/middle/centre`, e.g. `red/yellow/blue`.
     *
     * Trailing rings may be left off or given as `-` to leave them unset, which is a
     * wildcard rather than a colour -- the same partial target the dialog produces
     * after one or two taps.
     */
    private fun parseGemPattern(spec: String): GemPattern? {
        val parts = spec.split('/', ',')
        if (parts.isEmpty() || parts.size > 3) return null
        var pattern = GemPattern.BLANK
        for ((index, raw) in parts.withIndex()) {
            val name = raw.trim().lowercase()
            if (name.isEmpty() || name == "-") continue
            val colour = GemColour.ALL.firstOrNull { GemColour.name(it) == name } ?: return null
            pattern = pattern.withZone(GemPattern.ZONE_ORDER[index], colour)
        }
        return pattern
    }

    /**
     * One-word camera commands, so the dials can be driven without tapping.
     *
     * Worth more here than the tap targets are: exposure is the setting most likely to
     * need a dozen tries in a room where you cannot see the screen well, and a scripted
     * sweep beats squinting at a HUD over a wall of LEDs.
     */
    private fun applyCameraCommand(command: String) {
        val tuning = pipeline.tuning()
        if (tuning == null) {
            Log.w(TAG, "debug: no camera control on this source")
            return
        }
        when (command.trim().lowercase()) {
            "darker" -> pipeline.nudgeExposure(darker = true)
            "brighter" -> pipeline.nudgeExposure(darker = false)
            "manual" -> pipeline.setCameraMode(CameraTuning.Mode.MANUAL)
            "auto" -> pipeline.setCameraMode(CameraTuning.Mode.AUTO)
            "ledwall" -> pipeline.applyLedWallPreset()
            "reset" -> pipeline.resetCamera()
            "aelock" -> pipeline.setCameraLocks(true, tuning.settings.lockAwb)
            "aefree" -> pipeline.setCameraLocks(false, tuning.settings.lockAwb)
            "awblock" -> pipeline.setCameraLocks(tuning.settings.lockAe, true)
            "awbfree" -> pipeline.setCameraLocks(tuning.settings.lockAe, false)
            "mono" -> pipeline.tuning()?.update { it.copy(diagnosticMono = true) }
            "nomono" -> pipeline.tuning()?.update { it.copy(diagnosticMono = false) }
            "autoev" -> pipeline.setAutoExposureEnabled(true)
            "noautoev" -> pipeline.setAutoExposureEnabled(false)
            else -> {
                Log.w(TAG, "debug: unrecognised camera command '$command'")
                return
            }
        }
        Log.i(TAG, "debug: cam $command -> ${tuning.describeRequest()}")
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startLiveSource() else toast("Camera permission is required to scan a wall")
    }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { openReplay(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionStore = SessionStore(this)

        // Canvas resolution is a launch-time choice because the GL textures are sized
        // from it, so it cannot be changed on a live pipeline:
        //
        //   adb shell am start -n com.puzzlesolver.app/.MainActivity --ef res 0.0005
        //
        // Note that canvas texels are *metric*, so texels-per-cell depends only on the
        // puzzle's physical cell size -- not on how close you stand. A 3 cm cell is ~20
        // texels at the 1.5 mm default however much of the frame it fills. Wall-scale
        // puzzles with 5-10 cm cells are comfortable at the default; anything
        // desk-sized needs a finer canvas, at the cost of covering less wall.
        // Launch-time exposure, applied before the camera is ever opened. Kept
        // separate from the broadcast because the two reach the camera at completely
        // different moments, and on this device only one of them lands -- see
        // docs/CAMERA_CONTROL.md.
        launchShutterMicros = intent?.getIntExtra("shutter", 0)?.toLong() ?: 0L
        launchIso = intent?.getIntExtra("iso", 0) ?: 0

        useSharedCamera = intent?.getBooleanExtra("sharedcam", false) ?: false
        preferDetail = intent?.getBooleanExtra("detail", false) ?: false
        Log.i(TAG, "launch: sharedCamera=$useSharedCamera preferDetail=$preferDetail")

        val metresPerTexel = intent?.getFloatExtra("res", 0f)
            ?.takeIf { it > 0f } ?: CanvasSpec().metresPerTexel
        val spec = CanvasSpec(metresPerTexel = metresPerTexel)
        Log.i(TAG, "canvas: ${"%.4f".format(spec.metresPerTexel)} m/texel, " +
            "${"%.2f".format(spec.widthMetres)}m wide")
        pipeline = ScanPipeline(spec = spec, displayRotationProvider = ::currentDisplayRotation)

        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(pipeline)
            // Continuous rather than on-demand: frames arrive from the camera whenever
            // they arrive, and the user was explicit that throughput beats battery.
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        gemTargets = pipeline.gemTargets.all()

        setContent {
            PuzzleSolverTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
                    ScanScreen(
                        state = uiState,
                        isRecording = recording,
                        replayName = replayName,
                        onRestartScan = { pipeline.restartScan() },
                        onRestartAll = { pipeline.restartEverything() },
                        onToggleRecording = { toggleRecording() },
                        isLogging = isLogging,
                        logSummary = logSummary,
                        onToggleLogging = { toggleLogging() },
                        onExport = { exportRun() },
                        onCaptureGems = {
                            startGemCapture(GEM_CAPTURE_FRAMES, GEM_CAPTURE_INTERVAL_MS)
                        },
                        onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                        onOpenLastRecording = { openLastRecording() },
                        onReturnToLive = { startLiveSource() },
                        onSelectPuzzleMode = { selectPuzzleMode(it) },
                        puzzleModes = pipeline.puzzleModes,
                        gemTargets = gemTargets,
                        onSetGemTarget = { slot, pattern ->
                            pipeline.setGemTarget(slot, pattern)
                            gemTargets = pipeline.gemTargets.all()
                            // Into the log, because "no match in view" and "no target
                            // entered" look identical afterwards and are not the same
                            // finding at all.
                            Log.i(
                                TAG,
                                "gem target ${slot + 1} = " +
                                    if (pattern.isBlank) "-" else pattern.toString(),
                            )
                        },
                        onNudgeExposure = { pipeline.nudgeExposure(it) },
                        onSetCameraManual = {
                            pipeline.setCameraMode(
                                if (it) CameraTuning.Mode.MANUAL else CameraTuning.Mode.AUTO
                            )
                        },
                        onSetCameraLocks = { ae, awb -> pipeline.setCameraLocks(ae, awb) },
                        onLedPreset = { pipeline.applyLedWallPreset() },
                        onAutoExposure = { pipeline.setAutoExposureEnabled(it) },
                        onResetCamera = { pipeline.resetCamera() },
                        onForceFlatWall = { pipeline.setForcedFlatWall(it) },
                        onExpectCells = { pipeline.setExpectedCells(it) },
                    )
                }
            }
        }

        // Poll the pipeline's published state. A frame-rate-independent 20 Hz is
        // plenty for text that a human reads, and it keeps the GL thread free of
        // any coupling to Compose.
        glView.postDelayed(object : Runnable {
            override fun run() {
                uiState = pipeline.state.get()
                arSource?.let { recording = it.isRecording }
                // The two live-camera modes get the log started for them the moment the
                // mode becomes active, for the same reason Gems gets the LED-wall preset:
                // there is one trip to the room, and a setting whose absence is only
                // discovered afterwards is not one to leave to memory. An explicit Stop
                // is respected.
                val liveMode = uiState.isGemsMode || uiState.isTerminalMode
                if (liveMode && !loggingSuppressed && !logCapture.isRunning) {
                    startLogging()
                }
                isLogging = logCapture.isRunning
                logSummary = logCapture.describe().takeIf { it.isNotEmpty() }
                checkForCameraFallback()
                glView.postDelayed(this, 50)
            }
        }, 50)
    }

    /**
     * Rebuilds the camera without sharing if sharing has failed.
     *
     * This is the one failure that cannot be recovered in place: a session created to
     * share the camera cannot fall back to letting ARCore open one, so once the shared
     * path gives up that session will never produce another frame. Rebuilding costs the
     * exposure dial and the wall fit; not rebuilding costs the app.
     *
     * Once only. If the plain path fails too, the problem is not the sharing and
     * restarting the source in a loop would only hide it.
     */
    private fun checkForCameraFallback() {
        if (!useSharedCamera || cameraFallbackDone) return
        if (pipeline.frameSource.get()?.cameraFailed != true) return
        cameraFallbackDone = true
        useSharedCamera = false
        Log.w(TAG, "shared camera failed; restarting without it, exposure control is gone")
        toast("Camera sharing failed on this device: no exposure control")
        startLiveSource()
    }

    private var cameraFallbackDone = false

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCamera.launch(Manifest.permission.CAMERA)
        } else if (intent?.getBooleanExtra("camprobe", false) == true && probe == null) {
            runCameraProbe()
        } else if (pipeline.frameSource.get() == null) {
            startLiveSource()
        } else {
            resumeSource(pipeline.frameSource.get())
        }
        glView.onResume()
        ContextCompat.registerReceiver(
            this,
            debugReceiver,
            IntentFilter(DEBUG_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered; harmless.
        }
        // Whatever is driving, not just ARCore: the plain camera holds the device open
        // and has to give it back, or coming out of the background finds it taken.
        pipeline.frameSource.get()?.pause()
        sessionResumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        probe?.close()
        pipeline.release()
        pipeline.frameSource.get()?.close()
    }

    // --- Sources ---------------------------------------------------------

    private fun runCameraProbe() {
        val micros = if (launchShutterMicros > 0) launchShutterMicros else 4000L
        val iso = if (launchIso > 0) launchIso else 100
        val id = try {
            getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.first()
        } catch (e: Exception) {
            toast("probe: no camera: ${e.message}")
            return
        }
        val p = CameraProbe(this)
        probe = p
        p.run(id, micros * 1000L, iso)
        glView.postDelayed({ toast(p.result) }, 3000)
    }

    /**
     * Chooses the puzzle type, and with it the camera.
     *
     * The two are not independent any more. Two modes read the camera frame directly and
     * want no pose at all, so they run on a plain Camera2 source; everything else needs
     * the pose and runs on ARCore. Gems is there because ARCore will not pass its
     * exposure request through to the sensor, Terminal because the wall it reads is
     * being played while it is read and there is nothing worth accumulating. Switching
     * modes therefore switches the source, which is heavier than it used to be -- but it
     * happens on a deliberate tap, and the alternative is a mode that cannot see the
     * wall it is pointed at.
     */
    private fun selectPuzzleMode(id: String?) {
        Log.i(TAG, "mode selected: ${id ?: "automatic"}")
        pipeline.selectPuzzleMode(id)
        val wantsLiveCamera = id == GemAdapter.ID || id == TerminalAdapter.ID
        if (wantsLiveCamera != usingLiveCamera) {
            usingLiveCamera = wantsLiveCamera
            startLiveSource()
        }
    }

    /** True while the plain Camera2 source is driving, i.e. Gems or Terminal. */
    private var usingLiveCamera = false

    private fun startLiveSource() {
        if (usingLiveCamera) {
            Log.i(TAG, "startLiveSource(): plain Camera2 for the pose-free modes")
            swapSource {
                arSource = null
                Camera2FrameSource(this).also { source ->
                    if (launchShutterMicros > 0 || launchIso > 0) {
                        source.tuning.update { t ->
                            t.copy(
                                mode = CameraTuning.Mode.MANUAL,
                                exposureNanos = if (launchShutterMicros > 0) {
                                    launchShutterMicros * 1000L
                                } else {
                                    t.exposureNanos
                                },
                                iso = if (launchIso > 0) launchIso else t.iso,
                            )
                        }
                    }
                }
            }
            replayName = null
            return
        }
        val ready = ensureArCore()
        Log.i(TAG, "startLiveSource(): arcoreReady=$ready")
        if (!ready) return
        swapSource {
            ArCoreFrameSource(
                context = this,
                useSharedCamera = useSharedCamera,
                preferDetail = preferDetail,
            ).also {
                if (launchShutterMicros > 0 || launchIso > 0) {
                    it.tuning.update { s ->
                        s.copy(
                            mode = CameraTuning.Mode.MANUAL,
                            exposureNanos = if (launchShutterMicros > 0) {
                                launchShutterMicros * 1000L
                            } else {
                                s.exposureNanos
                            },
                            iso = if (launchIso > 0) launchIso else s.iso,
                        )
                    }
                }
                it.create()
                arSource = it
            }
        }
        replayName = null
        resumeArSession()
    }

    private fun openReplay(uri: Uri) {
        // An ARCore recording is an MP4 with extra tracks, so we cannot tell the two
        // apart by extension. Try the faithful path first and fall back to plain
        // decode, telling the user which one they got -- the difference matters,
        // because only the AR dataset path reproduces the real geometry.
        if (ensureArCore()) {
            try {
                swapSource {
                    ArCoreFrameSource(this, playbackDataset = uri).also {
                        it.create()
                        arSource = it
                    }
                }
                resumeArSession()
                replayName = "AR recording"
                toast("Replaying with recorded ARCore tracking")
                return
            } catch (e: Exception) {
                Log.i(TAG, "not an ARCore dataset, falling back to plain video", e)
            }
        }
        swapSource {
            arSource = null
            VideoFrameSource(this, uri)
        }
        replayName = "plain video (vision-only)"
        toast("No AR data in this file: detection only, geometry is approximate")
    }

    private fun openLastRecording() {
        val last = sessionStore.mostRecentRecording()
        if (last == null) {
            toast("No recordings yet")
            return
        }
        openReplay(last)
    }

    private fun swapSource(factory: () -> FrameSource) {
        val previous = pipeline.frameSource.get()

        // Retire the outgoing AR session before building the incoming one, and clear
        // the resumed flag with it.
        //
        // That flag is the whole reason replay never worked. resumeArSession() is a
        // no-op while it believes a session is already running, so a freshly created
        // playback session was left paused and every update() on it threw
        // SessionPausedException -- which surfaced as a replay that silently did
        // nothing while the live camera carried on underneath.
        val outgoing = arSource
        outgoing?.pause()
        sessionResumed = false

        val next = try {
            factory()
        } catch (e: Exception) {
            Log.e(TAG, "failed to create frame source", e)
            toast("Could not open that source: ${e.message}")
            // The factory failed, so arSource is still the outgoing one. Put it back
            // rather than leaving the app with a paused camera and no way to recover.
            if (arSource === outgoing) resumeArSession()
            return
        }
        // Resume before publishing, not after. The GL thread starts calling update()
        // the instant the source is visible to it, and a session that is not resumed
        // yet throws SessionPausedException on every one of those calls. Harmless in
        // itself -- the next frame succeeds -- but it buried the genuine failure above
        // in identical noise, which cost more than the race ever did.
        resumeSource(next)
        pipeline.frameSource.set(next)
        // attachTexture must run on the GL thread; the renderer owns the texture name.
        glView.queueEvent {
            previous?.close()
            pipeline.restartEverything()
        }
    }

    /**
     * Starts whichever source is in play.
     *
     * Generic rather than ARCore-specific since Gems arrived: the plain camera needs
     * resuming exactly as much, and an earlier version of this only knew how to start an
     * ARCore session, so picking Gems produced a black screen and no error anywhere.
     *
     * [sessionResumed] still guards only the ARCore path, where a double resume is what
     * it was written to prevent; the camera source is idempotent about it.
     */
    private fun resumeSource(source: FrameSource?) {
        if (source == null) return
        if (source === arSource && sessionResumed) return
        try {
            source.resume()
            if (source === arSource) sessionResumed = true
        } catch (e: Exception) {
            Log.e(TAG, "source resume failed", e)
            toast("Could not start the camera: ${e.message}")
        }
    }

    private fun resumeArSession() = resumeSource(arSource)

    private fun toggleRecording() {
        val source = arSource
        if (source == null) {
            toast("Recording needs the live camera")
            return
        }
        if (source.isRecording) {
            source.stopRecording()
            recording = false
            toast("Recording saved")
            return
        }
        // ARCore refuses to begin recording on a running session, so bounce it.
        val destination = sessionStore.newRecordingUri()
        source.pause()
        sessionResumed = false
        if (source.startRecording(destination)) {
            resumeArSession()
            recording = true
            toast("Recording; replay it later to debug this scan")
        } else {
            resumeArSession()
            toast("Could not start recording")
        }
    }

    private fun ensureArCore(): Boolean {
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> true
                // ARCore is installing; Play hands control back and we retry in onResume.
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> false
            }
        } catch (e: UnavailableException) {
            toast("ARCore is not available on this device: ${e.message}")
            false
        }
    }

    /**
     * Display rotation as a `Surface.ROTATION_*` constant, which ARCore needs to build
     * its camera-texture transform.
     */
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    // --- Getting the run off the phone ----------------------------------

    /**
     * Starts writing this process's log to a file on the phone.
     *
     * The heartbeat has always been written; until now it was only ever *read* over adb,
     * which quietly made a cable a precondition for the mode most likely to need
     * diagnosing and least likely to have one. The ring buffer is shared with every
     * other process and wraps without saying so, so "plug the phone in afterwards" is
     * not a substitute -- by then the interesting part may be gone.
     */
    private fun startLogging() {
        if (logCapture.start(logHeader())) {
            Log.i(TAG, "logging to ${logCapture.file?.absolutePath}")
        } else {
            toast(logCapture.failure ?: "Could not start logging")
        }
    }

    private fun toggleLogging() {
        if (logCapture.isRunning) {
            // Only a deliberate stop suppresses the auto-start; anything else and the
            // button would look broken in Gems, where the poll would restart it at once.
            loggingSuppressed = true
            logCapture.stop()
            toast("Log saved: ${logCapture.file?.name}")
        } else {
            loggingSuppressed = false
            startLogging()
        }
    }

    /**
     * What `device.txt` used to carry, written into the log itself.
     *
     * In the file rather than beside it because the two get separated: a log pasted into
     * a message arrives without the folder it came from, and a run whose device, build
     * and camera settings are unknown supports whichever conclusion the reader already
     * had.
     */
    private fun logHeader(): List<String> = listOf(
        "device:  ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        "android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
        "app:     ${packageManager.getPackageInfo(packageName, 0).versionName}",
        // In the header rather than left to ARCore's own log lines, which the tag filter
        // drops: the version is worth a line and the running commentary is not.
        "arcore:  ${versionOf("com.google.ar.core")}",
        "flags:   sharedcam=$useSharedCamera detail=$preferDetail",
    )

    private fun versionOf(pkg: String): String = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "not installed"
    }

    /**
     * Zips the run and offers it to the share sheet.
     *
     * The counterpart to `tools/collect-session.sh`, for the case that script cannot
     * cover: the phone is not plugged into anything and will not be for a while. What
     * leaves this way is the same set of artifacts minus the ARCore recordings, which
     * are too large to send and stay behind for adb -- [SessionBundle] reports how many
     * it left, so their absence is visible rather than silently assumed.
     */
    private fun exportRun() {
        val root = getExternalFilesDir(null)
        if (root == null) {
            toast("No external storage to export from")
            return
        }
        // Stopped first, and only if it is running: a log still being written would go
        // into the zip truncated at whatever byte the copy thread had reached, which is
        // the one artifact where the tail is the part that matters.
        val wasLogging = logCapture.isRunning
        if (wasLogging) logCapture.stop()
        val bundle = try {
            SessionBundle.write(root, SessionBundle.nextDestination(java.io.File(root, "exports")))
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            toast("Export failed: ${e.message}")
            return
        }
        if (bundle.entries == 0) {
            toast("Nothing to export yet -- press Capture at the wall first")
            return
        }
        share(bundle)
    }

    private fun share(bundle: SessionBundle.Result) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", bundle.file)
        } catch (e: Exception) {
            Log.e(TAG, "could not share ${bundle.file}", e)
            toast("Saved to ${bundle.file.name} but could not share it")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, bundle.file.name)
            putExtra(Intent.EXTRA_TEXT, bundle.describe())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        toast("Exported ${bundle.describe()}")
        startActivity(Intent.createChooser(intent, "Send the run"))
    }

    /**
     * Starts recording what the gem scanner is reading, into the app's files directory.
     *
     * This is what [toggleRecording] is for every other mode. It has to be a different
     * thing rather than a branch inside it: an ARCore dataset replays through ARCore's
     * own tracker and is a regression test of the whole stack, and Gems has no tracker,
     * no pose and no ARCore session to record from. What it has instead is a frame and
     * the reading made of that frame, which is all its answer ever depended on -- so
     * that is what gets written. See [com.puzzlesolver.app.record.GemRecorder].
     *
     * Reachable from a button and from the debug broadcast. The button is the one that
     * matters, because a capture is only worth anything while the phone is pointed at
     * the wall; the broadcast is for scripting a sequence, and for triggering one
     * without the tap jogging the aim.
     */
    private fun startGemCapture(frames: Int, intervalMillis: Long) {
        val directory = java.io.File(getExternalFilesDir(null), "gems")
        pipeline.startGemCapture(directory, frames, intervalMillis)
        Log.i(TAG, "gem capture: $frames frames every ${intervalMillis}ms -> $directory")
        toast("Capturing $frames frames -- keep the wall in view")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val DEBUG_ACTION = "com.puzzlesolver.app.DEBUG"

        /**
         * Twelve frames just under a second apart.
         *
         * Long enough to span a pan, an auto-exposure step and most of a round; short
         * enough that the phone is still pointed where the user meant it to be by the
         * end. Both are overridable from the broadcast, because the right answer depends
         * on which of those turns out to be the problem and that is not known in advance.
         */
        const val GEM_CAPTURE_FRAMES = 12
        const val GEM_CAPTURE_INTERVAL_MS = 900L
    }
}
