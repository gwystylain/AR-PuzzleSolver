package com.puzzlesolver.app.spot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.spot.ScreenLocator
import com.puzzlesolver.core.puzzle.spot.SpotFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * One round of Spot: six photos taken, then compared.
 *
 * Each photo is checked the moment it is taken -- is the screen in it -- because that is
 * the one moment it can still be retaken: the room moves on to the next picture a few
 * seconds later. A photo without the screen in it is not kept, and the user is told why
 * while the picture is still up.
 *
 * Every round is also written to the phone, under `files/spot/`, as the photos taken and
 * a line per photo of what was found. Not for the user: for the next time a round comes
 * out wrong, so that it can be replayed at a desk (`SpotReplay`) rather than described.
 *
 * [take] asks the camera for its next frame; the pipeline answers on its own thread, and
 * everything heavier than a hand-off runs on one worker thread of its own, so the
 * [ScreenLocator] -- which reuses its buffers -- is only ever used from one thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotController(
    context: Context,
    private val take: ((RgbImage?) -> Unit) -> Unit,
) {
    enum class Phase { TAKING, FINDING, DONE }

    /** One photo's answer, ready to draw. */
    class Shown(
        /** The straightened screen; null when it was not found in this photo. */
        val image: ImageBitmap?,
        val differences: List<SpotFinder.Difference>,
    )

    data class State(
        val phase: Phase = Phase.TAKING,
        /** The screen from each photo kept so far, straightened on its outline. */
        val thumbnails: List<ImageBitmap> = emptyList(),
        /** A photo has been asked for and not yet checked. */
        val busy: Boolean = false,
        /** Why the last photo was not kept, until the next one is. */
        val message: String? = null,
        val results: List<Shown> = emptyList(),
        val status: String = "",
    ) {
        val target: Int get() = SpotFinder.PHOTOS
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val locator = ScreenLocator()
    /** Logs each step of lining the photos up, so a round that comes out wrong says where. */
    private val finder = SpotFinder(log = { Log.i(TAG, it) })

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Main thread only. The worker is handed copies.
    private val photos = ArrayList<RgbImage>()
    private val screens = ArrayList<ScreenLocator.Screen>()
    private var round: File? = null

    /**
     * Bumped by [reset]. Anything that finishes for an earlier round -- a photo still being
     * checked, a comparison still running -- finds it changed and drops its result, so a
     * Reset mid-way is a clean start and not a race.
     */
    private var generation = 0

    fun takePhoto() {
        val s = _state.value
        if (s.phase != Phase.TAKING || s.busy) return
        val gen = generation
        _state.update { it.copy(busy = true, message = null) }
        // A camera that never answers must not leave the shutter dead.
        val watchdog = scope.launch {
            delay(SHOT_TIMEOUT_MS)
            if (gen == generation && _state.value.busy) {
                _state.update { it.copy(busy = false, message = "The camera did not answer; try again") }
            }
        }
        take { frame ->
            scope.launch {
                watchdog.cancel()
                if (gen != generation) return@launch
                if (frame == null) {
                    _state.update { it.copy(busy = false, message = "No camera frame; try again") }
                    return@launch
                }
                keep(frame, gen)
            }
        }
    }

    private suspend fun keep(frame: RgbImage, gen: Int) {
        val checked = withContext(worker) {
            val screen = locator.locate(frame.luma())
            Checked(screen, screen?.let { bitmap(finder.straighten(frame, it, THUMBNAIL_WIDTH)) }, locator.lastFailure)
        }
        if (gen != generation) return
        val screen = checked.screen
        if (screen == null || checked.thumbnail == null) {
            Log.i(TAG, "photo not kept: ${checked.failure}")
            _state.update {
                it.copy(busy = false, message = "Couldn't find the whole screen in that one (${checked.failure}). Take it again.")
            }
            return
        }
        photos.add(frame)
        screens.add(screen)
        val number = photos.size
        save(frame, number)
        Log.i(TAG, "photo $number of ${SpotFinder.PHOTOS} kept")
        _state.update { it.copy(busy = false, thumbnails = it.thumbnails + checked.thumbnail) }
        if (number >= SpotFinder.PHOTOS) find(gen)
    }

    private class Checked(val screen: ScreenLocator.Screen?, val thumbnail: ImageBitmap?, val failure: String)

    private fun find(gen: Int) {
        _state.update { it.copy(phase = Phase.FINDING) }
        val taken = photos.toList()
        val found = screens.toList()
        val dir = round
        scope.launch {
            val shown = withContext(worker) {
                val result = finder.find(taken, found)
                Log.i(TAG, "${result.status} in ${result.millis} ms")
                dir?.let { writeReport(it, result) }
                result to result.photos.map { p -> Shown(p.screen?.let { bitmap(it) }, p.differences) }
            }
            if (gen != generation) return@launch
            _state.update { it.copy(phase = Phase.DONE, results = shown.second, status = shown.first.status) }
        }
    }

    /** Throws the round away and starts a new one, whatever it was doing. */
    fun reset() {
        generation++
        photos.clear()
        screens.clear()
        round = null
        _state.value = State()
        Log.i(TAG, "reset")
    }

    fun release() {
        scope.cancel()
    }

    // --- Keeping the round ----------------------------------------------

    private fun save(frame: RgbImage, number: Int) {
        val dir = round ?: File(app.getExternalFilesDir(null), "spot/" + STAMP.format(Date())).also {
            round = it
        }
        scope.launch(worker) {
            try {
                dir.mkdirs()
                val b = Bitmap.createBitmap(frame.toArgb(), frame.width, frame.height, Bitmap.Config.ARGB_8888)
                File(dir, "$number.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                b.recycle()
            } catch (e: Exception) {
                // Losing the copy costs a replay, never the round.
                Log.w(TAG, "could not save photo $number", e)
            }
        }
    }

    private fun writeReport(dir: File, result: SpotFinder.Result) {
        try {
            dir.mkdirs()
            File(dir, "found.txt").writeText(
                buildString {
                    appendLine("${result.status} in ${result.millis} ms")
                    for (p in result.photos) {
                        append("${p.index + 1}.jpg: ")
                        appendLine(
                            when {
                                p.screen == null -> "screen not found"
                                p.differences.isEmpty() -> "no difference"
                                else -> p.differences.joinToString { d ->
                                    "[%.3f,%.3f - %.3f,%.3f] %.0f".format(Locale.US, d.left, d.top, d.right, d.bottom, d.score)
                                }
                            }
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not write the round's report", e)
        }
    }

    private fun bitmap(image: RgbImage): ImageBitmap =
        Bitmap.createBitmap(image.toArgb(), image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap()

    private companion object {
        const val TAG = "SpotController"

        /** Long enough for a camera mid-retune; short enough that a dead one is noticed. */
        const val SHOT_TIMEOUT_MS = 3_000L

        const val THUMBNAIL_WIDTH = 320
        const val JPEG_QUALITY = 92

        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
