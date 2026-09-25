package com.puzzlesolver.core

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall read with the phone held the other way round.
 *
 * Found on a monitor test: held in landscape the other way, the camera's buffer is upside
 * down, and the reader -- which assumed one way up -- read every display confidently and
 * wrongly. `090` came out as `060` and took the green rectangle; nothing was flagged, and
 * the ranking settled. The frames captured on that test read correctly turned over, which
 * is also what these do: the fixtures turned half a turn in code rather than more files.
 */
class TerminalUpsideDownTest {

    /** The 17 September wall, as `TerminalRoomTest` has it. */
    private val room = listOf(
        listOf("066", "025", "055", "004", "058", "024"),
        listOf("056", "045", "006", "018", "073", "084", "031"),
        listOf("044", "060", "086", "027", "030", "076"),
        listOf("070", "009", "054", "001", "083", "002", "080"),
        listOf("032", "034", "049", "003", "074", "026"),
    )

    private fun turned(frame: GrayImage): GrayImage {
        val out = GrayImage(frame.width, frame.height)
        val n = frame.width * frame.height
        for (i in 0 until n) out.data[n - 1 - i] = frame.data[i]
        return out
    }

    /** Reading order for a frame that is upside down: bottom row first, right to left. */
    private fun rowsUpsideDown(displays: List<TerminalScanner.Display>): List<List<String>> {
        val sorted = displays.sortedByDescending { it.box.y + it.box.height }
        val out = ArrayList<MutableList<TerminalScanner.Display>>()
        for (d in sorted) {
            val row = out.lastOrNull()
            val bottom = d.box.y + d.box.height
            val rowBottom = row?.get(0)?.let { it.box.y + it.box.height }
            if (row != null && rowBottom!! - bottom < row[0].box.height * 0.6f) row.add(d)
            else out.add(mutableListOf(d))
        }
        return out.map { r -> r.sortedByDescending { it.box.x + it.box.width }.map { it.text } }
    }

    @Test
    fun `a wall shot upside down reads as itself`() {
        val scanner = TerminalScanner()
        val frame = turned(Pgm.resource("terminal-room.pgm.gz"))
        scanner.scan(frame)
        val result = scanner.scan(frame)
        assertTrue(result.upsideDown)
        assertEquals(room, rowsUpsideDown(result.displays))
        assertTrue(result.settled)
        assertEquals(1, result.lowest?.value)
        assertEquals(2, result.second?.value)
    }

    @Test
    fun `the right way up is still read the right way up`() {
        val scanner = TerminalScanner()
        val frame = Pgm.resource("terminal-room.pgm.gz")
        repeat(25) {
            val result = scanner.scan(frame)
            assertFalse("turned over on scan $it", result.upsideDown)
        }
    }

    @Test
    fun `the bloomed wall and the clip read upside down too`() {
        for ((fixture, lowest) in listOf("terminal-bloom.pgm.gz" to (28 to 29), "terminal-wall.pgm" to (8 to 12))) {
            val scanner = TerminalScanner()
            val frame = turned(Pgm.resource(fixture))
            scanner.scan(frame)
            val result = scanner.scan(frame)
            assertTrue("$fixture not read upside down", result.upsideDown)
            assertEquals(fixture, lowest.first, result.lowest?.value)
            assertEquals(fixture, lowest.second, result.second?.value)
        }
    }

    /**
     * Turning the phone over mid-round. The ranking may drop out for a scan or two while
     * the scanner makes sure, but it must never point at the wrong display meanwhile.
     */
    @Test
    fun `turning the phone over mid-round never draws a wrong rectangle`() {
        val upright = Pgm.resource("terminal-room.pgm.gz")
        val over = turned(upright)
        val scanner = TerminalScanner()
        repeat(3) { scanner.scan(upright) }
        assertEquals(1, scanner.scan(upright).lowest?.value)

        var recoveredAfter = -1
        for (i in 1..10) {
            val result = scanner.scan(over)
            val lowest = result.lowest?.value
            assertTrue("scan $i after turning over put green on $lowest", lowest == null || lowest == 1)
            if (lowest == 1 && recoveredAfter < 0) recoveredAfter = i
        }
        assertTrue("recovered after $recoveredAfter scans", recoveredAfter in 1..4)
    }
}
