package com.puzzlesolver.app.frame

import com.puzzlesolver.core.math.Intrinsics
import com.puzzlesolver.core.math.Pose

/**
 * One frame, as everything downstream sees it.
 *
 * The camera image itself is *not* here. It stays on the GPU as an external
 * texture, because copying a 1920x1440 image to the CPU every frame would cost
 * more than the rest of the pipeline combined. [textureId] identifies it; the
 * accumulator samples it directly.
 */
data class FrameData(
    val timestampNanos: Long,
    val textureId: Int,
    val textureTarget: Int,
    val intrinsics: Intrinsics,
    /** Camera pose in world space, or null when the source cannot supply one. */
    val pose: Pose?,
    val trackingState: TrackingState,
    /**
     * Sparse world points believed to lie on surfaces, as flat xyz triples. Fed to
     * the wall fitter. Empty when the source has no depth or feature data.
     */
    val points: FloatArray,
    val pointCount: Int,
    /**
     * Texture coordinates for the four NDC corners of the display quad, as supplied
     * by ARCore. Eight floats, not a matrix -- the mapping is affine and the
     * accumulator derives what it needs from three corners.
     */
    val textureTransform: FloatArray,
    /**
     * Column-major GL projection matrix for this frame.
     *
     * Carried on the frame rather than fetched from the session on demand: ARCore's
     * `Session.update()` advances the frame, so asking it again mid-draw would
     * silently consume a second frame and halve the effective capture rate.
     */
    val projection: FloatArray,
    /** Column-major GL view matrix for this frame. */
    val view: FloatArray,
    val frameIndex: Long,
)

enum class TrackingState {
    /** Pose is trustworthy; accumulate. */
    TRACKING,

    /** Moving too fast, too dark, or still initialising. Frames are dropped. */
    PAUSED,

    /** No pose at all. Vision-only sources sit here permanently. */
    UNAVAILABLE,
}

/**
 * Where frames come from.
 *
 * The three implementations -- live ARCore, ARCore dataset playback, and plain
 * video -- exist specifically so that the identical processing code runs against
 * a recording. A bug seen on the wall can be recorded once and then replayed as
 * many times as it takes, with a debugger attached and no need to stand in the
 * right room.
 */
interface FrameSource {

    val kind: Kind

    enum class Kind {
        /** Live camera through ARCore. */
        LIVE,

        /**
         * An ARCore recording. Poses, IMU and intrinsics are replayed exactly as
         * captured, so results are bit-comparable with the live run.
         */
        AR_DATASET,

        /**
         * An arbitrary MP4 with no AR data. Degraded: no world pose, so the wall is
         * tracked visually and results are indicative rather than exact. Useful for
         * testing detection and solving against footage you did not record yourself.
         */
        PLAIN_VIDEO,
    }

    /** Must be called on the GL thread; the source needs a texture to render into. */
    fun attachTexture(textureId: Int)

    /**
     * Tells the source the viewport it is rendering into, and the display rotation.
     *
     * Mandatory for ARCore, not optional: it derives both the camera-texture coordinate
     * transform and the projection matrix from this. Skip it and ARCore logs
     * "Display geometry has an invalid width: 0" and hands back a transform that
     * silently misaligns the background and makes the accumulator sample the wrong
     * texels -- which looks like a geometry bug anywhere but here.
     *
     * @param rotation one of the `Surface.ROTATION_*` constants
     */
    fun setDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int)

    /**
     * Advances by one frame. Blocking is fine for replay sources -- the pipeline
     * runs replay as fast as processing allows rather than in real time, which is
     * the point of replay.
     *
     * @return null at end of stream.
     */
    fun nextFrame(): FrameData?

    /** True once a replay source has run out. Always false for [Kind.LIVE]. */
    val isFinished: Boolean

    /** Total frames, or -1 when unknown (always -1 for live). */
    val frameCount: Long

    /**
     * Short human-readable summary of how the source is configured, for the heartbeat
     * log. Carried here rather than logged at construction time because startup logs are
     * easily lost in ARCore's very chatty native output, and because this way the
     * configuration is visible on every heartbeat line rather than once at t=0.
     */
    val diagnostics: String get() = ""

    /**
     * Camera dials, for sources that own a camera. Null for every replay source, where
     * exposure is a property of the recording and cannot be changed after the fact.
     */
    val tuning: CameraTuning? get() = null

    /** One line on how the camera is doing, or null when this source has no camera. */
    val cameraStatus: String? get() = null

    /**
     * True when this source took the camera over and then lost it.
     *
     * Distinct from "no camera control", and much worse: a session built to share the
     * camera cannot fall back to letting ARCore open one, so a source in this state will
     * never produce another frame. The activity watches for it and rebuilds the source
     * without sharing, which costs the exposure dial and keeps the app.
     */
    val cameraFailed: Boolean get() = false

    /**
     * Applies any camera change the UI or the auto-tuner has asked for.
     *
     * Called from the frame loop rather than from whatever thread made the request,
     * because ARCore forbids re-issuing a capture request while it is active: applying
     * one means pausing ARCore, re-issuing, and resuming, and that belongs on the
     * source's own thread.
     *
     * @return true when a change was applied, which the pipeline treats as a reason to
     *         throw the mosaic away. Not damage control: a canvas holding texels from
     *         two exposures has seams the colour classifiers read as real.
     */
    fun applyPendingTuning(): Boolean = false

    fun pause()
    fun resume()
    fun close()
}
