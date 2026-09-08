package com.puzzlesolver.app.record

import android.util.Log
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Records what the gem scanner saw: the frame it read, and the reading it made of it.
 *
 * Gems is the one mode with no debug artifact of its own. Everywhere else the app
 * records an ARCore dataset and dumps the canvas, and neither exists here -- there is no
 * ARCore session and no canvas -- so before this a run that went wrong in the room left
 * nothing behind but a heartbeat line. That is enough to see *that* the wall did not
 * read and never enough to see *why*: the classifier is a vote over hues, and the only
 * way to review a vote is to have the pixels it was cast on.
 *
 * So each captured frame is written twice over.
 *
 * The pixels go out as a **P6 PPM**, which is not an arbitrary choice: it is the format
 * `GemFixture` already loads, so a frame off the real wall drops into `core`'s test
 * resources and becomes a fixture `RealGemFrameTest` can be pointed straight at. A
 * misread in the room turns into a failing unit test on a desk, which is the difference
 * between fixing it and arguing about it.
 *
 * The reading goes out as text alongside -- pitch, exposure, and the three rings of
 * every gem in the frame. That is the ground truth a fix gets diffed against, and it is
 * deliberately laid out in the same shape as `GemFixture.LABELLED`, so a row corrected
 * by eye can be pasted into the fixture rather than retyped.
 *
 * Gzipped because a 1080p PPM is 6 MB and an LED wall is mostly black: it costs one
 * stream wrapper and turns a 75 MB pull into a few megabytes. `gunzip` on the way out,
 * which `tools/collect-session.sh` does.
 */
class GemRecorder(val directory: File) {

    /**
     * Everything worth knowing about one scan, gathered where all of it is in scope.
     *
     * Passed as one object rather than a dozen parameters because the pipeline is the
     * only place that can see the frame, the reading, the targets and the camera at
     * once, and splitting the call would mean the recorder reaching back for half of it.
     */
    class Sample(
        @JvmField val view: CanvasView,
        @JvmField val result: GemScanner.Result,
        @JvmField val targets: List<GemPattern>,
        @JvmField val cameraAsked: String,
        @JvmField val cameraActual: String,
        /** Null when the device has not said yet, which is not the same as a no. */
        @JvmField val cameraHonoured: Boolean?,
        @JvmField val meanLuma: Int,
        @JvmField val droppedFrames: Long,
        @JvmField val scanMillis: Float,
        @JvmField val profile: String,
    )

    /** Frames still to be written in the armed burst. Read by the HUD each frame. */
    @Volatile
    var remaining = 0
        private set

    /** What the last burst did, for the HUD. Null until one has finished. */
    @Volatile
    var lastMessage: String? = null
        private set

    private class Request(val frames: Int, val intervalNanos: Long)

    /**
     * Armed on the UI thread, picked up on the solver thread.
     *
     * A handoff rather than a lock, because the two ends have opposite requirements: a
     * write takes a hundred milliseconds and the tap that starts it must not wait on
     * one. Nothing else here is touched from outside [offer], so this one volatile
     * reference is the whole of the synchronisation.
     */
    @Volatile
    private var pending: Request? = null

    private var intervalNanos = 0L
    private var dueNanos = 0L
    private var burstStartNanos = 0L
    private var fileIndex = 0
    private var written = 0

    /**
     * Arms a burst of [frames], one every [intervalMillis].
     *
     * A burst rather than a single frame because one frame is a coin flip. The phone is
     * panning, the auto-exposure loop may step mid-capture, and the round turns over on
     * its own clock -- so the frame that shows the failure is rarely the frame anyone
     * would have picked. Ten-odd seconds of them covers all three, and the sidecar makes
     * it obvious afterwards which one to keep.
     */
    fun arm(frames: Int, intervalMillis: Long) {
        if (frames <= 0) {
            cancel()
            return
        }
        pending = Request(frames, intervalMillis.coerceAtLeast(0L) * 1_000_000L)
    }

    fun cancel() {
        pending = null
        remaining = 0
    }

    /**
     * Writes this scan if a burst is armed and the next frame is due.
     *
     * Called from the solver thread with the scanner's borrowed view still held, which
     * is the point: the pixels and the reading are then guaranteed to be the same frame.
     * Sampled anywhere else they would be a frame or two apart, and a report whose image
     * and numbers disagree is worse than no report at all.
     *
     * @return true if this sample was written.
     */
    fun offer(sample: Sample): Boolean {
        pending?.let { request ->
            pending = null
            remaining = request.frames
            intervalNanos = request.intervalNanos
            burstStartNanos = System.nanoTime()
            dueNanos = burstStartNanos
            fileIndex = nextFileIndex()
            written = 0
            Log.i(TAG, "gem capture armed: ${request.frames} frames -> ${directory.absolutePath}")
        }
        if (remaining <= 0) return false
        val now = System.nanoTime()
        if (now < dueNanos) return false
        dueNanos = now + intervalNanos
        remaining--
        return write(sample, now)
    }

    private fun write(sample: Sample, nowNanos: Long): Boolean {
        if (!directory.exists() && !directory.mkdirs()) {
            fail("could not create ${directory.absolutePath}")
            return false
        }
        val name = FRAME_PREFIX + "%04d".format(fileIndex) + FRAME_SUFFIX
        val file = File(directory, name)
        try {
            writePpm(sample.view, file)
        } catch (e: Exception) {
            Log.e(TAG, "gem frame write failed", e)
            fail("capture failed: ${e.message}")
            return false
        }
        appendLog(name, sample, nowNanos, file.length())
        fileIndex++
        written++
        if (remaining == 0) {
            lastMessage = "$written frames -> ${directory.absolutePath}"
            Log.i(TAG, "gem capture complete: $lastMessage")
        }
        return true
    }

    /**
     * The frame as P6 PPM, gzipped.
     *
     * Written top-down and not flipped, unlike the canvas dump: this is a camera image
     * in image coordinates, not a bottom-up GL render target. Getting that backwards
     * would produce a dump that looks entirely plausible and puts every gem coordinate
     * in the sidecar on the wrong row.
     *
     * Colour comes through [CanvasView.rgbAt], the same BT.601 the fixture loader
     * inverts on the way back in, so a frame written here and reloaded by `GemFixture`
     * is the view the scanner had to within rounding. A greyscale frame -- one captured
     * before chroma was streaming -- still writes, because "no colour arrived" is a
     * finding and an absent file is not.
     */
    private fun writePpm(view: CanvasView, file: File) {
        val width = view.width
        val height = view.height
        val rgb = FloatArray(3)
        val row = ByteArray(width * 3)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { out ->
            out.write("P6\n$width $height\n255\n".toByteArray(Charsets.US_ASCII))
            for (y in 0 until height) {
                var o = 0
                for (x in 0 until width) {
                    view.rgbAt(x, y, rgb)
                    row[o] = rgb[0].toInt().toByte()
                    row[o + 1] = rgb[1].toInt().toByte()
                    row[o + 2] = rgb[2].toInt().toByte()
                    o += 3
                }
                out.write(row)
            }
        }
    }

    /**
     * The reading, appended to one growing text file.
     *
     * Appended per frame rather than written once at the end, so a capture interrupted
     * by the app dying still leaves everything up to that point -- which is exactly the
     * run most worth having.
     */
    private fun appendLog(name: String, sample: Sample, nowNanos: Long, bytes: Long) {
        val result = sample.result
        val sb = StringBuilder(1024)
        sb.append('\n').append(name)
            .append("  t=").append("%.2f".format((nowNanos - burstStartNanos) / 1e9f)).append('s')
            .append("  ").append(sample.view.width).append('x').append(sample.view.height)
            .append("  ").append(bytes / 1024).append("kB")
        if (!sample.view.hasColour) sb.append("  NO CHROMA -- nothing here can be classified")
        sb.append('\n')

        sb.append("pitch=").append("%.1f".format(result.pitch))
            .append("px washed=").append("%.3f".format(result.washedOut))
            .append(" luma=").append(sample.meanLuma)
            .append(" blobs=").append(result.blobCount)
            .append(" lit=").append(result.gems.size)
            .append(" readable=").append(result.gems.count { it.pattern.isReadable })
            .append(" matched=").append(result.matchCount)
            .append(" dropped=").append(sample.droppedFrames)
            .append('\n')

        sb.append("cam asked='").append(sample.cameraAsked)
            .append("' actual='").append(sample.cameraActual)
            .append("' honoured=").append(sample.cameraHonoured ?: "unknown").append('\n')

        sb.append("scan=").append("%.1f".format(sample.scanMillis)).append("ms  ")
            .append(sample.profile).append('\n')

        sb.append("targets=")
        sample.targets.forEachIndexed { i, target ->
            sb.append(i + 1).append(':')
                .append(if (target.isBlank) "-" else target.toString()).append(' ')
        }
        sb.append('\n')
        sb.append("status='").append(result.status).append("'\n")

        // Deliberately the shape of GemFixture.LABELLED: once the image has been over a
        // zoom and the true rings are known, a corrected row goes into the fixture as it
        // stands instead of being retyped, and the classifier's answer sits beside the
        // eye's in the same units.
        sb.append("       x        y   radius  outer   middle  centre   conf slot\n")
        for (gem in result.gems) {
            sb.append(
                "%8.1f %8.1f %8.1f  %-7s %-7s %-7s %5.2f %4d\n".format(
                    gem.x, gem.y, gem.radius,
                    GemColour.name(gem.pattern.outer),
                    GemColour.name(gem.pattern.middle),
                    GemColour.name(gem.pattern.centre),
                    gem.confidence,
                    gem.matchedSlot,
                )
            )
        }

        try {
            FileOutputStream(File(directory, LOG_NAME), true).use {
                it.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "gem log append failed", e)
        }
    }

    private fun fail(message: String) {
        lastMessage = message
        remaining = 0
    }

    /**
     * Continues the numbering rather than restarting it, so a second burst never
     * overwrites the first. Same reasoning as [SessionStore]: a counter sorts correctly
     * in every tool and carries no timezone surprises.
     */
    private fun nextFileIndex(): Int {
        val existing = directory.listFiles { f ->
            f.isFile && f.name.startsWith(FRAME_PREFIX) && f.name.endsWith(FRAME_SUFFIX)
        }?.size ?: 0
        var index = existing + 1
        while (File(directory, FRAME_PREFIX + "%04d".format(index) + FRAME_SUFFIX).exists()) {
            index++
        }
        return index
    }

    private companion object {
        const val TAG = "GemRecorder"
        const val FRAME_PREFIX = "gem-"
        const val FRAME_SUFFIX = ".ppm.gz"
        const val LOG_NAME = "gems-log.txt"
    }
}
