package com.puzzlesolver.core.puzzle.jigsaw

/**
 * The colours a lit floor tile shows in the Jigsaw room, each with the one-letter code the
 * grids are written in. Green is the box's own frame and never a piece: it is read so the
 * box can be found, then dropped.
 *
 * The codes are the lobby protocol's (`JigsawBoard.COLOURS` in `:spell`), green aside; the
 * app checks the two agree.
 */
enum class FloorColour(val code: Char) {
    WHITE('W'),
    YELLOW('Y'),
    ORANGE('O'),
    RED('R'),
    PINK('K'),
    PURPLE('P'),
    BLUE('B'),
    GREEN('G'),
    ;

    companion object {
        /** Every colour a piece can be, in the order the palette shows them. */
        val PIECES: List<FloorColour> = entries.filter { it != GREEN }

        fun of(code: Char): FloorColour? = entries.firstOrNull { it.code == code }

        /**
         * The colour of a lit tile from the mean of its lit pixels.
         *
         * By hue first and saturation second, with the bands set on photos of the room. Two
         * things make it harder than a hue wheel: the room is lit green, so a white tile comes
         * out tinted yellow-green (hue 60 to 75, saturation 0.1 to 0.2), and a tile's core is
         * clipped, so a far-off orange one washes out to a saturation of 0.25 to 0.3 -- which
         * is why orange is told from white by its hue, under 45, and yellow from white by its
         * saturation, over 0.4. Green covers every hue from lime to teal: the box frame is
         * teal on one phone's photos and spring green on another's, and the walls glow pure
         * green, all of which should be dropped alike.
         */
        fun classify(r: Int, g: Int, b: Int): FloorColour {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max <= 0) return WHITE
            val s = (max - min).toFloat() / max
            val h = hue(r, g, b, max, min)
            return when {
                h >= 80f && h < 195f && s >= 0.5f -> GREEN
                h >= 195f && h < 255f && s >= 0.4f -> BLUE
                h >= 255f && h < 300f && s >= 0.3f -> PURPLE
                h >= 300f && h < 345f && s >= 0.3f -> PINK
                (h >= 345f || h < 15f) && s >= 0.45f -> RED
                (h < 45f || h >= 345f) && s >= 0.25f -> ORANGE
                h >= 45f && h < 80f && s >= 0.4f -> YELLOW
                else -> WHITE
            }
        }

        /** Hue in degrees, 0 to 360; 0 for a grey. */
        private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Float {
            val d = (max - min).toFloat()
            if (d <= 0f) return 0f
            val h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            return if (h < 0f) h + 360f else h
        }
    }
}
