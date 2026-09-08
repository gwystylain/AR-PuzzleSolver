package com.puzzlesolver.app.frame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Asks one question, on the device, and answers it with a number: **does this phone
 * honour a manual exposure request from an ordinary app at all?**
 *
 * It exists because the shared-camera path came back with a flat no. Asked for 1/250 s
 * at ISO 100, the frame metadata reported 1/100 s at ISO 2008 — before ARCore's first
 * resume, after it, and re-asserted a beat later. Even `CONTROL_EFFECT_MODE_MONO`, which
 * would have turned the preview visibly grey, changed nothing. So the app's capture
 * request was reaching nobody, and there are exactly two explanations:
 *
 *  - ARCore owns the request while it is active and quietly discards the app's, or
 *  - this OEM ignores manual controls from a non-privileged app on this camera.
 *
 * Those imply completely different next steps — one is worth engineering around, the
 * other is not — and no amount of reading the code separates them. This opens the camera
 * with no ARCore anywhere near it, sets the same manual request, and reports what the
 * sensor did. Whatever it says is the answer.
 *
 * Deliberately standalone and short-lived: it holds the camera for a second, logs, and
 * gets out of the way, because nothing else can open the camera while it runs.
 */
class CameraProbe(context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)

    /** Human-readable verdict, published for the HUD and the log. */
    @Volatile
    var result: String = "not run"
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var framesSeen = 0

    @SuppressLint("MissingPermission")   // only ever called after the permission check
    fun run(cameraId: String, exposureNanos: Long, iso: Int) {
        val t = HandlerThread("camera-probe").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            finish("probe: could not read characteristics: ${e.message}")
            return
        }
        val manual = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR } == true
        Log.i(TAG, "probe: camera $cameraId advertises MANUAL_SENSOR=$manual")

        // Small and YUV, because nothing looks at these frames. The only output that
        // matters is the metadata attached to them.
        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, h)
        reader = imageReader

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure(camera, imageReader, exposureNanos, iso, manual)
                }

                override fun onDisconnected(camera: CameraDevice) = finish("probe: camera disconnected")

                override fun onError(camera: CameraDevice, error: Int) = finish("probe: camera error $error")
            }, h)
        } catch (e: Exception) {
            finish("probe: openCamera failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")   // the SessionConfiguration overload is API 28+; minSdk is 26
    private fun configure(
        camera: CameraDevice,
        imageReader: ImageReader,
        exposureNanos: Long,
        iso: Int,
        manual: Boolean,
    ) {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(imageReader.surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNanos, 33_333_333L))

        camera.createCaptureSession(
            listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    try {
                        captureSession.setRepeatingRequest(
                            builder.build(),
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    s: CameraCaptureSession,
                                    request: CaptureRequest,
                                    r: TotalCaptureResult,
                                ) {
                                    framesSeen++
                                    // The first few frames of any session carry
                                    // whatever the 3A block was doing on the way in, so
                                    // the verdict is taken once it has settled.
                                    if (framesSeen < SETTLE_FRAMES) return
                                    val gotExposure = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                    val gotIso = r.get(CaptureResult.SENSOR_SENSITIVITY)
                                    val gotAeMode = r.get(CaptureResult.CONTROL_AE_MODE)
                                    val honoured = gotExposure != null &&
                                        kotlin.math.abs(gotExposure - exposureNanos) < exposureNanos / 4
                                    finish(
                                        "probe: manualSupported=$manual asked " +
                                            "${exposureNanos / 1000}us iso$iso, got " +
                                            "${gotExposure?.div(1000)}us iso$gotIso " +
                                            "aeMode=$gotAeMode -> " +
                                            if (honoured) "HONOURED" else "IGNORED"
                                    )
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        finish("probe: setRepeatingRequest failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) =
                    finish("probe: capture session configuration failed")
            },
            handler,
        )
    }

    private fun finish(verdict: String) {
        if (result != "not run" && result != "running") return
        result = verdict
        Log.i(TAG, verdict)
        handler?.post { close() }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private companion object {
        const val TAG = "CameraProbe"
        const val SETTLE_FRAMES = 12
    }
}
