package com.puzzlesolver.app.record

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Where scan recordings live.
 *
 * App-private external files, so recordings survive a reinstall-free debug cycle
 * and can be pulled off with `adb pull` without any permission dance:
 *
 *   adb shell ls /sdcard/Android/data/com.puzzlesolver.app/files/sessions
 *   adb pull /sdcard/Android/data/com.puzzlesolver.app/files/sessions
 *
 * Nothing here is user-facing content, so it stays out of MediaStore.
 */
class SessionStore(private val context: Context) {

    private val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply {
            if (!exists()) mkdirs()
        }

    /**
     * A fresh destination for an ARCore recording.
     *
     * Named by monotonic counter rather than timestamp: the point of these files is
     * ordering and reproducibility during a debug session, and a counter sorts
     * correctly in every tool without timezone surprises.
     */
    fun newRecordingUri(): Uri {
        val existing = directory.listFiles { f -> f.name.startsWith(PREFIX) }?.size ?: 0
        var index = existing + 1
        var file = File(directory, "$PREFIX%04d.mp4".format(index))
        while (file.exists()) {
            index++
            file = File(directory, "$PREFIX%04d.mp4".format(index))
        }
        return Uri.fromFile(file)
    }

    fun recordings(): List<Uri> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Uri.fromFile(it) }
            ?: emptyList()

    fun mostRecentRecording(): Uri? = recordings().firstOrNull()

    private companion object {
        const val PREFIX = "scan-"
    }
}
