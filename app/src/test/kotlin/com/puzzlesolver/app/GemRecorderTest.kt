package com.puzzlesolver.app

import com.puzzlesolver.app.record.GemRecorder
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The one artifact a live Gems run leaves behind.
 *
 * Worth a test rather than a look, because every way this can fail is silent and none
 * of them shows up until hours after the room. A dump written upside down, a header the
 * fixture loader will not parse, a second burst overwriting the first, a sidecar whose
 * coordinates belong to a different frame than the image beside it -- all of those
 * produce files of a plausible size, and all of them are discovered by trying to use
 * them, which is exactly when going back is no longer possible.
 *
 * The load-bearing claim is the round trip. `GemRecorder` writes P6 PPM specifically so
 * that a frame off the wall can be dropped into `core/src/test/resources` and loaded by
 * `GemFixture`; if the bytes it writes are not the bytes that loader expects, the whole
 * point of choosing that format is lost. So the parse here is deliberately the same
 * shape as `GemFixture.load` rather than a convenience library.
 */
class GemRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- Fixtures --------------------------------------------------------

    /** A view whose RGB is known exactly, so the round trip can be measured. */
    private fun viewOf(width: Int, height: Int, colours: List<Triple<Int, Int, Int>>): CanvasView {
        val luma = GrayImage(width, height)
        val chroma = ChromaImage(width, height)
        for (i in 0 until width * height) {
            val (r, g, b) = colours[i % colours.size]
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
            val cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
            luma.data[i] = y.toInt().coerceIn(0, 255).toByte()
            chroma.data[i * 2] = cb.toInt().coerceIn(0, 255).toByte()
            chroma.data[i * 2 + 1] = cr.toInt().coerceIn(0, 255).toByte()
        }
        return CanvasView(luma, chroma)
    }

    private fun result(): GemScanner.Result = GemScanner.Result(
        gems = listOf(
            GemScanner.Gem(
                12.5f, 34.5f, 8f,
                GemPattern(GemColour.RED, GemColour.YELLOW, GemColour.BLUE),
                0.81f, 2,
            ),
            GemScanner.Gem(
                56.5f, 78.5f, 8f,
                GemPattern(GemColour.BLUE, GemColour.UNKNOWN, GemColour.PURPLE),
                0.22f, 0,
            ),
        ),
        matchCount = 1,
        blobCount = 5,
        pitch = 164.5f,
        washedOut = 0.031f,
        status = "1 match  ·  1 of 2 gems read",
    )

    private fun sample(view: CanvasView) = GemRecorder.Sample(
        view = view,
        result = result(),
        targets = listOf(
            GemPattern(GemColour.RED, GemColour.YELLOW, GemColour.BLUE),
            GemPattern.BLANK,
        ),
        cameraAsked = "manual 1/250s iso100",
        cameraActual = "4000us iso100",
        cameraHonoured = true,
        meanLuma = 41,
        droppedFrames = 7,
        scanMillis = 23.4f,
        profile = "detect 11ms pitch 0ms read 12ms",
    )

    /** Zero interval so every offer is due; the clock is not what is under test here. */
    private fun armed(directory: File, frames: Int) =
        GemRecorder(directory).also { it.arm(frames, 0L) }

    // --- Tests -----------------------------------------------------------

    @Test
    fun `writes one frame per offer and then stops`() {
        val dir = temp.newFolder("gems")
        val recorder = armed(dir, 3)
        val view = viewOf(4, 4, listOf(Triple(200, 30, 30)))

        val written = (1..6).count { recorder.offer(sample(view)) }

        assertEquals("a burst of three writes three frames and no more", 3, written)
        assertEquals(0, recorder.remaining)
        assertEquals(3, frames(dir).size)
    }

    @Test
    fun `nothing is written until a burst is armed`() {
        val dir = temp.newFolder("gems")
        val recorder = GemRecorder(dir)
        val view = viewOf(4, 4, listOf(Triple(200, 30, 30)))

        repeat(5) { recorder.offer(sample(view)) }

        assertEquals("the live path pays nothing until asked", 0, frames(dir).size)
    }

    @Test
    fun `the frame reloads as a P6 PPM carrying the colours that went in`() {
        val dir = temp.newFolder("gems")
        val colours = listOf(
            Triple(220, 20, 30),        // red
            Triple(30, 200, 90),        // green
            Triple(40, 90, 220),        // blue
            Triple(210, 200, 40),       // yellow
        )
        val view = viewOf(4, 2, colours)
        armed(dir, 1).offer(sample(view))

        val (width, height, rgb) = loadPpm(frames(dir).single())
        assertEquals(4, width)
        assertEquals(2, height)

        // BT.601 out and back is lossy only by rounding, so the tolerance is tight on
        // purpose: anything looser would pass a channel swap, which is the mistake this
        // is really here to catch.
        for (i in 0 until width * height) {
            val (r, g, b) = colours[i % colours.size]
            assertClose("pixel $i red", r, rgb[i * 3])
            assertClose("pixel $i green", g, rgb[i * 3 + 1])
            assertClose("pixel $i blue", b, rgb[i * 3 + 2])
        }
    }

    @Test
    fun `rows come out top-down, not flipped like the canvas dump`() {
        val dir = temp.newFolder("gems")
        // One row white, one row black. A vertical flip is invisible in a symmetric
        // image and silently puts every sidecar coordinate on the wrong row.
        val luma = GrayImage(2, 2)
        luma.data[0] = 255.toByte()
        luma.data[1] = 255.toByte()
        val chroma = ChromaImage(2, 2).also { it.fill(128) }
        armed(dir, 1).offer(sample(CanvasView(luma, chroma)))

        val (_, _, rgb) = loadPpm(frames(dir).single())
        assertTrue("the first row written is the first row of the image", rgb[0] > 200)
        assertTrue("the second row is the dark one", rgb[6] < 55)
    }

    @Test
    fun `the sidecar carries the frame's reading beside it`() {
        val dir = temp.newFolder("gems")
        armed(dir, 1).offer(sample(viewOf(4, 4, listOf(Triple(200, 30, 30)))))

        val log = File(dir, "gems-log.txt").readText()

        assertTrue("names the frame it belongs to", log.contains(frames(dir).single().name))
        assertTrue("pitch, without which the image is not a fixture", log.contains("pitch=164.5"))
        assertTrue("what the camera was asked for", log.contains("manual 1/250s iso100"))
        assertTrue("and what it actually did", log.contains("4000us iso100"))
        assertTrue("blobs found, separately from gems lit", log.contains("blobs=5"))
        assertTrue("the targets in play at the time", log.contains("1:red/yellow/blue"))
        assertTrue("an empty slot reads as empty", log.contains("2:-"))
        assertTrue("a read ring", log.contains("red     yellow  blue"))
        assertTrue("a ring the reader would not name", log.contains("blue    ?       purple"))
    }

    @Test
    fun `a second burst continues the numbering rather than overwriting the first`() {
        val dir = temp.newFolder("gems")
        val view = viewOf(4, 4, listOf(Triple(200, 30, 30)))
        val recorder = armed(dir, 2)
        repeat(2) { recorder.offer(sample(view)) }

        recorder.arm(2, 0L)
        repeat(2) { recorder.offer(sample(view)) }

        assertEquals(
            listOf("gem-0001.ppm.gz", "gem-0002.ppm.gz", "gem-0003.ppm.gz", "gem-0004.ppm.gz"),
            frames(dir).map { it.name },
        )
    }

    @Test
    fun `a greyscale frame is still written, because no colour arriving is a finding`() {
        val dir = temp.newFolder("gems")
        val view = CanvasView(GrayImage(4, 4).also { it.fill(120) })
        armed(dir, 1).offer(sample(view))

        assertEquals(1, frames(dir).size)
        assertTrue(File(dir, "gems-log.txt").readText().contains("NO CHROMA"))
    }

    // --- Helpers ---------------------------------------------------------

    private fun frames(dir: File): List<File> =
        dir.listFiles { f: File -> f.name.endsWith(".ppm.gz") }?.sortedBy { it.name } ?: emptyList()

    private fun assertClose(what: String, expected: Int, actual: Int) {
        assertTrue("$what: expected ~$expected but got $actual", abs(expected - actual) <= 3)
    }

    /**
     * Parses P6 the way `GemFixture.load` does, because agreeing with *that* parser is
     * the property under test. A tolerant reader here would hide exactly the header bug
     * that would make a captured frame useless as a fixture.
     */
    private fun loadPpm(file: File): Triple<Int, Int, IntArray> {
        GZIPInputStream(file.inputStream()).use { input ->
            fun token(): String {
                val sb = StringBuilder()
                var c = input.read()
                while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) {
                    c = input.read()
                }
                while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                    sb.append(c.toChar())
                    c = input.read()
                }
                return sb.toString()
            }
            assertEquals("P6", token())
            val width = token().toInt()
            val height = token().toInt()
            assertEquals("255", token())
            val raw = readFully(input, width * height * 3)
            val rgb = IntArray(raw.size) { raw[it].toInt() and 0xFF }
            return Triple(width, height, rgb)
        }
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val raw = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(raw, read, size - read)
            if (n <= 0) break
            read += n
        }
        assertEquals("short frame", size, read)
        return raw
    }
}
