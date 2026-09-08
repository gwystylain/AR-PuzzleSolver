package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ring reading against real pixels off the wall.
 *
 * The fixture is a crop of `testVideos/Gems/VID20260814182431.mp4`, one of the two
 * clips shot at a reduced exposure -- the only ones in which the rings are visible at
 * all, which is a finding in itself and is documented in [docs/GEM_PUZZLE.md]. Eleven
 * gems in it were labelled by eye at high zoom, and this asserts the classifier agrees.
 *
 * Deliberately *not* an end-to-end test. It hands [GemReader] gem centres measured by
 * hand rather than running lattice detection, because the frame is an unrectified
 * camera image and the lattice in it is perspective-warped -- exactly what the canvas
 * removes before any adapter sees it. Mixing the two would mean a geometry regression
 * showing up as a colour failure. `GemEndToEndTest` covers the whole chain on a
 * rectified board; this covers the one part a synthetic board cannot honestly test,
 * which is whether real LEDs under real bloom classify correctly.
 *
 * The thresholds encode where the difficulty actually lies. The outer ring is the
 * largest band and the furthest from anything else glowing, and it is required to be
 * perfect. The centre dot is a handful of texels sitting under the combined glow of
 * both rings outside it, and is only required to be mostly right.
 */
class RealGemFrameTest {

    /** Lattice pitch measured in the source frame, in pixels. Unchanged by the crop. */
    private val pitch = 165.2f

    /**
     * Gem centres in fixture coordinates, with the three rings read by eye at 8x zoom.
     * A null zone is one the labeller would not commit to, and is not asserted.
     */
    private val labelled = listOf(
        Gem(85.7f, 92.0f, GemColour.RED, GemColour.YELLOW, GemColour.RED),
        Gem(264.5f, 102.7f, GemColour.PURPLE, GemColour.BLUE, GemColour.BLUE),
        Gem(85.8f, 272.5f, GemColour.RED, GemColour.YELLOW, null),
        Gem(261.4f, 281.2f, GemColour.RED, GemColour.GREEN, GemColour.GREEN),
        Gem(430.5f, 287.0f, GemColour.YELLOW, GemColour.BLUE, GemColour.BLUE),
        Gem(85.6f, 445.6f, GemColour.YELLOW, GemColour.BLUE, GemColour.BLUE),
        Gem(257.6f, 451.8f, GemColour.BLUE, GemColour.BLUE, null),
        Gem(422.7f, 458.9f, GemColour.RED, GemColour.GREEN, GemColour.GREEN),
        Gem(85.0f, 612.4f, GemColour.BLUE, GemColour.PURPLE, GemColour.PURPLE),
        Gem(253.9f, 617.4f, GemColour.BLUE, GemColour.YELLOW, GemColour.RED),
        Gem(415.3f, 624.9f, GemColour.BLUE, GemColour.BLUE, GemColour.PURPLE),
    )

    private class Gem(
        val x: Float,
        val y: Float,
        val outer: Int,
        val middle: Int,
        val centre: Int?,
    )

    @Test
    fun `outer rings are read exactly`() {
        val view = loadFixture()
        val reader = GemReader()
        val wrong = StringBuilder()
        for (gem in labelled) {
            val read = reader.readAt(view, gem.x, gem.y, pitch).pattern
            if (read.outer != gem.outer) {
                wrong.append("\n  (${gem.x}, ${gem.y}) expected ${GemColour.name(gem.outer)} ")
                    .append("but read ${GemColour.name(read.outer)}")
            }
        }
        assertEquals("outer ring misreads:$wrong", 0, wrong.count { it == '\n' })
    }

    @Test
    fun `all three rings are mostly right`() {
        val view = loadFixture()
        val reader = GemReader()
        var asserted = 0
        var correct = 0
        val report = StringBuilder()
        for (gem in labelled) {
            val read = reader.readAt(view, gem.x, gem.y, pitch).pattern
            val expected = listOf(gem.outer, gem.middle, gem.centre)
            val got = listOf(read.outer, read.middle, read.centre)
            for (zone in 0..2) {
                val want = expected[zone] ?: continue
                asserted++
                if (want == got[zone]) {
                    correct++
                } else {
                    report.append("\n  (${gem.x}, ${gem.y}) ${GemPattern.zoneName(zone)}: ")
                        .append("expected ${GemColour.name(want)}, read ${GemColour.name(got[zone])}")
                }
            }
        }
        val accuracy = correct.toFloat() / asserted
        assertTrue(
            "only $correct of $asserted rings read correctly (${(accuracy * 100).toInt()}%):$report",
            accuracy >= 0.8f,
        )
    }

    @Test
    fun `a gem on this wall is never unlit`() {
        val view = loadFixture()
        val reader = GemReader()
        for (gem in labelled) {
            assertTrue(
                "gem at (${gem.x}, ${gem.y}) read as unlit",
                reader.readAt(view, gem.x, gem.y, pitch).lit,
            )
        }
    }

    /**
     * Loads the PPM and encodes it the way the capture path does.
     *
     * Going through luma plus BT.601 chroma rather than handing the reader RGB
     * directly is the point: that round trip is what the GPU accumulator does, and any
     * hue the encoding loses would be lost on device too. Testing against raw RGB
     * would flatter the classifier by removing a step it actually has to survive.
     */
    private fun loadFixture(): CanvasView {
        val stream = javaClass.classLoader!!.getResourceAsStream("gems-wall.ppm")
            ?: error("fixture missing")
        stream.use { input ->
            fun token(): String {
                val sb = StringBuilder()
                var c = input.read()
                while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = input.read()
                if (c == '#'.code) {
                    while (c != '\n'.code) c = input.read()
                    return token()
                }
                while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                    sb.append(c.toChar())
                    c = input.read()
                }
                return sb.toString()
            }
            require(token() == "P6")
            val w = token().toInt()
            val h = token().toInt()
            token()                                     // maxval
            val raw = ByteArray(w * h * 3)
            var read = 0
            while (read < raw.size) {
                val n = input.read(raw, read, raw.size - read)
                if (n <= 0) break
                read += n
            }
            require(read == raw.size) { "short fixture: $read of ${raw.size}" }

            val luma = GrayImage(w, h)
            val chroma = ChromaImage(w, h)
            for (i in 0 until w * h) {
                val r = (raw[i * 3].toInt() and 0xFF).toFloat()
                val g = (raw[i * 3 + 1].toInt() and 0xFF).toFloat()
                val b = (raw[i * 3 + 2].toInt() and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
                val cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
                luma.data[i] = y.toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2] = cb.toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2 + 1] = cr.toInt().coerceIn(0, 255).toByte()
            }
            return CanvasView(luma, chroma)
        }
    }
}
