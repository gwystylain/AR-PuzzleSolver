package com.puzzlesolver.app.pipeline

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.puzzlesolver.app.frame.Camera2FrameSource
import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.app.frame.FrameData
import com.puzzlesolver.app.frame.FrameSource
import com.puzzlesolver.app.frame.TrackingState
import com.puzzlesolver.app.record.GemRecorder
import com.puzzlesolver.app.render.CameraBackgroundRenderer
import com.puzzlesolver.app.render.CanvasAccumulator
import com.puzzlesolver.app.render.GlUtil
import com.puzzlesolver.app.render.GlyphAtlas
import com.puzzlesolver.app.render.OverlayRenderer
import com.puzzlesolver.app.surface.WallTracker
import com.puzzlesolver.core.PuzzleEngine
import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.PuzzleRegistry
import com.puzzlesolver.core.puzzle.bombs.BombAdapter
import com.puzzlesolver.core.puzzle.gems.GemAdapter
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import com.puzzlesolver.core.puzzle.TemplateGlyphClassifier
import com.puzzlesolver.core.puzzle.nonogram.NonogramAdapter
import com.puzzlesolver.core.puzzle.terminal.TerminalAdapter
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import com.puzzlesolver.core.puzzle.sudoku.SudokuAdapter
import com.puzzlesolver.core.solve.SolveOutcome
import com.puzzlesolver.core.surface.WallSurface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Everything the scan does, wired together.
 *
 * Thread split:
 *
 *   GL thread     pulls frames, fits the wall, accumulates the mosaic, issues
 *                 readbacks, draws. Must never block -- this is the frame clock.
 *   Solver thread runs [PuzzleEngine] against a snapshot of the canvas. Free to
 *                 take tens of milliseconds; the GL thread does not wait on it.
 *
 * The handoff is a copy of the canvas mirror rather than a lock over it. A 16 MB
 * memcpy at a few hertz is far cheaper than the alternative, and the alternative is
 * worse than slow: a torn read of a half-updated cell can yield a *confidently
 * wrong* glyph, which then has to be discovered by contradiction and unwound. Paying
 * a couple of milliseconds to make that impossible is the right trade.
 */
class ScanPipeline(
    private val spec: CanvasSpec = CanvasSpec(),
    /**
     * Current display rotation as a `Surface.ROTATION_*` constant. A provider rather
     * than a stored value so it cannot go stale: the activity handles rotation itself
     * via `configChanges`, so nothing would otherwise push an update.
     */
    private val displayRotationProvider: () -> Int = { 0 },
) : GLSurfaceView.Renderer {

    /** Set before the GL thread starts, or swapped in when the user picks a video. */
    val frameSource = AtomicReference<FrameSource?>(null)

    /** Display rotation the frame source was last told about. See [refreshDisplayGeometryIfTurned]. */
    private var appliedRotation = -1

    private val atlas = GlyphAtlas()
    private val accumulator = CanvasAccumulator(spec)
    private val background = CameraBackgroundRenderer()
    private var overlayRenderer: OverlayRenderer? = null

    private val wallTracker = WallTracker()

    /**
     * Built lazily rather than in [onSurfaceCreated], because none of it needs a GL
     * context -- the glyph templates are rasterised with the platform font engine into
     * plain bitmaps -- and because the activity can legitimately call [restartScan]
     * before the surface exists. GLSurfaceView runs `queueEvent` work on the GL thread
     * independently of surface creation, so a `lateinit` here is a crash waiting for a
     * cold start to happen.
     */
    private val classifier by lazy { TemplateGlyphClassifier(atlas.templates) }

    /**
     * The four gems the user is hunting for, in Gems mode.
     *
     * Owned here rather than by the adapter because both threads need it: the UI
     * writes it as colours are tapped and the solver thread reads it. See [GemTargets]
     * for why that handoff is an atomic swap rather than a lock.
     */
    val gemTargets = GemTargets()

    /**
     * Walks the camera's exposure down until the wall stops being blown out.
     *
     * Lives here because it is the only place that can see both halves of the loop: the
     * solver's measurement of how much colour survived, and the frame source's dial.
     */
    val autoExposure = AutoExposure()

    /** Set once the LED-wall camera preset has been applied, so it happens once. */
    private var ledPresetApplied = false

    /** Exposure changes made this session, for the HUD and the heartbeat. */
    private var exposureChanges = 0

    /**
     * Reads gems straight off the camera frame, with no wall, canvas or pose.
     *
     * The whole AR pipeline below is bypassed when this is in play. Not a shortcut:
     * matching a gem depends on that gem alone, so the geometry the rest of the app
     * exists to recover buys nothing here -- and the camera that can deliver a readable
     * frame is the one ARCore is not holding. See docs/CAMERA_CONTROL.md.
     */
    private val gemScanner = GemScanner(gemTargets)

    /**
     * Reads the terminal wall straight off the camera frame, on the same terms.
     *
     * Gems bypasses the AR pipeline because ARCore will not give it an exposure it can
     * read at. Terminal bypasses it for a different reason and the code path is the
     * same: the wall is *played* while it is being read -- the lowest number is hit and
     * its display clears every few seconds -- so a mosaic accumulated over a pan would
     * hold numbers that were on the wall at different times. There is nothing worth
     * accumulating. See docs/TERMINAL_PUZZLE.md.
     */
    private val terminalScanner = TerminalScanner()

    /** Set when the active source is the pose-free camera, i.e. Gems or Terminal. */
    private var liveSource: Camera2FrameSource? = null

    /**
     * Which pose-free mode the live camera is being driven for.
     *
     * Set from the mode menu rather than inferred from the source, because the source
     * is the same `Camera2FrameSource` either way and only the user's choice separates
     * them.
     */
    @Volatile
    private var liveMode: LiveMode = LiveMode.NONE

    @Volatile
    private var liveResult: GemScanner.Result = GemScanner.Result.EMPTY

    @Volatile
    private var liveTerminalResult: TerminalScanner.Result = TerminalScanner.Result.EMPTY

    private var liveScanMillis = 0f
    private var liveMeanLuma = 0

    /**
     * Writes out what the gem scanner saw, when asked to.
     *
     * Created on first use rather than at construction because it needs a directory and
     * the pipeline is deliberately free of Android context. Null until a capture has
     * been asked for, which is also the off switch: the live path pays nothing for this
     * existing.
     */
    @Volatile
    private var gemRecorder: GemRecorder? = null

    private val registry: PuzzleRegistry by lazy {
        PuzzleRegistry(
            listOf(
                SudokuAdapter(classifier),
                NonogramAdapter(classifier),
                BombAdapter(),
                GemAdapter(),
                TerminalAdapter(),
            )
        )
    }

    private val engine: PuzzleEngine by lazy { PuzzleEngine(spec, registry) }

    /** True once the GL objects exist. Guards the ones that cannot be touched before. */
    @Volatile
    private var glReady = false

    /**
     * Debug request to assert a flat wall instead of fitting one, in metres ahead of the
     * camera. Zero means fit normally.
     */
    @Volatile
    private var forceFlatWallDistance = 0f

    @Volatile
    private var forceFlatWallPending = false

    /** Set by the debug control; serviced on the GL thread, which owns the mirror. */
    @Volatile
    private var dumpRequest: File? = null

    private val coverage = CoverageMap(spec, CanvasAccumulator.COVERAGE_STRIDE)
    private val coverageBytes = ByteArray(coverage.cols * coverage.rows)

    /** CPU mirror of the canvas, filled tile by tile from the GPU. */
    private val mirror = GrayImage(spec.widthTexels, spec.heightTexels)
    private val snapshot = GrayImage(spec.widthTexels, spec.heightTexels)
    private val tileBytes = ByteArray(CanvasAccumulator.TILE_SIZE * CanvasAccumulator.TILE_SIZE)

    /**
     * Chroma mirror, allocated only once a puzzle asks for colour.
     *
     * Two bytes a texel over a 4096 canvas is 32 MB, and exactly one puzzle type needs
     * it, so it stays null until something does. `chromaWanted` is set from the GL
     * thread once an adapter that requires colour is in play.
     */
    private var chromaMirror: ChromaImage? = null

    /**
     * Set when the solver has stopped because it ran out of memory, cleared by
     * [restartScan].
     *
     * A latch rather than a plain log line, because the failure it guards is not
     * transient and retrying it is actively harmful. The detector's working set is sized
     * by how much wall has been scanned, so the retry 250 ms later asks the same heap for
     * the same allocation and fails identically -- and a few hundred of those a minute is
     * enough GC thrash to walk the process up to a low-memory kill. Stopping and saying
     * why beats looping silently.
     */
    @Volatile
    private var solverFailure: String? = null
    private var chromaSnapshot: ChromaImage? = null
    private val chromaTileBytes =
        ByteArray(CanvasAccumulator.TILE_SIZE * CanvasAccumulator.TILE_SIZE * 2)
    private var chromaWanted = false
    private var chromaTilesCollected = 0

    private val tilesX = spec.widthTexels / CanvasAccumulator.TILE_SIZE
    private val tilesY = spec.heightTexels / CanvasAccumulator.TILE_SIZE
    private val tileDirty = BooleanArray(tilesX * tilesY)
    private var tileCursor = 0

    private var cameraTextureId = 0
    private var textureCreated = false

    /** The source the camera texture is currently bound to, so swaps re-attach. */
    private var attachedSource: FrameSource? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val viewProjection = FloatArray(16)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)

    private var lastSurface: WallSurface? = null
    private var overlayGeneration = 0
    private var builtOverlayGeneration = -1

    private var frameCounter = 0L

    /**
     * When the last heartbeat went out, and the frame count at that moment.
     *
     * The heartbeat used to fire every 120 frames, which is a bug of exactly the kind it
     * exists to catch: a log keyed to the frame loop goes quiet when the frame loop does,
     * so the run that most needs a line in the log is the one that produces none. On
     * device this read as total silence from the moment Gems became active -- no
     * heartbeat, no error, nothing -- which says only that something is wrong and not
     * one word about what.
     *
     * On a clock instead, and emitted from the no-frame path too, the same failure prints
     * `fps=0.0 frame=none` every two seconds and diagnoses itself.
     */
    private var lastHeartbeatNanos = 0L
    private var framesAtLastHeartbeat = 0L
    private var accumulatedFrames = 0L
    private var tilesCollected = 0L

    /**
     * Brightest byte ever read back from the canvas.
     *
     * Distinguishes "tiles arrive but are black" from "tiles arrive somewhere useless" --
     * a blank mirror has both explanations and they need opposite fixes.
     */
    private var brightestTileByte = 0

    // --- Solver thread ---------------------------------------------------

    private val solverBusy = AtomicBoolean(false)
    private val solverThread = SolverThread()

    /** See [onSurfaceCreated]: the GL context, and so that callback, can recur. */
    private var solverThreadStarted = false

    /** Latest state, published for the UI to observe. */
    val state = AtomicReference(UiState())

    /**
     * One entry in the game-mode menu.
     *
     * Built from the registry rather than listed in the UI, so a new solver appears in
     * the menu by being registered and nothing else. That is the whole point of the
     * indirection -- the alternative is a hardcoded list that silently goes stale.
     */
    /** Which pose-free live mode the camera is being driven for, if any. */
    private enum class LiveMode { NONE, GEMS, TERMINAL }

    /**
     * One display to outline, in viewport pixels.
     *
     * A rectangle rather than a ring, unlike Gems, because the thing being pointed at is
     * a rectangle: a box that traces the display reads as "this panel" at a glance,
     * where a circle round it would have to be big enough to swallow its neighbours.
     * [rank] is 0 for the lowest number on the wall and 1 for the second lowest.
     */
    data class TerminalHighlight(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val rank: Int,
        val label: String,
    )

    /** One gem to ring, in viewport pixels. [slot] is the 1-based target it matched. */
    data class GemHighlight(
        val x: Float,
        val y: Float,
        val radius: Float,
        val slot: Int,
    )

    data class PuzzleMode(
        val id: String,
        val displayName: String,
        val needsColour: Boolean,
        /**
         * Whether choosing this mode takes ARCore out of the picture.
         *
         * Worth saying in the menu rather than leaving to be discovered: picking one of
         * these swaps the frame source, so recording, replay and the wall readout all
         * disappear from the HUD at the same moment. That is a surprising amount of the
         * app to lose without being told why.
         */
        val needsLiveCamera: Boolean,
    )

    /** Every puzzle type this build can solve. Fixed for the life of the pipeline. */
    val puzzleModes: List<PuzzleMode> by lazy {
        registry.all().map {
            PuzzleMode(
                it.id,
                it.displayName,
                it.requiresColour,
                it.id == GemAdapter.ID || it.id == TerminalAdapter.ID,
            )
        }
    }

    data class UiState(
        /** Mode the user picked, or null when identification is automatic. */
        val pinnedPuzzleId: String? = null,
        /** Mode actually in use, which before identification settles may be neither. */
        val activePuzzleId: String? = null,
        val wallDescription: String = "looking for the wall",
        val wallForced: Boolean = false,
        val wallIssue: String? = null,
        val wallConverged: Boolean = false,
        val coverageFraction: Float = 0f,
        val cellsRead: Int = 0,
        val cellCount: Int = 0,
        val gridSummary: String? = null,
        val puzzleName: String? = null,
        val status: String = "point the camera at the puzzle",
        val solved: Boolean = false,
        val solvedEarly: Boolean = false,
        val frameMillis: Float = 0f,
        val solveMillis: Float = 0f,
        val accumulatedFrames: Long = 0,
        val sourceKind: FrameSource.Kind? = null,
        val replayFinished: Boolean = false,
        /**
         * The solution's own one-line summary, when it has one.
         *
         * Gems needs this: its answer is a live count of matches rather than a board,
         * and "solved" says nothing a user wants to know. Empty for every other mode,
         * which keeps the banner's existing wording untouched.
         */
        val solutionLabel: String = "",
        /** The adapter's tally of its last read pass; shown in the debug panel. */
        val readStats: String? = null,
        /** What the camera was asked for, or null when this source has no camera. */
        val cameraRequest: String? = null,
        /** What the frame metadata says the camera actually did. */
        val cameraActual: String? = null,
        /** How the camera itself is doing: opening, running, or why not. */
        val cameraStatus: String? = null,
        /** Whether this source offers any camera control at all. */
        val cameraControllable: Boolean = false,
        /** What the device says the camera can do, for the HUD. */
        val cameraCapabilities: String? = null,
        /**
         * False when the camera is measurably not doing what it was asked. See
         * [com.puzzlesolver.app.frame.CameraTuning.honoured].
         */
        val cameraHonoured: Boolean? = null,
        /**
         * Gems to ring on screen, in viewport pixels.
         *
         * Screen space rather than wall space because in live Gems there is no wall
         * space. The highlight tracks the gem in the image, which for a puzzle whose
         * answer is "that one, there" is the same thing and a great deal less machinery.
         */
        val gemOverlay: List<GemHighlight> = emptyList(),
        /** True while the pose-free camera is driving Gems. */
        val liveGems: Boolean = false,
        /** True while the pose-free camera is driving Terminal. */
        val liveTerminal: Boolean = false,
        /**
         * Displays to outline on screen, in viewport pixels.
         *
         * Screen space for the same reason the gem rings are: there is no wall space in
         * a mode that never fits a wall. The display is found in the image and the
         * rectangle is drawn where the image says it is, which for a puzzle whose answer
         * is "that one, next" is the same answer with far less machinery.
         */
        val terminalOverlay: List<TerminalHighlight> = emptyList(),
        /**
         * False while the terminal reading has yet to be confirmed twice running. On
         * screen because the rectangles are stale rather than absent while it is false,
         * and a user about to watch a ball fire deserves to know which.
         */
        val terminalSettled: Boolean = false,
        /**
         * Frames left in an armed gem capture, zero when idle.
         *
         * On screen because a capture is the one debug action taken *while* pointing the
         * phone at the wall, so the user has to be able to see that it is running and
         * when to stop panning -- a burst that quietly recorded the ceiling is worse
         * than one that was never started.
         */
        val gemCaptureRemaining: Int = 0,
        /** What the last gem capture wrote, so the room gets a confirmation. */
        val gemCaptureMessage: String? = null,
        /** One line on what the auto-exposure loop is doing. */
        val autoExposure: String? = null,
        /**
         * Fraction of the puzzle the active adapter found blown out, or -1 when it has
         * no opinion. Surfaced because on these walls it is the single number that
         * explains a scan going nowhere.
         */
        val exposureHint: Float = -1f,
        val cameraManual: Boolean = false,
        val aeLocked: Boolean = false,
        val awbLocked: Boolean = false,
    ) {
        /**
         * Whether the gem target controls belong on screen.
         *
         * Either the user picked Gems or identification landed on it. Both count,
         * because the controls are how the mode is used at all -- withholding them
         * until the user also pins a mode the app already worked out for itself would
         * be a puzzle of its own.
         */
        val isGemsMode: Boolean
            get() = pinnedPuzzleId == GemAdapter.ID ||
                (pinnedPuzzleId == null && activePuzzleId == GemAdapter.ID)

        /** Whether the terminal wall is the mode in play, pinned or identified. */
        val isTerminalMode: Boolean
            get() = pinnedPuzzleId == TerminalAdapter.ID ||
                (pinnedPuzzleId == null && activePuzzleId == TerminalAdapter.ID)

        /**
         * Whether the puzzle in play is one of the three walls that light themselves.
         *
         * Those are the only modes where the camera dial is part of using the app
         * rather than a debug affordance, so it is the condition for putting the
         * exposure controls on screen without being asked. Terminal is in the list even
         * though nothing adjusts its exposure automatically -- the panels are read from
         * luma and the phone's own metering handles them -- because it is still a lit
         * wall in a dark room, and a user who does find it blown out should have the dial
         * in front of them rather than behind a debug toggle.
         */
        val isSelfLitWallMode: Boolean
            get() {
                val id = pinnedPuzzleId ?: activePuzzleId
                return id == GemAdapter.ID || id == BombAdapter.ID || id == TerminalAdapter.ID
            }

        /**
         * Whether the AR pipeline is out of the picture entirely.
         *
         * The two live modes have no wall fit, no canvas, no coverage and no recorded
         * geometry, so every control and readout that describes one of those has nothing
         * to say. This is the condition for leaving them off screen, and it is a
         * property of the *pipeline* rather than of either puzzle -- which is why it is
         * asked as one question and not as two.
         */
        val livePoseFree: Boolean get() = liveGems || liveTerminal
    }

    // --- GLSurfaceView.Renderer ------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        atlas.initialize()
        accumulator.initialize()
        background.initialize()
        overlayRenderer = OverlayRenderer(spec, atlas).also { it.initialize() }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cameraTextureId = ids[0]
        GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        textureCreated = true

        // Any source present at context creation is attached by the swap check in
        // onDrawFrame, so there is nothing to do for it here.
        attachedSource = null
        glReady = true
        // Guarded, because this is not a once-per-process callback. The view is asked to
        // preserve the EGL context across a pause, but that is best-effort and documented
        // as such: the context can still be lost, and every renderer callback including
        // this one then runs again against a fresh one. Starting an already-started
        // thread throws IllegalThreadStateException on the GL thread, which takes the
        // app with it -- a crash that would only ever appear after a backgrounding, which
        // is the hardest kind to reproduce deliberately.
        if (!solverThreadStarted) {
            solverThreadStarted = true
            solverThread.start()
        }
        GlUtil.checkError("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        applyDisplayGeometry(frameSource.get())
    }

    /**
     * Hands the viewport to the frame source. Called on surface change and on every
     * source swap, since a newly created ARCore session starts with no geometry at all.
     */
    /**
     * Returns the viewport to the on-screen surface.
     *
     * Kept here rather than inside the renderers because only the pipeline knows the
     * surface dimensions; the accumulator legitimately owns its own viewport while it is
     * rendering into an offscreen target, it just must not be the last one to set it.
     */
    /**
     * Writes the observed part of the CPU canvas mirror out as a PNG.
     *
     * The single most useful diagnostic available, and it should have been here sooner:
     * every stage after the mosaic consumes this image, so when detection misbehaves the
     * first question is whether the image is sharp, skewed, or nonsense -- and no amount
     * of scalar telemetry answers that. Looking at it beats inferring it.
     */
    private fun writeMirrorDump(file: File) {
        var minCx = Int.MAX_VALUE
        var minCy = Int.MAX_VALUE
        var maxCx = -1
        var maxCy = -1
        for (cy in 0 until coverage.rows) {
            val row = cy * coverage.cols
            for (cx in 0 until coverage.cols) {
                if (coverage.confidence[row + cx] > CoverageMap.MIN_USEFUL_CONFIDENCE) {
                    if (cx < minCx) minCx = cx
                    if (cx > maxCx) maxCx = cx
                    if (cy < minCy) minCy = cy
                    if (cy > maxCy) maxCy = cy
                }
            }
        }
        if (maxCx < 0) {
            Log.w(TAG, "canvas dump skipped: nothing observed")
            return
        }
        val stride = coverage.stride
        val x0 = minCx * stride
        val y0 = minCy * stride
        val w = ((maxCx + 1) * stride).coerceAtMost(mirror.width) - x0
        val h = ((maxCy + 1) * stride).coerceAtMost(mirror.height) - y0
        if (w <= 0 || h <= 0) return

        // Colour when the canvas is carrying it. For a wall of coloured buttons a
        // greyscale dump answers almost nothing -- every lit button clips to the same
        // white, so the one question worth asking of the image, "what colour did the
        // app actually see here", is exactly the one it cannot answer.
        val chroma = chromaMirror.takeIf { chromaWanted }
        val rgb = FloatArray(3)
        val view = CanvasView(mirror, chroma)

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            // The canvas is stored bottom-up because it is a GL render target, so flip
            // vertically on the way out. Otherwise every dump looks upside down and
            // invites exactly the wrong conclusion about the geometry.
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                view.rgbAt(x0 + x, y0 + y, rgb)
                pixels[dst + x] = (0xFF shl 24) or
                    (rgb[0].toInt() shl 16) or (rgb[1].toInt() shl 8) or rgb[2].toInt()
            }
        }
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // The metres-per-texel and origin are what make the dump replayable offline:
            // without them the image is a picture, with them it is a canvas the core
            // tests can be pointed straight at.
            Log.i(
                TAG,
                "canvas dump: ${w}x${h} at ($x0,$y0) colour=${chroma != null} " +
                    "metresPerTexel=${spec.metresPerTexel} -> ${file.absolutePath}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "canvas dump failed", e)
        } finally {
            bmp.recycle()
        }
    }

    private fun restoreScreenViewport() {
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
    }

    private fun applyDisplayGeometry(source: FrameSource?) {
        if (source == null || surfaceWidth <= 0 || surfaceHeight <= 0) return
        appliedRotation = displayRotationProvider()
        source.setDisplayGeometry(appliedRotation, surfaceWidth, surfaceHeight)
    }

    /**
     * Re-applies the geometry when the display has turned without resizing.
     *
     * [onSurfaceChanged] catches three of the four transitions, because portrait and
     * landscape are different sizes. It does not catch the other two: portrait to
     * reverse portrait, and landscape to reverse landscape, are both 1080x2376 to
     * 1080x2376. No resize, no callback, and the source keeps a rotation that is 180
     * degrees out -- an upside-down preview with the overlay rings upside down on top
     * of it, which is a hard thing to recognise as a rotation bug while standing at a
     * wall.
     *
     * Polled per frame rather than pushed from `onConfigurationChanged`, because the
     * geometry has to be handed over on the GL thread and the provider is a cheap read
     * of the display's current rotation.
     */
    private fun refreshDisplayGeometryIfTurned(source: FrameSource) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        if (displayRotationProvider() == appliedRotation) return
        applyDisplayGeometry(source)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStart = System.nanoTime()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val source = frameSource.get() ?: return
        if (!textureCreated) return

        // A source swapped in from the UI thread has not been given the camera texture
        // yet -- attachTexture must happen here, because the texture name belongs to
        // this GL context and a video source builds its SurfaceTexture from it.
        if (source !== attachedSource) {
            source.attachTexture(cameraTextureId)
            // A fresh ARCore session has no display geometry until it is told, and
            // without it every texture coordinate and projection matrix it returns is
            // wrong. Re-apply on every swap, not just on surface change.
            applyDisplayGeometry(source)
            attachedSource = source
            // A different camera is a different dial, and any exposure conclusion
            // reached about the old one is about a device that is no longer in play.
            autoExposure.reset()
            ledPresetApplied = false
            liveSource = source as? Camera2FrameSource
            liveResult = GemScanner.Result.EMPTY
            liveTerminalResult = TerminalScanner.Result.EMPTY
            solverThread.post { terminalScanner.reset() }
        }
        refreshDisplayGeometryIfTurned(source)

        // Any camera change the UI or the auto-tuner asked for is applied here, on the
        // frame loop, because ARCore forbids re-issuing a capture request while it is
        // active and the source has to pause it to do so. The mosaic goes with it:
        // texels accumulated at the old exposure would sit alongside texels at the new
        // one, and the seam between them is exactly the kind of colour edge the button
        // classifiers are built to believe.
        if (source.applyPendingTuning()) {
            exposureChanges++
            Log.i(TAG, "camera retuned; restarting the scan")
            restartScan()
        }

        // Both of these belong here rather than further down with the rest of the
        // solver handling, because everything below returns early until a wall has been
        // fitted -- and exposure is not something to sort out *after* the wall fit. In
        // a dark room it is part of what makes the wall fit possible, and a camera that
        // ignores the request should say so in the first second rather than never.
        source.tuning?.let { autoExposure.noteHonoured(it.honoured()) }
        maybeApplyLedWallPreset(source)

        val frame = source.nextFrame()
        if (frame == null) {
            if (source.isFinished) publish { it.copy(replayFinished = true) }
            // The shared camera opens asynchronously, so "no frame yet" is a normal
            // state that lasts a second or two at startup and after every retune.
            // Without this the HUD would sit on stale text through all of it and look
            // like a hang.
            publish {
                it.copy(
                    // Only while nothing has ever arrived. Once frames are flowing, a
                    // poll that finds none is the normal case -- the camera runs slower
                    // than the render loop -- and overwriting the real status with the
                    // camera's made the HUD flicker between the two.
                    status = if (it.liveGems) it.status else source.cameraStatus ?: it.status,
                    cameraStatus = source.cameraStatus,
                    cameraControllable = source.tuning?.capabilities?.available == true,
                    cameraRequest = source.tuning?.describeRequest(),
                    cameraActual = source.tuning?.reported?.describe(),
                )
            }
            // Reported, not skipped. A source that has stopped delivering is the single
            // most important thing the log can say, and it is the one state the old
            // frame-counted heartbeat could never reach.
            heartbeat(null, null, frameStart)
            return
        }
        frameCounter++

        background.draw(frame.textureId, frame.textureTransform)

        // The pose-free modes end here: draw the camera, scan it, publish. No wall fit,
        // no mosaic, no solver, and nothing below this point runs.
        liveSource?.let {
            maybeDispatchLiveScan()
            if (liveMode == LiveMode.TERMINAL) publishLiveTerminal(it, frameStart)
            else publishLive(it, frameStart)
            heartbeat(frame, null, frameStart)
            return
        }

        // 1. Wall. Nothing else can happen until we know what we are projecting onto.
        if (forceFlatWallPending) {
            val distance = forceFlatWallDistance
            if (distance <= 0f) {
                wallTracker.forcePlanarWall(null, 0f)
                forceFlatWallPending = false
                restartScan()
            } else if (frame.pose != null) {
                // Needs a pose to know which way the camera is facing, so this retries
                // each frame until tracking comes up rather than failing silently.
                if (wallTracker.forcePlanarWall(frame.pose, distance)) {
                    forceFlatWallPending = false
                    restartScan()
                }
            }
        }
        val surfaceChanged = wallTracker.update(frame)
        val surface = wallTracker.surface
        if (surface != null && surfaceChanged && lastSurface != null) {
            // The wall moved under the mosaic. Everything accumulated was warped with
            // the old surface, so it is no longer consistent -- start over rather than
            // blend two geometries and get a doubled image.
            Log.i(TAG, "surface changed materially, resetting canvas")
            accumulator.clear()
            coverage.clear()
            mirror.fill(0)
            tileDirty.fill(false)
            engine.reset()
            accumulatedFrames = 0
        }
        lastSurface = surface

        if (surface == null || !wallTracker.isConverged) {
            publishTrackingAndBeat(frame, frameStart)
            return
        }

        // 2. Accumulate, but only from frames whose pose we trust.
        if (frame.trackingState == TrackingState.TRACKING && frame.pose != null) {
            frame.pose.let { pose ->
                buildMatrices(frame, pose)
                accumulator.accumulate(frame, surface, projection, view)
                accumulator.requestCoverage()
                accumulatedFrames++
            }
        }

        // 3. Drain readbacks. Both are non-blocking; on most frames neither is ready.
        drainCoverage()
        streamTiles()

        // The offscreen passes above retarget the viewport to the canvas (4096 square)
        // and the coverage buffer (256 square). Put it back before anything draws to the
        // screen again, including the next frame's camera background -- otherwise the
        // preview is squeezed into a small corner and the rest of the display stays
        // black.
        restoreScreenViewport()

        dumpRequest?.let {
            dumpRequest = null
            writeMirrorDump(it)
        }

        // 4. Hand a snapshot to the solver thread when it is free.
        maybeDispatchSolve()

        // 5. Overlay.
        val engineState = solverThread.latest
        // Turn colour on the moment something asks for it. Costs a 32 MB mirror and
        // triples tile bandwidth, so it stays off until a puzzle that needs it appears.
        if (engineState?.colourRequested == true) chromaWanted = true
        if (engineState != null && overlayGeneration != builtOverlayGeneration) {
            val grid = engineState.grid
            if (grid != null && engineState.outcome is SolveOutcome.Solved) {
                overlayRenderer?.build(engineState.overlay, grid, surface)
            } else {
                overlayRenderer?.clear()
            }
            builtOverlayGeneration = overlayGeneration
        }
        overlayRenderer?.draw(viewProjection)

        publishFull(frame, engineState, frameStart)
        heartbeat(frame, engineState, frameStart)
    }

    /**
     * Drops the exposure for a gem wall, once.
     *
     * Gems, and only Gems. Mines wants the opposite: its classifier reads the *glow
     * around* a button that has clipped its own face to white, and it was verified on
     * device at the camera's own exposure, so darkening for it would take away the very
     * thing it reads. The dials are on screen for both walls; this is only about what
     * happens without being asked.
     *
     * Keyed off the mode the user pinned as well as the one identification settled on,
     * because pinning is a decision made before any of the evidence arrives and it
     * should not have to wait for the evidence.
     */
    private fun maybeApplyLedWallPreset(source: FrameSource) {
        if (ledPresetApplied) return
        val pinned = state.get().pinnedPuzzleId
        val active = solverThread.latest?.adapterId
        if (pinned != GemAdapter.ID && active != GemAdapter.ID) return
        val tuning = source.tuning ?: return
        // Not until the camera has said what it can do. Applying the preset against
        // default capabilities silently downgrades it to the no-manual-sensor fallback,
        // which is how a phone that can hold 1/250 s ended up merely asking for -3 EV.
        if (!tuning.capabilities.available) return
        ledPresetApplied = true
        if (tuning.applyLedWallPreset()) {
            // The preset is a guess -- a good one, but several stops in one move.
            // Telling the loop it happened is what lets it climb back out if the guess
            // was too dark for this room.
            autoExposure.noteExternalDarkening()
            Log.i(TAG, "gems active; applying the LED-wall camera preset")
        }
    }

    /**
     * Periodic one-line dump of everything that matters, to logcat.
     *
     * Exists because the on-screen HUD is unreadable while you are holding the phone at
     * arm's length panning a wall, and because Compose text does not show up in a
     * `uiautomator` dump. This is the view a second person -- or a log file pulled after
     * the fact -- gets of what the pipeline was actually doing:
     *
     *   adb logcat -s ScanPipeline:I
     */
    private fun heartbeat(frame: FrameData?, engineState: PuzzleEngine.State?, frameStart: Long) {
        val now = System.nanoTime()
        if (now - lastHeartbeatNanos < HEARTBEAT_INTERVAL_NANOS) return
        // Measured over the gap actually elapsed rather than the nominal one, so a
        // heartbeat delayed by a slow frame reports the rate it really saw.
        val elapsed = (now - lastHeartbeatNanos) / 1e9f
        val fps = if (lastHeartbeatNanos == 0L) 0f else (frameCounter - framesAtLastHeartbeat) / elapsed
        lastHeartbeatNanos = now
        framesAtLastHeartbeat = frameCounter
        val coveragePercent = 100f * coverage.observedCells /
            (coverage.cols * coverage.rows).coerceAtLeast(1)
        // Live Gems arrives here with no engine state at all -- no wall, no grid, no
        // adapter -- so two thirds of the line below is dashes and nothing the mode
        // actually does appears in it. This is that missing half. Without a capture it
        // is the only record of a gem run that survives leaving the room, and the split
        // between blobs, lit and readable is what says which stage gave up.
        val gems = liveSource?.takeIf { liveMode == LiveMode.GEMS }?.let { source ->
            val r = liveResult
            " gems[blobs=${r.blobCount} lit=${r.gems.size} " +
                "readable=${r.gems.count { g -> g.pattern.isReadable }} " +
                "matched=${r.matchCount} pitch=${"%.1f".format(r.pitch)}px " +
                "luma=$liveMeanLuma lattice=${"%.2f".format(liveResult.latticeSpread)} " +
                "dropped=${source.droppedFrames} " +
                "targets=${gemTargets.active().size}/${gemTargets.size} " +
                "scan=${"%.1f".format(liveScanMillis)}ms " +
                "cap=${gemRecorder?.remaining ?: 0} status='${r.status}']"
        } ?: ""
        // The same argument as for the gems line above: in this mode two thirds of the
        // heartbeat is dashes, and without a line of its own a whole run leaves no
        // record of what the reader actually saw.
        val terminal = if (liveMode == LiveMode.TERMINAL) {
            val r = liveTerminalResult
            " term[displays=${r.displays.size} numbers=${r.remaining} unread=${r.unread} " +
                "settled=${r.settled} next=${r.lowest?.text ?: "-"} then=${r.second?.text ?: "-"} " +
                "luma=$liveMeanLuma dropped=${liveSource?.droppedFrames ?: 0} " +
                "scan=${"%.1f".format(liveScanMillis)}ms " +
                "prof='${terminalScanner.describeProfile()}' status='${r.status}']"
        } else {
            ""
        }
        Log.i(
            TAG,
            "f=$frameCounter fps=${"%.1f".format(fps)} " +
                "[${frameSource.get()?.diagnostics}] " +
                "track=${frame?.trackingState ?: "NO-FRAME"} pts=${frame?.pointCount ?: 0} " +
                "wall='${wallTracker.describe()}' locked=${wallTracker.isConverged} " +
                "pool=${wallTracker.pooledPoints} why='${wallTracker.convergenceIssue ?: "locked"}' " +
                "inliers=${wallTracker.lastFitInliers} rms=${"%.4f".format(wallTracker.lastFitRms)} " +
                "span=${"%.2f".format(wallTracker.observedSpanMetres)}m " +
                "cov=${"%.1f".format(coveragePercent)}% used=$accumulatedFrames " +
                "tiles=$tilesCollected chromaTiles=$chromaTilesCollected " +
                "ink=${mirrorInkPermille()}permille " +
                "tileMax=$brightestTileByte " +
                "gridWhy='${engineState?.gridRejection ?: "-"}' " +
                "grid=${engineState?.grid?.let { "${it.cols}x${it.rows}" } ?: "-"} " +
                "pitch=${engineState?.grid?.let { g -> fmtPitch(g.pitchX, g.pitchY) } ?: "-"} " +
                "gconf=${"%.2f".format(engineState?.grid?.confidence ?: 0f)} " +
                "puzzle=${engineState?.adapterId ?: "-"} " +
                "cells=${engineState?.cellsRead ?: 0}/${engineState?.cellCount ?: 0} " +
                "outcome=${engineState?.outcome?.javaClass?.simpleName ?: "-"} " +
                "read[${engineState?.readStats ?: "-"}] " +
                "cam[${tuning()?.describeRequest() ?: "none"} -> " +
                "${tuning()?.reported?.describe() ?: "-"}] " +
                "ae[${autoExposure.describe()} changes=$exposureChanges " +
                "washed=${"%.2f".format(liveResult.washedOut)}] " +
                "frame=${"%.1f".format((System.nanoTime() - frameStart) / 1e6f)}ms " +
                "solve=${"%.1f".format(engineState?.lastStepMillis ?: 0f)}ms" + gems + terminal,
        )
    }

    /**
     * Takes the frame's own matrices when the source supplied them, and synthesises
     * them from pose plus intrinsics otherwise.
     *
     * Reading them off [FrameData] rather than re-querying the session is not just
     * tidiness: `Session.update()` advances to the next frame, so a second call here
     * would consume a frame the pipeline never processes.
     */
    private fun buildMatrices(frame: FrameData, pose: com.puzzlesolver.core.math.Pose) {
        if (frame.projection.any { it != 0f } && frame.projection[15] != 1f) {
            // A real GL projection matrix has m[15] == 0; identity does not. That is
            // how we tell a supplied matrix from the video source's placeholder.
            System.arraycopy(frame.projection, 0, projection, 0, 16)
            System.arraycopy(frame.view, 0, view, 0, 16)
        } else {
            perspectiveFromIntrinsics(frame, projection)
            viewFromPose(pose, view)
        }
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    private fun perspectiveFromIntrinsics(frame: FrameData, out: FloatArray) {
        val i = frame.intrinsics
        val near = 0.05f
        val far = 60f
        java.util.Arrays.fill(out, 0f)
        out[0] = 2f * i.fx / i.width
        out[5] = 2f * i.fy / i.height
        out[8] = 1f - 2f * i.cx / i.width
        out[9] = 2f * i.cy / i.height - 1f
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -2f * far * near / (far - near)
    }

    private fun viewFromPose(pose: com.puzzlesolver.core.math.Pose, out: FloatArray) {
        val r = pose.right
        val u = pose.up
        val f = pose.forward
        val t = pose.translation
        // Inverse of the camera-to-world rigid transform, column-major for GL.
        out[0] = r.x; out[4] = r.y; out[8] = r.z; out[12] = -(r dot t)
        out[1] = u.x; out[5] = u.y; out[9] = u.z; out[13] = -(u dot t)
        out[2] = -f.x; out[6] = -f.y; out[10] = -f.z; out[14] = (f dot t)
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
    }

    private fun drainCoverage() {
        if (accumulator.collectCoverage(coverageBytes)) {
            val before = coverage.observedCells
            coverage.updateFromBytes(coverageBytes, frameCounter.toInt())
            if (coverage.observedCells != before) markDirtyTilesFromCoverage()
        }
    }

    /**
     * Flags the canvas tiles whose coverage improved, so tile streaming spends its
     * bandwidth where there is something new to read rather than cycling the whole
     * 4096-square canvas.
     */
    private fun markDirtyTilesFromCoverage() {
        val cellsPerTile = CanvasAccumulator.TILE_SIZE / coverage.stride
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                if (tileDirty[ty * tilesX + tx]) continue
                var any = false
                loop@ for (cy in 0 until cellsPerTile) {
                    val gy = ty * cellsPerTile + cy
                    if (gy >= coverage.rows) break
                    for (cx in 0 until cellsPerTile) {
                        val gx = tx * cellsPerTile + cx
                        if (gx >= coverage.cols) break
                        if (coverage.confidence[gy * coverage.cols + gx] > CoverageMap.MIN_USEFUL_CONFIDENCE) {
                            any = true
                            break@loop
                        }
                    }
                }
                if (any) tileDirty[ty * tilesX + tx] = true
            }
        }
    }

    /** Issues a few tile reads per frame and files whatever has landed. */
    private fun streamTiles() {
        var issued = 0
        var scanned = 0
        while (issued < TILES_PER_FRAME && scanned < tileDirty.size) {
            val idx = tileCursor
            tileCursor = (tileCursor + 1) % tileDirty.size
            scanned++
            if (!tileDirty[idx]) continue
            if (!accumulator.requestTile(idx % tilesX, idx / tilesX)) break
            if (chromaWanted) accumulator.requestChromaTile(idx % tilesX, idx / tilesX)
            tileDirty[idx] = false
            issued++
        }

        var collected = 0
        while (collected < TILES_PER_FRAME) {
            val origin = accumulator.collectTile(tileBytes) ?: break
            for (b in tileBytes) {
                val v = b.toInt() and 0xFF
                if (v > brightestTileByte) brightestTileByte = v
            }
            copyTileIntoMirror(origin[0], origin[1])
            tilesCollected++
            collected++
        }

        if (!chromaWanted) return
        val chroma = chromaMirror ?: ChromaImage(spec.widthTexels, spec.heightTexels)
            .also { chromaMirror = it }
        var chromaCollected = 0
        while (chromaCollected < TILES_PER_FRAME) {
            val origin = accumulator.collectChromaTile(chromaTileBytes) ?: break
            copyChromaTileIntoMirror(chroma, origin[0], origin[1])
            chromaTilesCollected++
            chromaCollected++
        }
    }

    /** Two bytes a texel, so the row arithmetic is doubled throughout. */
    private fun copyChromaTileIntoMirror(dst: ChromaImage, x0: Int, y0: Int) {
        val size = CanvasAccumulator.TILE_SIZE
        for (row in 0 until size) {
            val dstY = y0 + row
            if (dstY < 0 || dstY >= dst.height) continue
            val copyWidth = minOf(size, dst.width - x0)
            if (copyWidth <= 0) continue
            System.arraycopy(
                chromaTileBytes, row * size * 2,
                dst.data, (dstY * dst.width + x0) * 2,
                copyWidth * 2,
            )
        }
    }

    /**
     * Fraction of the CPU mirror carrying ink, sampled on a coarse lattice.
     *
     * The one number that separates "the detector is failing" from "the detector has
     * nothing to work with" -- a silently empty mirror looks identical to an aperiodic
     * one from the outside.
     */
    private fun mirrorInkPermille(): Int {
        var nonZero = 0
        var total = 0
        var y = 0
        while (y < mirror.height) {
            val row = y * mirror.width
            var x = 0
            while (x < mirror.width) {
                if (mirror.data[row + x] != 0.toByte()) nonZero++
                total++
                x += 16
            }
            y += 16
        }
        return if (total == 0) 0 else nonZero * 1000 / total
    }

    private fun fmtPitch(x: Float, y: Float): String =
        "${"%.1f".format(x)}/${"%.1f".format(y)}tx"

    private fun copyTileIntoMirror(x0: Int, y0: Int) {
        val size = CanvasAccumulator.TILE_SIZE
        for (row in 0 until size) {
            val dstY = y0 + row
            if (dstY >= mirror.height) break
            System.arraycopy(
                tileBytes, row * size,
                mirror.data, dstY * mirror.width + x0,
                minOf(size, mirror.width - x0),
            )
        }
    }

    /**
     * Asks for a gem scan, at a fraction of the frame rate.
     *
     * Ten hertz rather than every frame: a scan costs tens of milliseconds and a
     * highlight that trails the wall by a tenth of a second is invisible to someone
     * panning a camera. Every frame would buy nothing and lose the scanner's thread to
     * the frame clock.
     */
    private fun maybeDispatchLiveScan() {
        if (solverBusy.get()) return
        if (frameCounter % LIVE_SCAN_EVERY_N_FRAMES != 0L) return
        solverThread.enqueue(false)
    }

    private fun runLiveScan(source: Camera2FrameSource) {
        val view = source.acquireView() ?: return
        val started = System.nanoTime()
        try {
            val result = gemScanner.scan(view)
            liveResult = result
            liveMeanLuma = meanLuma(view.luma)
            liveScanMillis = (System.nanoTime() - started) / 1e6f
            // Inside the borrow, deliberately, and after the timing so the recorder's
            // own cost is not charged to the scan. This is the one place where the
            // pixels and the reading made of them are provably the same frame; recording
            // from anywhere else would put an image and a table of coordinates that
            // quietly disagree into the same report.
            gemRecorder?.offer(
                GemRecorder.Sample(
                    view = view,
                    result = result,
                    targets = gemTargets.all(),
                    cameraAsked = source.tuning.describeRequest(),
                    cameraActual = source.tuning.reported.describe(),
                    cameraHonoured = source.tuning.honoured(),
                    meanLuma = liveMeanLuma,
                    droppedFrames = source.droppedFrames,
                    scanMillis = liveScanMillis,
                    profile = gemScanner.describeProfile(),
                )
            )
        } finally {
            source.releaseView()
        }
    }

    /**
     * Average brightness of the frame the scanner actually got, on a coarse lattice.
     *
     * An independent read on the exposure, and the only one that cannot be fooled: the
     * capture metadata says what the camera claims, this says what arrived. When the two
     * disagree, the pixels are right.
     */
    private fun meanLuma(luma: GrayImage): Int {
        var total = 0L
        var count = 0
        var y = 0
        while (y < luma.height) {
            val row = y * luma.width
            var x = 0
            while (x < luma.width) {
                total += (luma.data[row + x].toInt() and 0xFF)
                count++
                x += 16
            }
            y += 16
        }
        return if (count == 0) 0 else (total / count).toInt()
    }

    /**
     * Reads the terminal wall from the newest camera frame.
     *
     * Deliberately does not feed [autoExposure]. That loop closes on how much *colour*
     * survived, which is the gem wall's problem and not this one: these panels are read
     * from luma alone, the phone's own metering handles them, and the reference clip was
     * shot at exactly that exposure. The dials are still on screen -- this is a self-lit
     * wall and a user who needs them should have them -- but nothing moves them unasked.
     */
    private fun runTerminalScan(source: Camera2FrameSource) {
        val view = source.acquireView() ?: return
        val started = System.nanoTime()
        try {
            liveTerminalResult = terminalScanner.scan(view.luma)
            liveMeanLuma = meanLuma(view.luma)
            liveScanMillis = (System.nanoTime() - started) / 1e6f
        } finally {
            source.releaseView()
        }
    }

    private fun publishLiveTerminal(source: Camera2FrameSource, frameStart: Long) {
        val result = liveTerminalResult
        val geometry = source.geometry
        val overlay = if (geometry == null) {
            emptyList()
        } else {
            val corner = FloatArray(2)
            result.displays.filter { it.rank >= 0 }.map { display ->
                // All four corners, not two. A quarter turn between the sensor and the
                // display swaps the axes, so the box that was top-left to bottom-right
                // in the image comes back the other way up on screen; taking the extremes
                // is right whichever way it landed and costs two more transforms.
                var left = Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                for (i in 0 until 4) {
                    val x = (display.box.x + if (i and 1 == 0) 0 else display.box.width).toFloat()
                    val y = (display.box.y + if (i and 2 == 0) 0 else display.box.height).toFloat()
                    geometry.imageToView(x, y, corner)
                    if (corner[0] < left) left = corner[0]
                    if (corner[0] > right) right = corner[0]
                    if (corner[1] < top) top = corner[1]
                    if (corner[1] > bottom) bottom = corner[1]
                }
                TerminalHighlight(left, top, right, bottom, display.rank, display.text)
            }
        }
        val scanMs = liveScanMillis
        publish {
            it.copy(
                activePuzzleId = TerminalAdapter.ID,
                liveGems = false,
                liveTerminal = true,
                gemOverlay = emptyList(),
                terminalOverlay = overlay,
                terminalSettled = result.settled,
                wallDescription = "live camera, no wall fitting",
                wallConverged = true,
                wallIssue = null,
                status = result.status,
                solved = false,
                solutionLabel = "",
                cellsRead = result.remaining,
                cellCount = result.displays.size,
                gridSummary = null,
                puzzleName = "Terminal",
                readStats = "scan " + scanMs.toInt() + "ms luma=" + liveMeanLuma +
                    " dropped=" + source.droppedFrames + "  ·  " + terminalScanner.describeProfile(),
                exposureHint = -1f,
                frameMillis = (System.nanoTime() - frameStart) / 1e6f,
                solveMillis = scanMs,
                sourceKind = source.kind,
                cameraRequest = source.tuning.describeRequest(),
                cameraActual = source.tuning.reported.describe(),
                cameraStatus = source.status,
                cameraControllable = source.tuning.capabilities.available,
                cameraCapabilities = source.tuning.capabilities.describe(),
                cameraHonoured = source.tuning.honoured(),
                autoExposure = autoExposure.describe(),
                cameraManual = source.tuning.settings.mode == CameraTuning.Mode.MANUAL,
                aeLocked = source.tuning.settings.lockAe,
                awbLocked = source.tuning.settings.lockAwb,
            )
        }
    }

    private fun publishLive(source: Camera2FrameSource, frameStart: Long) {
        val result = liveResult
        val geometry = source.geometry
        val overlay = if (geometry == null) {
            emptyList()
        } else {
            val point = FloatArray(2)
            result.matches.map { gem ->
                geometry.imageToView(gem.x, gem.y, point)
                GemHighlight(
                    x = point[0],
                    y = point[1],
                    // From the pitch rather than the gem's own lit radius: the ring drawn
                    // should be the size of a button, not the size of however much of it
                    // happens to be glowing at this exposure.
                    radius = geometry.scaleLength(result.pitch * GEM_RING_FRACTION),
                    slot = gem.matchedSlot,
                )
            }
        }
        val scanMs = liveScanMillis
        publish {
            it.copy(
                activePuzzleId = GemAdapter.ID,
                liveGems = true,
                liveTerminal = false,
                gemOverlay = overlay,
                terminalOverlay = emptyList(),
                gemCaptureRemaining = gemRecorder?.remaining ?: 0,
                gemCaptureMessage = gemRecorder?.lastMessage,
                wallDescription = "live camera, no wall fitting",
                wallConverged = true,
                wallIssue = null,
                status = result.status,
                solved = false,
                solutionLabel = "",
                cellsRead = result.gems.size,
                cellCount = result.gems.size,
                gridSummary = if (result.pitch > 0) "${result.pitch.toInt()} px pitch" else null,
                puzzleName = "Gems",
                readStats = "scan " + scanMs.toInt() + "ms luma=" + liveMeanLuma +
                    " dropped=" + source.droppedFrames + "  ·  " + gemScanner.describeProfile(),
                exposureHint = result.washedOut,
                frameMillis = (System.nanoTime() - frameStart) / 1e6f,
                solveMillis = scanMs,
                sourceKind = source.kind,
                cameraRequest = source.tuning.describeRequest(),
                cameraActual = source.tuning.reported.describe(),
                cameraStatus = source.status,
                cameraControllable = source.tuning.capabilities.available,
                cameraCapabilities = source.tuning.capabilities.describe(),
                cameraHonoured = source.tuning.honoured(),
                autoExposure = autoExposure.describe(),
                cameraManual = source.tuning.settings.mode == CameraTuning.Mode.MANUAL,
                aeLocked = source.tuning.settings.lockAe,
                awbLocked = source.tuning.settings.lockAwb,
            )
        }
    }

    private fun maybeDispatchSolve() {
        if (solverBusy.get()) return
        if (frameCounter % SOLVE_EVERY_N_FRAMES != 0L) return
        // Freeze the canvas for the solver. See the class comment for why this is a
        // copy rather than a lock.
        System.arraycopy(mirror.data, 0, snapshot.data, 0, mirror.data.size)
        chromaMirror?.let { live ->
            val frozen = chromaSnapshot ?: ChromaImage(spec.widthTexels, spec.heightTexels)
                .also { chromaSnapshot = it }
            System.arraycopy(live.data, 0, frozen.data, 0, live.data.size)
        }
        solverThread.enqueue(scanLooksComplete())
    }

    /**
     * Whether the user has covered the whole detected board.
     *
     * Only meaningful once a grid exists: before that "complete" is unanswerable, and
     * puzzles requiring a full scan must not be told the scan is done.
     */
    private fun scanLooksComplete(): Boolean {
        val grid = solverThread.latest?.grid ?: return false
        val corners = arrayOf(
            grid.cellToCanvas(0f, 0f),
            grid.cellToCanvas(grid.cols.toFloat(), 0f),
            grid.cellToCanvas(0f, grid.rows.toFloat()),
            grid.cellToCanvas(grid.cols.toFloat(), grid.rows.toFloat()),
        )
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (c in corners) {
            if (c[0] < minX) minX = c[0]
            if (c[0] > maxX) maxX = c[0]
            if (c[1] < minY) minY = c[1]
            if (c[1] > maxY) maxY = c[1]
        }
        return coverage.coverageOfRect(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt()) > 0.99f
    }

    // --- Publishing ------------------------------------------------------

    private inline fun publish(transform: (UiState) -> UiState) {
        state.set(transform(state.get()))
    }

    private fun publishTrackingAndBeat(frame: FrameData, frameStart: Long) {
        publishTracking(frame, frameStart)
        heartbeat(frame, solverThread.latest, frameStart)
    }

    private fun publishTracking(frame: FrameData, frameStart: Long) {
        publish {
            it.copy(
                activePuzzleId = solverThread.latest?.adapterId,
                wallDescription = wallTracker.describe(),
                wallForced = wallTracker.isForced,
                wallIssue = wallTracker.convergenceIssue,
                wallConverged = false,
                status = when (frame.trackingState) {
                    TrackingState.TRACKING -> "mapping the wall, keep panning slowly"
                    TrackingState.PAUSED -> "hold steadier, tracking is struggling"
                    TrackingState.UNAVAILABLE -> "no world tracking on this source"
                },
                frameMillis = (System.nanoTime() - frameStart) / 1e6f,
                sourceKind = frameSource.get()?.kind,
                accumulatedFrames = accumulatedFrames,
                cameraRequest = tuning()?.describeRequest(),
                cameraActual = tuning()?.reported?.describe(),
                cameraStatus = frameSource.get()?.cameraStatus,
                cameraControllable = tuning()?.capabilities?.available == true,
                cameraCapabilities = tuning()?.capabilities?.describe(),
                cameraHonoured = tuning()?.honoured(),
                autoExposure = autoExposure.describe(),
                cameraManual = tuning()?.settings?.mode == CameraTuning.Mode.MANUAL,
                aeLocked = tuning()?.settings?.lockAe == true,
                awbLocked = tuning()?.settings?.lockAwb == true,
            )
        }
    }

    private fun publishFull(frame: FrameData, engineState: PuzzleEngine.State?, frameStart: Long) {
        val solved = engineState?.outcome is SolveOutcome.Solved
        val grid = engineState?.grid
        publish {
            it.copy(
                activePuzzleId = engineState?.adapterId,
                wallDescription = wallTracker.describe(),
                wallForced = wallTracker.isForced,
                wallIssue = null,
                wallConverged = true,
                coverageFraction = coverage.observedCells.toFloat() /
                    (coverage.cols * coverage.rows).coerceAtLeast(1),
                cellsRead = engineState?.cellsRead ?: 0,
                cellCount = engineState?.cellCount ?: 0,
                gridSummary = grid?.let { g -> "${g.cols} x ${g.rows}" },
                puzzleName = engineState?.adapterName,
                status = describeOutcome(engineState),
                solved = solved,
                solvedEarly = engineState?.solvedEarly == true,
                solutionLabel = (engineState?.outcome as? SolveOutcome.Solved)?.solution?.label ?: "",
                readStats = engineState?.readStats,
                frameMillis = (System.nanoTime() - frameStart) / 1e6f,
                solveMillis = engineState?.lastStepMillis ?: 0f,
                accumulatedFrames = accumulatedFrames,
                sourceKind = frameSource.get()?.kind,
                cameraRequest = tuning()?.describeRequest(),
                cameraActual = tuning()?.reported?.describe(),
                cameraStatus = frameSource.get()?.cameraStatus,
                cameraControllable = tuning()?.capabilities?.available == true,
                cameraCapabilities = tuning()?.capabilities?.describe(),
                cameraHonoured = tuning()?.honoured(),
                autoExposure = autoExposure.describe(),
                cameraManual = tuning()?.settings?.mode == CameraTuning.Mode.MANUAL,
                aeLocked = tuning()?.settings?.lockAe == true,
                awbLocked = tuning()?.settings?.lockAwb == true,
            )
        }
    }

    // The elvis reads the volatile exactly once, which a test-then-dereference would not:
    // the debug rescan broadcast clears it from the main thread while the frame loop is
    // reading it here, and that window is a null dereference on the render thread.
    private fun describeOutcome(s: PuzzleEngine.State?): String = solverFailure ?: when {
        s == null -> "scanning"
        s.grid == null -> "looking for a grid"
        s.adapterName == null -> "grid found, identifying the puzzle"
        else -> when (val o = s.outcome) {
            is SolveOutcome.Solved ->
                if (s.solvedEarly) "solved from a partial scan" else "solved"
            is SolveOutcome.NeedMoreData -> when {
                s.policy == com.puzzlesolver.core.solve.SolutionPolicy.REQUIRES_FULL_SCAN ->
                    "needs the whole board: ${s.cellsRead}/${s.cellCount} cells read"
                o.remainingAmbiguity > 1 -> "still ambiguous, keep scanning"
                else -> "reading cells: ${s.cellsRead}/${s.cellCount}"
            }
            is SolveOutcome.Contradiction -> "re-reading suspect cells (${o.reason})"
            SolveOutcome.Pending -> "solving"
        }
    }

    // --- Lifecycle -------------------------------------------------------

    /**
     * Discards the mosaic and all derived state, keeping the wall fit.
     *
     * Safe to call before the GL context exists: the activity swaps in a frame source
     * from `onResume`, which can reach here through `queueEvent` ahead of
     * [onSurfaceCreated]. Everything is already empty at that point, so skipping the
     * GPU-side clear is not just safe but correct.
     */
    fun restartScan() {
        solverFailure = null
        if (glReady) accumulator.clear()
        coverage.clear()
        mirror.fill(0)
        tileDirty.fill(false)
        engine.reset()
        accumulatedFrames = 0
        tilesCollected = 0
        brightestTileByte = 0
        overlayGeneration++
    }

    /** Discards the wall fit as well. Use when the user has moved to a different wall. */
    fun restartEverything() {
        wallTracker.reset()
        lastSurface = null
        restartScan()
    }

    /**
     * Asserts a flat wall [distanceMetres] ahead of the camera, bypassing the fit, or
     * returns to real fitting when passed zero.
     */
    fun setForcedFlatWall(distanceMetres: Float) {
        forceFlatWallDistance = distanceMetres
        forceFlatWallPending = true
    }

    val isWallForced: Boolean get() = wallTracker.isForced

    fun requestMirrorDump(file: File) {
        dumpRequest = file
    }

    /**
     * Arms a burst capture of what the gem scanner is reading, into [directory].
     *
     * The live-Gems counterpart to [requestMirrorDump], and needed for the same reason
     * that one exists: no amount of scalar telemetry answers "what did the app actually
     * see". It is a separate entry point rather than a branch inside the dump because
     * the two write different things at different times -- the canvas dump is one image
     * of an accumulated mosaic, this is a series of raw frames each paired with the
     * reading made of it. See [GemRecorder].
     */
    fun startGemCapture(directory: File, frames: Int, intervalMillis: Long) {
        val recorder = gemRecorder?.takeIf { it.directory == directory }
            ?: GemRecorder(directory).also { gemRecorder = it }
        recorder.arm(frames, intervalMillis)
    }

    fun cancelGemCapture() {
        gemRecorder?.cancel()
    }

    fun setExpectedCells(cells: Int?) {
        solverThread.post { engine.setExpectedCells(cells) }
    }

    /**
     * Chooses the game mode, or hands the choice back to the evidence when [id] is null.
     *
     * Published immediately rather than waiting for the solver thread to acknowledge it:
     * the menu closes on the tap, and a checkmark that lags a frame behind the tap reads
     * as the button not having worked.
     */
    fun selectPuzzleMode(id: String?) {
        publish { it.copy(pinnedPuzzleId = id) }
        // Before the source swaps, so the first frame of the new mode is read by the
        // right reader. A ranking confirmed against the previous wall is worse than
        // none: it would put a green rectangle on a display that is not there.
        liveMode = when (id) {
            GemAdapter.ID -> LiveMode.GEMS
            TerminalAdapter.ID -> LiveMode.TERMINAL
            else -> LiveMode.NONE
        }
        liveTerminalResult = TerminalScanner.Result.EMPTY
        // Posted rather than called here: the scanner belongs to the solver thread, and
        // resetting it from under a scan in progress is exactly the kind of race that
        // shows up once a week and never in a test.
        solverThread.post {
            terminalScanner.reset()
            if (id == null) engine.autoDetectPuzzle() else engine.pinAdapter(id)
        }
    }

    /** The live camera's dials, or null when the current source has no camera. */
    fun tuning(): CameraTuning? = frameSource.get()?.tuning

    /**
     * Steps the exposure by hand, and stops the auto-tuner in the process.
     *
     * Taking over has to mean taking over: a loop that kept adjusting under a user who
     * has just told it what they want would be worse than no loop at all.
     */
    fun nudgeExposure(darker: Boolean): Boolean {
        val t = tuning() ?: return false
        autoExposure.enabled = false
        return if (darker) t.darker() else t.brighter()
    }

    fun setCameraMode(mode: CameraTuning.Mode) {
        tuning()?.update { it.copy(mode = mode) }
        autoExposure.enabled = false
    }

    fun setCameraLocks(lockAe: Boolean, lockAwb: Boolean) {
        tuning()?.update { it.copy(lockAe = lockAe, lockAwb = lockAwb) }
    }

    fun applyLedWallPreset() {
        tuning()?.applyLedWallPreset()
    }

    fun resetCamera() {
        tuning()?.reset()
        autoExposure.reset()
        autoExposure.enabled = true
    }

    fun setAutoExposureEnabled(enabled: Boolean) {
        autoExposure.enabled = enabled
        if (enabled) autoExposure.reset()
    }

    /**
     * Sets one of the four gem targets, or clears it when [pattern] is blank.
     *
     * The engine only re-solves when new cells arrive, so a target edit has to force
     * the issue: [PuzzleEngine.reconfigureSolver] marks every cell unread, which makes
     * the next pass produce a full delta and the matcher recompute. That is exactly
     * what it exists for -- a setting that changes the answer without changing what is
     * on the wall -- and it is far cheaper than it sounds, because the pixels are
     * already in the mirror and re-reading them costs one pass over the board.
     */
    fun setGemTarget(slot: Int, pattern: GemPattern) {
        gemTargets.set(slot, pattern)
        solverThread.post { engine.reconfigureSolver() }
    }

    fun clearGemTargets() {
        gemTargets.clearAll()
        solverThread.post { engine.reconfigureSolver() }
    }

    /**
     * Stops the solver thread. Called from the activity's `onDestroy`, i.e. the main
     * thread.
     *
     * Deliberately does *not* delete GL objects. There is no current EGL context on this
     * thread, so the deletes would either be silently dropped or crash depending on the
     * driver -- and they are unnecessary regardless, because tearing down the context
     * frees every texture, buffer and framebuffer that belonged to it. The per-renderer
     * `release()` methods exist for the case where we ever need to rebuild these objects
     * while a context is live.
     */
    fun release() {
        solverThread.quit()
    }

    /**
     * Runs [PuzzleEngine] off the frame clock.
     *
     * One thread, one queued request at a time. Queueing more would only mean solving
     * against canvases that a newer snapshot has already superseded.
     */
    private inner class SolverThread : Thread("puzzle-solver") {
        @Volatile var latest: PuzzleEngine.State? = null
            private set

        private val lock = Object()
        private var requested = false
        private var requestComplete = false
        private var running = true
        private val posted = ArrayDeque<() -> Unit>()

        fun enqueue(complete: Boolean) {
            synchronized(lock) {
                requested = true
                requestComplete = complete
                solverBusy.set(true)
                lock.notifyAll()
            }
        }

        fun post(action: () -> Unit) {
            synchronized(lock) {
                posted.addLast(action)
                lock.notifyAll()
            }
        }

        fun quit() {
            synchronized(lock) {
                running = false
                lock.notifyAll()
            }
        }

        override fun run() {
            while (true) {
                val complete = synchronized(lock) {
                    while (running && !requested && posted.isEmpty()) lock.wait()
                    if (!running) return
                    // A debug action throwing must not take the solver thread with it.
                    while (posted.isNotEmpty()) {
                        val action = posted.removeFirst()
                        try {
                            action.invoke()
                        } catch (e: Throwable) {
                            Log.e(TAG, "queued solver action failed", e)
                        }
                    }
                    val c = requestComplete
                    requested = false
                    c
                }
                try {
                    val live = liveSource
                    if (live != null && liveMode == LiveMode.TERMINAL) {
                        runTerminalScan(live)
                        continue
                    }
                    if (live != null) {
                        runLiveScan(live)
                        // The loop is fed from here rather than from the frame clock
                        // because the measurement only exists once per scan, and acting
                        // on the same one twice would step the exposure twice for one
                        // problem. Applying the change is the frame loop's job; this
                        // only moves the dial.
                        // Whether a wall was seen, as well as the hint. A negative hint
                        // alone cannot tell an exposure that lost the wall from a camera
                        // that is not pointed at one, and the loop must not undo the
                        // LED-wall preset for the second. A pitch is only reported once
                        // the blobs have been checked for being a lattice rather than a
                        // scattering, which is what makes this trustworthy where frame
                        // brightness was not.
                        tuning()?.let {
                            autoExposure.consider(
                                it,
                                liveResult.washedOut,
                                wallInView = liveResult.looksLikeAWall,
                            )
                        }
                        continue
                    }
                    if (solverFailure != null) continue
                    val view = CanvasView(snapshot, if (chromaWanted) chromaSnapshot else null)
                    val result = engine.step(view, coverage, complete)
                    val previous = latest
                    latest = result
                    if (previous?.outcome != result.outcome || previous.overlay != result.overlay) {
                        overlayGeneration++
                    }
                } catch (e: OutOfMemoryError) {
                    // Caught apart from everything else and treated as terminal for this
                    // scan. See [solverFailure] for why retrying is worse than stopping.
                    // Resetting the engine is what actually releases the buffers that
                    // could not be grown, so the process comes back down rather than
                    // sitting at the ceiling waiting for the next attempt.
                    solverFailure = "ran out of memory on a region this large -- rescan to continue"
                    Log.e(TAG, "solver ran out of memory; stopping until the next rescan", e)
                    try {
                        engine.reset()
                    } catch (_: Throwable) {
                        // Reset allocates nothing worth having a second failure over.
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "solver step failed", e)
                } finally {
                    solverBusy.set(false)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ScanPipeline"

        /**
         * Tiles streamed per frame. Eight 256-square tiles is 512 KB, which at 60 fps
         * refreshes the whole canvas roughly twice a second -- far faster than a user
         * can pan across it.
         */
        const val TILES_PER_FRAME = 8

        /** Solve at ~4 Hz on a 60 fps stream. Grids do not move. */
        const val SOLVE_EVERY_N_FRAMES = 15L

        /** Live gem scans at ~10 Hz on a 30 fps stream. Walls do not move that fast. */
        const val LIVE_SCAN_EVERY_N_FRAMES = 3L

        /**
         * Radius of the drawn highlight as a fraction of the button pitch. Half a pitch
         * would touch the neighbours; this sits just outside the lens.
         */
        const val GEM_RING_FRACTION = 0.30f

        /**
         * Two seconds of wall clock, not a frame count.
         *
         * Readable in a live logcat either way; the difference is that this one keeps
         * printing when the frames stop, which is the case worth having a log for.
         */
        const val HEARTBEAT_INTERVAL_NANOS = 2_000_000_000L
    }
}
