package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.terminal.DisplayDetector
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal reader against a frame captured *in the room, mid-round, by the app*.
 *
 * `TerminalWallTest` reads a frame from a clip filmed on the phone's camera app. This
 * reads the first frame of the first capture `TerminalRecorder` ever brought back, and
 * the two are different in the way that matters: this one has the failure the clip never
 * showed. Shot from where a player stands, the octagonal housings at the ends of the wall
 * face the camera and catch enough light that the coarse detector swallows them into the
 * display's box. Eight boxes in this frame came out a third too wide; the three equal
 * tiles they were cut into landed mid-digit, and `066` read as `??6`, `076` as `07?`,
 * `080` as `000` -- which, being the lowest number on the wall, got the green rectangle.
 *
 * That is the whole of the quarter of the wall that two visits reported unread, and it
 * was diagnosed as motion blur until this frame arrived and showed sharp digits in wide
 * boxes. `DisplayDetector.refine` is the fix, and this test is the evidence that it
 * holds: every one of the 32 displays reads, including the eight that did not.
 *
 * The frame is 1920x1080, the live capture size, at the exposure the phone was actually
 * running -- which was two stops over, for a reason that is also now fixed. It is kept at
 * that exposure deliberately: a fixture that reads *despite* clipped digits is worth more
 * than one that reads because they were clean.
 */
class TerminalRoomTest {

    /** The wall as it stood at 17:24, labelled by eye from the same frame. */
    private val expected = listOf(
        listOf("066", "025", "055", "004", "058", "024"),
        listOf("056", "045", "006", "018", "073", "084", "031"),
        listOf("044", "060", "086", "027", "030", "076"),
        listOf("070", "009", "054", "001", "083", "002", "080"),
        listOf("032", "034", "049", "003", "074", "026"),
    )

    /** The eight displays whose boxes swallowed the housing before the refinement. */
    private val swallowed = setOf("066", "025", "024", "056", "045", "076", "080", "044")

    private fun frame() = Pgm.resource("terminal-room.pgm.gz")

    private fun rows(displays: List<TerminalScanner.Display>): List<List<TerminalScanner.Display>> {
        val sorted = displays.sortedBy { it.box.y }
        val out = ArrayList<MutableList<TerminalScanner.Display>>()
        for (d in sorted) {
            val row = out.lastOrNull()
            if (row != null && d.box.y - row[0].box.y < row[0].box.height * 0.6f) row.add(d)
            else out.add(mutableListOf(d))
        }
        return out.map { it.sortedBy { d -> d.box.x } }
    }

    @Test
    fun `the capture round-trips from the phone`() {
        val img = frame()
        assertEquals(1920, img.width)
        assertEquals(1080, img.height)
    }

    @Test
    fun `finds the wall and nothing else`() {
        // 32 displays, and not the lit rim of the side panel at the left edge of the
        // frame, which the coarse pass returned as a 33rd in every frame of the capture.
        val boxes = DisplayDetector().detect(frame())
        assertEquals(32, boxes.size)
        for (b in boxes) assertTrue("a box touches the frame edge at x=${b.x}", b.x > 0)
    }

    @Test
    fun `boxes hug the tiles, housing or not`() {
        // The measured signature of the failure was width: read displays at 120-128,
        // swallowed ones at 140-176. After refinement they are one population.
        val widths = DisplayDetector().detect(frame()).map { it.width }.sorted()
        assertTrue(
            "widths ${widths.first()}..${widths.last()} still span a housing",
            widths.last() <= widths.first() * 1.15,
        )
    }

    @Test
    fun `reads every display on the wall`() {
        val scanner = TerminalScanner()
        scanner.scan(frame())
        val result = scanner.scan(frame())
        val read = rows(result.displays).map { row -> row.map { it.text } }
        assertEquals(expected, read)
        assertEquals("every display shows a number", 32, result.remaining)
        assertEquals(0, result.unread)

        // And the swallowed ones in particular, by name, so a regression says which.
        val texts = result.displays.map { it.text }.toSet()
        for (s in swallowed) assertTrue("$s was not read", s in texts)
    }

    @Test
    fun `the green rectangle is on 001, not on 080`() {
        val scanner = TerminalScanner()
        scanner.scan(frame())
        val result = scanner.scan(frame())
        assertTrue(result.settled)
        assertEquals(1, result.lowest?.value)
        assertEquals(2, result.second?.value)
        // The display that used to read as 000 and take the green rectangle.
        val eighty = result.displays.first { it.text == "080" }
        assertEquals(-1, eighty.rank)
    }
}
