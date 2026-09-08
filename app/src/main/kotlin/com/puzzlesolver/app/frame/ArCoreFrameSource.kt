package com.puzzlesolver.app.frame

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.ImageMetadata
import com.google.ar.core.Session
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.TrackingState as ArTrackingState
import com.puzzlesolver.core.math.Intrinsics
import com.puzzlesolver.core.math.Pose
import java.util.EnumSet

/**
 * Live ARCore capture, and -- via the same class -- playback of an ARCore
 * recording.
 *
 * Using ARCore's own Recording and Playback API rather than rolling our own video
 * plus pose sidecar matters more than it looks. During playback ARCore re-runs its
 * real tracking over the recorded camera and IMU streams, so poses, anchors,
 * feature points and depth all behave as they did live. A recording is therefore a
 * faithful regression test of the whole stack, not just of the parts downstream of
 * pose.
 */
class ArCoreFrameSource(
    private val context: Context,
    /** Non-null to replay a recording instead of using the live camera. */
    private val playbackDataset: Uri? = null,
    /**
     * Whether to take the camera over from ARCore so exposure can be set.
     *
     * Defaulted off by the activity on measurement -- ARCore discards the app's capture
     * request while it is active, at least on the device this was built against. See
     * `MainActivity.useSharedCamera` and docs/CAMERA_CONTROL.md. The machinery stays
     * because the finding is version- and device-specific and cheap to re-test.
     *
     * Never used for playback: a recording carries the exposure it was shot at.
     */
    private val useSharedCamera: Boolean = false,
    /**
     * Prefer the largest camera image over the highest frame rate.
     *
     * Frame rate buys mosaic redundancy, which sharpens a canvas that is already
     * resolving what it needs to. Resolution buys detail that no amount of redundancy
     * recovers -- and a gem's centre dot is three texels across, so on that wall detail
     * is the scarce thing. Off by default; on for the button walls.
     */
    private val preferDetail: Boolean = false,
    /** Camera dials, shared with the UI. Ignored unless the shared camera comes up. */
    override val tuning: CameraTuning = CameraTuning(),
) : FrameSource {

    override val kind: FrameSource.Kind =
        if (playbackDataset != null) FrameSource.Kind.AR_DATASET else FrameSource.Kind.LIVE

    var session: Session? = null
        private set

    private var textureId: Int = -1
    private var frameIndex = 0L
    private var finished = false

    /**
     * Reused across frames. Depth point clouds run to a few thousand points, and
     * reallocating that every frame is exactly the kind of steady-state garbage
     * that turns a smooth 60 fps into a stuttery 55.
     */
    private var pointBuffer = FloatArray(INITIAL_POINT_CAPACITY * 3)
    private val textureTransform = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    override val isFinished: Boolean get() = finished
    override val frameCount: Long get() = -1

    @Volatile
    private var configSummary: String = "config=pending"

    /** Null when this session is not sharing the camera, which is every replay. */
    private var cameraController: SharedCameraController? = null

    override val diagnostics: String
        get() = configSummary + (cameraController?.let { " cam='${it.status}'" } ?: "")

    override val cameraStatus: String?
        get() = cameraController?.status

    override val cameraFailed: Boolean
        get() = cameraController?.failed == true

    override fun applyPendingTuning(): Boolean = cameraController?.applyTuning() ?: false

    fun create(): Session {
        Log.i(TAG, "create(): begin, playback=${playbackDataset != null}")
        // Shared camera is live-only. During playback the frames come off the recording
        // at the exposure they were shot at, and there is no camera to take over.
        val wantShared = useSharedCamera && playbackDataset == null
        val s = if (wantShared) {
            try {
                Session(context, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: Exception) {
                Log.w(TAG, "shared camera session unavailable; using ARCore's own camera", e)
                Session(context)
            }
        } else {
            Session(context)
        }
        val config = Config(s).apply {
            // Nothing here is about saving power -- the user was explicit that speed
            // matters and battery does not.
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.DISABLED   // we fit our own wall
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
        }

        playbackDataset?.let {
            s.setPlaybackDatasetUri(it)
        }
        s.configure(config)
        val shared = wantShared && s.isSharedCamera()
        try {
            selectFastestHighResConfig(s, shared)
        } catch (e: Exception) {
            // A camera-config choice failing is survivable: ARCore keeps its default,
            // which costs us frame rate but nothing else. Losing the whole session over
            // it would not be.
            configSummary = "config=default (${e.javaClass.simpleName})"
            Log.w(TAG, "camera config selection failed; keeping ARCore's default", e)
        }
        session = s
        if (shared) {
            // Kept even when the bind fails, and deliberately. A session created to
            // share the camera will not open one for itself, so there is nothing to fall
            // back to *within* this session -- the recovery is the activity noticing
            // [cameraFailed] and rebuilding the source without sharing. Dropping the
            // controller here would hide the failure and leave the app frameless.
            val controller = SharedCameraController(context, tuning)
            if (!controller.bind(s, s.cameraConfig.cameraId)) {
                Log.w(TAG, "shared camera bind failed; the activity will rebuild without it")
            }
            cameraController = controller
        }
        Log.i(TAG, "create(): session ready, sharedCamera=${cameraController != null}")
        return s
    }

    /**
     * Whether ARCore actually gave us a shared-camera session.
     *
     * Asked rather than assumed: [Session] construction with the feature can succeed on
     * a device that then refuses to hand over a [com.google.ar.core.SharedCamera], and
     * finding that out at bind time would leave the camera half-configured.
     */
    private fun Session.isSharedCamera(): Boolean = try {
        sharedCamera.let { true }
    } catch (e: Exception) {
        false
    }

    /**
     * Picks the camera config with the highest frame rate, breaking ties on CPU
     * image resolution.
     *
     * Frame rate first because the canvas mosaic improves with the number of
     * distinct viewpoints, not with any single frame's detail; a 60 fps stream
     * covers a panned wall with roughly twice the redundancy of a 30 fps one, and
     * redundancy is what the max-confidence accumulator turns into sharpness.
     */
    private fun selectFastestHighResConfig(s: Session, shared: Boolean) {
        val filter = CameraConfigFilter(s).apply {
            targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60, CameraConfig.TargetFps.TARGET_FPS_30)
            // ARCore cannot drive a hardware depth sensor while sharing the camera, so
            // asking for one here would filter every config out on exactly the devices
            // that have one.
            depthSensorUsage = if (shared) {
                EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE)
            } else {
                EnumSet.of(
                    CameraConfig.DepthSensorUsage.REQUIRE_AND_USE,
                    CameraConfig.DepthSensorUsage.DO_NOT_USE,
                )
            }
        }
        // A filter that matches nothing is not an error, it just means this device has
        // no config satisfying every constraint at once -- commonly no 60 fps mode, or
        // no depth sensor. Falling back to the unfiltered list keeps us choosing the
        // fastest config available instead of silently accepting ARCore's default.
        var configs = s.getSupportedCameraConfigs(filter)
        if (configs.isEmpty()) {
            Log.w(TAG, "no camera config matched the 60/30 fps filter; using the unfiltered list")
            configs = s.getSupportedCameraConfigs(CameraConfigFilter(s))
        }
        if (configs.isEmpty()) {
            Log.w(TAG, "device reports no selectable camera configs at all")
            return
        }
        // The whole list, once. Which configs a device offers is the first thing you
        // want when the frame rate or the image size is not what you expected, and it is
        // not derivable from the one that got picked.
        for (c in configs) {
            Log.i(
                TAG,
                "  candidate: tex=${c.textureSize} cpu=${c.imageSize} " +
                    "fps=${c.fpsRange} depth=${c.depthSensorUsage}",
            )
        }
        // Preference order, and why depth outranks frame rate:
        //
        // The wall fit is the gate on everything else -- until a surface is locked,
        // nothing accumulates and no puzzle can be read. A hardware depth sensor
        // delivers dense metric points immediately, whereas depth-from-motion needs the
        // user to move first and yields far sparser points. Frame rate only buys mosaic
        // redundancy, which matters *after* the wall is locked. So converge first, then
        // sharpen.
        //
        // Detail mode reorders the last two. On a wall of gems the thing that limits
        // reading is texels across a ring, not how many looks we get at it, and no
        // amount of redundancy invents detail the sensor never sampled.
        val order = if (preferDetail) {
            compareBy<CameraConfig>(
                { if (it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) 1 else 0 },
                { it.textureSize.width.toLong() * it.textureSize.height },
                { it.fpsRange.upper },
            )
        } else {
            compareBy<CameraConfig>(
                { if (it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) 1 else 0 },
                { it.fpsRange.upper },
                { it.imageSize.width.toLong() * it.imageSize.height },
            )
        }
        val best = configs.maxWithOrNull(order)
        if (best != null) {
            s.cameraConfig = best
            configSummary = "tex=${best.textureSize} cpu=${best.imageSize} " +
                "fps=${best.fpsRange.upper} depth=${best.depthSensorUsage} " +
                "of=${configs.size} ${if (preferDetail) "detail" else "speed"}"
            Log.i(TAG, "camera config: $configSummary")
        }
    }

    override fun attachTexture(textureId: Int) {
        this.textureId = textureId
        session?.setCameraTextureName(textureId)
        // The controller cannot create its capture session until it has this, so handing
        // it over is what starts the camera when the activity resumed first.
        cameraController?.setTextureId(textureId)
    }

    override fun setDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        session?.setDisplayGeometry(rotation, widthPx, heightPx)
    }

    override fun nextFrame(): FrameData? {
        val s = session ?: return null
        if (textureId < 0) return null

        val frame: Frame = try {
            s.update()
        } catch (e: SessionPausedException) {
            // Expected, and only ever transient. Swapping sources pauses the outgoing
            // session while the render thread is still a frame or two behind, so it
            // calls update() on something already stopped. The next frame comes from
            // the new source and everything proceeds.
            //
            // It is logged quietly and without a stack trace on purpose: as a warning
            // with a trace it produced a wall of identical noise on every swap, which
            // is exactly where a genuine failure -- a playback session that was never
            // resumed at all -- sat unnoticed.
            Log.d(TAG, "update on a paused session; source swap in progress")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "session update failed", e)
            return null
        }

        if (playbackDataset != null && s.playbackStatus == PlaybackStatus.FINISHED) {
            finished = true
            return null
        }

        if (frameIndex % METADATA_EVERY_N_FRAMES == 0L) readCameraMetadata(frame)

        val camera = frame.camera
        val tracking = when (camera.trackingState) {
            ArTrackingState.TRACKING -> TrackingState.TRACKING
            ArTrackingState.PAUSED -> TrackingState.PAUSED
            else -> TrackingState.UNAVAILABLE
        }

        frame.transformCoordinates2d(
            com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            QUAD_NDC,
            com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
            quadTexCoords,
        )
        // Pack the 4 texture coords into the transform slot; the accumulator shader
        // interpolates them across the quad rather than doing a matrix multiply.
        System.arraycopy(quadTexCoords, 0, textureTransform, 0, 8)

        val pose: Pose? = if (tracking == TrackingState.TRACKING) {
            val p = camera.pose
            Pose.normalized(
                p.tx(), p.ty(), p.tz(),
                p.qx(), p.qy(), p.qz(), p.qw(),
            )
        } else {
            null
        }

        val intr = camera.imageIntrinsics
        val focal = intr.focalLength
        val principal = intr.principalPoint
        val dims = intr.imageDimensions
        val intrinsics = Intrinsics(focal[0], focal[1], principal[0], principal[1], dims[0], dims[1])

        val count = collectPoints(frame)

        camera.getProjectionMatrix(projection, 0, NEAR, FAR)
        camera.getViewMatrix(viewMatrix, 0)

        return FrameData(
            timestampNanos = frame.timestamp,
            textureId = textureId,
            textureTarget = android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            intrinsics = intrinsics,
            pose = pose,
            trackingState = tracking,
            points = pointBuffer,
            pointCount = count,
            textureTransform = textureTransform,
            projection = projection,
            view = viewMatrix,
            frameIndex = frameIndex++,
        )
    }

    /**
     * Reads back what the camera actually did with this frame.
     *
     * The point of doing this at all is that a capture request is a *request*. A device
     * may clamp exposure compensation, ignore a manual exposure time, or have ARCore
     * re-issue the request with its own values -- and every one of those looks the same
     * from here, which is a picture that is still wrong. Showing requested and actual
     * side by side in the HUD turns "why is it still blown out" into one glance.
     *
     * Works on any ARCore session, shared camera or not, so a device that refuses to
     * hand over the camera still reports what it chose for itself.
     */
    private fun readCameraMetadata(frame: Frame) {
        val metadata = try {
            frame.imageMetadata
        } catch (e: Exception) {
            return
        }
        tuning.reported = CameraTuning.Reported(
            exposureNanos = metadata.longOrNull(ImageMetadata.SENSOR_EXPOSURE_TIME),
            iso = metadata.intOrNull(ImageMetadata.SENSOR_SENSITIVITY),
            evSteps = metadata.intOrNull(ImageMetadata.CONTROL_AE_EXPOSURE_COMPENSATION),
            aeMode = metadata.intOrNull(ImageMetadata.CONTROL_AE_MODE),
            aeLocked = metadata.byteOrNull(ImageMetadata.CONTROL_AE_LOCK)?.let { it.toInt() != 0 },
            awbMode = metadata.intOrNull(ImageMetadata.CONTROL_AWB_MODE),
            awbLocked = metadata.byteOrNull(ImageMetadata.CONTROL_AWB_LOCK)?.let { it.toInt() != 0 },
        )
    }

    // A key the device does not report throws rather than returning null, and which
    // keys a device reports varies. Absent is a normal answer here, not an error.
    private fun ImageMetadata.longOrNull(key: Int): Long? =
        try { getLong(key) } catch (e: Exception) { null }

    private fun ImageMetadata.intOrNull(key: Int): Int? =
        try { getInt(key) } catch (e: Exception) { null }

    private fun ImageMetadata.byteOrNull(key: Int): Byte? =
        try { getByte(key) } catch (e: Exception) { null }

    /**
     * Harvests world points from the depth point cloud when the device has depth,
     * falling back to tracked feature points otherwise.
     *
     * Feature points are sparser and noisier, but the wall fitter only needs a few
     * dozen inliers to pin down a circle, and RANSAC is what the noise is for.
     */
    private fun collectPoints(frame: Frame): Int {
        return try {
            frame.acquirePointCloud().use { cloud ->
                val buf = cloud.points          // x, y, z, confidence
                val n = buf.remaining() / 4
                ensureCapacity(n)
                var out = 0
                val start = buf.position()
                for (i in 0 until n) {
                    val base = start + i * 4
                    val conf = buf.get(base + 3)
                    if (conf < MIN_POINT_CONFIDENCE) continue
                    pointBuffer[out * 3] = buf.get(base)
                    pointBuffer[out * 3 + 1] = buf.get(base + 1)
                    pointBuffer[out * 3 + 2] = buf.get(base + 2)
                    out++
                }
                out
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun ensureCapacity(points: Int) {
        if (pointBuffer.size < points * 3) {
            pointBuffer = FloatArray(points * 3 * 2)
        }
    }

    // --- Recording -------------------------------------------------------

    /**
     * Starts writing an ARCore dataset. Call before [resume]; ARCore refuses to
     * start recording on a running session.
     */
    fun startRecording(destination: Uri): Boolean {
        val s = session ?: return false
        return try {
            s.startRecording(RecordingConfig(s).setMp4DatasetUri(destination).setAutoStopOnPause(true))
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            false
        }
    }

    fun stopRecording() {
        try {
            session?.stopRecording()
        } catch (e: Exception) {
            Log.w(TAG, "stopRecording failed", e)
        }
    }

    val isRecording: Boolean
        get() = session?.recordingStatus == com.google.ar.core.RecordingStatus.OK

    override fun pause() {
        val controller = cameraController
        if (controller != null) {
            // The controller owns the ARCore pause too, because it also has to shut the
            // camera device down -- and doing those in the wrong order leaves ARCore
            // holding surfaces from a camera that is already gone.
            controller.requestPause()
            return
        }
        try {
            session?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause failed", e)
        }
    }

    override fun resume() {
        val controller = cameraController
        if (controller != null && !controller.failed) {
            // Asynchronous: the camera opens on its own thread and ARCore is resumed
            // from the capture-session callback. Until then nextFrame() returns null,
            // which the pipeline already handles because tracking can be absent for
            // plenty of other reasons.
            controller.requestResume()
            controller.setTextureId(textureId)
            return
        }
        session?.resume()
    }

    override fun close() {
        cameraController?.close()
        cameraController = null
        session?.close()
        session = null
    }

    private val quadTexCoords = FloatArray(8)

    private companion object {
        const val TAG = "ArCoreFrameSource"
        const val NEAR = 0.05f
        const val FAR = 60f
        const val MIN_POINT_CONFIDENCE = 0.3f

        /**
         * How often to read capture metadata back off the frame. Seven JNI calls, and
         * exposure does not change per frame even when it is not locked, so a quarter
         * of a second is plenty and keeps this off the hot path.
         */
        const val METADATA_EVERY_N_FRAMES = 15L
        const val INITIAL_POINT_CAPACITY = 4096

        /** Full-screen quad in NDC, matching the accumulator's vertex order. */
        val QUAD_NDC = floatArrayOf(
            -1f, -1f,
            +1f, -1f,
            -1f, +1f,
            +1f, +1f,
        )
    }
}
