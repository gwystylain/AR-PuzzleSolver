package com.puzzlesolver.core

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.terminal.DisplayDetector
import com.puzzlesolver.core.puzzle.terminal.DisplayMeter
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Metering the terminal wall on its displays, against the three real walls there are.
 *
 * The reference clip is the target by definition, and the two captures from the room are
 * the two exposures the camera's own metering chose there -- both too bright, one of them
 * bright enough to read `058` as `008` all round. The meter has to say so, and say by how
 * much, because the controller turns that number straight into a new exposure.
 */
class DisplayMeterTest {

    private fun reading(frame: GrayImage): DisplayMeter.Reading? = TerminalScanner().scan(frame).exposure

    /**
     * The frame as it would have come out [stops] brighter or darker, on the same gamma
     * the meter assumes. Clipped at white, which is the whole point on the bright side:
     * the digits stop saying anything, and the meter has to fall back to the tile face.
     */
    private fun reexposed(frame: GrayImage, stops: Double): GrayImage {
        val gain = 2.0.pow(stops)
        val lut = IntArray(256) { v ->
            val linear = (v / 255.0).pow(2.2) * gain
            (linear.coerceAtMost(1.0).pow(1 / 2.2) * 255).roundToInt()
        }
        val out = GrayImage(frame.width, frame.height)
        for (i in 0 until frame.width * frame.height) {
            out.data[i] = lut[frame.data[i].toInt() and 0xFF].toByte()
        }
        return out
    }

    @Test
    fun `the reference clip is on target`() {
        val r = reading(Pgm.resource("terminal-wall.pgm"))!!
        assertFalse("the clip's digits are not clipped", r.clipped)
        assertTrue("clip meters ${r.describe()}", abs(r.stopsOver) < 0.3f)
    }

    @Test
    fun `the 17 September round was a stop over`() {
        val r = reading(Pgm.resource("terminal-room.pgm.gz"))!!
        assertTrue("half the display area was white", r.clipped)
        assertTrue("17 September meters ${r.describe()}", r.stopsOver in 0.8f..1.4f)
    }

    @Test
    fun `the 24 September round was nearly two stops over`() {
        val r = reading(Pgm.resource("terminal-bloom.pgm.gz"))!!
        assertTrue(r.clipped)
        assertTrue("24 September meters ${r.describe()}", r.stopsOver in 1.5f..2.1f)
    }

    /**
     * The number the controller acts on, checked against a shift that is known.
     *
     * Either side of where the digits clip, because that is where the estimate changes
     * which percentile it reads -- a seam there would make the controller overshoot or
     * stall just as the wall comes into range.
     */
    @Test
    fun `a known change of exposure is measured as that change`() {
        val clip = Pgm.resource("terminal-wall.pgm")
        val base = reading(clip)!!.stopsOver
        for (stops in listOf(-2.0, -1.0, -0.5, 0.5, 1.0, 1.5, 2.0, 2.5)) {
            val r = reading(reexposed(clip, stops))
            assertNotNull("wall lost at ${"%+.1f".format(stops)}", r)
            val measured = r!!.stopsOver - base
            assertTrue(
                "${"%+.1f".format(stops)} stops measured as ${"%+.2f".format(measured)} (${r.describe()})",
                abs(measured - stops) < 0.25,
            )
        }
    }

    @Test
    fun `brighter always meters brighter`() {
        val clip = Pgm.resource("terminal-wall.pgm")
        var previous = -Float.MAX_VALUE
        var stops = -2.0
        while (stops <= 3.0) {
            val r = reading(reexposed(clip, stops))!!
            assertTrue("not monotonic at ${"%+.2f".format(stops)}", r.stopsOver >= previous)
            previous = r.stopsOver
            stops += 0.25
        }
    }

    @Test
    fun `cleared displays are not metered`() {
        val result = TerminalScanner().scan(Pgm.resource("terminal-bloom.pgm.gz"))
        val lit = result.displays.count { !it.cleared }
        assertEquals("the frame has four cleared", 28, lit)
        assertEquals(lit, result.exposure!!.displays)
    }

    @Test
    fun `a few displays are not enough to meter`() {
        val frame = Pgm.resource("terminal-wall.pgm")
        val boxes = DisplayDetector().detect(frame)
        val meter = DisplayMeter()
        assertNull(meter.measure(frame, boxes.take(DisplayMeter.MIN_DISPLAYS - 1)))
        assertNotNull(meter.measure(frame, boxes.take(DisplayMeter.MIN_DISPLAYS)))
    }
}
