package com.puzzlesolver.core.puzzle.gems

/**
 * The four colours a gem's rings can take.
 *
 * Four and not five: the room's LEDs are RGB, and the wall only ever drives them to
 * red, yellow, green and blue. A ring that reads as *white* is not a fifth colour, it
 * is a yellow ring bright enough to desaturate -- see [GemPalette] for why that is a
 * measurement rather than an assumption.
 *
 * There used to be a purple, and it never existed. What was labelled purple on the early
 * clips was a red ring under the glow of a cyan one -- red light plus cyan light is
 * magenta -- and the user confirmed the game has no purple gem. Keeping it cost twice:
 * it was a fifth bucket for red to leak into, and it sat 26 degrees from red, the closest
 * pair on the palette.
 *
 * [UNKNOWN] is load-bearing in two different places and means the same thing in both:
 * *nothing is asserted here*. On an observation it means the ring could not be named
 * and the gem must not be claimed as a match; on a target it means the user has not
 * chosen that ring yet, so it matches anything.
 */
object GemColour {

    const val UNKNOWN = 0
    const val RED = 1
    const val YELLOW = 2
    const val GREEN = 3
    const val BLUE = 4

    /** Every nameable colour, in palette order. */
    @JvmField
    val ALL = intArrayOf(RED, YELLOW, GREEN, BLUE)

    fun name(colour: Int): String = when (colour) {
        RED -> "red"
        YELLOW -> "yellow"
        GREEN -> "green"
        BLUE -> "blue"
        else -> "?"
    }

    /**
     * Roughly what each colour looks like on screen, as 0xRRGGBB.
     *
     * For drawing the palette and the target swatches only. Deliberately *not* the
     * measured wall hues: the wall's "blue" measures cyan, and a swatch painted in that
     * would sit closer to the green one than a user glancing at a phone can comfortably
     * tell apart. Recognisable beats faithful for a button you tap.
     */
    fun displayRgb(colour: Int): Int = when (colour) {
        RED -> 0xE53935
        YELLOW -> 0xFDD835
        GREEN -> 0x43D96B
        BLUE -> 0x2196F3
        else -> 0x555F6A
    }
}

/**
 * The three concentric rings of one gem, outermost first.
 *
 * Used for both halves of the feature, which is the point: a target the user typed in
 * and a gem read off the wall are the same shape, so matching is [matches] and nothing
 * else. A zone set to [GemColour.UNKNOWN] on a *target* is a wildcard; on an
 * *observation* it is a refusal, and [isReadable] is what tells the two apart.
 */
data class GemPattern(
    val outer: Int = GemColour.UNKNOWN,
    val middle: Int = GemColour.UNKNOWN,
    val centre: Int = GemColour.UNKNOWN,
) {
    /** True when the user has chosen nothing, i.e. this target filters nothing out. */
    val isBlank: Boolean
        get() = outer == GemColour.UNKNOWN &&
            middle == GemColour.UNKNOWN &&
            centre == GemColour.UNKNOWN

    /** True when all three rings were named, i.e. this is a complete reading. */
    val isReadable: Boolean
        get() = outer != GemColour.UNKNOWN &&
            middle != GemColour.UNKNOWN &&
            centre != GemColour.UNKNOWN

    /** How many rings carry a colour. */
    val specifiedZones: Int
        get() = (if (outer != GemColour.UNKNOWN) 1 else 0) +
            (if (middle != GemColour.UNKNOWN) 1 else 0) +
            (if (centre != GemColour.UNKNOWN) 1 else 0)

    fun zone(zone: Int): Int = when (zone) {
        ZONE_OUTER -> outer
        ZONE_MIDDLE -> middle
        else -> centre
    }

    fun withZone(zone: Int, colour: Int): GemPattern = when (zone) {
        ZONE_OUTER -> copy(outer = colour)
        ZONE_MIDDLE -> copy(middle = colour)
        else -> copy(centre = colour)
    }

    /**
     * Whether an [observed] gem satisfies this pattern read as a target.
     *
     * Unset zones of the target are wildcards, which is what makes a half-entered
     * target useful the moment the first colour is picked. Unset zones of the
     * *observation* are not: a ring we failed to name cannot be claimed to match a
     * ring the user did specify, so it fails. That asymmetry is deliberate -- the
     * failure that costs the user a round is a gem highlighted that does not match.
     */
    fun matches(observed: GemPattern): Boolean {
        if (outer != GemColour.UNKNOWN && outer != observed.outer) return false
        if (middle != GemColour.UNKNOWN && middle != observed.middle) return false
        if (centre != GemColour.UNKNOWN && centre != observed.centre) return false
        return true
    }

    /** Packs into one non-negative int, for [com.puzzlesolver.core.solve.CellObservation]. */
    fun pack(): Int = outer * 36 + middle * 6 + centre

    override fun toString(): String =
        "${GemColour.name(outer)}/${GemColour.name(middle)}/${GemColour.name(centre)}"

    companion object {
        const val ZONE_OUTER = 0
        const val ZONE_MIDDLE = 1
        const val ZONE_CENTRE = 2

        /** The order the input dialog walks the rings in: outside in, as described. */
        @JvmField
        val ZONE_ORDER = intArrayOf(ZONE_OUTER, ZONE_MIDDLE, ZONE_CENTRE)

        fun zoneName(zone: Int): String = when (zone) {
            ZONE_OUTER -> "outer ring"
            ZONE_MIDDLE -> "middle ring"
            else -> "centre dot"
        }

        val BLANK = GemPattern()

        fun unpack(packed: Int): GemPattern =
            if (packed < 0) BLANK
            else GemPattern(
                outer = (packed / 36) % 6,
                middle = (packed / 6) % 6,
                centre = packed % 6,
            )
    }
}
