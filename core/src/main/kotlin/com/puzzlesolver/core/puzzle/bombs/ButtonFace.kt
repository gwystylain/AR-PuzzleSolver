package com.puzzlesolver.core.puzzle.bombs

import com.puzzlesolver.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Names a lit button from its own face, at the exposure Gems reads the gem wall at.
 *
 * [ButtonPalette] reads the glow *around* a button, because at the exposure the camera
 * picks for itself every lit face is clipped to white and the glow is all the colour
 * there is. At the LED-wall preset -- 1/250 s at floor ISO, white balance locked -- the
 * face is not clipped, and the face is a far better witness than the glow: it is the LED
 * itself rather than its reflection off a panel the room washes purple. That is what
 * made Gems reliable (see docs/GEM_PUZZLE.md), and a mines button is the easy case of a
 * gem: one colour across the whole face rather than three concentric ones.
 *
 * So this is [com.puzzlesolver.core.puzzle.gems.GemPalette]'s vote with the rings taken
 * out. Every texel bright enough to be LED votes, weighted by how saturated it is:
 *
 *  - **barely saturated** votes white. A white button lights all three channels, and
 *    at this exposure that is what it looks like;
 *  - **saturated** votes for the nearest hue. The centroids are the gem wall's, measured
 *    on the same camera at the same preset: red 352, green 146, blue 198 -- which is
 *    cyan, and calling it 240 would put every blue nearer green. An armed mine lights
 *    red *and* blue, and sits halfway between them at 275.
 *
 * A vote rather than an average for the reason Gems found: a texel where a red core
 * blooms into the panel and one where the panel is just purple average into a colour
 * that neither of them is.
 */
object ButtonFace {

    /** Radius to read, as a fraction of the pitch. The face of a button is about 0.26. */
    const val FACE_FRACTION = 0.22f

    /** Below this on the brightest channel, a texel is unlit button or panel. */
    const val MIN_VALUE = 60

    /**
     * Below this saturation a texel votes white.
     *
     * Set wide rather than tight. A white LED behind white balance locked in a purple
     * room comes out faintly tinted, and every coloured LED on the gem wall measured far
     * above this; a white button read as a weak colour is the error to design against.
     */
    const val WHITE_SATURATION = 0.30f

    /**
     * Fraction of the face that has to be lit for the button to count as lit.
     *
     * A quarter, not most: a button caught moving smears its light across the disc, and
     * a dark button never gets anywhere near this however the panel is lit.
     */
    const val MIN_LIT_FRACTION = 0.25f

    /** Hue centroids in degrees for the four coloured classes. White is decided apart. */
    private val CLASSES = intArrayOf(Button.HAZARD, Button.GREEN, Button.BLUE, Button.MINE)
    private val HUES = floatArrayOf(352f, 146f, 198f, 275f)

    class Reading(
        /** Whether enough of the face is lit to call the button on. */
        @JvmField val lit: Boolean,
        /** The [Button] it reads as, or [Button.OPAQUE] when the vote is split. */
        @JvmField val button: Int,
        /** Margin of the winner over the runner-up, 0..1. */
        @JvmField val confidence: Float,
        /** Mean brightest channel over the face, lit or not. */
        @JvmField val brightness: Float,
        /** Fraction of the face clipped on all three channels. */
        @JvmField val blown: Float,
        /** Fraction of the face clipped on at least one channel: a lit LED at a bright exposure. */
        @JvmField val clipped: Float,
        /** Mean RGB of the lit texels, for the scan log. */
        @JvmField val meanR: Float,
        @JvmField val meanG: Float,
        @JvmField val meanB: Float,
    ) {
        override fun toString(): String =
            "${Button.name(button)} conf=%.2f lit=$lit bright=%.0f blown=%.2f clipped=%.2f rgb=%.0f,%.0f,%.0f".format(
                confidence, brightness, blown, clipped, meanR, meanG, meanB,
            )
    }

    /**
     * Reads the disc of [radius] around ([cx], [cy]).
     *
     * @return null when the disc lies too far outside the image to say anything.
     */
    fun read(image: RgbImage, cx: Float, cy: Float, radius: Float): Reading? {
        val r2 = radius * radius
        val x0 = (cx - radius).toInt().coerceAtLeast(0)
        val x1 = (cx + radius).toInt().coerceAtMost(image.width - 1)
        val y0 = (cy - radius).toInt().coerceAtLeast(0)
        val y1 = (cy + radius).toInt().coerceAtMost(image.height - 1)
        // Enough texels to vote; past that the stride opens up so a big disc costs the
        // same as a small one.
        val area = Math.PI * r2
        val stride = maxOf(1, sqrt(area / TARGET_SAMPLES).toInt())

        val votes = FloatArray(8)
        var total = 0
        var lit = 0
        var blown = 0
        var clipped = 0
        var brightness = 0f
        var sr = 0f
        var sg = 0f
        var sb = 0f
        val data = image.data
        var y = y0
        while (y <= y1) {
            val dy = y - cy
            var x = x0
            while (x <= x1) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    val i = (y * image.width + x) * 3
                    val r = data[i].toInt() and 0xFF
                    val g = data[i + 1].toInt() and 0xFF
                    val b = data[i + 2].toInt() and 0xFF
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    total++
                    brightness += max
                    if (min >= CLIP) blown++
                    if (max >= CLIP) clipped++
                    if (max >= MIN_VALUE) {
                        lit++
                        sr += r
                        sg += g
                        sb += b
                        val s = (max - min).toFloat() / max
                        if (s < WHITE_SATURATION) {
                            votes[Button.WHITE] += 1f - s
                        } else {
                            votes[nearest(hueOf(r, g, b, max, max - min))] += s
                        }
                    }
                }
                x += stride
            }
            y += stride
        }
        // A disc mostly off the edge of the frame is not a reading of the button.
        if (total < MIN_TEXELS || total < area / (stride * stride) * 0.5) return null

        var best = Button.OPAQUE
        var bestV = 0f
        var second = 0f
        for (k in votes.indices) {
            val v = votes[k]
            if (v > bestV) {
                second = bestV
                bestV = v
                best = k
            } else if (v > second) {
                second = v
            }
        }
        val isLit = lit >= MIN_LIT_TEXELS && lit >= total * MIN_LIT_FRACTION
        val confidence = if (bestV > 0f) (bestV - second) / bestV else 0f
        val n = lit.coerceAtLeast(1)
        return Reading(
            lit = isLit,
            button = if (isLit && bestV > 0f) best else Button.OPAQUE,
            confidence = confidence,
            brightness = brightness / total,
            blown = blown.toFloat() / total,
            clipped = clipped.toFloat() / total,
            meanR = sr / n,
            meanG = sg / n,
            meanB = sb / n,
        )
    }

    class Glow(@JvmField val button: Int, @JvmField val confidence: Float, val hue: Float, val saturation: Float) {
        override fun toString(): String =
            (if (button < 0) "too faint" else Button.name(button)) +
                " hue=%.0f sat=%.2f conf=%.2f".format(hue, saturation, confidence)
    }

    /**
     * Names the light a clipped button throws onto the panel, given as its excess over
     * the panel's own colour.
     *
     * The fallback for a frame bright enough that the face itself is clipped: the same
     * classes and the same hue circle as the face, applied to the glow.
     *
     * @return a [Glow] whose button is -1 when there is too little excess to name.
     */
    fun nameGlow(r: Float, g: Float, b: Float): Glow {
        val rr = r.coerceAtLeast(0f)
        val gg = g.coerceAtLeast(0f)
        val bb = b.coerceAtLeast(0f)
        val max = maxOf(rr, gg, bb)
        val min = minOf(rr, gg, bb)
        if (rr + gg + bb < MIN_GLOW) return Glow(-1, 0f, 0f, 0f)
        val s = (max - min) / max
        val hue = hueOf(rr, gg, bb, max, max - min)
        if (s < GLOW_WHITE_SATURATION) {
            return Glow(Button.WHITE, (1f - s / GLOW_WHITE_SATURATION).coerceIn(0f, 1f), hue, s)
        }
        // How clearly coloured, and how clearly nearer one hue than the next.
        val sorted = HUES.map { h -> var d = abs(hue - h); if (d > 180f) d = 360f - d; d }.sorted()
        val margin = (sorted[1] - sorted[0]) / sorted[1].coerceAtLeast(1f)
        val colourful = ((s - GLOW_WHITE_SATURATION) / 0.2f).coerceIn(0f, 1f)
        return Glow(nearest(hue), margin * colourful, hue, s)
    }

    /** The coloured class nearest [hue] on the circle. */
    fun nearest(hue: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in HUES.indices) {
            var d = abs(hue - HUES[k])
            if (d > 180f) d = 360f - d
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return CLASSES[best]
    }

    private fun hueOf(r: Int, g: Int, b: Int, max: Int, delta: Int): Float =
        hueOf(r.toFloat(), g.toFloat(), b.toFloat(), max.toFloat(), delta.toFloat())

    private fun hueOf(r: Float, g: Float, b: Float, max: Float, delta: Float): Float {
        if (delta <= 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** Excess below this, summed over the channels, is too little light to name. */
    private const val MIN_GLOW = 30f

    /** Below this saturation the glow is white's. Measured on the reference pan. */
    private const val GLOW_WHITE_SATURATION = 0.30f

    /** All three channels at or above this and the texel says nothing but "bright". */
    const val CLIP = 250

    private const val TARGET_SAMPLES = 200.0
    private const val MIN_TEXELS = 12
    private const val MIN_LIT_TEXELS = 6
}
