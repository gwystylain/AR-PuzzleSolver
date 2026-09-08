package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPalette
import com.puzzlesolver.core.puzzle.gems.GemReader
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an over-exposed gem does to the reader, and why it used to go unnoticed.
 *
 * The vote in [GemPalette] was built against *mixtures*: two rings' glows overlapping
 * make a blend, a blend is desaturated, and [GemPalette.MIN_SATURATION] throws it out.
 * Clipping is the mirror image of that and walked straight past it. When a red centre
 * dot and a green middle ring both bloom into one texel, R and G both peg at the sensor
 * ceiling and B stays low. That is not a washed-out texel -- it is a vivid, fully
 * saturated yellow, and every texel across the bloom is the same vivid yellow, so the
 * vote is unanimous and the confidence comes back high.
 *
 * On the run of 2026-08-20 that produced [GemFixture.loadBlown], 92% of middle rings
 * came back yellow or cyan for this reason, and the two targets that had been entered --
 * both with green in the inner rings -- could not match anywhere on the wall however
 * carefully the camera was aimed.
 *
 * The fix is not that the reader gets these gems right. It cannot: the information is
 * gone, clipped away in the sensor. The fix is that it stops *claiming* to get them
 * right, so the over-exposure becomes visible to the HUD and to the auto-exposure loop,
 * which is what brings the camera back down to where the rings are readable.
 */
class GemClippingTest {

    /**
     * One gem, painted by hand, so the failure can be stated exactly rather than found.
     *
     * @param centre RGB of the centre dot and everything inward of the middle ring.
     * @param outer RGB of the outer ring band.
     */
    private fun paintGem(
        pitch: Float,
        centre: Triple<Int, Int, Int>,
        outer: Triple<Int, Int, Int>,
    ): CanvasView {
        val size = (pitch * 2).toInt()
        val luma = GrayImage(size, size)
        val chroma = ChromaImage(size, size)
        chroma.fill(128)
        val cx = size / 2f
        val cy = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val d = kotlin.math.hypot(x - cx, y - cy) / pitch
                val rgb = when {
                    d <= GemPalette.MIDDLE_OUTER -> centre
                    d >= GemPalette.OUTER_INNER && d <= GemPalette.OUTER_OUTER -> outer
                    else -> Triple(0, 0, 0)
                }
                val r = rgb.first
                val g = rgb.second
                val b = rgb.third
                val yy = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
                val cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
                val i = y * size + x
                luma.data[i] = yy.toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2] = cb.toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2 + 1] = cr.toInt().coerceIn(0, 255).toByte()
            }
        }
        return CanvasView(luma, chroma)
    }

    private val green = Triple(0, 200, 40)

    @Test
    fun `a red dot bloomed into a green ring is refused, not called yellow`() {
        val pitch = 140f
        // R and G both at the ceiling with B low: exactly what the sensor hands back
        // when two LEDs of different colours bloom into the same texel. Its hue is 60
        // degrees, six degrees off the palette's yellow, and its saturation is 0.92 --
        // so nothing but the clip test can tell it is not a yellow ring.
        val doubleClipped = Triple(255, 255, 20)
        val read = GemReader().readAt(paintGem(pitch, doubleClipped, green), pitch, pitch, pitch)

        assertNotEquals(
            "a red dot blooming into a green ring must never be reported as a yellow ring",
            GemColour.YELLOW,
            read.pattern.middle,
        )
        assertEquals(GemColour.UNKNOWN, read.pattern.middle)
        assertEquals(GemColour.UNKNOWN, read.pattern.centre)
        // The outer ring is outside the bloom and still reads, which is what the run on
        // the wall showed too: outer rings kept a plausible spread of all five colours
        // while the inner two collapsed onto the two blend hues.
        assertEquals(GemColour.GREEN, read.pattern.outer)
    }

    @Test
    fun `green bloomed into blue is refused rather than called cyan`() {
        val pitch = 140f
        val doubleClipped = Triple(20, 255, 253)
        val read = GemReader().readAt(paintGem(pitch, doubleClipped, green), pitch, pitch, pitch)
        assertEquals(
            "the wall's blue measures as cyan, so a green-over-blue bloom lands on it exactly",
            GemColour.UNKNOWN,
            read.pattern.middle,
        )
    }

    @Test
    fun `one clipped channel is a lit LED and still reads`() {
        val pitch = 140f
        // A green LED at full tilt: G pegged, R and B low. One channel at the ceiling is
        // the normal state of every ring on this wall -- the other two still carry the
        // ratio that names the hue, so rejecting on one would blind the reader entirely.
        val singleClipped = Triple(30, 255, 90)
        val read = GemReader().readAt(paintGem(pitch, singleClipped, green), pitch, pitch, pitch)
        assertEquals(GemColour.GREEN, read.pattern.middle)
        assertTrue("a clean single-channel clip is not washed out", read.washedOut < 0.2f)
    }

    @Test
    fun `a double-clipped gem is reported as washed out, however saturated it looks`() {
        val pitch = 140f
        val doubleClipped = Triple(255, 255, 20)
        val read = GemReader().readAt(paintGem(pitch, doubleClipped, green), pitch, pitch, pitch)
        // This is the assertion the whole fix hangs on. The number it produces is what
        // the HUD shows and what the auto-exposure loop closes on; while double-clipped
        // texels counted as usable it read low, so nothing downstream had any reason to
        // act and the camera stayed two stops too bright for the rest of the session.
        assertTrue(
            "washedOut came out at ${read.washedOut}, which no threshold would act on",
            read.washedOut > 0.35f,
        )
    }

    @Test
    fun `the frame that broke the run is now called over-exposed`() {
        val view = GemFixture.loadBlown()
        val result = GemScanner(GemTargets()).scan(view)

        assertTrue("found ${result.blobCount} gems", result.blobCount >= 12)
        // Measured at 0.19 before the clip test existed -- under the 0.35 the HUD acts
        // on and under the 0.25 the auto-exposure loop aims for, so the app looked at
        // this frame and concluded there was simply no match in view.
        assertTrue(
            "washedOut came out at ${result.washedOut}; this frame must read as over-exposed",
            result.washedOut > 0.35f,
        )
        assertTrue(
            "the HUD must say what is wrong and what to do: ${result.status}",
            result.status.contains("over-exposed") && result.status.contains("darken"),
        )
    }

    /**
     * The read that started it: at this exposure the reader is not merely wrong about
     * the inner rings, it is wrong in one direction. Yellow and cyan are the two hues a
     * pair of clipped channels can make, and nothing else on the wall produces them in
     * that quantity.
     */
    @Test
    fun `the inner rings of the blown frame no longer come back uniformly yellow or cyan`() {
        val view = GemFixture.loadBlown()
        val reader = GemReader()
        var blend = 0
        var total = 0
        for (i in GemFixture.BLOWN_POSITIONS.indices step 2) {
            val read = reader.readAt(
                view,
                GemFixture.BLOWN_POSITIONS[i],
                GemFixture.BLOWN_POSITIONS[i + 1],
                GemFixture.BLOWN_PITCH,
            )
            total++
            if (read.pattern.middle == GemColour.YELLOW || read.pattern.middle == GemColour.BLUE) blend++
        }
        // Before the clip test this was 17 of 17 -- every middle ring in the crop named
        // as one of the two blend hues, each with a confidence to match.
        assertTrue("$blend of $total middle rings still read as a blend hue", blend < total)
    }
}
