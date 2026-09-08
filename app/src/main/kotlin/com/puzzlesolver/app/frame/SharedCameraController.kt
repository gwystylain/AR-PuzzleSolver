package com.puzzlesolver.app.frame

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera

/**
 * Drives the camera directly, alongside ARCore, so the app can choose its own exposure.
 *
 * ARCore's ordinary `Session` opens the camera itself and exposes no control over it.
 * Shared camera mode inverts that: the app opens the camera through Camera2, hands
 * ARCore the surfaces it needs, and keeps the capture request. That is the only route
 * to an exposure dial, and on the gem wall an exposure dial is the difference between
 * a readable image and a wall of white discs.
 *
 * Three rules from ARCore's documentation shape everything here, and breaking any of
 * them fails in a way that looks like something else:
 *
 *  - **`setRepeatingRequest` must not be called while ARCore is active.** So changing a
 *    setting is pause ARCore, re-issue, resume ARCore -- see [applyTuning]. The initial
 *    settings are issued before ARCore ever resumes, so the common case costs nothing.
 *  - **The repeating request must target every surface ARCore created.** Missing one
 *    silently starves motion tracking rather than erroring.
 *  - **ARCore cannot use a hardware depth sensor in this mode.** That would be a real
 *    loss if anything used depth; nothing does. The wall fitter runs off
 *    `Frame.acquirePointCloud`, which is motion-tracking feature points and is
 *    unaffected. Checked rather than assumed -- there is no `acquireDepthImage` call
 *    anywhere in the app.
 *
 * Every failure path here falls back rather than throws. A device that will not open
 * its camera this way should still run the app with ARCore's own session and no
 * exposure control, because a solver that works without a dial beats a dial that
 * crashes the app.
 */
class SharedCameraController(
    context: Context,
    private val tuning: CameraTuning,
) {
    private val manager = context.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var session: Session? = null
    private var shared: SharedCamera? = null
    private var cameraId: String? = null

    // Written on the camera thread, read from the frame loop when it asks whether a
    // retune can be applied yet.
    @Volatile
    private var device: CameraDevice? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null

    @Volatile
    private var requestBuilder: CaptureRequest.Builder? = null

    /** Set from the GL thread when the renderer hands over its external texture. */
    @Volatile
    private var textureId: Int = -1

    @Volatile
    private var wantRunning = false

    @Volatile
    private var opening = false

    @Volatile
    private var appliedGeneration = -1

    /**
     * Set by the camera thread when a retune has actually landed, and consumed by the
     * frame loop.
     *
     * The frame loop cannot report the change the moment it asks for one: the work is
     * posted to the camera thread and takes a pause, a request and a resume to complete,
     * and the frames in between still carry the old exposure. Telling the pipeline to
     * throw the mosaic away early would just mean re-accumulating those same stale
     * frames into the fresh canvas.
     */
    @Volatile
    private var retuneCompleted = false

    /** True while the ARCore session is resumed and producing frames. */
    @Volatile
    var arActive: Boolean = false
        private set

    /** One line for the HUD and the heartbeat. */
    @Volatile
    var status: String = "camera not started"
        private set

    /**
     * Set once this controller has given up, so the caller can fall back to a plain
     * ARCore session instead of waiting for frames that will never arrive.
     */
    @Volatile
    var failed: Boolean = false
        private set

    /**
     * Binds to a session created with [Session.Feature.SHARED_CAMERA] and reads what
     * the camera can actually do.
     *
     * Capabilities come from `CameraCharacteristics` rather than from trying things and
     * seeing what sticks, because a rejected capture request does not report which key
     * it disliked -- it just quietly gives you the old picture.
     */
    fun bind(session: Session, cameraId: String): Boolean {
        this.session = session
        this.cameraId = cameraId
        this.shared = try {
            session.sharedCamera
        } catch (e: Exception) {
            fail("session has no shared camera: ${e.message}")
            return false
        }
        tuning.capabilities = readCapabilities(cameraId)
        Log.i(TAG, "camera $cameraId: ${tuning.capabilities.describe()}")
        return true
    }

    private fun readCapabilities(cameraId: String): CameraTuning.Capabilities = try {
        val c = manager.getCameraCharacteristics(cameraId)
        val evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manual = caps?.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        } == true
        // A device reporting a compensation step of 1/3 EV needs three steps per EV.
        // Rounding rather than truncating: 1/2 and 1/3 are the only values in practice
        // and both round exactly.
        val stepsPerEv = evStep
            ?.takeIf { it.toDouble() > 0.0 }
            ?.let { Math.round(1.0 / it.toDouble()).toInt() }
            ?.coerceIn(1, 6)
            ?: 1
        CameraTuning.Capabilities(
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
    } catch (e: Exception) {
        Log.w(TAG, "could not read camera characteristics", e)
        CameraTuning.Capabilities(issue = e.javaClass.simpleName)
    }

    /** Called from the GL thread once the renderer's external texture exists. */
    fun setTextureId(id: Int) {
        textureId = id
        maybeOpen()
    }

    fun requestResume() {
        wantRunning = true
        ensureThread()
        maybeOpen()
    }

    /**
     * Opens the camera once both preconditions hold.
     *
     * Two of them, arriving on different threads and in either order: the activity
     * resuming, and the GL thread creating the camera texture. ARCore needs the texture
     * name before the capture session is created, so whichever lands second is what
     * starts the sequence.
     */
    private fun maybeOpen() {
        if (!wantRunning || textureId < 0 || failed) return
        if (device != null || opening) return
        val id = cameraId ?: return
        val h = handler ?: return
        opening = true
        status = "opening the camera"
        h.post {
            try {
                openLocked(id)
            } catch (e: SecurityException) {
                fail("camera permission was refused")
            } catch (e: Exception) {
                fail("could not open the camera: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")   // the activity gates every path here on CAMERA
    private fun openLocked(id: String) {
        val s = shared ?: return
        manager.openCamera(id, s.createARDeviceStateCallback(deviceCallback, handler!!), handler)
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (!wantRunning) {
                // The activity paused while the camera was opening. Handing this device
                // to ARCore now would leave both holding a camera nobody is going to
                // use, and the next resume would find `device` already set and never
                // open one.
                camera.close()
                return
            }
            device = camera
            try {
                createCaptureSession(camera)
            } catch (e: Exception) {
                fail("could not configure the camera: ${e.message}")
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera disconnected")
            closeCamera()
            status = "camera disconnected"
        }

        override fun onError(camera: CameraDevice, error: Int) {
            fail("camera error $error")
            closeCamera()
        }
    }

    @Suppress("DEPRECATION")   // the SessionConfiguration overload is API 28+; minSdk is 26
    private fun createCaptureSession(camera: CameraDevice) {
        val s = shared ?: return
        val arSession = session ?: return

        // ARCore needs its texture before the stream starts, not after: the surfaces it
        // is about to hand us are bound to it.
        arSession.setCameraTextureName(textureId)

        // TEMPLATE_RECORD rather than PREVIEW because it targets a steady frame rate
        // over a pretty preview, and a steady frame rate is what the mosaic is made of.
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        val surfaces: MutableList<Surface> = s.arCoreSurfaces
        for (surface in surfaces) builder.addTarget(surface)
        requestBuilder = builder

        camera.createCaptureSession(
            surfaces,
            s.createARSessionStateCallback(sessionCallback, handler!!),
            handler,
        )
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            captureSession = cameraCaptureSession
            // Issued *before* ARCore is resumed, which is the one moment the app is
            // allowed to set a repeating request. Everything after this costs a pause.
            //
            // Marking it applied in the same breath is what stops the frame loop from
            // immediately "re-applying" settings that are already in force. Without it
            // every startup paused and resumed ARCore once for nothing and threw away a
            // mosaic that had just been started -- and it raced this callback, since
            // ARCore may deliver it on its own thread rather than ours.
            appliedGeneration = tuning.generation
            writeRepeatingRequest()
            resumeArCore()
        }

        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
            fail("the camera refused this combination of streams")
        }
    }

    private fun resumeArCore() {
        val arSession = session ?: return
        try {
            arSession.resume()
            arActive = true
            status = "camera running (${tuning.describeRequest()})"
            Log.i(TAG, "ARCore resumed on the shared camera")
            reassertAfterResume()
        } catch (e: Exception) {
            fail("ARCore would not start on the shared camera: ${e.message}")
        }
    }

    /**
     * Re-issues the capture request a moment after ARCore has resumed.
     *
     * Measured on device, and the reason this exists: setting the request before
     * `Session.resume()` -- which is what ARCore's own sample does and what its
     * documentation implies -- does not survive the resume. ARCore installs a repeating
     * request of its own, and the frame metadata comes back with auto-exposure values
     * whatever was asked for. A request for 1/250 s at ISO 100 read back as 1/100 s at
     * ISO 2334.
     *
     * So the request is written again once ARCore has settled. This is the thing the
     * documentation warns against, and the warning is taken seriously: it is done once,
     * a beat after the resume rather than continuously, so ARCore is never in a fight
     * with us over the request. Whether it took is not assumed either -- the HUD reads
     * the exposure back off the frame metadata, and that is what the "actual" line is.
     */
    private fun reassertAfterResume() {
        handler?.postDelayed({
            if (!arActive) return@postDelayed
            writeRepeatingRequest()
            Log.i(TAG, "re-asserted the capture request after resume")
        }, REASSERT_DELAY_MS)
    }

    /**
     * Re-issues the capture request with whatever [tuning] now says.
     *
     * Called from the frame loop rather than from the tap that changed the setting: the
     * work has to happen on the camera thread and with ARCore paused, and doing that
     * from the UI thread would mean the user's tap could stall on a session pause.
     *
     * @return true when a change was actually applied, which the caller uses to discard
     *         the mosaic. That is not damage control -- a canvas holding texels from two
     *         different exposures has seams in it that the colour classifier reads as
     *         real, so throwing it away is the correct response to changing exposure.
     */
    fun applyTuning(): Boolean {
        // A retune that has finished is reported exactly once, on the first frame after
        // the camera actually changed.
        if (retuneCompleted) {
            retuneCompleted = false
            return true
        }
        if (failed || captureSession == null) return false
        val generation = tuning.generation
        if (generation == appliedGeneration) return false
        appliedGeneration = generation
        val h = handler ?: return false
        h.post {
            val arSession = session
            val wasActive = arActive
            try {
                if (wasActive && arSession != null) {
                    arSession.pause()
                    arActive = false
                }
                writeRepeatingRequest()
                if (wasActive && arSession != null) {
                    arSession.setCameraTextureName(textureId)
                    arSession.resume()
                    arActive = true
                    // Same reason as at startup: the request written a moment ago did
                    // not survive the resume, so it goes in again once ARCore has
                    // installed its own.
                    reassertAfterResume()
                }
                status = "camera running (${tuning.describeRequest()})"
                Log.i(TAG, "camera retuned: ${tuning.describeRequest()}")
                retuneCompleted = true
            } catch (e: Exception) {
                Log.e(TAG, "retune failed", e)
                status = "retune failed: ${e.message}"
                // Best effort: get frames flowing again even if the new setting did not
                // take. A stuck-paused session is far worse than a wrong exposure.
                try {
                    if (!arActive && arSession != null) {
                        arSession.resume()
                        arActive = true
                    }
                } catch (_: Exception) {
                }
                // Still a change from the mosaic's point of view: whatever the camera
                // is doing now, it is not what the accumulated texels were shot at.
                retuneCompleted = true
            }
        }
        return true
    }

    private fun writeRepeatingRequest() {
        val builder = requestBuilder ?: return
        val cs = captureSession ?: return
        val s = tuning.settings
        val caps = tuning.capabilities

        if (s.mode == CameraTuning.Mode.MANUAL && caps.manualSensor) {
            // CONTROL_MODE has to be AUTO for the AE mode to mean anything: with it at
            // USE_SCENE_MODE the 3A block runs its own program and quietly ignores the
            // AE keys, which reads from the outside as the request not taking at all.
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNanos)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            // Several HALs reject a manual request outright when the frame duration is
            // left at zero, and rejecting it means keeping the old auto values. Held at
            // 1/30 s or the exposure, whichever is longer, so a long shutter does not
            // ask for a frame rate the sensor cannot read out.
            builder.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(s.exposureNanos, 33_333_333L),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evSteps)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, s.lockAe)
        }
        // Locking white balance is independent of the exposure mode and matters on its
        // own: the gem classifier decides on hue, and a room washed in shifting purple
        // has auto white balance rotating every hue in the frame between passes.
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, s.lockAwb)
        builder.set(
            CaptureRequest.CONTROL_EFFECT_MODE,
            if (s.diagnosticMono) CaptureRequest.CONTROL_EFFECT_MODE_MONO
            else CaptureRequest.CONTROL_EFFECT_MODE_OFF,
        )

        try {
            cs.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "setRepeatingRequest failed", e)
            status = "camera rejected the request: ${e.reason}"
        } catch (e: IllegalStateException) {
            // The capture session was closed under us, which happens during teardown.
            Log.d(TAG, "capture session gone while retuning")
        }
    }

    fun requestPause() {
        wantRunning = false
        val arSession = session
        try {
            if (arActive && arSession != null) {
                arSession.pause()
                arActive = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "pause failed", e)
        }
        // Posted rather than done here so every touch of the camera objects happens on
        // the one thread that owns them. `close()` uses quitSafely, which runs whatever
        // is still queued, so this cannot be dropped on the way out.
        val h = handler
        if (h != null) h.post { closeCamera() } else closeCamera()
        status = "camera paused"
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        requestBuilder = null
        opening = false
        // So the next open re-issues the current settings rather than believing the
        // fresh capture session already has them.
        appliedGeneration = -1
        retuneCompleted = false
    }

    fun close() {
        requestPause()
        thread?.quitSafely()
        thread = null
        handler = null
        session = null
        shared = null
    }

    private fun ensureThread() {
        if (thread != null) return
        val t = HandlerThread("shared-camera").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    private fun fail(reason: String) {
        failed = true
        opening = false
        status = reason
        Log.e(TAG, "shared camera unavailable: $reason")
    }

    private companion object {
        const val TAG = "SharedCamera"

        /**
         * How long to let ARCore settle after a resume before writing the request back.
         *
         * Long enough that ARCore has installed its own and is not about to install
         * another, short enough that the user does not watch a blown-out wall for a
         * noticeable beat first.
         */
        const val REASSERT_DELAY_MS = 400L
    }
}
