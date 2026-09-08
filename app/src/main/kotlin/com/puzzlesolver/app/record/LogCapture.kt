package com.puzzlesolver.app.record

import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * The app's own logcat, streamed to a file on the phone.
 *
 * Everything the heartbeat says was already being written -- to a ring buffer that a
 * laptop reads over adb. That is the wrong place for it. The trip to the room is made
 * with a phone and nothing else, the buffer is shared with every other process on the
 * device and wraps without warning, and by the time the phone is next plugged in the
 * only part of the run still in it may be the part after the interesting bit.
 *
 * So this spawns `logcat` as a child process and copies its output to a file. An app may
 * always read its own log -- `READ_LOGS` governs reading *other* processes' entries --
 * so `--pid` on our own pid needs no permission and picks up nothing that is not ours.
 *
 * Two properties of that come free and are worth knowing, because they change what the
 * button has to be pressed *for*:
 *
 * - **It starts with history.** `logcat` prints what is already buffered for the pid
 *   before it begins following, so pressing Log ten minutes in still captures those ten
 *   minutes. Forgetting to press it first is not the failure it looks like.
 * - **It catches the crash.** An uncaught exception is logged by the runtime under this
 *   process's pid, so `AndroidRuntime` and the stack trace land in the file like
 *   anything else, without an exception handler of our own.
 */
class LogCapture(private val directory: File) {

    @Volatile
    var isRunning = false
        private set

    /** The file being written, or the last one written. Null before the first start. */
    @Volatile
    var file: File? = null
        private set

    @Volatile
    var bytes = 0L
        private set

    /** Why the last start failed, or null. Shown rather than swallowed. */
    @Volatile
    var failure: String? = null
        private set

    private var process: Process? = null
    private var startedAt = 0L

    /**
     * Set while [stop] is deliberately tearing the child down.
     *
     * Destroying the process closes the stream a blocked `read` is sitting in, which
     * throws. That is the normal way this ends, and logging it as a warning with a stack
     * trace -- which it did -- puts `InterruptedIOException` in the artifact every time
     * anyone presses Stop or Export. A log whose own shutdown looks like a failure costs
     * a reader real time on the one file they came to trust.
     */
    @Volatile
    private var stopping = false

    /**
     * One line for the button: how long it has been running and how much it has.
     *
     * Both, because either alone has a plausible failure that looks like success. A
     * timer that climbs while the byte count does not means the child died and nothing
     * is being written; bytes with no time means it was never started.
     */
    fun describe(): String {
        if (!isRunning) return failure ?: file?.let { "log: ${it.name}, ${bytes / 1024} kB" } ?: ""
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        return "logging %d:%02d  ·  %d kB".format(seconds / 60, seconds % 60, bytes / 1024)
    }

    /**
     * Starts a new log file, with [header] written into it first.
     *
     * The header is what `device.txt` was for: a log that does not say which device,
     * build and settings produced it invites the wrong conclusion twice over. It is
     * passed in rather than gathered here so this class stays free of Android context.
     */
    fun start(header: List<String>): Boolean {
        if (isRunning) return true
        failure = null
        stopping = false
        if (!directory.exists() && !directory.mkdirs()) {
            failure = "log failed: cannot create ${directory.name}/"
            return false
        }
        val target = File(directory, nextName())
        val child = try {
            ProcessBuilder(command())
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "could not start logcat", e)
            failure = "log failed: ${e.message}"
            return false
        }

        file = target
        bytes = 0L
        startedAt = SystemClock.elapsedRealtime()
        process = child
        isRunning = true

        // A daemon thread: the copy must not be what keeps the process alive at exit,
        // and there is nothing here worth waiting on during a shutdown.
        Thread({ pump(child, target, header) }, "log-capture").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "log capture started -> ${target.absolutePath}")
        return true
    }

    fun stop() {
        val child = process ?: return
        stopping = true
        process = null
        isRunning = false
        child.destroy()
        Log.i(TAG, "log capture stopped: ${file?.name}, $bytes bytes")
    }

    /**
     * Our own tags in full, plus errors from everything else.
     *
     * Measured on a CPH2655 rather than guessed at, and the guess would have been wrong
     * about which way: an unfiltered capture of eighteen seconds came to 2606 lines, of
     * which **sixteen** were this app's. The rest was ART talking to itself about JNI
     * references and missing `classes.dex` entries, at a rate that would bury a
     * twenty-minute run in fifteen megabytes of nothing. A log nobody can find the
     * heartbeat in is not much better than no log.
     *
     * Filtering by tag and not by `--pid` alone is therefore the whole point, but the
     * `*:E` on the end is what makes it safe: every unlisted tag still reports at error
     * and above, so an uncaught exception -- logged by the runtime as `AndroidRuntime`
     * at E and F -- lands in the file exactly as before. What is dropped is warnings and
     * below from code that is not ours, which is the noise and only the noise.
     */
    private fun command(): List<String> = buildList {
        add("logcat")
        add("-v")
        add("time")
        add("--pid=" + AndroidProcess.myPid())
        APP_TAGS.forEach { add("$it:I") }
        add("*:E")
    }

    private fun pump(child: Process, target: File, header: List<String>) {
        try {
            BufferedOutputStream(FileOutputStream(target), 1 shl 15).use { out ->
                writeHeader(out, header)
                val buffer = ByteArray(1 shl 14)
                val input = child.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    bytes += n
                    // Flushed per read rather than left to the buffer, because the run
                    // that matters most is the one that ends in a crash or a battery
                    // pull, and a buffered tail is exactly the part that would be lost.
                    out.flush()
                    if (bytes > MAX_BYTES) {
                        out.write("\n--- log capture stopped at $MAX_BYTES bytes ---\n".toByteArray())
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (stopping) Log.i(TAG, "log capture closed") else Log.w(TAG, "log capture ended", e)
        } finally {
            child.destroy()
            isRunning = false
            stopping = false
        }
    }

    private fun writeHeader(out: OutputStream, header: List<String>) {
        val text = buildString {
            append("# puzzlesolver log capture\n")
            header.forEach { append("# ").append(it).append('\n') }
            append("#\n# logcat below is this process only, and begins with whatever was\n")
            append("# already buffered -- so it reaches back before the button was pressed.\n\n")
        }
        val raw = text.toByteArray()
        out.write(raw)
        bytes += raw.size
    }

    /** Counter-named, like [SessionStore]: sorts correctly everywhere, no timezones. */
    private fun nextName(): String {
        val existing = directory.listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX)
        }?.size ?: 0
        var index = existing + 1
        while (File(directory, PREFIX + "%04d".format(index) + SUFFIX).exists()) index++
        return PREFIX + "%04d".format(index) + SUFFIX
    }

    private companion object {
        const val TAG = "LogCapture"

        /**
         * Every tag this app logs under. Kept as a list rather than derived, because
         * there is nothing to derive it from -- and a tag missing from here goes missing
         * from the log silently, so it is worth grepping `const val TAG` after adding a
         * class that logs.
         */
        val APP_TAGS = listOf(
            "ScanPipeline",
            "MainActivity",
            "LogCapture",
            "GemRecorder",
            "SessionBundle",
            "SharedCamera",
            "AutoExposure",
            "Camera2Source",
            "CameraProbe",
            "ArCoreFrameSource",
            "VideoFrameSource",
            "WallTracker",
            "GlUtil",
        )
        const val PREFIX = "run-"
        const val SUFFIX = ".txt"

        /**
         * Unreachable in practice -- the heartbeat is a few hundred bytes every two
         * seconds -- and here only so that a component stuck in a log loop cannot fill
         * the phone during a trip where nobody is watching storage.
         */
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
