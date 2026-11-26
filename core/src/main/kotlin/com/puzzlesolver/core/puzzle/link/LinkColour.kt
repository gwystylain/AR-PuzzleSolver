package com.puzzlesolver.core.puzzle.link

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The five colours a Link panel's buttons light in.
 *
 * The practice game (activate.ryflix.ca/link.html) draws exactly these five --
 * `#ff3b3b`, `#ffe03b`, `#4dff6e`, `#3b8fff`, `#b94dff` -- and every button is one solid
 * colour, never rings. On the room's RGB buttons yellow is red and green lit together, and
 * purple red and blue together.
 *
 * Plain ints rather than an enum because a scan holds hundreds of them and votes over them
 * by index.
 */
object LinkColour {
    const val UNKNOWN = -1
    const val RED = 0
    const val YELLOW = 1
    const val GREEN = 2
    const val BLUE = 3
    const val PURPLE = 4
    const val COUNT = 5

    private const val LETTERS = "RYGBP"

    fun letter(colour: Int): Char = if (colour in 0 until COUNT) LETTERS[colour] else '?'

    /** The colour a board letter stands for, or [UNKNOWN] for `?` and anything else. */
    fun fromLetter(ch: Char): Int = LETTERS.indexOf(ch.uppercaseChar()).let { if (it >= 0) it else UNKNOWN }

    fun name(colour: Int): String = when (colour) {
        RED -> "red"
        YELLOW -> "yellow"
        GREEN -> "green"
        BLUE -> "blue"
        PURPLE -> "purple"
        else -> "unknown"
    }

    /**
     * Where each colour sits on the hue circle at the LED-wall preset, in degrees.
     *
     * Red, green and blue are the gem and mines walls' measurements on this camera at the
     * same preset (docs/GEM_PUZZLE.md): red 352; green 146, though it has drifted from 131
     * to 178 between visits with the locked white balance; blue 208-223, which is cyan
     * rather than textbook blue. Yellow is Gems' 62-69. Purple has not been seen yet: an
     * armed mine on the mines wall, also red and blue lit together, measured 275, and one
     * pink-magenta button 322. These are only where a scan starts -- [LinkPalette] moves
     * them to wherever a scan's own buttons fall, because the white balance does not stay
     * put between visits.
     */
    @JvmField
    val HUES = floatArrayOf(352f, 62f, 146f, 212f, 288f)

    /** How saturated each one typically reads, for the same purpose. Yellow and purple are mixes, so paler. */
    @JvmField
    val SATURATIONS = floatArrayOf(0.85f, 0.65f, 0.85f, 0.85f, 0.70f)

    /** The colour nearest [hue] on the circle, by the starting centroids. */
    fun nearestHue(hue: Float, hues: FloatArray = HUES): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in hues.indices) {
            val d = hueDistance(hue, hues[k])
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return best
    }

    fun hueDistance(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    /** Hue in degrees, 0 when there is none. */
    fun hueOf(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta <= 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    fun saturationOf(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        return if (max <= 0f) 0f else (max - minOf(r, g, b)) / max
    }

    /** A colour as a point on the chroma plane: saturation out from the middle, hue round it. */
    fun chromaX(hue: Float, saturation: Float): Float = saturation * cos(Math.toRadians(hue.toDouble())).toFloat()

    fun chromaY(hue: Float, saturation: Float): Float = saturation * sin(Math.toRadians(hue.toDouble())).toFloat()
}
