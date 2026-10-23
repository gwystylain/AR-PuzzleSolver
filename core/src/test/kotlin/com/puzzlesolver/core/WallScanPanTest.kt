package com.puzzlesolver.core

import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.WallScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A pan the way the app takes it: lattice frames and colour frames, alternating.
 *
 * The reference footage was shot at one exposure, so it can only test half of the
 * scanner. This renders the other half. Lattice frames show every button, lit ones as
 * plain white blooms -- deliberately carrying no colour at all, so any colour on the
 * board read back has to have come from the colour frames. Colour frames are at the
 * Gems exposure: black panel, black unlit buttons, and lit faces in the hues the gem
 * wall measured on this camera.
 *
 * The camera moves between the two, with a little roll and drift, at thirty frames a
 * second -- so every colour frame has to be mapped onto a lattice taken 33 ms earlier.
 */
class WallScanPanTest {

    private val pitch = 30f
    private val width = 480
    private val height = 300

    /** Every colour, a mine already placed, and lit buttons spread along the whole wall. */
    private val truth = BombBoard.parse(
        """
        ....W.........B......W..........R..........W.....B.........W....
        .W.......R.........W.....X..........W..........W.......W........
        G.....B......W...........W....B..........R......W...........B...
        ...W.....W.......R...W.......W.....W..W.........R.....W.........
        ........B.....W.........B........W.........B..........W......W..
        ..W........W.......W.......W.....R......W......W..........B.....
        ......R........W.......W........W.........W........W.........W..
        """
    )

    private fun ledAtColourExposure(kind: Int): IntArray? = when (kind) {
        Button.WHITE -> intArrayOf(222, 226, 214)
        Button.BLUE -> intArrayOf(40, 172, 240)
        Button.HAZARD -> intArrayOf(240, 28, 64)
        Button.GREEN -> intArrayOf(40, 232, 110)
        Button.MINE -> intArrayOf(214, 36, 236)
        else -> null
    }

    private fun render(cameraX: Float, cameraY: Float, roll: Float, colourFrame: Boolean): RgbImage {
        val img = RgbImage(width, height)
        val panel = if (colourFrame) intArrayOf(2, 2, 3) else intArrayOf(45, 43, 78)
        val unlit = if (colourFrame) intArrayOf(5, 5, 6) else intArrayOf(176, 166, 196)
        val cr = cos(roll)
        val sr = sin(roll)
        val face = pitch * 0.26f
        val glow = pitch * 0.42f
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Screen to wall: undo the roll about the frame centre, then the camera.
                val dx = x - width / 2f
                val dy = y - height / 2f
                val wx = dx * cr + dy * sr + cameraX
                val wy = -dx * sr + dy * cr + cameraY
                val col = Math.round(wx / pitch)
                val row = Math.round(wy / pitch)
                var rgb = panel
                if (col in 0 until truth.cols && row in 0 until truth.rows) {
                    val d = hypot(wx - col * pitch, wy - row * pitch)
                    val kind = truth.kindAt(truth.index(col, row))
                    val led = ledAtColourExposure(kind)
                    rgb = when {
                        led == null -> if (d <= face) unlit else panel
                        colourFrame -> if (d <= face) led else panel
                        d <= face -> intArrayOf(255, 255, 255)
                        d <= glow -> {
                            // A white bloom, the same for every colour.
                            val f = 1f - (d - face) / (glow - face)
                            IntArray(3) { (panel[it] + (230 - panel[it]) * f * 0.6f).toInt() }
                        }
                        else -> panel
                    }
                }
                img.set(x, y, rgb[0], rgb[1], rgb[2])
            }
        }
        return img
    }

    @Test
    fun `alternating lattice and colour frames read every colour`() {
        val scanner = WallScanner(log = { if (!it.startsWith("colour")) println("  $it") })
        val columnsPerSecond = 4f
        val frameNanos = 33_333_333L
        // Starts with the green a column and a half in from the left, all rows in view.
        val startX = width / 2f - pitch * 1.5f
        val endX = truth.cols * pitch + width / 2f - pitch * 2f
        val rowsCentre = (truth.rows - 1) * pitch / 2f
        var t = 0L
        var n = 0
        while (true) {
            val seconds = t / 1e9f
            val x = startX + seconds * columnsPerSecond * pitch
            if (x > endX) break
            val y = rowsCentre + 6f * sin(seconds * 1.3f)
            val roll = Math.toRadians(1.5 * sin(seconds * 0.7)).toFloat()
            val colour = n % 2 == 1
            scanner.offer(render(x, y, roll, colour), if (colour) WallScanner.Exposure.COLOUR else WallScanner.Exposure.LATTICE, t)
            t += frameNanos
            n++
        }
        val wall = scanner.result()
        assertNotNull("the green was never found", wall)
        wall!!
        println(wall.describe())
        wall.warnings.forEach { println("warning: $it") }
        assertTrue(wall.endSeen)
        assertEquals(truth.cols, wall.cols)
        assertEquals(truth.toString().trim(), wall.describe().trim())
    }

    @Test
    fun `losing the wall for a second and coming back finds the place by the lit buttons`() {
        val scanner = WallScanner(log = { if (it.contains("relocated") || it.contains("unplaced")) println("  $it") })
        val frameNanos = 33_333_333L
        val startX = width / 2f - pitch * 1.5f
        val endX = truth.cols * pitch + width / 2f - pitch * 2f
        val rowsCentre = (truth.rows - 1) * pitch / 2f
        var t = 0L
        var n = 0
        var relocated = false
        while (true) {
            val seconds = t / 1e9f
            val x = startX + seconds * 4f * pitch
            if (x > endX) break
            val colour = n % 2 == 1
            // Pointed at the floor from 5 s to 6 s: black frames, and the pan carries on.
            val frame = if (seconds in 5f..6f) RgbImage(width, height) else render(x, rowsCentre, 0f, colour)
            val p = scanner.offer(frame, if (colour) WallScanner.Exposure.COLOUR else WallScanner.Exposure.LATTICE, t)
            if (seconds > 5.8f && seconds < 6f) assertEquals(WallScanner.Phase.LOST, p.phase)
            if (seconds > 6.5f && p.phase == WallScanner.Phase.SCANNING) relocated = true
            t += frameNanos
            n++
        }
        assertTrue("never picked the count up again", relocated)
        val wall = scanner.result()!!
        assertEquals(truth.cols, wall.cols)
        assertEquals(truth.toString().trim(), wall.describe().trim())
    }
}
