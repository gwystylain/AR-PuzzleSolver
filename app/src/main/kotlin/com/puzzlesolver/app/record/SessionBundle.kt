package com.puzzlesolver.app.record

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `tools/collect-session.sh`, but it runs on the phone.
 *
 * That script gathers the run into one folder over adb, which assumes a cable and a
 * laptop. Neither is in the room. This does the same gathering into one zip that the
 * share sheet can hand to mail, Drive, or Files -- so the evidence can leave the phone
 * on the phone's own terms, minutes after the run rather than whenever the two devices
 * are next in the same place.
 *
 * Two directories are deliberately left out.
 *
 * **`sessions/`** holds ARCore recordings at roughly 100 MB a minute. They are the best
 * artifact Mines has and the worst possible thing to attach to an email; they stay
 * behind for `adb pull`, and the count of what was skipped is reported so their absence
 * is a decision the reader can see rather than a hole they have to notice.
 *
 * **`exports/`** is this. Zipping previous zips into the next one doubles the bundle
 * every time it is pressed.
 */
object SessionBundle {

    class Result(
        val file: File,
        val entries: Int,
        val bytes: Long,
        /** Files left out because they are too large to share this way. */
        val skipped: Int,
    ) {
        /** One line for a toast, since this is the confirmation the room gets. */
        fun describe(): String {
            val size = if (bytes > 1024 * 1024) {
                "%.1f MB".format(bytes / 1024f / 1024f)
            } else {
                "${bytes / 1024} kB"
            }
            val tail = if (skipped > 0) ", $skipped AR recording(s) left for adb" else ""
            return "$entries files, $size$tail"
        }
    }

    private val EXCLUDED = setOf("exports", "sessions")

    fun write(root: File, destination: File): Result {
        destination.parentFile?.mkdirs()
        var entries = 0
        var skipped = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            // The frames are already gzipped and the logs are text, so this is spent
            // almost entirely on the logs, where it earns its keep several times over.
            zip.setLevel(Deflater.BEST_COMPRESSION)
            val buffer = ByteArray(1 shl 15)
            for (file in root.walkTopDown()) {
                if (file.isDirectory) continue
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val top = relative.substringBefore('/')
                if (top in EXCLUDED) {
                    if (top == "sessions") skipped++
                    continue
                }
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
                entries++
            }
        }
        val result = Result(destination, entries, destination.length(), skipped)
        Log.i(TAG, "bundle: ${result.describe()} -> ${destination.absolutePath}")
        return result
    }

    /** Counter-named, like everything else written here, and for the same reason. */
    fun nextDestination(exports: File): File {
        if (!exports.exists()) exports.mkdirs()
        val existing = exports.listFiles { f -> f.name.endsWith(".zip") }?.size ?: 0
        var index = existing + 1
        var file = File(exports, "puzzlesolver-%04d.zip".format(index))
        while (file.exists()) {
            index++
            file = File(exports, "puzzlesolver-%04d.zip".format(index))
        }
        return file
    }

    private const val TAG = "SessionBundle"
}
