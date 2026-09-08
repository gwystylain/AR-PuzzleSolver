package com.puzzlesolver.core

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.terminal.DigitReader
import com.puzzlesolver.core.puzzle.terminal.DisplayDetector
import com.puzzlesolver.core.puzzle.terminal.GlyphNormaliser
import com.puzzlesolver.core.puzzle.terminal.TerminalDigits
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal read end to end, against real pixels off the wall.
 *
 * The fixture is frame 0 of `testVideos/Terminal/VID20260904193707.mp4` scaled to
 * 1280x720 -- a whole thirty-two display wall in a dark room, shot handheld on the
 * phone's own camera at its own exposure, with one display already cleared. The
 * expected numbers were read off it by eye at high zoom.
 *
 * This is the test that matters for this mode, because every hard part of it is a
 * property of real pixels rather than of the algorithm: whether a panel comes back as
 * one blob, whether an opening actually separates a digit from the tile it is drawn on,
 * and whether the reading is stable enough to rank. None of those can be honestly
 * answered by a synthetic board, and all of them can be answered in a few milliseconds
 * here rather than by installing on a device and standing in a room.
 *
 * **What this does and does not establish.** The shipped templates
 * ([com.puzzlesolver.core.puzzle.terminal.TerminalDigits]) are averaged from this same
 * clip, so this is not independent evidence that the classifier generalises to another
 * installation -- and it is not claimed to be. Frame 0 is at least genuinely held out:
 * the templates were built from frames 20 onward, at a different distance and with
 * different motion blur. What the test does establish, and what it is here for, is that
 * the whole chain -- detect, isolate, normalise, match, rank -- reproduces a wall of
 * numbers labelled by hand, and it will say so the moment any stage of that changes.
 */
class TerminalWallTest {

    /** The wall as labelled by eye, row by row, top to bottom and left to right. */
    private val expected = listOf(
        listOf("048", "094", "024", "086", "090", "072"),
        listOf("087", "012", "091", "083", "030", "085", "065"),
        listOf("057", "099", "032", "008", "089", "043"),
        listOf("059", "074", "---", "082", "069", "021", "073"),
        listOf("027", "039", "093", "071", "018", "052"),
    )

    private fun loadFixture(): GrayImage {
        val stream = javaClass.classLoader!!.getResourceAsStream("terminal-wall.pgm")
            ?: error("fixture missing")
        stream.use { input ->
            // PGM: "P5", width height, maxval, then raw bytes. Comments start with '#'.
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
            require(token() == "P5")
            val w = token().toInt()
            val h = token().toInt()
            token()                                     // maxval
            val data = ByteArray(w * h)
            var read = 0
            while (read < data.size) {
                val n = input.read(data, read, data.size - read)
                if (n <= 0) break
                read += n
            }
            require(read == data.size) { "short fixture: $read of ${data.size}" }
            return GrayImage(w, h, data)
        }
    }

    private fun scanner() = TerminalScanner()

    /** Groups displays into wall rows the way a person reading the wall would. */
    private fun rows(displays: List<TerminalScanner.Display>): List<List<TerminalScanner.Display>> {
        val sorted = displays.sortedBy { it.box.y }
        val out = ArrayList<MutableList<TerminalScanner.Display>>()
        for (d in sorted) {
            val row = out.lastOrNull()
            if (row != null && d.box.y - row[0].box.y < row[0].box.height * 0.6f) {
                row.add(d)
            } else {
                out.add(mutableListOf(d))
            }
        }
        return out.map { it.sortedBy { d -> d.box.x } }
    }

    @Test
    fun `fixture loads with plausible content`() {
        val img = loadFixture()
        assertEquals(1280, img.width)
        assertEquals(720, img.height)
        var lit = 0
        for (b in img.data) if ((b.toInt() and 0xFF) > 120) lit++
        val fraction = lit.toFloat() / img.data.size
        // A dark room with a wall of lit panels in it: a few per cent, not none and not
        // most. Guards against loading a black frame or a blown-out one.
        assertTrue("suspicious lit fraction $fraction", fraction in 0.01f..0.30f)
    }

    @Test
    fun `finds every display on the wall`() {
        val boxes = DisplayDetector().detect(loadFixture())
        assertEquals("expected 32 displays", 32, boxes.size)
        // All the same size, which is what says they are displays and not an assortment
        // of bright things that happen to be rectangular.
        val widths = boxes.map { it.width }.sorted()
        assertTrue(
            "display widths spread too far: ${widths.first()}..${widths.last()}",
            widths.last() <= widths.first() * 1.35,
        )
    }

    @Test
    fun `reads the whole wall`() {
        val result = scanner().scan(loadFixture())
        val read = rows(result.displays).map { row -> row.map { it.text } }
        assertEquals(expected, read)
        assertEquals("31 displays still show a number", 31, result.remaining)
        assertEquals("nothing should be illegible", 0, result.unread)
    }

    @Test
    fun `ranks the lowest two once the reading has settled`() {
        val scanner = scanner()
        val frame = loadFixture()

        // The first scan has nothing to agree with, so it publishes no ranking. That is
        // the design and not a wrinkle: see TerminalScanner.
        val first = scanner.scan(frame)
        assertTrue("nothing should be settled on the first scan", !first.settled)
        assertEquals(null, first.lowest)

        val second = scanner.scan(frame)
        assertTrue("a repeated frame must settle", second.settled)
        assertEquals(8, second.lowest?.value)
        assertEquals(12, second.second?.value)
        assertTrue("status should name the next number", second.status.contains("008"))

        // Exactly one of each, or the overlay would draw two green rectangles.
        assertEquals(1, second.displays.count { it.rank == 0 })
        assertEquals(1, second.displays.count { it.rank == 1 })
    }

    @Test
    fun `a cleared display is read as cleared rather than as a number`() {
        val scanner = scanner()
        val frame = loadFixture()
        scanner.scan(frame)
        val result = scanner.scan(frame)
        val cleared = result.displays.filter { it.cleared }
        assertEquals("one display has already been hit", 1, cleared.size)
        assertEquals(null, cleared[0].value)
        assertEquals("---", cleared[0].text)
        assertEquals("a cleared display is never ranked", -1, cleared[0].rank)
    }

    /**
     * The reader has to keep working as the user moves, and the only axis of that a
     * still fixture can exercise is distance. Half scale puts a display at about
     * seventy-five pixels across, which is as far back as the whole wall can be framed
     * on this phone.
     */
    @Test
    fun `still reads the wall from further back`() {
        val full = loadFixture()
        val half = GrayImage(full.width / 2, full.height / 2)
        full.subImageDecimated(0, 0, full.width, full.height, 2, half)

        val scanner = scanner()
        scanner.scan(half)
        val result = scanner.scan(half)
        assertEquals(32, result.displays.size)
        assertNotNull("should still find a lowest", result.lowest)
        assertEquals(8, result.lowest?.value)
        assertEquals(12, result.second?.value)
    }

    /**
     * Prints the read wall rather than asserting on it, so a failing read can be
     * understood from one test run instead of a sequence of guesses. Kept as a test so
     * it stays compiling and runnable.
     */
    @Test
    fun `report what the reader sees`() {
        val detector = DisplayDetector()
        val scanner = TerminalScanner(detector = detector)
        val frame = loadFixture()
        scanner.scan(frame)
        val result = scanner.scan(frame)
        println("detector: ${detector.lastReport}")
        println("profile:  ${scanner.describeProfile()}")
        println("status:   ${result.status}")
        for (row in rows(result.displays)) {
            println(row.joinToString("  ") { d ->
                val tag = when (d.rank) {
                    0 -> "G"
                    1 -> "Y"
                    else -> " "
                }
                "$tag${d.text}@${"%.2f".format(d.confidence)}"
            })
        }
        assertTrue(result.displays.isNotEmpty())
    }

    @Test
    fun `the shipped templates are ten well-formed digits`() {
        val templates = TerminalDigits.templates
        assertEquals(10, templates.size)
        assertEquals((0..9).toList(), templates.map { it.label })
        for (t in templates) {
            assertEquals(GlyphNormaliser.SIZE, t.width)
            assertEquals(GlyphNormaliser.SIZE, t.height)
            assertTrue("template ${t.label} is blank", t.norm > 1f)
        }
    }

    /**
     * How much room the classifier actually has, measured rather than assumed.
     *
     * Ten glyphs drawn at one size in one frame correlate highly with each other
     * whatever they are -- five against eight is 0.92 here -- so the pairwise similarity
     * of the templates says nothing useful on its own. The number that matters is the
     * gap between the right template and the best wrong one *on a real glyph*, and that
     * is what this measures. It is also where
     * [com.puzzlesolver.core.puzzle.terminal.TerminalDigits.MIN_CORRELATION] comes from.
     */
    @Test
    fun `every glyph on the wall wins by a margin`() {
        val frame = loadFixture()
        val boxes = DisplayDetector().detect(frame)
        val reader = DigitReader(object : com.puzzlesolver.core.puzzle.GlyphClassifier {
            override fun classify(normalized: GrayImage) = -1 to 0f
        })
        val templates = TerminalDigits.templates
        val labels = rows(
            boxes.map { TerminalScanner.Display(it, null, "", 0f, false, -1) }
        ).flatMapIndexed { ri, row -> row.mapIndexed { ci, d -> d.box to expected[ri][ci] } }

        var worstScore = 1f
        var worstMargin = 1f
        var worstWhere = ""
        var checked = 0
        for ((box, label) in labels) {
            if (label.startsWith("-")) continue
            for (k in 0 until DigitReader.TILES) {
                val glyph = reader.isolate(frame, box, k) ?: error("tile $k of $label read as blank")
                val scores = templates.map { it.label to correlate(glyph, it) }.sortedByDescending { it.second }
                assertEquals("misread $label tile $k", label[k] - '0', scores[0].first)
                val margin = scores[0].second - scores[1].second
                if (scores[0].second < worstScore) worstScore = scores[0].second
                if (margin < worstMargin) {
                    worstMargin = margin
                    worstWhere = "$label tile $k (${scores[0].first} over ${scores[1].first})"
                }
                checked++
            }
        }
        println("$checked glyphs; worst correlation $worstScore, worst margin $worstMargin at $worstWhere")
        assertEquals(93, checked)
        // The classifier calls anything below 0.70 unreadable, so a real glyph has to
        // clear that with room to spare or the mode would drop digits at the first bit
        // of glare.
        assertTrue("worst correlation $worstScore is too close to the floor", worstScore > 0.85f)
        assertTrue("worst margin $worstMargin", worstMargin > 0.04f)
    }

    private fun correlate(
        glyph: GrayImage,
        template: com.puzzlesolver.core.puzzle.TemplateGlyphClassifier.Template,
    ): Float {
        val n = glyph.width * glyph.height
        var mean = 0f
        for (i in 0 until n) mean += (glyph.data[i].toInt() and 0xFF).toFloat()
        mean /= n
        var dot = 0f
        var acc = 0f
        for (i in 0 until n) {
            val v = (glyph.data[i].toInt() and 0xFF) - mean
            dot += v * template.values[i]
            acc += v * v
        }
        return dot / (kotlin.math.sqrt(acc) * template.norm)
    }

    @Test
    fun `patch geometry is what the morphology assumes`() {
        // The opening packs a mask row into one Long, which only works while the patch
        // is no wider than 64. Nothing enforces that at compile time.
        assertTrue("patch too wide to pack into a Long", DigitReader.PATCH_WIDTH <= 64)
    }
}
