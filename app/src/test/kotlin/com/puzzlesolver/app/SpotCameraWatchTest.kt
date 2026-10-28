package com.puzzlesolver.app

import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.app.pipeline.SpotCameraWatch
import com.puzzlesolver.app.pipeline.TerminalExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch that gives Spot's camera back to its own exposure when the screen has been
 * lost too long -- the way out of the round on 2026-10-27, where Spot inherited Gems'
 * LED-wall preset and never saw the screen to meter it.
 */
class SpotCameraWatchTest {

    private class FakeClock {
        var now = 1_000_000L
        fun tick(millis: Long) { now += millis }
    }

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

    /** The camera as Gems leaves it: the LED-wall preset. */
    private fun ledWall() = CameraTuning().apply {
        capabilities = caps
        applyLedWallPreset()
    }

    /** Frames a tenth of a second apart for [millis]; true if any of them reset the camera. */
    private fun frames(watch: SpotCameraWatch, clock: FakeClock, tuning: CameraTuning, millis: Long, screen: Boolean): Boolean {
        var reset = false
        var t = 0L
        while (t < millis) {
            if (watch.consider(tuning, screen)) reset = true
            clock.tick(100)
            t += 100
        }
        return reset
    }

    @Test
    fun `a camera too dark to find the screen goes back to its own exposure`() {
        val clock = FakeClock()
        val watch = SpotCameraWatch(clock::now)
        val tuning = ledWall()
        assertEquals(CameraTuning.Mode.MANUAL, tuning.settings.mode)

        assertFalse(frames(watch, clock, tuning, SpotCameraWatch.BLIND_MILLIS - 200, screen = false))
        assertTrue(frames(watch, clock, tuning, 400, screen = false))
        assertEquals(CameraTuning.Settings(), tuning.settings)
    }

    @Test
    fun `it waits longer than the meter does to undo its own step`() {
        assertTrue(SpotCameraWatch.BLIND_MILLIS > TerminalExposure.LOST_WALL_MILLIS)
    }

    @Test
    fun `a screen in view keeps whatever the meter chose`() {
        val clock = FakeClock()
        val watch = SpotCameraWatch(clock::now)
        val tuning = ledWall()
        val chosen = tuning.settings
        assertFalse(frames(watch, clock, tuning, 30_000, screen = true))
        assertEquals(chosen, tuning.settings)
    }

    @Test
    fun `a glance away shorter than the wait changes nothing`() {
        val clock = FakeClock()
        val watch = SpotCameraWatch(clock::now)
        val tuning = ledWall()
        val chosen = tuning.settings
        repeat(5) {
            assertFalse(frames(watch, clock, tuning, 3_000, screen = false))
            assertFalse(frames(watch, clock, tuning, 500, screen = true))
        }
        assertEquals(chosen, tuning.settings)
    }

    @Test
    fun `a camera already on its own exposure is left alone`() {
        val clock = FakeClock()
        val watch = SpotCameraWatch(clock::now)
        val tuning = CameraTuning().apply { capabilities = caps }
        val generation = tuning.generation
        assertFalse(frames(watch, clock, tuning, 30_000, screen = false))
        assertEquals(generation, tuning.generation)
    }
}
