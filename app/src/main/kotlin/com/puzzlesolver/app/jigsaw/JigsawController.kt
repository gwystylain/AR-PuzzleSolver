package com.puzzlesolver.app.jigsaw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.jigsaw.FloorColour
import com.puzzlesolver.core.puzzle.jigsaw.FloorReader
import com.puzzlesolver.core.puzzle.jigsaw.FloorRegions
import com.puzzlesolver.spell.JigsawBoard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jigsaw's photos: taken with the phone's own camera app, read into a grid here, and handed
 * to the editor as a [Draft] for the player to check before it goes to the lobby.
 *
 * **The phone's camera app, not this one's.** The floor is lit tiles in a dark room, and the
 * camera app's processing -- several exposures merged -- keeps their colours apart where a
 * single exposure clips half of them to white; its wide lens takes in a whole end of the
 * room; and it needs nothing from this app's camera pipeline, which stays off while the
 * lobby is up. A photo already taken can be picked from the gallery instead.
 *
 * **Every photo is kept** under `files/jigsaw/` on the phone, with a `.txt` of what was read
 * from it, so a round read wrong can be replayed at a desk rather than described.
 *
 * The photo itself never leaves the phone; only the grid read from it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JigsawController(context: Context) {

    /** One lit cell of a read, where it is in the photo, for drawing over it. */
    class Mark(
        /** Its four corners in the photo's pixels, x then y. */
        val corners: FloatArray,
        val colour: FloorColour,
        /** Part of what was kept: a shape in the box, or a piece outside it. */
        val kept: Boolean,
    )

    /** A region read or being drawn, for the player to check and send. */
    class Draft(
        val id: Long,
        val region: Int,
        /** The photo, upright; null when drawing by hand. */
        val photo: ImageBitmap?,
        val marks: List<Mark>,
        /** The grid as read, or as the lobby has it: rows of colour codes and '.'. */
        val rows: List<String>,
        /** What to tell the player about the read: why it failed, or how the photo was cut. */
        val message: String?,
    )

    data class State(
        /** A photo is being read, for this region. */
        val reading: Int? = null,
        val draft: Draft? = null,
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jigsaw.photos", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val reader = FloorReader(log = { Log.i(TAG, it) })
    private var drafts = 0L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Where the camera app should put the photo for [region]: a new file, handed over as a
     * content address. Remembered across the app being killed while the camera is up.
     */
    fun photoTarget(region: Int): Uri {
        val dir = File(app.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val file = File(dir, "${STAMP.format(Date())}-${NAMES[region]}.jpg")
        prefs.edit().putInt(KEY_REGION, region).putString(KEY_FILE, file.path).apply()
        return FileProvider.getUriForFile(app, "${app.packageName}.jigsaw", file)
    }

    /** Which region a photo picked from the gallery is for, until it comes back. */
    fun picking(region: Int) {
        prefs.edit().putInt(KEY_REGION, region).remove(KEY_FILE).apply()
    }

    /** The camera app has come back; [taken] is whether it saved a photo. */
    fun photoTaken(taken: Boolean) {
        val region = prefs.getInt(KEY_REGION, -1)
        val file = prefs.getString(KEY_FILE, null)?.let { File(it) }
        prefs.edit().remove(KEY_FILE).apply()
        if (region !in 0 until JigsawBoard.REGIONS || file == null) return
        if (!taken || !file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        read(region, Uri.fromFile(file), keep = null)
    }

    /** A photo picked from the gallery, for the region last asked for. */
    fun photoPicked(uri: Uri) {
        val region = prefs.getInt(KEY_REGION, -1)
        if (region !in 0 until JigsawBoard.REGIONS) return
        val dir = File(app.getExternalFilesDir(null), DIR)
        read(region, uri, keep = File(dir, "${STAMP.format(Date())}-${NAMES[region]}-picked.jpg"))
    }

    /** Opens the editor on [current] -- the lobby's grid for [region], or none -- without a photo. */
    fun draw(region: Int, current: String?) {
        _state.update {
            it.copy(draft = Draft(++drafts, region, null, emptyList(), current?.let(JigsawBoard::rows).orEmpty(), null))
        }
    }

    fun closeDraft() {
        _state.update { it.copy(draft = null) }
    }

    fun release() {
        scope.cancel()
    }

    private fun read(region: Int, uri: Uri, keep: File?) {
        _state.update { it.copy(reading = region) }
        scope.launch {
            val draft = withContext(worker) {
                try {
                    readPhoto(region, uri, keep)
                } catch (e: Exception) {
                    Log.w(TAG, "could not read the photo", e)
                    Draft(++drafts, region, null, emptyList(), emptyList(), "Couldn't open that photo (${e.javaClass.simpleName}). Draw it instead, or take another.")
                }
            }
            _state.update { State(reading = null, draft = draft) }
        }
    }

    private fun readPhoto(region: Int, uri: Uri, keep: File?): Draft {
        val bitmap = decode(uri)
        val image = RgbImage(bitmap.width, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for ((i, p) in pixels.withIndex()) {
            image.data[i * 3] = (p shr 16).toByte()
            image.data[i * 3 + 1] = (p shr 8).toByte()
            image.data[i * 3 + 2] = p.toByte()
        }
        val read = reader.read(image)
        val cut = if (read.failure != null) null else if (region == JigsawBoard.BOX) FloorRegions.box(read) else FloorRegions.pieces(read)
        val marks = if (cut == null) emptyList() else marks(read, cut)
        val message = read.failure?.let { "Couldn't read the floor: $it. Take it again from higher up, or draw it." }
            ?: cut?.note
            ?: if (cut?.rows.isNullOrEmpty()) "Nothing lit was read in that photo." else null
        Log.i(TAG, "${NAMES[region]}: ${cut?.rows?.joinToString("/") ?: read.failure}")
        keep?.let { save(uri, it) }
        report(keep ?: (uri.path?.let { File(it) }), read, cut)
        return Draft(++drafts, region, bitmap.asImageBitmap(), marks, cut?.rows.orEmpty(), message)
    }

    /** The photo, upright, at about two megapixels: enough for a tile twenty pixels across at the far end. */
    private fun decode(uri: Uri): Bitmap {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        require(long > 0) { "not an image" }
        var sample = 1
        while (long / (sample * 2) >= TARGET_LONG_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("not an image")
        val orientation = try {
            resolver.openInputStream(uri).use { ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(270f); m.postScale(-1f, 1f) }
            else -> return decoded
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    private fun marks(read: FloorReader.Read, cut: FloorRegions.Region): List<Mark> {
        val toImage = read.imageFromGrid ?: return emptyList()
        val dropped = cut.dropped.toHashSet()
        val p = DoubleArray(2)
        val out = ArrayList<Mark>()
        for (row in 0 until read.height) {
            for (col in 0 until read.width) {
                val colour = read.colour(col, row) ?: continue
                val corners = FloatArray(8)
                for ((k, d) in CORNERS.withIndex()) {
                    toImage.map(col + d.first, row + d.second, p)
                    corners[k * 2] = p[0].toFloat()
                    corners[k * 2 + 1] = p[1].toFloat()
                }
                out += Mark(corners, colour, kept = (col to row) !in dropped)
            }
        }
        return out
    }

    private fun save(uri: Uri, file: File) {
        try {
            file.parentFile?.mkdirs()
            app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            // Losing the copy costs a replay, never the read.
            Log.w(TAG, "could not keep the photo", e)
        }
    }

    private fun report(photo: File?, read: FloorReader.Read, cut: FloorRegions.Region?) {
        if (photo == null) return
        try {
            File(photo.parentFile, photo.nameWithoutExtension + ".txt").writeText(
                buildString {
                    appendLine(read.failure?.let { "failed: $it" } ?: "${read.width}x${read.height} grid from ${read.tiles} tiles")
                    for (r in 0 until read.height) {
                        appendLine(String(CharArray(read.width) { c -> read.colour(c, r)?.code ?: '.' }))
                    }
                    if (cut != null) {
                        appendLine("kept:")
                        cut.rows.forEach { appendLine(it) }
                        cut.note?.let { appendLine("note: $it") }
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not write the read", e)
        }
    }

    private companion object {
        const val TAG = "Jigsaw"
        const val DIR = "jigsaw"
        const val KEY_REGION = "pending.region"
        const val KEY_FILE = "pending.file"
        const val TARGET_LONG_SIDE = 1600
        val NAMES = listOf("box", "left", "right")
        val CORNERS = listOf(-0.5 to -0.5, 0.5 to -0.5, 0.5 to 0.5, -0.5 to 0.5)
        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
