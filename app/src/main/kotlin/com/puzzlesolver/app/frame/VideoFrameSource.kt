package com.puzzlesolver.app.frame

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.util.Log
import android.view.Surface
import com.puzzlesolver.core.math.Intrinsics
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Plays an ordinary MP4 with no AR metadata.
 *
 * **Read this before relying on it.** An MP4 carries no camera pose, and the whole
 * pipeline is built on projecting frames onto a world-anchored wall. Without a pose
 * there is nothing to project onto, so this source currently *previews* frames and
 * reports [TrackingState.UNAVAILABLE]; the accumulator, and therefore detection and
 * solving, stay idle. The HUD says so rather than pretending otherwise.
 *
 * The real replay path is [ArCoreFrameSource] with a playback dataset. ARCore's
 * Recording and Playback API replays the camera and IMU streams through its actual
 * tracker, so poses, feature points and depth behave as they did live and a recording
 * is a faithful regression test of the entire stack. Record your scans and replay
 * those.
 *
 * This class exists for the case where somebody hands you footage you did not record.
 * Making it useful means adding vision-only tracking: estimate frame-to-frame motion
 * from feature correspondences, chain it into a relative trajectory, and hand the
 * pipeline a pose in an arbitrary but self-consistent frame. That is a real piece of
 * work and is deliberately not faked here -- a wrong pose silently produces a smeared
 * canvas and wrong answers, which is far worse than an honest idle. Decoding,
 * intrinsics plumbing and the GL path are all in place for whoever picks it up: supply
 * a non-null pose in [FrameData] and the rest of the pipeline engages unchanged.
 */
class VideoFrameSource(
    private val context: Context,
    private val uri: Uri,
    /**
     * Horizontal field of view assumed for the footage, degrees. Without AR metadata
     * we cannot know the real intrinsics; this only affects vision-only pose
     * estimates, not detection or solving.
     */
    private val assumedHorizontalFovDeg: Float = 66f,
) : FrameSource {

    override val kind = FrameSource.Kind.PLAIN_VIDEO

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var textureId = -1
    private var width = 0
    private var height = 0
    private var durationUs = 0L
    private var frameIndex = 0L
    private var finished = false
    private var inputDone = false

    private val bufferInfo = MediaCodec.BufferInfo()
    private val textureTransform = FloatArray(16)
    private val emptyPoints = FloatArray(0)

    /**
     * Identity matrices. Without AR metadata there is no pose, so the pipeline runs
     * vision-only and synthesises its own matrices from the assumed intrinsics; these
     * are placeholders that keep [FrameData] non-null.
     */
    private val identityProjection = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val identityView = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    /** Set by the GL thread when a decoded frame lands on the SurfaceTexture. */
    @Volatile private var frameAvailable = false
    private val frameLock = Object()

    override val isFinished: Boolean get() = finished

    override val frameCount: Long
        get() = if (durationUs <= 0) -1 else -1     // MP4 headers rarely carry an exact count

    val durationMillis: Long get() = TimeUnit.MICROSECONDS.toMillis(durationUs)

    override fun attachTexture(textureId: Int) {
        this.textureId = textureId
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }
        surfaceTexture = st
        surface = Surface(st)
        open()
    }

    private fun open() {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            Log.e(TAG, "no video track in $uri")
            finished = true
            return
        }
        ex.selectTrack(track)
        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        surfaceTexture?.setDefaultBufferSize(width, height)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surface, null, 0)
        c.start()

        extractor = ex
        codec = c
    }

    override fun nextFrame(): FrameData? {
        val c = codec ?: return null
        val ex = extractor ?: return null
        if (finished) return null

        // Feed input until the decoder gives us an output frame. Replay runs as fast
        // as the pipeline can consume, which is the whole point of replay -- a 40 s
        // capture should not take 40 s to re-test.
        var guard = 0
        while (guard++ < MAX_SPIN) {
            if (!inputDone) {
                val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf: ByteBuffer = c.getInputBuffer(inIndex)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            val outIndex = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = bufferInfo.size > 0
                    c.releaseOutputBuffer(outIndex, render)
                    if (render && awaitFrame()) {
                        val st = surfaceTexture!!
                        st.updateTexImage()
                        st.getTransformMatrix(textureTransform)
                        return FrameData(
                            timestampNanos = TimeUnit.MICROSECONDS.toNanos(bufferInfo.presentationTimeUs),
                            textureId = textureId,
                            textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            intrinsics = assumedIntrinsics(),
                            pose = null,                       // vision-only mode
                            trackingState = TrackingState.UNAVAILABLE,
                            points = emptyPoints,
                            pointCount = 0,
                            textureTransform = textureTransform,
                            projection = identityProjection,
                            view = identityView,
                            frameIndex = frameIndex++,
                        )
                    }
                    if (eos) {
                        finished = true
                        return null
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                    surfaceTexture?.setDefaultBufferSize(width, height)
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        finished = true
                        return null
                    }
                }
            }
        }
        return null
    }

    /** Waits for the decoder's frame to actually reach the SurfaceTexture. */
    private fun awaitFrame(): Boolean {
        synchronized(frameLock) {
            var waited = 0L
            while (!frameAvailable && waited < FRAME_WAIT_MS) {
                frameLock.wait(10)
                waited += 10
            }
            val got = frameAvailable
            frameAvailable = false
            return got
        }
    }

    private fun assumedIntrinsics(): Intrinsics {
        val fx = (width * 0.5f) / kotlin.math.tan(Math.toRadians(assumedHorizontalFovDeg / 2.0)).toFloat()
        return Intrinsics(fx, fx, width * 0.5f, height * 0.5f, width, height)
    }

    /** No-op: an MP4 has no AR session whose viewport needs telling. */
    override fun setDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun close() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        extractor?.release()
        extractor = null
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private companion object {
        const val TAG = "VideoFrameSource"
        const val TIMEOUT_US = 5_000L
        const val FRAME_WAIT_MS = 500L
        const val MAX_SPIN = 200
    }
}
