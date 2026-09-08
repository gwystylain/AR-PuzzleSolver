package com.puzzlesolver.core.puzzle.bombs

import kotlin.math.sqrt

/**
 * Turns the colour of a lit button into a [Button] constant.
 *
 * Two things about this wall make the obvious approach fail, and both were measured
 * off real footage rather than guessed.
 *
 * **The button itself carries no colour.** Every lit button blows the sensor out to
 * pure white across its whole face and some way past it -- core and inner halo both
 * measure (255, 255, 255) at zero saturation, for red, white and blue alike. The room
 * is dark and the LEDs are bright, so any exposure that renders the unlit buttons at
 * all will clip the lit ones. Sampling the middle of a button, which is what glyph
 * reading does, reports every colour as identical white. The colour survives only in
 * the glow thrown onto the panel *around* the button, so that annulus is what gets
 * sampled. See [HALO_INNER_FRACTION].
 *
 * **Absolute colour is meaningless; excess over ambient is not.** The room washes the
 * whole wall in shifting purple. Across one fifteen-second pan the raw halo of a white
 * button ranged from rgb(87, 73, 111) to rgb(145, 114, 156) -- a factor of over one
 * and a half in brightness and a visible change in tint. Subtracting the local ambient
 * and normalising to a chromaticity that sums to one collapsed all twenty samples onto
 * 0.34 : 0.29 : 0.37, and the three classes separate cleanly.
 *
 * Classification is nearest centroid in that chromaticity space, with the confidence
 * taken from how much closer the winner is than the runner-up. A button that lands
 * between two classes is reported as [Button.OPAQUE], which makes the solver refuse
 * rather than guess -- the right trade when a wrong colour produces a confident plan
 * that loses the room.
 */
object ButtonPalette {

    /**
     * Annulus to sample, as a fraction of the cell pitch measured from the centre.
     *
     * The blown-out region ran to about 0.22 of the pitch in the reference footage and
     * neighbouring buttons start at 0.5, so this sits in the gap: outside the clipping,
     * inside the cell.
     */
    const val HALO_INNER_FRACTION = 0.24f
    const val HALO_OUTER_FRACTION = 0.38f

    /** Below this fraction of the local peak, a button is not lit at all. */
    const val LIT_THRESHOLD = 0.55f

    /**
     * Class centroids in ambient-subtracted chromaticity, `r : g : b` summing to 1.
     *
     * White, blue and red are measured: 20, 3 and 5 samples respectively, taken from
     * `testVideos/mines1.mp4` across frames with very different ambient light. Note
     * that white does *not* sit at the neutral third -- it measures slightly magenta,
     * partly the LED and partly imperfect ambient subtraction. Using a theoretical
     * (0.33, 0.33, 0.33) instead would push every white sample toward its neighbours.
     */
    @JvmField
    val WHITE = floatArrayOf(0.345f, 0.293f, 0.363f)

    @JvmField
    val BLUE = floatArrayOf(0.150f, 0.313f, 0.540f)

    @JvmField
    val RED = floatArrayOf(0.646f, 0.106f, 0.248f)

    /**
     * PROVISIONAL -- no sample yet, no green button appeared in the reference footage.
     * Placed where a green LED should land. Wrong only in degree: green is far from
     * every measured class, so a poor centroid costs confidence rather than accuracy.
     */
    @JvmField
    val GREEN = floatArrayOf(0.150f, 0.650f, 0.200f)

    /**
     * PROVISIONAL -- no armed mine appeared in the reference footage.
     *
     * An armed mine lights red *and* blue, so it should land between those two with
     * almost no green. That last part is what separates it from white, and it is worth
     * being explicit about because the margin is the thinnest in the palette: white
     * already measures magenta-leaning, so red-versus-blue balance will not tell them
     * apart. **The green fraction does** -- about 0.29 for white against roughly 0.10
     * for a magenta mine.
     *
     * Worth confirming against footage with a mine actually placed before trusting it,
     * which is why [classify] reports a margin the caller can threshold on.
     */
    @JvmField
    val MINE = floatArrayOf(0.450f, 0.100f, 0.450f)

    private val CLASSES = arrayOf(
        Button.WHITE to WHITE,
        Button.BLUE to BLUE,
        Button.HAZARD to RED,
        Button.GREEN to GREEN,
        Button.MINE to MINE,
    )

    /**
     * @param button the [Button] constant, or [Button.OPAQUE] when nothing fits well.
     * @param confidence 0..1, from the margin over the runner-up.
     */
    class Reading(@JvmField val button: Int, @JvmField val confidence: Float)

    /**
     * Normalises the LED's contribution and names it.
     *
     * @param halo mean linear RGB of the annulus around the button.
     * @param ambient mean linear RGB of unlit panel nearby.
     */
    fun classify(halo: FloatArray, ambient: FloatArray): Reading {
        val r = (halo[0] - ambient[0]).coerceAtLeast(0f)
        val g = (halo[1] - ambient[1]).coerceAtLeast(0f)
        val b = (halo[2] - ambient[2]).coerceAtLeast(0f)
        val sum = r + g + b
        // Nothing above ambient means nothing is lit here.
        if (sum < MIN_EXCESS) return Reading(Button.EMPTY, 1f)
        return classifyChroma(floatArrayOf(r / sum, g / sum, b / sum))
    }

    /** The decision itself, on an already-normalised chromaticity. */
    fun classifyChroma(chroma: FloatArray): Reading {
        var best = Button.OPAQUE
        var bestD = Float.MAX_VALUE
        var secondD = Float.MAX_VALUE
        for ((button, centroid) in CLASSES) {
            val d = distance(chroma, centroid)
            if (d < bestD) {
                secondD = bestD
                bestD = d
                best = button
            } else if (d < secondD) {
                secondD = d
            }
        }
        // Too far from everything: a colour we have no name for. Say so rather than
        // rounding it to the nearest thing we happen to know.
        if (bestD > MAX_ACCEPTED_DISTANCE) return Reading(Button.OPAQUE, 0f)

        val margin = if (secondD == Float.MAX_VALUE) 1f else (secondD - bestD) / secondD
        return Reading(best, margin.coerceIn(0f, 1f))
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /**
     * Chromaticity is scale-free, so a nearly-black cell would otherwise normalise
     * sensor noise into a confident colour.
     */
    private const val MIN_EXCESS = 12f

    /**
     * Roughly half the gap between the two closest measured centroids, so a reading
     * has to be nearer one class than the classes are to each other.
     */
    private const val MAX_ACCEPTED_DISTANCE = 0.16f
}
