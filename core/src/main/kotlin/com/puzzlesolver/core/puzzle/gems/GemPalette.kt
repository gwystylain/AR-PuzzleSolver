package com.puzzlesolver.core.puzzle.gems

import com.puzzlesolver.core.image.CanvasView

/**
 * Names the colour of one ring of a gem.
 *
 * Everything here was measured off `testVideos/Gems/VID20260814182431.mp4` -- the
 * lower-exposure pan -- rather than reasoned about, and three findings shaped it. Each
 * one broke an approach that looked obviously right first.
 *
 * **The rings are LEDs, so every one of them clips.** Inside a gem the brightest
 * channel sits at 240-255 essentially everywhere; a value or "excess over ambient"
 * threshold, which is what [com.puzzlesolver.core.puzzle.bombs.ButtonPalette] uses
 * on the mines wall, separates nothing at all here. What survives clipping is *hue*:
 * a red LED clips R and leaves G and B low, a blue one clips B and leaves R low. So
 * this classifier reads hue and ignores brightness beyond a lit/unlit test.
 *
 * **Averaging hue across a ring is worse than useless.** Each ring throws a diffuse
 * glow over the whole gem face, so the middle ring's band contains its own LEDs
 * *plus* the outer ring's wash. Averaging a red wash with green dots yields a hue
 * near yellow -- a colour neither ring is, reported confidently. Classifying every
 * texel and taking a weighted vote fixes it, because the wash and the dots vote for
 * their own colours and the majority is the ring. That single change took per-ring
 * accuracy on the reference frame from 71% to 85%.
 *
 * **Saturation is the right vote weight.** A texel where two rings' glows overlap is
 * a mixture, and mixtures are less saturated than either source. Weighting by
 * saturation is therefore weighting by how much a texel is one colour rather than a
 * blend, and it costs nothing to compute since the hue conversion needs it anyway.
 *
 * **Two clipped channels name no ring, however saturated the texel looks.** The vote
 * above defends against *mixtures*, and a mixture is desaturated, so [MIN_SATURATION]
 * catches it. Clipping is the opposite failure and slips straight through: when a red
 * dot and a green ring both bloom into the same texel, R and G both peg at the sensor
 * ceiling and B stays low, which is a vivid, fully saturated yellow -- a colour neither
 * ring is, agreed on by every texel in the bloom, and so reported with high confidence.
 * Green over blue gives the same at cyan. On a run off the wall, 92% of middle rings
 * came back yellow or cyan for exactly this reason, and the targets being hunted could
 * not match at any exposure. So a texel with two channels at [CLIP_LEVEL] is discarded
 * before it votes and counted in [Reading.washedOut] instead: it is evidence about the
 * exposure, not about the ring.
 *
 * On 29 hand-labelled gems from that frame -- 87 rings -- this reads 90% correctly,
 * with the outer ring at 100%. The residue is almost all the centre dot, which is a
 * handful of texels across and sits in the deepest part of the glow from both rings
 * outside it.
 */
object GemPalette {

    /**
     * Ring radii as fractions of the lattice pitch, inner then outer edge.
     *
     * Fractions of *pitch* rather than of the gem's apparent size on purpose. The
     * lens is 0.5 of the pitch across and the LED rings sit at fixed radii on the
     * board behind it, so these are physical constants of the wall; the apparent disc
     * radius is not, because it grows and shrinks with how blown out the exposure is.
     *
     * The dot rings measure at 0.105 and 0.20 of pitch from the centre -- 0.42 and
     * 0.81 of the lens radius. The bands here are those rings with a little room
     * either side, chosen by fitting against the hand-labelled frame: widening them
     * pulls in the neighbouring ring's glow, narrowing them starves the vote.
     */
    const val CENTRE_INNER = 0.000f
    const val CENTRE_OUTER = 0.036f
    const val MIDDLE_INNER = 0.086f
    const val MIDDLE_OUTER = 0.124f
    const val OUTER_INNER = 0.175f
    const val OUTER_OUTER = 0.230f

    /**
     * Measured hue centroids, in degrees.
     *
     * Note how far two of them are from their names: the wall's "blue" measures 198
     * degrees, which is cyan, and its "purple" measures 326, which is magenta. Using
     * textbook 240 and 280 instead would put every blue reading nearer green than
     * blue. These come from 50 hand-labelled rings.
     */
    @JvmField
    val HUES = floatArrayOf(
        352f,   // red
        66f,    // yellow
        146f,   // green
        198f,   // blue
        326f,   // purple
    )

    /** Below this on the brightest channel, a texel is unlit panel rather than gem. */
    const val MIN_VALUE = 60f

    /**
     * At or above this, a channel has hit the sensor's ceiling: its true value is
     * unknown and could be anything above.
     *
     * One clipped channel is fine, and is the normal state of a lit LED -- the other two
     * still carry the ratio that names the hue. Two is not, because the ratio between
     * *them* is precisely what has been lost, and that ratio is the whole difference
     * between a red dot blooming into a green ring and an actual yellow one.
     *
     * 250 rather than 255 because the capture path is not lossless: the frame arrives as
     * luma plus subsampled chroma and is reconstructed through BT.601, which lands a
     * clipped channel a count or two either side of the ceiling. The same number, for
     * the same reason, as [com.puzzlesolver.core.image.CanvasView.meanRgbInAnnulus].
     */
    const val CLIP_LEVEL = 250f

    /**
     * Below this saturation a texel is a blend of two rings, or a clipped LED core,
     * and its hue is not evidence about either ring. Such texels are counted -- see
     * [Reading.washedOut] -- because a gem that is *entirely* washed out is the
     * signature of an exposure this app cannot read, and that is worth saying out
     * loud rather than guessing through.
     */
    const val MIN_SATURATION = 0.16f

    /** A ring needs this many usable texels before it is named at all. */
    const val MIN_SAMPLES = 6

    /**
     * Roughly how many texels of a band to look at. Above this the stride opens up.
     *
     * Two hundred is far more than the vote needs -- six is the floor for naming a ring
     * at all -- and leaves room for most of them to be rejected as too dim or too
     * desaturated and still decide the ring.
     */
    const val TARGET_SAMPLES = 220.0

    /**
     * @param colour a [GemColour] constant, or [GemColour.UNKNOWN] when the vote was
     *        too close or there was nothing to vote with.
     * @param confidence 0..1, the winner's share of the vote over the runner-up.
     * @param washedOut fraction of lit texels that carried no usable hue -- either too
     *        desaturated to name, or two channels clipped together. Both are exposure
     *        faults, both are invisible in the reading itself, so they are one number.
     * @param lit whether any texel in the band was brighter than the panel behind it.
     */
    class Reading(
        @JvmField val colour: Int,
        @JvmField val confidence: Float,
        @JvmField val washedOut: Float,
        @JvmField val lit: Boolean,
    )

    private val UNREADABLE = Reading(GemColour.UNKNOWN, 0f, 0f, false)

    /**
     * Classifies the annulus [innerRadius, outerRadius) around a texel.
     *
     * @param scratch reused RGB buffer, so a full board read allocates nothing.
     */
    fun classifyAnnulus(
        view: CanvasView,
        cx: Float,
        cy: Float,
        innerRadius: Float,
        outerRadius: Float,
        votes: FloatArray = FloatArray(GemColour.ALL.size),
        scratch: FloatArray = FloatArray(3),
    ): Reading {
        votes.fill(0f)
        var lit = 0
        var usable = 0

        val inner2 = innerRadius * innerRadius
        val outer2 = outerRadius * outerRadius
        val x0 = (cx - outerRadius).toInt().coerceAtLeast(0)
        val x1 = (cx + outerRadius).toInt().coerceAtMost(view.width - 1)
        val y0 = (cy - outerRadius).toInt().coerceAtLeast(0)
        val y1 = (cy + outerRadius).toInt().coerceAtMost(view.height - 1)

        // Sampled rather than exhaustive. This is a vote, and a vote does not get more
        // right with more voters once it has enough of them: the outer band of a gem at
        // a 70-pixel pitch holds around a thousand texels and the winner is the same
        // from two hundred. The centre dot is small enough that the stride stays at one,
        // which is where the samples are actually scarce. Measured on device, this took
        // reading a frame of gems from 27 ms to under 5.
        val samples = Math.PI * (outer2 - inner2)
        val step = if (samples <= TARGET_SAMPLES) 1
        else kotlin.math.sqrt(samples / TARGET_SAMPLES).toInt().coerceAtLeast(1)

        var y = y0
        while (y <= y1) {
            val dy = y - cy
            var x = x0
            while (x <= x1) {
                val dx = x - cx
                val d2 = dx * dx + dy * dy
                if (d2 >= inner2 && d2 <= outer2) {
                    view.rgbAt(x, y, scratch)
                    val r = scratch[0]
                    val g = scratch[1]
                    val b = scratch[2]
                    val max = maxOf(r, g, b)
                    if (max >= MIN_VALUE) {
                        lit++
                        val min = minOf(r, g, b)
                        val delta = max - min
                        val saturation = delta / max
                        // Tested before saturation rather than after, because this
                        // failure looks nothing like the one saturation catches: a
                        // double-clipped texel is vividly saturated and entirely wrong.
                        val clipped = (if (r >= CLIP_LEVEL) 1 else 0) +
                            (if (g >= CLIP_LEVEL) 1 else 0) +
                            (if (b >= CLIP_LEVEL) 1 else 0)
                        if (clipped < 2 && saturation >= MIN_SATURATION) {
                            usable++
                            votes[nearestColour(hueOf(r, g, b, max, delta))] += saturation
                        }
                    }
                }
                x += step
            }
            y += step
        }

        if (lit == 0) return UNREADABLE
        val washedOut = (lit - usable).toFloat() / lit
        if (usable < MIN_SAMPLES) return Reading(GemColour.UNKNOWN, 0f, washedOut, true)

        var bestIndex = -1
        var best = 0f
        var second = 0f
        for (i in votes.indices) {
            if (votes[i] > best) {
                second = best
                best = votes[i]
                bestIndex = i
            } else if (votes[i] > second) {
                second = votes[i]
            }
        }
        if (bestIndex < 0 || best <= 0f) return Reading(GemColour.UNKNOWN, 0f, washedOut, true)
        return Reading(GemColour.ALL[bestIndex], (best - second) / best, washedOut, true)
    }

    /** Index into [GemColour.ALL] of the nearest centroid on the hue circle. */
    fun nearestColour(hueDegrees: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in HUES.indices) {
            var d = kotlin.math.abs(hueDegrees - HUES[i])
            if (d > 180f) d = 360f - d
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** Hue in degrees, given the channel maximum and range the caller already has. */
    private fun hueOf(r: Float, g: Float, b: Float, max: Float, delta: Float): Float {
        if (delta <= 0f) return 0f
        val h = when (max) {
            r -> (g - b) / delta
            g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } * 60f
        return if (h < 0f) h + 360f else h
    }
}
