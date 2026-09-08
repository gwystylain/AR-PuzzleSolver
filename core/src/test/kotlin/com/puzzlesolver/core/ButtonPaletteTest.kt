package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.ButtonPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier against real measurements rather than invented ones.
 *
 * Every sample below is a lit button measured off `testVideos/mines1.mp4`: the mean
 * of the glow annulus around it, minus the local ambient, normalised to sum to one.
 * The frame and pixel position are kept so any of them can be traced back and looked
 * at. Twenty-eight buttons over fifteen seconds of panning, under ambient light that
 * swings from deep blue to magenta.
 *
 * The point of testing against these rather than against the centroids is that the
 * centroids were *derived* from them -- a test built on invented values would pass by
 * construction and tell us nothing about whether the room is separable.
 */
class ButtonPaletteTest {

    /** frame, x, y, r, g, b, expected. */
    private val samples = listOf(
        Sample("001", 857, 427, 0.15f, 0.31f, 0.54f, Button.BLUE),
        Sample("001", 350, 775, 0.17f, 0.30f, 0.53f, Button.BLUE),
        Sample("005", 45, 1389, 0.13f, 0.33f, 0.55f, Button.BLUE),

        Sample("002", 623, 1438, 0.33f, 0.30f, 0.37f, Button.WHITE),
        Sample("003", 115, 787, 0.33f, 0.30f, 0.37f, Button.WHITE),
        Sample("003", 1013, 1325, 0.33f, 0.30f, 0.37f, Button.WHITE),
        Sample("004", 84, 1156, 0.34f, 0.31f, 0.35f, Button.WHITE),
        Sample("005", 223, 773, 0.34f, 0.31f, 0.35f, Button.WHITE),
        Sample("005", 1022, 961, 0.36f, 0.29f, 0.35f, Button.WHITE),
        Sample("005", 434, 972, 0.36f, 0.28f, 0.36f, Button.WHITE),
        Sample("006", 819, 985, 0.35f, 0.29f, 0.36f, Button.WHITE),
        Sample("006", 627, 987, 0.34f, 0.29f, 0.37f, Button.WHITE),
        Sample("006", 36, 1031, 0.36f, 0.28f, 0.37f, Button.WHITE),
        Sample("006", 627, 1170, 0.35f, 0.28f, 0.37f, Button.WHITE),
        Sample("007", 352, 990, 0.35f, 0.28f, 0.36f, Button.WHITE),
        Sample("007", 149, 999, 0.34f, 0.29f, 0.37f, Button.WHITE),
        Sample("007", 158, 1194, 0.35f, 0.29f, 0.37f, Button.WHITE),
        Sample("014", 318, 403, 0.34f, 0.30f, 0.36f, Button.WHITE),
        Sample("014", 1033, 410, 0.33f, 0.30f, 0.37f, Button.WHITE),
        Sample("014", 844, 947, 0.34f, 0.29f, 0.37f, Button.WHITE),
        Sample("014", 510, 1440, 0.34f, 0.29f, 0.38f, Button.WHITE),
        Sample("015", 465, 384, 0.33f, 0.30f, 0.37f, Button.WHITE),
        Sample("015", 287, 943, 0.34f, 0.29f, 0.37f, Button.WHITE),

        Sample("006", 824, 792, 0.65f, 0.11f, 0.24f, Button.HAZARD),
        Sample("006", 247, 1010, 0.68f, 0.08f, 0.24f, Button.HAZARD),
        Sample("006", 816, 1168, 0.65f, 0.10f, 0.25f, Button.HAZARD),
        Sample("007", 347, 789, 0.62f, 0.13f, 0.25f, Button.HAZARD),
        Sample("007", 358, 1180, 0.63f, 0.11f, 0.26f, Button.HAZARD),
    )

    private class Sample(
        val frame: String,
        val x: Int,
        val y: Int,
        val r: Float,
        val g: Float,
        val b: Float,
        val expected: Int,
    ) {
        val chroma get() = floatArrayOf(r, g, b)
        override fun toString() = "frame $frame at ($x,$y)"
    }

    @Test
    fun `every measured button is classified correctly`() {
        for (s in samples) {
            val got = ButtonPalette.classifyChroma(s.chroma)
            assertEquals(
                "$s: expected ${Button.name(s.expected)}, got ${Button.name(got.button)}",
                s.expected,
                got.button,
            )
        }
    }

    @Test
    fun `every measured button is classified with room to spare`() {
        // Not merely on the right side of the boundary. A reading that only just wins
        // would be one exposure change away from flipping, and a flipped colour makes
        // the solver confidently wrong rather than visibly stuck.
        for (s in samples) {
            val got = ButtonPalette.classifyChroma(s.chroma)
            assertTrue(
                "$s classified as ${Button.name(got.button)} with only ${got.confidence} margin",
                got.confidence > 0.35f,
            )
        }
    }

    @Test
    fun `the classes stay apart under the ambient swing the footage actually shows`() {
        // The same white button measured rgb(87,73,111) in one frame and rgb(145,114,156)
        // in another -- a big shift in both brightness and tint. Both must read white,
        // which is the whole argument for subtracting ambient instead of thresholding
        // raw colour.
        val dimAmbient = floatArrayOf(30f, 24f, 52f)
        val brightAmbient = floatArrayOf(47f, 38f, 94f)
        val dim = ButtonPalette.classify(floatArrayOf(87f, 73f, 111f), dimAmbient)
        val bright = ButtonPalette.classify(floatArrayOf(145f, 114f, 156f), brightAmbient)
        assertEquals(Button.WHITE, dim.button)
        assertEquals(Button.WHITE, bright.button)
    }

    @Test
    fun `an unlit button reads as black rather than as a colour`() {
        // Chromaticity is scale-free, so without a floor on the excess this would
        // happily normalise sensor noise into a confident red.
        val ambient = floatArrayOf(47f, 38f, 94f)
        val reading = ButtonPalette.classify(floatArrayOf(49f, 39f, 95f), ambient)
        assertEquals(Button.EMPTY, reading.button)
    }

    @Test
    fun `a colour with no name in the palette is refused, not rounded to the nearest`() {
        // Strong yellow: nothing in the room is meant to look like this, so the honest
        // answer is "unknown", which makes the solver stop and ask rather than plan
        // around a button it has misread.
        val reading = ButtonPalette.classifyChroma(floatArrayOf(0.45f, 0.45f, 0.10f))
        assertEquals(Button.OPAQUE, reading.button)
    }

    @Test
    fun `a magenta mine is separated from white by its green fraction`() {
        // The thinnest margin in the palette, and the one with no measured sample
        // behind it. White already leans magenta, so the red-to-blue balance cannot
        // tell them apart -- only the green fraction does, about 0.29 against 0.10.
        // If this ever regresses, the app will read armed mines as clearable buttons.
        val mine = ButtonPalette.classifyChroma(floatArrayOf(0.45f, 0.10f, 0.45f))
        assertEquals(Button.MINE, mine.button)

        val white = ButtonPalette.classifyChroma(ButtonPalette.WHITE)
        assertEquals(Button.WHITE, white.button)

        // Halfway between the two is genuinely ambiguous and must be refused.
        val between = ButtonPalette.classifyChroma(floatArrayOf(0.40f, 0.20f, 0.41f))
        assertTrue(
            "a reading between white and mine should be low confidence or refused",
            between.button == Button.OPAQUE || between.confidence < 0.35f,
        )
    }
}
