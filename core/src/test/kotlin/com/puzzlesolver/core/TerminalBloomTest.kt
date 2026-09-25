package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.terminal.DisplayDetector
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal reader against a wall shot bright enough that the digits bloom.
 *
 * `term-0025` of the capture from the round played on 24 September, with the shutter
 * pinned at 1/53 s and ISO 6400 -- nearly a stop brighter than the 17 September capture
 * `TerminalRoomTest` reads, and enough that every digit is clipped to white and its
 * counters have shrunk to a few pixels. Where a zero, five, six, eight or nine is widest
 * the gap between it and the tile's border closes, the seam bridges the rest, and the
 * border came through the opening joined to the digit. Every wide digit then had the
 * same silhouette and the counters were all that was left to tell them apart. On this
 * frame that read `058` as `008`, `059` as `009`, `057` as `007`, `065` as `060` and `046`
 * as `040` -- and put the green rectangle on `007` and the yellow on `008`, where the two
 * lowest numbers on the wall were `028` and `029`. The player reported it as the app
 * mixing up eights and zeros.
 *
 * `DigitReader.severBorders` is the fix and a set of templates from the same exposure is
 * the rest of it. This frame is held out of that set: it comes from the second burst of
 * the capture, and the templates from the first.
 */
class TerminalBloomTest {

    /** The wall as it stood, labelled by eye from the same frame. Four displays are clear. */
    private val expected = listOf(
        listOf("046", "066", "093", "072", "033", "070"),
        listOf("034", "083", "---", "---", "058", "097", "071"),
        listOf("028", "060", "029", "---", "067", "049"),
        listOf("086", "038", "061", "062", "---", "059", "065"),
        listOf("042", "090", "045", "052", "073", "057"),
    )

    /** The displays this frame used to misread, each as what it used to read. */
    private val misread = mapOf("058" to "008", "059" to "009", "057" to "007", "065" to "060", "046" to "040")

    private fun frame() = Pgm.resource("terminal-bloom.pgm.gz")

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

    private fun settled(): TerminalScanner.Result {
        val scanner = TerminalScanner()
        scanner.scan(frame())
        return scanner.scan(frame())
    }

    @Test
    fun `finds the whole wall`() {
        assertEquals(32, DisplayDetector().detect(frame()).size)
    }

    @Test
    fun `nothing on the wall is read as the wrong number`() {
        val result = settled()
        val read = rows(result.displays)
        assertEquals(expected.map { it.size }, read.map { it.size })
        for ((ri, row) in read.withIndex()) {
            for ((ci, d) in row.withIndex()) {
                val truth = expected[ri][ci]
                when {
                    truth == "---" -> assertTrue("$truth at ($ri,$ci) is clear, read ${d.text}", d.cleared)
                    d.value != null -> assertEquals("display at ($ri,$ci)", truth, d.text)
                }
            }
        }
        // Two are left unread rather than guessed at: 071, whose box takes in its lit
        // housing, and 049, with glare across the foot of the nine. Illegible is an honest
        // answer; a wrong number is the failure this test is here for.
        assertTrue("${result.unread} displays unread", result.unread <= 2)
        assertEquals(26, result.remaining)
    }

    @Test
    fun `the digits that used to read as zeros read as themselves`() {
        val texts = settled().displays.map { it.text }.toSet()
        val onWall = expected.flatten().toSet()
        for ((truth, old) in misread) {
            assertTrue("$truth was not read", truth in texts)
            // 060 is on the wall in its own right, so only the others can be absent.
            if (old !in onWall) assertTrue("$old is not on the wall but was read", old !in texts)
        }
    }

    @Test
    fun `the green rectangle is on 028, not on 007`() {
        val result = settled()
        assertTrue(result.settled)
        assertEquals(28, result.lowest?.value)
        assertEquals(29, result.second?.value)
    }
}
