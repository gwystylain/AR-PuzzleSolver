package com.puzzlesolver.app.frame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.opengl.GLES11Ext
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.math.Intrinsics

/**
 * The camera, with nothing between the app and it.
 *
 * Exists because ARCore will not pass an exposure request through to the sensor — it
 * discards the app's capture request entirely while it is tracking, measured on 1.48 and
 * on the current 1.54, while the identical request through plain Camera2 is honoured to
 * the microsecond. See docs/CAMERA_CONTROL.md. On a wall of bright LEDs in a dark room
 * that is the difference between an image with the gem rings in it and an image without.
 *
 * The price is the camera pose, and with it the wall fit, the metric canvas and the
 * AR-anchored overlay. For Gems that turned out to be no price at all: whether a gem
 * matches depends on that gem alone, a highlight only has to land where the gem is on
 * screen right now, and the wall is rearranged between rounds so there was never
 * anything worth accumulating. Mines still runs on ARCore, where its classifier wants
 * the exposure the camera picks anyway.
 *
 * Two outputs, because the app needs the frame twice over: a `SurfaceTexture` so the GL
 * background can draw it without a copy, and an `ImageReader` so the gem scanner can read
 * pixels. Both come off the same capture request, so what the user sees and what the
 * scanner reads are the same frame.
 */
class Camera2FrameSource(
    private val context: Context,
    override val tuning: CameraTuning = CameraTuning(),
) : FrameSource {

    override val kind: FrameSource.Kind = FrameSource.Kind.LIVE

    private val manager = context.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var cameraId: String = ""

    // Volatile because they are written on the camera thread and read from the render
    // thread, which is where a retune is noticed. Without it the render thread can hold
    // a stale null for `session` indefinitely, and the symptom is subtle: the very first
    // capture request lands (it is issued from the camera thread) and every later change
    // silently does not.
    @Volatile
    private var device: CameraDevice? = null

    @Volatile
    private var session: CameraCaptureSession? = null

    @Volatile
    private var builder: CaptureRequest.Builder? = null

    /** Which exposure mode the current builder was made for; see [writeRequest]. */
    @Volatile
    private var builtForMode: CameraTuning.Mode? = null

    @Volatile
    private var reader: ImageReader? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var textureId = -1

    /** Buffer size the camera is delivering, and the sensor's mounting rotation. */
    private var captureSize = Size(0, 0)
    private var sensorOrientation = 90

    private var displayRotationDegrees = 0
    private var viewWidth = 0
    private var viewHeight = 0

    /**
     * Buffer pixels to view pixels: what the overlay uses to put a ring on a gem.
     *
     * [GemScanner] reads the YUV buffer exactly as it arrives and reports gems in its
     * coordinates, so this is the mapping that has to start from those.
     */
    @Volatile
    var geometry: PreviewGeometry? = null
        private set

    /**
     * The same mapping, but starting from whatever the *producer* hands the shader.
     *
     * Usually identical to [geometry], and separate because on some devices it is not.
     * `SurfaceTexture.getTransformMatrix` is documented as the map from the coordinates
     * you supply into the ones that actually sample the buffer, and it is where a
     * producer puts its crop and its row order. On this OnePlus it also transposes s and
     * t -- the camera applies the sensor's 90 degree mount itself -- and the result was
     * that the rotation got applied twice, once here and once there, and two turns in
     * opposite senses cancel: a 1920x1080 landscape buffer laid straight down a portrait
     * screen. That is what "the preview is sideways in portrait" was.
     *
     * The matrix is a fixed property of the producer, not of the display: measured at
     * all four display rotations on this device it never changes. So when it transposes,
     * the sensor mount is already accounted for and the only turn left to apply is the
     * display's.
     */
    @Volatile
    private var backgroundGeometry: PreviewGeometry? = null

    /** Whether the producer's transform transposes the axes. Null until a frame arrives. */
    private var producerRotates: Boolean? = null

    @Volatile
    private var wantRunning = false

    @Volatile
    private var opening = false

    @Volatile
    private var appliedGeneration = -1

    @Volatile
    private var frameAvailable = false

    @Volatile
    var status: String = "camera not started"
        private set

    override val cameraStatus: String?
        get() = if (frameIndex == 0L) {
            // Until a frame has come through, the status *is* the diagnosis. Which
            // precondition is missing matters and each one fails silently on its own, so
            // they go on screen rather than to a log this device drops under load.
            "waiting for frames: tex=${surfaceTexture != null} avail=$frameAvailable " +
                "geom=${geometry != null} session=${session != null} polls=$pollCount  ·  $status"
        } else {
            status
        }

    /**
     * Sensor mount, display, and the net rotation the preview is drawn with.
     *
     * All three, because only the last one matters and only the first one used to be
     * reported. `rot=90` never changed -- it is where the sensor is bolted in -- so a
     * heartbeat full of it looked like the rotation was being handled while the preview
     * was drawn sideways. `disp` is what turns as the phone turns and `net` is what
     * [PreviewGeometry] actually uses, so a preview that disagrees with the phone in
     * hand can be diagnosed from the log rather than from the room.
     */
    override val diagnostics: String
        get() = "cam2 ${captureSize.width}x${captureSize.height} " +
            "sensor=$sensorOrientation disp=$displayRotationDegrees " +
            "net=${(sensorOrientation - displayRotationDegrees + 360) % 360} '$status'"

    private var frameIndex = 0L
    private var pollCount = 0L

    @Volatile
    private var captureCount = 0L

    private val transformMatrix = FloatArray(16)
    private val texCoords = FloatArray(8)
    private var logComposite = false
    private val identityMatrix = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }
    private val emptyPoints = FloatArray(0)

    // --- CPU frames ------------------------------------------------------

    /**
     * Two buffers and a hand-off, rather than a queue.
     *
     * The scanner wants the newest frame, never a backlog: a gem highlighted where it was
     * four frames ago is worse than one highlighted a frame late. So a frame that arrives
     * while the previous one is still being read is simply dropped, and the buffer the
     * consumer is holding is never the one being written.
     */
    private class Buffer {
        var view: CanvasView? = null
        var width = 0
        var height = 0
    }

    private val bufferA = Buffer()
    private val bufferB = Buffer()
    private val lock = Object()
    private var ready: Buffer? = null
    private var consuming: Buffer? = null

    /** Frames the scanner never saw because it was still busy with an earlier one. */
    @Volatile
    var droppedFrames = 0L
        private set

    /**
     * Takes the newest CPU frame, or null when there is not a fresh one.
     *
     * The caller must call [releaseView] when finished, or the producer will assume the
     * buffer is still in use and keep dropping frames into the other one.
     */
    fun acquireView(): CanvasView? = synchronized(lock) {
        val b = ready ?: return null
        ready = null
        consuming = b
        b.view
    }

    fun releaseView() = synchronized(lock) { consuming = null }

    // --- Lifecycle -------------------------------------------------------

    override fun attachTexture(textureId: Int) {
        this.textureId = textureId
        // Created here and nowhere else. `SurfaceTexture(int)` binds to the GL texture in
        // the *calling* thread's EGL context, and this is the only thread that has one.
        // Built on the camera thread instead it constructs without complaint, streams
        // frames the producer is happy with, and then updateTexImage on the GL thread
        // quietly never delivers one -- a black preview and no error anywhere.
        if (surfaceTexture == null) {
            surfaceTexture = SurfaceTexture(textureId)
        }
        maybeOpen()
    }

    override fun setDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        displayRotationDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        viewWidth = widthPx
        viewHeight = heightPx
        rebuildGeometry()
    }

    /**
     * Says which way up the preview came out, after everything has been composed.
     *
     * The orientation is decided by five numbers, a corner order and a matrix from the
     * driver, each plausible on its own, and the only place the answer exists is in the
     * four finished texture coordinates. Two of those inputs cannot be seen from outside
     * the app and a screenshot does not capture a `GLSurfaceView`, so without this the
     * only instrument is a person holding the phone and saying it looks wrong.
     *
     * Reads the finished quad rather than re-deriving it: which axis of the buffer each
     * screen axis runs along, and whether that is the pairing the frame shape calls for.
     */
    private fun reportPreviewOrientation() {
        val screenXMovesS = kotlin.math.abs(texCoords[2] - texCoords[0]) >
            kotlin.math.abs(texCoords[3] - texCoords[1])
        val bufferLongIsWidth = captureSize.width >= captureSize.height
        val viewLongIsWidth = viewWidth >= viewHeight
        // The screen's long axis has to run along the buffer's long axis.
        val screenLongMovesS = if (viewLongIsWidth) screenXMovesS else !screenXMovesS
        val upright = screenLongMovesS == bufferLongIsWidth
        Log.i(
            TAG,
            "preview: view ${viewWidth}x$viewHeight buffer " +
                "${captureSize.width}x${captureSize.height} disp=$displayRotationDegrees  " +
                "screen-x runs along buffer " + (if (screenXMovesS) "width" else "height") +
                ", screen-y along buffer " + (if (screenXMovesS) "height" else "width") +
                "  => " + (if (upright) "UPRIGHT" else "SIDEWAYS"),
        )
    }

    private fun rebuildGeometry() {
        if (captureSize.width <= 0 || viewWidth <= 0) return
        val net = (sensorOrientation - displayRotationDegrees + 360) % 360
        val g = PreviewGeometry(
            imageWidth = captureSize.width,
            imageHeight = captureSize.height,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            rotationDegrees = net,
        )
        geometry = g
        backgroundGeometry = if (producerRotates == true) {
            // The producer has already turned the buffer upright, so the image the
            // shader is sampling is the transposed one and the only rotation left to
            // apply is the display's. Both halves matter: the dimensions swap because
            // the crop is computed against the image the producer presents, and the
            // angle drops to the display's alone because the sensor mount is spent.
            PreviewGeometry(
                imageWidth = captureSize.height,
                imageHeight = captureSize.width,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                rotationDegrees = (360 - displayRotationDegrees) % 360,
            )
        } else {
            g
        }
        logComposite = true
    }

    override fun resume() {
        wantRunning = true
        ensureThread()
        maybeOpen()
    }

    override fun pause() {
        wantRunning = false
        val h = handler
        if (h != null) h.post { closeCamera() } else closeCamera()
        status = "camera paused"
    }

    override fun close() {
        pause()
        thread?.quitSafely()
        thread = null
        handler = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
    }

    private fun ensureThread() {
        if (thread != null) return
        val t = HandlerThread("camera2").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    private fun maybeOpen() {
        if (!wantRunning || textureId < 0 || surfaceTexture == null) return
        if (device != null || opening) return
        val h = handler ?: return
        // Latched here, before the post, and not cleared until the device callback
        // lands. Two things race to start the camera -- the activity resuming and the GL
        // thread handing over its texture -- and `device` is not set until `onOpened`,
        // so a guard on that alone lets both through. The result is two open camera
        // devices and two capture sessions: the sensor follows one of them and this
        // class holds a reference to the other, so every later exposure change succeeds
        // and does nothing. Frames keep arriving throughout, which is what made it look
        // like the camera was ignoring the request.
        opening = true
        h.post { openLocked() }
    }

    @SuppressLint("MissingPermission")   // the activity gates every path here on CAMERA
    private fun openLocked() {
        if (device != null) {
            opening = false
            return
        }
        try {
            val id = pickCamera() ?: run {
                // Cleared on the way out, like every other path that leaves without an
                // open in flight. The latch exists to stop two opens racing, so leaving
                // it set after failing to start one does not prevent a race, it prevents
                // the camera ever being opened again -- and maybeOpen() returns silently,
                // so it looks like nothing is even trying.
                opening = false
                status = "no back camera"
                return
            }
            cameraId = id
            val characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            captureSize = pickSize(characteristics)
            tuning.capabilities = readCapabilities(characteristics)
            rebuildGeometry()
            Log.i(TAG, "camera $id: ${captureSize.width}x${captureSize.height}, " +
                "sensor $sensorOrientation deg, ${tuning.capabilities.describe()}")

            allocateBuffers()

            val r = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.YUV_420_888, IMAGE_BUFFERS,
            )
            r.setOnImageAvailableListener(::onImage, handler)
            reader = r

            val st = surfaceTexture ?: run {
                opening = false
                status = "no camera texture yet"
                return
            }
            st.setDefaultBufferSize(captureSize.width, captureSize.height)
            st.setOnFrameAvailableListener({ frameAvailable = true }, handler)
            previewSurface = Surface(st)

            status = "opening the camera"
            manager.openCamera(id, deviceCallback, handler)
        } catch (e: Exception) {
            opening = false
            status = "could not open the camera: ${e.message}"
            Log.e(TAG, "openCamera failed", e)
        }
    }

    private fun pickCamera(): String? =
        manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()

    /**
     * The largest YUV stream at or under [MAX_CAPTURE_PIXELS].
     *
     * Resolution is the scarce thing here: a gem's centre dot is a few pixels across even
     * at 1080p, and the ring constants were measured against 1080p footage of this exact
     * wall, so staying there means they transfer without reinterpretation. Larger would
     * cost conversion time for detail the rings do not have.
     */
    private fun pickSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(1920, 1080)
        return sizes
            .filter { it.width.toLong() * it.height <= MAX_CAPTURE_PIXELS }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    private fun readCapabilities(c: CameraCharacteristics): CameraTuning.Capabilities {
        val evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manual = caps?.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        } == true
        val stepsPerEv = evStep
            ?.takeIf { it.toDouble() > 0.0 }
            ?.let { Math.round(1.0 / it.toDouble()).toInt() }
            ?.coerceIn(1, 6)
            ?: 1
        return CameraTuning.Capabilities(
            available = true,
            manualSensor = manual && exposure != null && iso != null,
            minEvSteps = evRange?.lower ?: 0,
            maxEvSteps = evRange?.upper ?: 0,
            evStepsPerEv = stepsPerEv,
            minExposureNanos = exposure?.lower ?: 100_000L,
            maxExposureNanos = exposure?.upper ?: 100_000_000L,
            minIso = iso?.lower ?: 50,
            maxIso = iso?.upper ?: 3200,
        )
    }

    private fun allocateBuffers() {
        for (b in listOf(bufferA, bufferB)) {
            b.width = captureSize.width
            b.height = captureSize.height
            b.view = CanvasView(
                GrayImage(captureSize.width, captureSize.height),
                ChromaImage(captureSize.width, captureSize.height),
            )
        }
        synchronized(lock) {
            ready = null
            consuming = null
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (!wantRunning) {
                camera.close()
                return
            }
            device = camera
            configure(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            status = "camera disconnected"
            closeCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            status = "camera error $error"
            Log.e(TAG, "camera error $error")
            closeCamera()
        }
    }

    @Suppress("DEPRECATION")   // the SessionConfiguration overload is API 28+; minSdk is 26
    private fun configure(camera: CameraDevice) {
        val preview = previewSurface ?: return
        val r = reader ?: return
        try {
            // Recorded before the session is built, not in the callback that follows it.
            // The callback lands hundreds of milliseconds later, and stamping it then
            // marks whatever the settings had become in the meantime as already applied
            // -- so a change made during a rebuild is silently swallowed.
            appliedGeneration = tuning.generation
            builder = newBuilder() ?: return
            builtForMode = tuning.settings.mode
            camera.createCaptureSession(
                listOf(preview, r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        if (writeRequest()) {
                            status = "camera running (${tuning.describeRequest()})"
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        status = "the camera refused this combination of streams"
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            status = "could not configure the camera: ${e.message}"
            Log.e(TAG, "configure failed", e)
        }
    }

    /**
     * Issues the capture request.
     *
     * Unlike the shared-camera path this may be called whenever we like -- there is no
     * ARCore to interfere with, which is the entire point of this class. So a change of
     * exposure costs one repeating request and nothing else: no session pause, no
     * tracking interruption, and no reason to throw anything away.
     */
    private fun writeRequest(): Boolean {
        val s = session ?: return false
        val settings = tuning.settings
        val caps = tuning.capabilities

        // A fresh builder whenever the exposure mode changes, rather than overwriting
        // keys on the old one. A CaptureRequest.Builder keeps every key ever set on it,
        // and this HAL stayed in AE_MODE_OFF when handed AE_MODE_ON alongside a stale
        // SENSOR_EXPOSURE_TIME -- the read-back said ae:off while the request said on.
        // Clearing keys individually relies on set(key, null) behaviour that is not
        // guaranteed; starting again cannot go wrong.
        if (settings.mode != builtForMode) {
            builder = newBuilder() ?: return false
            builtForMode = settings.mode
        }
        val b = builder ?: return false

        if (settings.mode == CameraTuning.Mode.MANUAL && caps.manualSensor) {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureNanos)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(settings.exposureNanos, FRAME_DURATION))
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.evSteps)
            b.set(CaptureRequest.CONTROL_AE_LOCK, settings.lockAe)
        }
        b.set(CaptureRequest.CONTROL_AWB_LOCK, settings.lockAwb)
        // Focus on the wall rather than hunting. A dark room of bright point sources is
        // where continuous autofocus is least happy, and the subject is a flat wall at a
        // roughly constant distance for the whole session.
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        return try {
            // Stopped before being restarted. Submitting a new repeating request over a
            // running one is legal and is what the API documents, but on this HAL only
            // the first request per session reached the sensor: the call succeeded, the
            // capture callbacks kept arriving, and the exposure never moved.
            s.setRepeatingRequest(b.build(), captureCallback, handler)
            true
        } catch (e: Exception) {
            // Reported rather than swallowed, and the caller must not paper over it with
            // a success message -- which is exactly what an earlier version did, leaving
            // a retune that threw looking identical to one that worked.
            Log.e(TAG, "setRepeatingRequest failed", e)
            status = "camera rejected: ${e.javaClass.simpleName} ${e.message}"
            false
        }
    }

    /**
     * Applies a new exposure by rebuilding the capture session around it.
     *
     * Heavier than re-issuing a repeating request, and not the first thing tried. On this
     * device a repeating request only reaches the sensor when it is the one the session
     * was configured with: every later `setRepeatingRequest` returned normally, kept the
     * capture callbacks flowing, and left the exposure exactly where it was. Stopping the
     * repeat first did not help, nor did a one-shot `capture` alongside it, nor rebuilding
     * the request from a fresh template. Configuring a new session does work, every time.
     *
     * The cost is a few hundred milliseconds of no frames per change, which is the right
     * trade for a dial that moves on a deliberate tap and then stays put. The camera
     * device itself stays open throughout, so this is a session rebuild rather than a
     * camera restart.
     */
    private fun retune() {
        val camera = device ?: return
        Log.i(TAG, "retune: rebuilding the capture session for ${tuning.describeRequest()}")
        status = "applying ${tuning.describeRequest()}"
        configure(camera)
    }

    /** A capture request targeting both outputs, with nothing else set on it yet. */
    private fun newBuilder(): CaptureRequest.Builder? {
        val camera = device ?: return null
        val preview = previewSurface ?: return null
        val r = reader ?: return null
        return try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                addTarget(r.surface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not build a capture request", e)
            null
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            // Counted here rather than off the render loop's frame index. That index
            // advances on a different thread and at a different rate, so gating on it
            // meant the read-back could sit stale indefinitely -- which is fatal for the
            // one line in the HUD whose whole job is to be the ground truth.
            if (captureCount++ % METADATA_EVERY_N_CAPTURES != 0L) return
            tuning.reported = CameraTuning.Reported(
                exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                evSteps = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                aeMode = result.get(CaptureResult.CONTROL_AE_MODE),
                aeLocked = result.get(CaptureResult.CONTROL_AE_LOCK),
                awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                awbLocked = result.get(CaptureResult.CONTROL_AWB_LOCK),
            )
        }
    }

    override fun applyPendingTuning(): Boolean {
        val generation = tuning.generation
        if (generation == appliedGeneration) return false
        if (session == null) return false
        appliedGeneration = generation
        handler?.post { retune() }
        // Deliberately not reported as a change worth discarding anything for. Nothing is
        // accumulated across frames here, so a new exposure simply applies to the next
        // frame and the one after that is read normally.
        return false
    }

    // --- Frames ----------------------------------------------------------

    override val isFinished: Boolean get() = false
    override val frameCount: Long get() = -1

    override fun nextFrame(): FrameData? {
        pollCount++
        if (pollCount % 120L == 0L && frameIndex == 0L) {
            // Nothing has ever come through. Which precondition is missing is the whole
            // question, and every one of them fails silently on its own.
            Log.w(
                TAG,
                "no frames yet: texture=${surfaceTexture != null} available=$frameAvailable " +
                    "geometry=${geometry != null} session=${session != null} status='$status'",
            )
        }
        val st = surfaceTexture ?: return null
        if (!frameAvailable) return null
        frameAvailable = false
        try {
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)
            // Does the producer's transform swap s and t? If it does it is applying the
            // sensor's mounting rotation itself, and applying ours on top cancels it.
            // Read off the matrix rather than assumed, because it is a property of the
            // device and this one disagrees with the obvious guess.
            val rotates = kotlin.math.abs(transformMatrix[4]) > kotlin.math.abs(transformMatrix[0])
            if (producerRotates != rotates) {
                producerRotates = rotates
                Log.i(
                    TAG,
                    "producer transform ${if (rotates) "transposes s/t: it turns the buffer " +
                        "upright itself" else "does not rotate"} " +
                        "[${transformMatrix[0]} ${transformMatrix[4]} ${transformMatrix[12]}] " +
                        "[${transformMatrix[1]} ${transformMatrix[5]} ${transformMatrix[13]}]",
                )
                rebuildGeometry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage failed", e)
            return null
        }
        val g = backgroundGeometry ?: geometry ?: return null
        g.quadTexCoords(texCoords)
        g.applyTransform(transformMatrix, texCoords)
        if (logComposite) {
            logComposite = false
            reportPreviewOrientation()
        }

        // Intrinsics are a placeholder: nothing downstream of a pose-free source uses
        // them, and inventing plausible numbers would be worse than obviously flat ones.
        val intrinsics = Intrinsics(
            captureSize.width.toFloat(), captureSize.width.toFloat(),
            captureSize.width * 0.5f, captureSize.height * 0.5f,
            captureSize.width, captureSize.height,
        )
        return FrameData(
            timestampNanos = st.timestamp,
            textureId = textureId,
            textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            intrinsics = intrinsics,
            // No pose, and honestly none: this source cannot produce one, and the parts
            // of the pipeline that need one are skipped rather than fed a guess.
            pose = null,
            trackingState = TrackingState.UNAVAILABLE,
            points = emptyPoints,
            pointCount = 0,
            textureTransform = texCoords,
            projection = identityMatrix,
            view = identityMatrix,
            frameIndex = frameIndex++,
        )
    }

    /**
     * Converts a YUV frame into the luma-plus-chroma view the gem reader expects.
     *
     * Chroma is upsampled to full resolution rather than the reader being taught about
     * half-resolution planes. That costs a pass over the frame, but it keeps
     * [CanvasView] -- which every gem test is written against -- as the single thing that
     * knows how colour is stored.
     */
    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val target = synchronized(lock) {
                // Dropped *before* the conversion, not after it.
                //
                // This class always meant to discard a frame that arrives while the last
                // one is still unread -- the buffer comment above says so -- but it used
                // to convert first and notice second, paying a full 1920x1080 YUV pass
                // for an image already destined for the bin.
                //
                // On device that was not merely wasteful, it was self-sustaining. This
                // callback and the preview SurfaceTexture's `onFrameAvailable` share one
                // camera thread, so a conversion running 30 times a second starves the
                // preview; the render loop only advances on a preview frame, and only
                // dispatches a scan when it advances -- so the consumer that would have
                // taken `ready` never ran, so every frame was discarded, so the thread
                // stayed saturated. Live Gems settled at 0.5 fps with the scanner
                // effectively stopped, and the camera looked healthy throughout because
                // capture callbacks and metadata read-back were never the thing blocked.
                if (ready != null) {
                    droppedFrames++
                    return
                }
                if (consuming === bufferA) bufferB else bufferA
            }
            val view = target.view
            if (view == null || target.width != image.width || target.height != image.height) {
                droppedFrames++
                return
            }
            convert(image, view)
            synchronized(lock) { ready = target }
        } catch (e: Exception) {
            Log.w(TAG, "frame conversion failed", e)
        } finally {
            image.close()
        }
    }

    private fun convert(image: Image, view: CanvasView) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val luma = view.luma.data
        if (yPixelStride == 1) {
            val row = ByteArray(yRowStride)
            for (y in 0 until height) {
                yBuf.position(y * yRowStride)
                val n = minOf(yRowStride, yBuf.remaining())
                yBuf.get(row, 0, n)
                System.arraycopy(row, 0, luma, y * width, minOf(width, n))
            }
        } else {
            for (y in 0 until height) {
                var p = y * yRowStride
                val out = y * width
                for (x in 0 until width) {
                    luma[out + x] = yBuf.get(p)
                    p += yPixelStride
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val chroma = view.chroma!!.data

        for (y in 0 until height) {
            val cy = y shr 1
            val uRow = cy * uRowStride
            val vRow = cy * vRowStride
            var out = y * width * 2
            for (x in 0 until width) {
                val cx = x shr 1
                chroma[out] = uBuf.get(uRow + cx * uPixelStride)
                chroma[out + 1] = vBuf.get(vRow + cx * vPixelStride)
                out += 2
            }
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previewSurface?.release()
        previewSurface = null
        // The SurfaceTexture is deliberately kept: it belongs to the GL texture, not to
        // the camera, and only the GL thread may make another one.
        builder = null
        builtForMode = null
        opening = false
        appliedGeneration = -1
        frameAvailable = false
    }

    private companion object {
        const val TAG = "Camera2Source"

        /**
         * 1080p. The ring constants were measured against 1080p footage of this wall, so
         * staying there means they transfer without reinterpretation, and a gem's centre
         * dot is only a few pixels across even here.
         */
        const val MAX_CAPTURE_PIXELS = 1920L * 1080L

        /** 1/30 s, so a long manual shutter does not ask for an impossible frame rate. */
        const val FRAME_DURATION = 33_333_333L

        const val IMAGE_BUFFERS = 3
        const val METADATA_EVERY_N_CAPTURES = 10L
    }
}
