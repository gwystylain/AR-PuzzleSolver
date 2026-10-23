package com.puzzlesolver.core.puzzle.spot

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.terminal.DisplayMeter

/**
 * Meters the exposure on the Spot screen alone, in the terms the terminal wall's loop
 * already acts on.
 *
 * The same problem as the terminal wall, and the same answer: a lit screen in a dark room
 * is exactly what a camera's own metering gets wrong, by opening up until the room is
 * grey. Here the cost is the comparison rather than a misread digit -- a white cloud that
 * appears on a pale sky is a difference of a few levels before clipping and of none after
 * it, and one of the two sample sets has exactly that cloud.
 *
 * The targets are the sample photos' own values, which were taken on the phone's camera
 * app and compare cleanly: across thirteen photos the screen's 98th percentile of luma
 * sat at 225 to 239 and its median at 146 to 163. Only the far, first photo of the first
 * set was brighter, at 249, and it was the one photo whose noise floor was raised.
 */
class ScreenMeter {

    private val histogram = IntArray(256)

    /** How far off the screen is, or null when too little of it was sampled to say. */
    fun measure(luma: GrayImage, screen: ScreenLocator.Screen): DisplayMeter.Reading? {
        java.util.Arrays.fill(histogram, 0)
        val c = screen.corners
        var count = 0
        // A lattice over the middle of the rectangle, bilinear between its corners, kept to
        // pixels that are screen: the chamfered corners of the rectangle are bezel.
        for (j in 0 until SAMPLES_DOWN) {
            val t = INSET + (1 - 2 * INSET) * j / (SAMPLES_DOWN - 1)
            for (i in 0 until SAMPLES_ACROSS) {
                val s = INSET + (1 - 2 * INSET) * i / (SAMPLES_ACROSS - 1)
                val top = lerp(c[0], c[2], s) to lerp(c[1], c[3], s)
                val bottom = lerp(c[6], c[4], s) to lerp(c[7], c[5], s)
                val x = lerp(top.first, bottom.first, t)
                val y = lerp(top.second, bottom.second, t)
                if (!screen.contains(x.toDouble(), y.toDouble())) continue
                val ix = x.toInt()
                val iy = y.toInt()
                if (ix < 0 || iy < 0 || ix >= luma.width || iy >= luma.height) continue
                histogram[luma[ix, iy]]++
                count++
            }
        }
        if (count < MIN_SAMPLES) return null
        val bright = percentile(count, BRIGHT_PERCENTILE)
        val face = percentile(count, FACE_PERCENTILE)
        val clipped = bright >= CLIP_LEVEL
        val stops = when {
            !clipped -> DisplayMeter.stopsBetween(bright, TARGET_BRIGHT)
            face < CLIP_LEVEL -> DisplayMeter.stopsBetween(face, TARGET_FACE)
            else -> DisplayMeter.MAX_STOPS
        }
        return DisplayMeter.Reading(
            displays = 1,
            bright = bright,
            face = face,
            stopsOver = stops.coerceIn(-DisplayMeter.MAX_STOPS, DisplayMeter.MAX_STOPS),
            clipped = clipped,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun percentile(count: Int, fraction: Float): Int {
        val rank = (count * fraction).toInt()
        var acc = 0
        for (v in 0..255) {
            acc += histogram[v]
            if (acc > rank) return v
        }
        return 255
    }

    companion object {
        const val TARGET_BRIGHT = 230
        const val TARGET_FACE = 152
        const val CLIP_LEVEL = 245

        private const val BRIGHT_PERCENTILE = 0.98f
        private const val FACE_PERCENTILE = 0.50f
        private const val SAMPLES_ACROSS = 64
        private const val SAMPLES_DOWN = 40
        private const val MIN_SAMPLES = 400

        /** Fraction of the rectangle left unsampled at each side. */
        private const val INSET = 0.08f
    }
}
