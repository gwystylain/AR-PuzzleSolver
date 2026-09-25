package com.puzzlesolver.app

import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.app.pipeline.TerminalExposure
import com.puzzlesolver.core.puzzle.terminal.DisplayMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The loop that keeps the terminal wall exposed on its displays rather than on the room.
 *
 * A state machine with a clock and nothing else, tested the way `AutoExposure` is. What
 * matters most is what it does *not* do: move on one frame, move while a change is still
 * reaching the sensor, move twice in two seconds, or move at all when the wall is not in
 * view. Every change it makes is a gap of a few hundred milliseconds in the preview.
 */
class TerminalExposureTest {

    private class FakeClock {
        var now = 1_000_000L
        fun tick(millis: Long) { now += millis }
    }

    /** The CPH2655, as its camera describes itself. */
    private val caps = CameraTuning.Capabilities(
        available = true,
        manualSensor = true,
        minEvSteps = -18,
        maxEvSteps = 18,
        evStepsPerEv = 6,
        minExposureNanos = 83_000L,
        maxExposureNanos = 30_000_000_000L,
        minIso = 100,
        maxIso = 6400,
    )

    /** A camera running [exposureNanos] at [iso], on its own metering. */
    private fun camera(exposureNanos: Long, iso: Int) = CameraTuning().apply {
        capabilities = caps
        reported = CameraTuning.Reported(exposureNanos = exposureNanos, iso = iso)
    }

    /** The sensor catching up with whatever was last asked for. */
    private fun arrive(tuning: CameraTuning) {
        tuning.reported = CameraTuning.Reported(
            exposureNanos = tuning.settings.exposureNanos,
            iso = tuning.settings.iso,
        )
    }

    private fun wall(stopsOver: Float) = DisplayMeter.Reading(
        displays = 28, bright = 255, face = 170, stopsOver = stopsOver, clipped = stopsOver > 0.6f,
    )

    /** Feeds [times] scans a tenth of a second apart; true if any of them moved the dial. */
    private fun scans(
        loop: TerminalExposure,
        tuning: CameraTuning,
        clock: FakeClock,
        reading: DisplayMeter.Reading?,
        times: Int,
    ): Boolean {
        var changed = false
        repeat(times) {
            clock.tick(100)
            if (loop.consider(tuning, reading)) changed = true
        }
        return changed
    }

    private fun total(s: CameraTuning.Settings) = s.exposureNanos.toDouble() * s.iso
    private fun total(r: CameraTuning.Reported) = r.exposureNanos!!.toDouble() * r.iso!!

    private fun stopsBetween(a: Double, b: Double) = kotlin.math.ln(a / b) / kotlin.math.ln(2.0)

    @Test
    fun `the 24 September round is corrected in one move`() {
        // What the old preset pinned that day, and what the wall metered at.
        val tuning = camera(18_867_925L, 6400)       // 1/53 s at the ISO ceiling
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        val before = total(tuning.reported)

        assertTrue(scans(loop, tuning, clock, wall(1.84f), 3))
        val s = tuning.settings
        assertEquals(CameraTuning.Mode.MANUAL, s.mode)
        assertEquals("the whole error in one go", -1.84, stopsBetween(total(s), before), 0.05)
        // Still at the gain ceiling, so the light comes off the shutter: 1/53 s becomes
        // about 1/190 s -- sharper as well as darker.
        assertEquals(6400, s.iso)
        assertEquals(5_270_000.0, s.exposureNanos.toDouble(), 100_000.0)
    }

    @Test
    fun `one reading is not enough`() {
        val tuning = camera(18_867_925L, 6400)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertFalse(scans(loop, tuning, clock, wall(1.84f), 2))
        assertEquals(TerminalExposure.Phase.MEASURING, loop.phase)
    }

    @Test
    fun `a bright frame among steady ones does not move it`() {
        val tuning = camera(4_000_000L, 2000)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        // The bright end of the wall swung through the middle of the frame for one scan.
        assertFalse(scans(loop, tuning, clock, wall(0.1f), 2))
        assertFalse(scans(loop, tuning, clock, wall(2.5f), 1))
        assertFalse(scans(loop, tuning, clock, wall(0.2f), 1))
        assertEquals(TerminalExposure.Phase.ON_TARGET, loop.phase)
    }

    @Test
    fun `within half a stop it is left alone`() {
        val tuning = camera(4_000_000L, 2000)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertFalse(scans(loop, tuning, clock, wall(0.45f), 20))
        assertFalse(scans(loop, tuning, clock, wall(-0.45f), 20))
        assertEquals(TerminalExposure.Phase.ON_TARGET, loop.phase)
        assertEquals(CameraTuning.Mode.AUTO, tuning.settings.mode)
    }

    @Test
    fun `no wall in view, no change`() {
        val tuning = camera(60_000_000L, 6400)       // the floor of a dark room
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertFalse(scans(loop, tuning, clock, null, 200))
        assertEquals(TerminalExposure.Phase.LOOKING, loop.phase)
        assertEquals(CameraTuning.Mode.AUTO, tuning.settings.mode)
    }

    @Test
    fun `it waits for the sensor before it measures again`() {
        val tuning = camera(18_867_925L, 6400)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertTrue(scans(loop, tuning, clock, wall(1.84f), 3))
        val asked = tuning.settings

        // Frames still at the old exposure say the same thing. Acting on them would take
        // the same two stops off twice.
        assertFalse(scans(loop, tuning, clock, wall(1.84f), 25))
        assertEquals(TerminalExposure.Phase.APPLYING, loop.phase)
        assertEquals(asked, tuning.settings)

        arrive(tuning)
        // One straddling frame skipped, then three to agree on.
        assertFalse(scans(loop, tuning, clock, wall(0.1f), 4))
        assertEquals(TerminalExposure.Phase.ON_TARGET, loop.phase)
    }

    @Test
    fun `changes are at least two seconds apart`() {
        val tuning = camera(10_000_000L, 800)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertTrue(scans(loop, tuning, clock, wall(1.0f), 3))
        arrive(tuning)
        // Still a stop over -- the phone's curve is steeper than the meter assumes -- but
        // it has only been a moment.
        assertFalse(scans(loop, tuning, clock, wall(1.0f), 5))
        clock.tick(TerminalExposure.MIN_INTERVAL_MILLIS)
        assertTrue(scans(loop, tuning, clock, wall(1.0f), 1))
    }

    @Test
    fun `an overshoot halves the next correction`() {
        val tuning = camera(10_000_000L, 800)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertTrue(scans(loop, tuning, clock, wall(2.0f), 3))
        arrive(tuning)
        val afterFirst = total(tuning.reported)

        // It came out a stop under: the move was too big for this phone.
        clock.tick(TerminalExposure.MIN_INTERVAL_MILLIS)
        assertTrue(scans(loop, tuning, clock, wall(-1.0f), 4))
        assertEquals(
            "half of the stop it is short by",
            0.5, stopsBetween(total(tuning.settings), afterFirst), 0.05,
        )
    }

    @Test
    fun `a change that loses the wall is undone and not repeated`() {
        val tuning = camera(10_000_000L, 800)
        // Start from a manual setting, so there is a manual setting to go back to.
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 10_000_000L, iso = 800) }
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        val original = tuning.settings

        assertTrue(scans(loop, tuning, clock, wall(2.5f), 3))
        val tried = total(tuning.settings)
        arrive(tuning)

        // Nothing readable at the new exposure, for long enough.
        assertFalse(scans(loop, tuning, clock, null, 29))
        assertTrue("undone after three seconds", scans(loop, tuning, clock, null, 2))
        assertEquals(original, tuning.settings)

        // The wall is back and still says two and a half over -- but that far down lost
        // it, so it stops half a stop short.
        arrive(tuning)
        clock.tick(TerminalExposure.MIN_INTERVAL_MILLIS)
        assertTrue(scans(loop, tuning, clock, wall(2.5f), 4))
        assertEquals(0.5, stopsBetween(total(tuning.settings), tried), 0.05)
    }

    @Test
    fun `a camera that never shows the request stands down, and resumes when it does`() {
        val tuning = camera(18_867_925L, 6400)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertTrue(scans(loop, tuning, clock, wall(1.84f), 3))
        val asked = tuning.settings

        assertFalse(scans(loop, tuning, clock, wall(1.84f), 40))
        assertEquals(TerminalExposure.Phase.NOT_HONOURED, loop.phase)
        assertEquals("and it asks for nothing more", asked, tuning.settings)

        arrive(tuning)
        assertFalse(scans(loop, tuning, clock, wall(0.0f), 4))
        assertEquals(TerminalExposure.Phase.ON_TARGET, loop.phase)
    }

    @Test
    fun `a dim wall takes gain first and never slows past 1 in 60`() {
        val dim = camera(4_000_000L, 800)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertTrue(scans(loop, dim, clock, wall(-1.5f), 3))
        assertEquals("the shutter stays at 1/250 s", 4_000_000L, dim.settings.exposureNanos)
        assertEquals(800 * 2.0.pow(1.5), dim.settings.iso.toDouble(), 20.0)

        val dark = camera(4_000_000L, 6400)
        val loop2 = TerminalExposure(clock::now)
        assertTrue(scans(loop2, dark, clock, wall(-2.5f), 3))
        assertEquals(6400, dark.settings.iso)
        assertEquals(TerminalExposure.SLOWEST_SHUTTER_NANOS, dark.settings.exposureNanos)
    }

    @Test
    fun `at the bottom of the dials it says so rather than asking again`() {
        val tuning = camera(83_000L, 100)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertFalse(scans(loop, tuning, clock, wall(2.0f), 10))
        assertEquals(TerminalExposure.Phase.AT_LIMIT, loop.phase)
    }

    @Test
    fun `the split buys a fast shutter with gain`() {
        // 1/100 s at ISO 1000, held at the same light.
        val (shutter, iso) = TerminalExposure.split(10_000_000.0 * 1000, caps)
        assertEquals(4_000_000L, shutter)
        assertEquals(2500, iso)

        // So bright that even the lowest gain is too much at 1/250 s: faster still.
        val (fast, lowest) = TerminalExposure.split(1_000_000.0 * 100, caps)
        assertEquals(100, lowest)
        assertEquals(1_000_000L, fast)
    }

    @Test
    fun `taking the dials by hand stops it`() {
        val tuning = camera(18_867_925L, 6400)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        loop.enabled = false
        assertFalse(scans(loop, tuning, clock, wall(1.84f), 30))
        assertEquals(TerminalExposure.Phase.OFF, loop.phase)
    }

    @Test
    fun `without a manual sensor there is nothing to do`() {
        val tuning = CameraTuning().apply {
            capabilities = caps.copy(manualSensor = false)
            reported = CameraTuning.Reported(exposureNanos = 18_867_925L, iso = 6400)
        }
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        assertFalse(scans(loop, tuning, clock, wall(1.84f), 30))
        assertEquals(TerminalExposure.Phase.IDLE, loop.phase)
    }

    @Test
    fun `it lands on target and then stays there`() {
        // A phone whose real curve is steeper than the meter's: every correction does
        // half again what was intended. The loop must still converge and then sit still.
        val tuning = camera(18_867_925L, 6400)
        val clock = FakeClock()
        val loop = TerminalExposure(clock::now)
        val target = total(tuning.reported) * 2.0.pow(-1.84)
        var moves = 0
        repeat(40) {
            arrive(tuning)
            val actualOver = (1.5 * stopsBetween(total(tuning.reported), target)).toFloat()
            clock.tick(250)
            if (scans(loop, tuning, clock, wall(actualOver), 1)) moves++
        }
        assertTrue("took $moves moves", moves in 1..4)
        arrive(tuning)
        val finalOver = 1.5 * stopsBetween(total(tuning.reported), target)
        assertTrue("ended ${"%+.2f".format(finalOver)} stops off", abs(finalOver) <= TerminalExposure.DEADBAND_STOPS)
        assertEquals(TerminalExposure.Phase.ON_TARGET, loop.phase)
    }
}
