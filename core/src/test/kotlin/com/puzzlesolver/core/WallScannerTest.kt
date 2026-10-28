package com.puzzlesolver.core

import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import com.puzzlesolver.core.puzzle.bombs.PlanOutcome
import com.puzzlesolver.core.puzzle.bombs.ScannedWall
import com.puzzlesolver.core.puzzle.bombs.WallScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mines wall read off a real pan, from the green button to the far end.
 *
 * The fixture is `testVideos/Mines/mines1.mp4` -- fifteen seconds of the whole wall, shot
 * on the phone's own camera app -- at every sixth frame (10 a second) and 270x480. One
 * exposure throughout, the camera's own, so every frame is a lattice frame and the
 * colours come off the glow round each clipped button: the fallback path, and the one
 * this footage can exercise. The colour exposure is exercised by [WallScanPanTest].
 *
 * The expected board was read by eye, button by button, off full-resolution crops of
 * the video: twenty-one lit buttons, every one in its own crop.
 */
class WallScannerTest {

    @Test
    fun `the reference pan reads the whole wall exactly`() {
        val wall = scan(MinesFixture.pan())
        println(wall.describe())
        wall.warnings.forEach { println("warning: $it") }

        assertTrue("the end of the wall was not seen", wall.endSeen)
        assertEquals("columns", WallScanner.COLUMNS, wall.cols)
        assertEquals("rows", WallScanner.ROWS, wall.rows)
        assertEquals(REFERENCE.trimIndent().trim() + "\n", wall.describe())
        assertTrue("warnings: ${wall.warnings}", wall.warnings.isEmpty())
    }

    @Test
    fun `the plan for the reference wall clears it without touching a hazard`() {
        val wall = scan(MinesFixture.pan())
        val started = System.nanoTime()
        val outcome = wall.plan(System.nanoTime() + 10_000_000_000L)
        println("planned in ${(System.nanoTime() - started) / 1_000_000} ms: $outcome")
        assertTrue("expected a plan, got $outcome", outcome is PlanOutcome.Found)
        val plan = (outcome as PlanOutcome.Found).plan

        var live = BombBoard.parse(REFERENCE)
        val sim = Detonation()
        for ((n, round) in plan.rounds.withIndex()) {
            for (m in round.mines) assertTrue("round ${n + 1} presses a lit button", live.isPlaceable(m))
            val fired = sim.fire(live, round.mines)
            assertFalse("round ${n + 1} fires on a red hazard", fired.losesGame)
            live = live.afterDetonation(fired.damage, round.mines)
        }
        assertTrue("board not cleared:\n$live", live.isCleared)
        println("${plan.totalMines} mines in ${plan.roundCount} detonation(s)")
    }

    @Test
    fun `a pan stopped halfway says so rather than passing for the whole wall`() {
        val frames = MinesFixture.pan()
        val wall = scan(frames.take(frames.size / 2))
        assertFalse(wall.endSeen)
        assertTrue("got ${wall.cols} columns", wall.cols in 20 until WallScanner.COLUMNS)
        assertTrue(wall.warnings.any { it.contains("before the end") })
        assertTrue(wall.warnings.any { it.contains("columns") })
    }

    @Test
    fun `nothing is read until the green has been seen`() {
        // Start the pan a second in, with the green already gone off the left edge.
        val scanner = WallScanner()
        for ((image, t) in MinesFixture.pan().drop(10)) scanner.offer(image, WallScanner.Exposure.LATTICE, t)
        assertEquals(WallScanner.Phase.FINDING_GREEN, scanner.progress().phase)
        assertEquals(null, scanner.result())
    }

    /**
     * The first scan in the room itself, on 2026-10-27, where the lattice frames came out
     * dark: the unlit buttons at 15-30 on a panel of 5, at the brightest the camera was
     * then allowed. On the phone not one lattice frame in 147 found the wall, and the
     * green was never seen. These are its first 40 frames, both kinds, as the app saved
     * them.
     */
    @Test
    fun `a dark room's lattice frames are lifted until the wall is found`() {
        val lines = ArrayList<String>()
        val scanner = WallScanner(log = { lines.add(it) })
        for ((image, t, exposure) in MinesFixture.dimRoom()) scanner.offer(image, exposure, t)
        lines.forEach { println("  $it") }

        val lattice = lines.filter { it.startsWith("lattice ") }
        val found = lattice.count { !it.contains(" none: ") }
        assertEquals("lattice frames", 20, lattice.size)
        assertTrue("the wall was found in $found of ${lattice.size} lattice frames", found >= 18)
        assertTrue("every lattice frame here is dim enough to lift", lattice.all { it.contains(" lift=") })
        assertTrue("the green was never found", lines.any { it.startsWith("green found") })
        assertNotNull(scanner.result())
    }

    @Test
    fun `an unread cell is planned around as a hazard`() {
        val wall = ScannedWall.parse(
            """
            .......
            ..W#W..
            .......
            """
        )
        val board = wall.toBombBoard()
        assertEquals(Button.HAZARD, board.kindAt(board.index(3, 1)))
        assertEquals(1, wall.unknownCount)
        val outcome = wall.plan()
        assertTrue("expected a plan, got $outcome", outcome is PlanOutcome.Found)
    }

    private fun scan(frames: List<Pair<RgbImage, Long>>): ScannedWall {
        val scanner = WallScanner(log = { println("  $it") })
        for ((image, t) in frames) scanner.offer(image, WallScanner.Exposure.LATTICE, t)
        val wall = scanner.result()
        assertNotNull("the green button was never found", wall)
        return wall!!
    }

    private companion object {
        /** The wall in `mines1.mp4`, read by eye. */
        const val REFERENCE = """
            .......B...................................................W...W
            ................................................................
            G...B...........W............W.......R..........................
            ..............................W..WR.WW........................W.
            ......................W.............WR..........................
            .....................W......B...................................
            .............W..............................................W...
        """
    }
}

/** Frames of `testVideos/Mines/mines1.mp4`, with their timestamps. */
object MinesFixture {
    fun pan(): List<Pair<RgbImage, Long>> {
        val loader = MinesFixture::class.java.classLoader!!
        val index = loader.getResourceAsStream("mines/pan/frames.txt")
            ?.bufferedReader()?.readLines()
            ?: error("mines/pan is missing from the test resources")
        return index.filter { it.isNotBlank() }.map { line ->
            val (name, t) = line.trim().split(' ')
            val image = SpotFixture.read(loader.getResourceAsStream("mines/pan/$name")!!)
            image to t.toLong()
        }
    }

    /** The first 40 frames of the scan in the room on 2026-10-27, lattice and colour. */
    fun dimRoom(): List<Triple<RgbImage, Long, WallScanner.Exposure>> {
        val loader = MinesFixture::class.java.classLoader!!
        val index = loader.getResourceAsStream("mines/dim-room/frames.txt")
            ?.bufferedReader()?.readLines()
            ?: error("mines/dim-room is missing from the test resources")
        return index.filter { it.isNotBlank() }.map { line ->
            val (name, t, kind) = line.trim().split(' ')
            val image = SpotFixture.read(loader.getResourceAsStream("mines/dim-room/$name")!!)
            val exposure = if (kind == "C") WallScanner.Exposure.COLOUR else WallScanner.Exposure.LATTICE
            Triple(image, t.toLong(), exposure)
        }
    }
}
