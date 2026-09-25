package com.puzzlesolver.core.puzzle.terminal

import com.puzzlesolver.core.image.GrayImage
import kotlin.math.ln
import kotlin.math.max

/**
 * Meters the exposure on the displays themselves, and says how far off it is in stops.
 *
 * The camera's own metering averages the whole frame, and a terminal frame is a dark room
 * with a wall of light in it -- so it opens up until the room is grey, and the displays
 * are two stops over by then. Both captures from the room show it: half of every
 * display's area clipped to white, digits bloomed until their counters were a few pixels
 * across. The reference clip, which reads perfectly, has none of its display area
 * clipped. Only the reader knows where the displays are, so only the reader can meter
 * them.
 *
 * **The window is wide, and this aims at its middle.** Re-exposing the reference clip in
 * linear light and reading it again, every display reads from two stops under to two
 * over, bar two of 360 left unread at the top. Past that it goes quickly: detection
 * starts to fail at three under, and at two and a half over a quarter of the wall is
 * unread, at three over most of it. The clip as shot sits in the middle, so its
 * exposure is the target. The two captures from the room meter at 1.1 and 1.8 stops
 * over it -- towards the bright edge, and the re-exposed clip has no glare or halo in it,
 * so the real edge is nearer than that. That is where the misreads were.
 *
 * Two numbers per display, then the median of each across the wall, so that one display
 * with glare on it or a far-end panel in shadow does not decide the exposure:
 *
 *  - **[Reading.bright]**, the 90th percentile -- the digits and the tile borders. When it
 *    is under white it is the exact answer: the stops between it and the clip's value.
 *  - **[Reading.face]**, the 25th percentile -- the tile face. Once the digits clip, the
 *    brightest tenth no longer says *how far* over, but the face is still on the scale
 *    for about three stops more, and it is what the estimate falls back to.
 *
 * Stops are counted on a 2.2 gamma, which is only roughly the phone's own tone curve.
 * That is enough: the controller re-meters after every change, so a curve that is a
 * little off costs a second, smaller correction rather than a wrong exposure.
 *
 * Only lit displays are metered. A cleared tile has no digit on it, so it has less bright
 * area, and a wall that is half cleared would otherwise read as under-exposed and be
 * brightened back into the bloom by the end of every round.
 */
class DisplayMeter {

    /** One frame's metering. */
    class Reading(
        /** Lit displays metered. */
        @JvmField val displays: Int,
        /** Median over displays of each one's 90th percentile of luma. */
        @JvmField val bright: Int,
        /** Median over displays of each one's 25th percentile of luma. */
        @JvmField val face: Int,
        /**
         * How far over the target exposure the wall is, in stops; negative when under.
         * Capped at [MAX_STOPS] either way, which is also what it says when the face
         * itself has clipped and there is nothing left on the scale to measure with.
         */
        @JvmField val stopsOver: Float,
        /** Whether [bright] had clipped, so the estimate came from [face]. */
        @JvmField val clipped: Boolean,
    ) {
        fun describe(): String =
            "bright=$bright face=$face stops=${"%+.2f".format(stopsOver)}" +
                (if (clipped) " (from face)" else "") + " n=$displays"
    }

    private val histogram = IntArray(256)
    private var brights = IntArray(64)
    private var faces = IntArray(64)

    /**
     * Meters the given displays, which the caller has already filtered to the lit ones.
     *
     * @return null when there are fewer than [MIN_DISPLAYS] of them. A handful of displays
     *         is not enough to be sure the thing in frame is the wall and not the floor on
     *         the way to it, and metering the floor is how the old preset locked in an
     *         exposure two stops too bright for the whole of the first captured round.
     */
    fun measure(luma: GrayImage, boxes: List<DisplayDetector.Box>): Reading? {
        if (boxes.size < MIN_DISPLAYS) return null
        if (brights.size < boxes.size) {
            brights = IntArray(boxes.size)
            faces = IntArray(boxes.size)
        }
        var n = 0
        for (box in boxes) {
            java.util.Arrays.fill(histogram, 0)
            var count = 0
            val x1 = (box.x + box.width).coerceAtMost(luma.width)
            val y1 = (box.y + box.height).coerceAtMost(luma.height)
            // Every other pixel each way. A display is a hundred-odd pixels across, so
            // this is still thousands of samples, and a quarter of the cost.
            var y = box.y.coerceAtLeast(0)
            while (y < y1) {
                val row = y * luma.width
                var x = box.x.coerceAtLeast(0)
                while (x < x1) {
                    histogram[luma.data[row + x].toInt() and 0xFF]++
                    count++
                    x += SAMPLE_STEP
                }
                y += SAMPLE_STEP
            }
            if (count == 0) continue
            brights[n] = percentile(count, BRIGHT_PERCENTILE)
            faces[n] = percentile(count, FACE_PERCENTILE)
            n++
        }
        if (n < MIN_DISPLAYS) return null
        val bright = median(brights, n)
        val face = median(faces, n)

        val clipped = bright >= CLIP_LEVEL
        val stops = when {
            !clipped -> stopsBetween(bright, TARGET_BRIGHT)
            face < CLIP_LEVEL -> stopsBetween(face, TARGET_FACE)
            else -> MAX_STOPS
        }
        return Reading(n, bright, face, stops.coerceIn(-MAX_STOPS, MAX_STOPS), clipped)
    }

    private fun percentile(count: Int, fraction: Float): Int {
        val rank = (count * fraction).toInt()
        var acc = 0
        for (v in 0..255) {
            acc += histogram[v]
            if (acc > rank) return v
        }
        return 255
    }

    private fun median(values: IntArray, n: Int): Int {
        val sorted = values.copyOf(n)
        sorted.sort()
        return sorted[n / 2]
    }

    companion object {
        /** Lit displays needed before the wall is metered at all: a third of it. */
        const val MIN_DISPLAYS = 10

        /**
         * The reference clip's own values: what a wall that reads perfectly looks like
         * to this meter. Measured over its frames, 1920x1080, as the phone shot them.
         */
        const val TARGET_BRIGHT = 193
        const val TARGET_FACE = 97

        /** At or above this a percentile is treated as clipped and says nothing. */
        const val CLIP_LEVEL = 245

        /** The furthest this will claim to be off, in stops, either way. */
        const val MAX_STOPS = 3f

        private const val BRIGHT_PERCENTILE = 0.90f
        private const val FACE_PERCENTILE = 0.25f
        private const val SAMPLE_STEP = 2
        private const val GAMMA = 2.2

        /** Stops of light between two luma values, on a plain gamma curve. */
        fun stopsBetween(measured: Int, target: Int): Float =
            (GAMMA * ln(max(measured, 1).toDouble() / target) / ln(2.0)).toFloat()
    }
}
