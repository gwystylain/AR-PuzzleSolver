package com.puzzlesolver.app

import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.app.pipeline.AutoExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera dial and the loop that turns it.
 *
 * Both are plain state machines with no Android in them beyond logging, and both are
 * hard to test any other way: the alternative is standing in a dark room watching a
 * HUD. The failure mode that matters is a loop that keeps adjusting -- every adjustment
 * pauses ARCore and throws the mosaic away, so one that oscillates would make the app
 * unusable rather than merely wrong.
 */
class CameraTuningTest {

    /**
     * A clock the test drives by hand, because the loop's timeouts are wall-clock now.
     *
     * They have to be: expressed as a pass count they assumed a 4 Hz loop, the live one
     * runs nearer 7, and the timeout meant as fifteen seconds fired in nine -- twice,
     * before the wall was ever in frame. Advancing this deliberately is also the only
     * way to assert the new behaviour without a fifteen second test.
     */
    private class FakeClock {
        var now = 1_000_000L
        fun tick(millis: Long) { now += millis }
    }

    private fun manualCapable() = CameraTuning.Capabilities(
        available = true,
        manualSensor = true,
        minEvSteps = -12,
        maxEvSteps = 12,
        evStepsPerEv = 2,
        minExposureNanos = 500_000L,       // 1/2000 s
        maxExposureNanos = 100_000_000L,
        minIso = 50,
        maxIso = 3200,
    )

    private fun evOnly() = CameraTuning.Capabilities(
        available = true,
        manualSensor = false,
        minEvSteps = -6,
        maxEvSteps = 6,
        evStepsPerEv = 2,
    )

    @Test
    fun `requests are clamped to what the device admits to`() {
        val tuning = CameraTuning()
        tuning.capabilities = evOnly()
        tuning.update { it.copy(evSteps = -50) }
        assertEquals(-6, tuning.settings.evSteps)

        // Manual is not merely clamped but refused: a device without a manual sensor
        // would silently ignore the exposure time and leave the user believing a dial
        // that does nothing.
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL) }
        assertEquals(CameraTuning.Mode.AUTO, tuning.settings.mode)
    }

    @Test
    fun `the generation only moves when something actually changes`() {
        val tuning = CameraTuning()
        tuning.capabilities = evOnly()
        val start = tuning.generation

        assertTrue(tuning.update { it.copy(evSteps = -2) })
        assertEquals(start + 1, tuning.generation)

        // Same value again: re-issuing a capture request costs a pause and a rescan, so
        // a no-op write must not trigger one.
        assertFalse(tuning.update { it.copy(evSteps = -2) })
        assertEquals(start + 1, tuning.generation)

        // A request that clamps back onto the value already in force is also a no-op,
        // which is what stops a user leaning on "darker" at the floor from rescanning
        // the wall once a tap.
        assertTrue(tuning.update { it.copy(evSteps = -99) })
        assertEquals(-6, tuning.settings.evSteps)
        val atFloor = tuning.generation
        assertFalse(tuning.update { it.copy(evSteps = -99) })
        assertEquals(atFloor, tuning.generation)
    }

    @Test
    fun `darker halves the exposure and stops at the sensor floor`() {
        val tuning = CameraTuning()
        tuning.capabilities = manualCapable()
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }

        assertTrue(tuning.darker())
        assertEquals(2_000_000L, tuning.settings.exposureNanos)
        assertTrue(tuning.darker())
        assertEquals(1_000_000L, tuning.settings.exposureNanos)

        // Down to the floor, then a refusal rather than a silent no-op -- the caller
        // uses the false to stop spending a rescan per step on nothing.
        while (tuning.darker()) { /* walk it down */ }
        assertEquals(500_000L, tuning.settings.exposureNanos)
        assertFalse(tuning.darker())
    }

    @Test
    fun `without a manual sensor the dial is exposure compensation`() {
        val tuning = CameraTuning()
        tuning.capabilities = evOnly()
        assertTrue(tuning.darker())
        // One whole EV, which on this device is two compensation steps.
        assertEquals(-2, tuning.settings.evSteps)
        assertTrue(tuning.brighter())
        assertEquals(0, tuning.settings.evSteps)
    }

    @Test
    fun `the LED wall preset prefers manual and falls back to compensation`() {
        val manual = CameraTuning().apply { capabilities = manualCapable() }
        assertTrue(manual.applyLedWallPreset())
        assertEquals(CameraTuning.Mode.MANUAL, manual.settings.mode)
        assertEquals(CameraTuning.LED_WALL_EXPOSURE_NANOS, manual.settings.exposureNanos)
        assertEquals("floor sensitivity, so the shutter does the work", 50, manual.settings.iso)
        assertTrue(manual.settings.lockAwb)

        val ev = CameraTuning().apply { capabilities = evOnly() }
        assertTrue(ev.applyLedWallPreset())
        assertEquals(CameraTuning.Mode.AUTO, ev.settings.mode)
        assertEquals("three stops down, clamped to the device range", -6, ev.settings.evSteps)
        assertTrue(ev.settings.lockAe)
        assertTrue(ev.settings.lockAwb)
    }

    @Test
    fun `nothing is applied when the device offers no control at all`() {
        val tuning = CameraTuning()
        assertFalse("no capabilities means no dial", tuning.capabilities.available)
        val exposure = AutoExposure()
        assertFalse(exposure.consider(tuning, hint = 0.9f))
        assertEquals(AutoExposure.Phase.IDLE, exposure.phase)
    }

    // --- the loop --------------------------------------------------------

    @Test
    fun `the loop walks down until the wall reads, then stops`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 32_000_000L) }
        val exposure = AutoExposure()

        // Blown out: one step, and only one, off a single measurement.
        assertTrue(exposure.consider(tuning, hint = 0.62f))
        assertEquals(16_000_000L, tuning.settings.exposureNanos)
        assertEquals(1, exposure.stepsDown)

        // The next passes carry no measurement yet, because the mosaic was just thrown
        // away. Acting on the stale hint here is the bug this guards.
        repeat(5) { assertFalse(exposure.consider(tuning, hint = -1f)) }
        assertEquals(16_000_000L, tuning.settings.exposureNanos)

        assertTrue(exposure.consider(tuning, hint = 0.55f))
        assertEquals(8_000_000L, tuning.settings.exposureNanos)

        // Under target -- but once is not enough. SETTLED is terminal, and a single
        // pass can catch the wall swinging past and measure a frame that is barely in
        // it. On the real wall that is exactly what happened: one reading locked a
        // two-stop error in for the rest of the session.
        assertFalse(exposure.consider(tuning, hint = 0.04f))
        assertEquals("one reading must not latch", AutoExposure.Phase.MEASURING, exposure.phase)
        assertFalse(exposure.consider(tuning, hint = 0.05f))
        assertEquals(AutoExposure.Phase.MEASURING, exposure.phase)
        assertFalse(exposure.consider(tuning, hint = 0.04f))
        assertEquals(AutoExposure.Phase.SETTLED, exposure.phase)
        repeat(10) { assertFalse(exposure.consider(tuning, hint = 0.9f)) }
        assertEquals(8_000_000L, tuning.settings.exposureNanos)
    }

    @Test
    fun `the loop gives up rather than walking the exposure into the floor`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 32_000_000L) }
        val exposure = AutoExposure()

        // Always blown out, whatever we do -- a wall this is simply not the right tool
        // for. It must stop, because every step costs a rescan.
        repeat(40) { exposure.consider(tuning, hint = 0.9f) }
        assertEquals(AutoExposure.Phase.EXHAUSTED, exposure.phase)
        assertTrue("should never exceed its own step budget", exposure.stepsDown <= 6)
    }

    @Test
    fun `a step that loses the wall is stepped back up`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 32_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }

        assertTrue(exposure.consider(tuning, hint = 0.62f, wallInView = true))
        assertEquals(16_000_000L, tuning.settings.exposureNanos)

        // Silence from here on: too dark to read anything, so no measurement ever
        // arrives. That silence is the only evidence available that the step was too
        // far, which is why it is acted on at all. Two timeouts' worth of passes: one to
        // climb back, and one to conclude that the climb did not help either.
        //
        // The wall is still in frame throughout -- that is what makes the silence the
        // exposure's fault and this recovery the right answer.
        var backedOff = false
        repeat(200) {
            clock.tick(250)
            if (exposure.consider(tuning, hint = -1f, wallInView = true)) backedOff = true
        }
        assertTrue("should have stepped back up", backedOff)
        assertEquals("never brighter than the camera's own choice", 32_000_000L, tuning.settings.exposureNanos)
        assertEquals(AutoExposure.Phase.BACKED_OFF, exposure.phase)
    }

    /**
     * The failure this guards is subtle and expensive: a camera pointed at the ceiling
     * produces exactly the same silence as an exposure one stop too dark, and a loop
     * that answered it by brightening without limit would end up worse than where it
     * started -- and then refuse to come back down, because it never descends twice.
     */
    @Test
    fun `it never brightens past where the camera started`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 8_000_000L) }
        val exposure = AutoExposure()

        // Nothing has darkened anything, and nothing is readable. There is no move to
        // make, however long that goes on.
        repeat(500) { assertFalse(exposure.consider(tuning, hint = -1f)) }
        assertEquals(8_000_000L, tuning.settings.exposureNanos)
    }

    /**
     * The LED-wall preset drops several stops in one move before this loop has run at
     * all, so the loop is told about it -- otherwise a preset too dark for the room
     * would leave the app staring at a black wall with a dial it refused to touch.
     */
    @Test
    fun `a preset that overshot is climbed back out of`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 1_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }
        exposure.noteExternalDarkening()

        var climbs = 0
        repeat(500) {
            clock.tick(250)
            if (exposure.consider(tuning, hint = -1f, wallInView = true)) climbs++
        }
        assertEquals("three stops back, matching what the preset is worth", 3, climbs)
        assertEquals(8_000_000L, tuning.settings.exposureNanos)
        assertEquals(AutoExposure.Phase.BACKED_OFF, exposure.phase)
    }

    /**
     * The loop must stand down against a camera that ignores it.
     *
     * Not hypothetical: ARCore's shared camera mode discards the app's capture request
     * on the device this was built against. Without this the loop would read the wall as
     * blown out, step down, discard the mosaic, find it still blown out, and repeat --
     * six scans thrown away and the exposure unchanged throughout, which is strictly
     * worse than having no loop.
     */
    @Test
    fun `a camera that ignores the request stops the loop`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        // What the sensor actually did, nowhere near what was asked.
        tuning.reported = CameraTuning.Reported(exposureNanos = 10_000_000L, iso = 2008)

        // Not on the first look. A change takes a moment to reach the sensor, so the
        // frames in between legitimately still carry the old settings, and accusing the
        // camera on one sample would fire on every adjustment.
        assertEquals(null, tuning.honoured())
        repeat(6) { tuning.honoured() }
        assertEquals(false, tuning.honoured())

        val exposure = AutoExposure()
        repeat(50) { assertFalse(exposure.consider(tuning, hint = 0.9f)) }
        assertEquals(AutoExposure.Phase.NOT_HONOURED, exposure.phase)
        assertEquals("must not have touched the dial", 4_000_000L, tuning.settings.exposureNanos)
    }

    @Test
    fun `a camera that obeys is not accused of ignoring the request`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        // Rounded to a sensor row time, which is what obedience actually looks like.
        tuning.reported = CameraTuning.Reported(exposureNanos = 3_999_000L, iso = 100)
        assertEquals(true, tuning.honoured())

        val exposure = AutoExposure()
        assertTrue(exposure.consider(tuning, hint = 0.9f))
        assertEquals(2_000_000L, tuning.settings.exposureNanos)
    }

    @Test
    fun `nothing is concluded before the camera has reported anything`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        // Auto at zero compensation is indistinguishable from the camera doing its own
        // thing, so it is not evidence either way and must not be read as failure.
        assertEquals(null, tuning.honoured())
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL) }
        assertEquals(null, tuning.honoured())
    }

    /**
     * Standing down has to be reversible. Left latched, one transient disagreement --
     * a change still in flight, a camera mid-restart -- silences the loop for the rest of
     * the session and leaves the HUD accusing a camera that is doing as it is told.
     */
    @Test
    fun `the loop resumes when the camera starts obeying again`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        val exposure = AutoExposure()

        exposure.noteHonoured(false)
        assertEquals(AutoExposure.Phase.NOT_HONOURED, exposure.phase)
        assertFalse(exposure.consider(tuning, hint = 0.9f))

        exposure.noteHonoured(true)
        assertEquals(AutoExposure.Phase.IDLE, exposure.phase)
        tuning.reported = CameraTuning.Reported(exposureNanos = 4_000_000L, iso = 100)
        assertTrue("should be working again", exposure.consider(tuning, hint = 0.9f))
    }

    @Test
    fun `disabling the loop stops it dead`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        val exposure = AutoExposure()
        exposure.enabled = false
        assertFalse(exposure.consider(tuning, hint = 0.99f))
        assertEquals(AutoExposure.Phase.IDLE, exposure.phase)
        assertEquals(0, exposure.stepsDown)
    }
    // --- the run of 2026-08-20 ------------------------------------------

    /**
     * The failure this whole group exists for.
     *
     * Picking Gems applied the LED-wall preset correctly, at 1/250 s. The user then
     * spent eighteen seconds walking to the wall with nothing in frame. Silence is the
     * only evidence the recovery path has, so it read those eighteen seconds as "the
     * step down lost the wall" and climbed back twice, landing at 1/62 s -- two stops
     * brighter than the preset, which is where the gem rings bloom into each other and
     * stop being readable at all.
     *
     * A negative hint cannot distinguish the two silences on its own, and frame
     * brightness -- the first thing tried -- cannot either: an LED wall two stops under
     * is nearly as dark as an empty room, while a black frame full of sensor noise is
     * not as dark as one. What does distinguish them is whether the blobs found are
     * arranged like a wall, which the scanner decides and passes in as `wallInView`.
     */
    @Test
    fun `a camera that is not pointed at the wall must not undo the preset`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }
        exposure.noteExternalDarkening()

        // Three minutes of a dark room. Long past both timeouts, and not a reason to
        // touch anything: there is no exposure that makes a wall appear.
        repeat(720) {
            clock.tick(250)
            assertFalse(exposure.consider(tuning, hint = -1f, wallInView = false))
        }
        assertEquals(
            "the preset must still be exactly where it was set",
            4_000_000L,
            tuning.settings.exposureNanos,
        )
    }

    /**
     * And the other half: having refused to act on an empty frame, it must still act
     * the moment there is something to act on. A gate that never opens is not a fix.
     */
    @Test
    fun `once the wall is in frame the loop works normally again`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }
        exposure.noteExternalDarkening()

        repeat(200) {
            clock.tick(250)
            exposure.consider(tuning, hint = -1f, wallInView = false)
        }
        assertEquals(4_000_000L, tuning.settings.exposureNanos)

        // The wall arrives, and it is blown out. That is a measurement, and it is acted
        // on immediately -- the gate is on the silence, not on the loop.
        clock.tick(250)
        assertTrue(exposure.consider(tuning, hint = 0.48f, wallInView = true))
        assertEquals(2_000_000L, tuning.settings.exposureNanos)
    }

    /**
     * The timeout was a pass count documented as "about fifteen seconds" on the
     * assumption of a 4 Hz loop. The live loop runs nearer 7 Hz, so it fired in nine --
     * which is how two of them fitted inside one walk across a room.
     */
    @Test
    fun `sixty fast passes are not fifteen seconds`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }
        exposure.noteExternalDarkening()

        // Well past the pass count, nowhere near the clock, wall genuinely in frame.
        repeat(150) {
            clock.tick(10)
            assertFalse(
                "climbed on pass count alone",
                exposure.consider(tuning, hint = -1f, wallInView = true),
            )
        }
        assertEquals(4_000_000L, tuning.settings.exposureNanos)

        // Same evidence, enough time passed, and now it moves.
        clock.tick(AutoExposure.LOST_THE_WALL_MILLIS)
        assertTrue(exposure.consider(tuning, hint = -1f, wallInView = true))
    }

    /**
     * [AutoExposure.Phase.SETTLED] is terminal, so it must not be reachable by luck. On
     * the wall a single pass caught the gems as the camera swung past -- blobs were zero
     * either side of it -- measured that one frame at 10% and locked a two-stop error in
     * for the rest of the session.
     */
    @Test
    fun `a lucky frame between bad ones does not settle the loop`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 32_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }

        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertNotEquals(AutoExposure.Phase.SETTLED, exposure.phase)

        // A real measurement, over target. Whatever the two before it said, the wall is
        // blown out, and the count of agreeing readings starts again.
        assertTrue(exposure.consider(tuning, hint = 0.60f, wallInView = true))
        assertEquals(16_000_000L, tuning.settings.exposureNanos)

        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertNotEquals("two readings must not be enough", AutoExposure.Phase.SETTLED, exposure.phase)
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertEquals(AutoExposure.Phase.SETTLED, exposure.phase)
    }
    /**
     * Found by leaving the phone face down on a desk with Gems selected, which is the
     * one state nobody thinks to test because nothing is supposed to happen in it.
     *
     * At 1/250 s in a dark room the frame is black, but sensor noise still clears the
     * detector's local-background threshold: ten to twenty blobs, a few of them reading
     * as lit, and the handful of saturated texels inside them averaging to a confident
     * *0% washed out*. Three of those and the loop called itself settled -- terminal --
     * eight seconds in, and would then have refused to darken anything when the wall
     * finally came into frame. The pitch in the heartbeat gave it away, jumping between
     * 43 px and 436 px where a wall holds near 130.
     *
     * `GemScanner` now declines to publish a hint off a handful of gems, which is the
     * primary fix. This is the second lock: a frame with no light in it says nothing
     * about the exposure in *either* direction, so it cannot settle the loop any more
     * than it can drive it back up.
     */
    @Test
    fun `sensor noise in a black frame does not settle the loop`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 4_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }
        exposure.noteExternalDarkening()

        // Fifty passes of a black frame insisting the exposure is perfect.
        repeat(50) {
            clock.tick(250)
            assertFalse(exposure.consider(tuning, hint = 0.0f, wallInView = false))
        }
        assertNotEquals(
            "a black frame must not be able to end the loop",
            AutoExposure.Phase.SETTLED,
            exposure.phase,
        )
        assertEquals(4_000_000L, tuning.settings.exposureNanos)

        // And the loop is still alive to do its job when the wall finally arrives.
        clock.tick(250)
        assertTrue(
            "the loop was left able to act",
            exposure.consider(tuning, hint = 0.62f, wallInView = true),
        )
        assertEquals(2_000_000L, tuning.settings.exposureNanos)
    }

    /**
     * The three readings have to be three *in a row*. Counting them cumulatively lets a
     * wall glimpsed three times across a minute of walking add up to a conclusion, which
     * is the same latch by a slower route.
     */
    @Test
    fun `the settling readings have to be consecutive`() {
        val tuning = CameraTuning().apply { capabilities = manualCapable() }
        tuning.update { it.copy(mode = CameraTuning.Mode.MANUAL, exposureNanos = 8_000_000L) }
        val clock = FakeClock()
        val exposure = AutoExposure { clock.now }

        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        // The wall goes out of frame. Not a reading, and not a vote either way.
        assertFalse(exposure.consider(tuning, hint = -1f, wallInView = false))
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertNotEquals(
            "the count must have restarted at the gap",
            AutoExposure.Phase.SETTLED,
            exposure.phase,
        )
        assertFalse(exposure.consider(tuning, hint = 0.10f, wallInView = true))
        assertEquals(AutoExposure.Phase.SETTLED, exposure.phase)
    }
}
