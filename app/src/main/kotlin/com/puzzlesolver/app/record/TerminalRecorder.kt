package com.puzzlesolver.app.record

import android.util.Log
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Records what the terminal reader saw: the frame it read, and the reading it made of it.
 *
 * This exists because of one visit to the room. The heartbeat reported that a quarter of
 * the lit displays were found and not read, steadily, all evening — and that is the whole
 * of what came back. Which displays, and how close they came to being read, are the two
 * questions that decide what to fix, and neither can be answered by a count. Working out
 * that the cause was motion blur took reproducing the failure against an old clip and a
 * simulated smear, which is a great deal of work to arrive at a guess that pixels would
 * have settled in a minute.
 *
 * [GemRecorder] makes the same argument for the same reason, and this is deliberately its
 * twin. Two differences, both because this reader is a different shape:
 *
 * - The frame goes out as a **P5 PGM**, not a PPM. Terminal reads luma and nothing else,
 *   so the greyscale frame *is* the reader's input rather than a rendering of it — and
 *   PGM is the format `TerminalWallTest` and `TerminalReplay` already load. A wall that
 *   misread in the room becomes a failing unit test on a desk, or a whole run replayed
 *   frame by frame offline, without converting anything.
 * - The sidecar is one row per display: where it is, what came back, and how confident
 *   the classifier was. That last column is the one the device log cannot carry and the
 *   one that separates "the threshold is in the wrong place" from "the glyph was never
 *   there".
 *
 * Gzipped, like the gem frames: a 1080p PGM is 2 MB and a dark wall compresses hard.
 */
class TerminalRecorder(val directory: File) {

    /**
     * Everything worth knowing about one scan, gathered where all of it is in scope.
     *
     * The luma image rather than a `CanvasView`, because that is what the scanner was
     * handed and what the file will hold. Passing the view instead would invite writing a
     * colour frame that no part of this mode has ever looked at.
     */
    class Sample(
        @JvmField val luma: GrayImage,
        @JvmField val result: TerminalScanner.Result,
        @JvmField val cameraAsked: String,
        @JvmField val cameraActual: String,
        /** Null when the device has not said yet, which is not the same as a no. */
        @JvmField val cameraHonoured: Boolean?,
        @JvmField val meanLuma: Int,
        @JvmField val droppedFrames: Long,
        @JvmField val scanMillis: Float,
        @JvmField val profile: String,
        /** The detector's own account of what it kept and what it threw away. */
        @JvmField val detectorReport: String,
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
     * Armed on the UI thread, picked up on the solver thread. A handoff rather than a
     * lock: a write takes a hundred milliseconds and the tap that starts it must not wait
     * on one.
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
     * A burst rather than a single frame, and here the reason is sharper than it is for
     * gems: the failure being chased is *transient*. A display reads on one frame and not
     * the next, so a single capture is as likely to record the wall working as the wall
     * failing. A run of them either way is the evidence.
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
     * Called from the solver thread with the scanner's borrowed view still held, which is
     * the point: the pixels and the reading are then guaranteed to be the same frame.
     * Sampled anywhere else they would be a frame or two apart, and on a wall whose whole
     * problem is that a display reads intermittently, an image and a table that disagree
     * would be worse than nothing.
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
            Log.i(TAG, "terminal capture armed: ${request.frames} frames -> ${directory.absolutePath}")
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
            writePgm(sample.luma, file)
        } catch (e: Exception) {
            Log.e(TAG, "terminal frame write failed", e)
            fail("capture failed: ${e.message}")
            return false
        }
        appendLog(name, sample, nowNanos, file.length())
        fileIndex++
        written++
        if (remaining == 0) {
            lastMessage = "$written frames -> ${directory.absolutePath}"
            Log.i(TAG, "terminal capture complete: $lastMessage")
        }
        return true
    }

    /**
     * The frame as P5 PGM, gzipped.
     *
     * Top-down and unflipped: this is a camera image in image coordinates, not a
     * bottom-up GL render target like the canvas dump. Getting that backwards would give
     * a picture that looks entirely plausible and put every box in the sidecar on the
     * wrong row.
     */
    private fun writePgm(luma: GrayImage, file: File) {
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { out ->
            out.write("P5\n${luma.width} ${luma.height}\n255\n".toByteArray(Charsets.US_ASCII))
            out.write(luma.data, 0, luma.width * luma.height)
        }
    }

    /**
     * The reading, appended to one growing text file.
     *
     * Appended per frame rather than written at the end, so a capture interrupted by the
     * app dying still leaves everything up to that point — which is exactly the run most
     * worth having.
     */
    private fun appendLog(name: String, sample: Sample, nowNanos: Long, bytes: Long) {
        val r = sample.result
        val sb = StringBuilder(2048)
        sb.append('\n').append(name)
            .append("  t=").append("%.2f".format((nowNanos - burstStartNanos) / 1e9f)).append('s')
            .append("  ").append(sample.luma.width).append('x').append(sample.luma.height)
            .append("  ").append(bytes / 1024).append("kB\n")

        val cleared = r.displays.count { it.cleared }
        sb.append("displays=").append(r.displays.size)
            .append(" numbers=").append(r.remaining)
            .append(" unread=").append(r.unread)
            .append(" cleared=").append(cleared)
            .append(" settled=").append(r.settled)
            .append(" luma=").append(sample.meanLuma)
            .append(" dropped=").append(sample.droppedFrames)
            .append('\n')

        sb.append("cam asked='").append(sample.cameraAsked)
            .append("' actual='").append(sample.cameraActual)
            .append("' honoured=").append(sample.cameraHonoured ?: "unknown").append('\n')

        sb.append("scan=").append("%.1f".format(sample.scanMillis)).append("ms  ")
            .append(sample.profile).append('\n')
        sb.append("detector: ").append(sample.detectorReport).append('\n')
        sb.append("status='").append(r.status).append("'\n")

        // One row per display, in reading order. The `read` column is what came back --
        // digits, question marks for a glyph that failed the floor, dashes for a cleared
        // tile -- and `conf` is how close the worst of the three came. Those two together
        // are the whole diagnosis: `0?4 conf=0.00` is a threshold question and `0-4` is a
        // segmentation one, and the device log renders both as "one not legible".
        sb.append("      x      y      w      h  read  conf  rank\n")
        for (d in r.displays.sortedWith(compareBy({ it.box.y / 40 }, { it.box.x }))) {
            sb.append(
                "%7d %6d %6d %6d  %-4s %5.2f %5s\n".format(
                    d.box.x, d.box.y, d.box.width, d.box.height,
                    d.text, d.confidence,
                    when (d.rank) {
                        0 -> "GREEN"
                        1 -> "YELLOW"
                        else -> "-"
                    },
                )
            )
        }

        try {
            FileOutputStream(File(directory, LOG_NAME), true).use {
                it.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "terminal log append failed", e)
        }
    }

    private fun fail(message: String) {
        lastMessage = message
        remaining = 0
    }

    /** Continues the numbering, so a second burst never overwrites the first. */
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
        const val TAG = "TerminalRecorder"
        const val FRAME_PREFIX = "term-"
        const val FRAME_SUFFIX = ".pgm.gz"
        const val LOG_NAME = "terminal-log.txt"
    }
}
