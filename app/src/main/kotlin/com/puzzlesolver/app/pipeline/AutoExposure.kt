package com.puzzlesolver.app.pipeline

import android.util.Log
import com.puzzlesolver.app.frame.CameraTuning

/**
 * Walks the exposure down until the puzzle stops being blown out, then stops.
 *
 * The problem it solves is one no amount of image processing can: on a wall that lights
 * itself in a dark room, the camera meters for the room, opens right up, and every
 * button arrives as a flat white disc. The colour is not dim in the image, it is *not
 * in* the image. Measured on the reference clips, the camera's own choice leaves 47% to
 * 69% of each gem carrying no hue at all; two stops down it is under 4%.
 *
 * The loop is closed through the scanner, because the scanner is the only part of the
 * app that can tell a correctly exposed wall from a blown out one.
 * `GemScanner` measures the fraction of gem area with no usable hue, and this walks the
 * exposure down a stop at a time until that clears.
 *
 * Three things make it conservative rather than clever, and all three are because a
 * change is expensive -- ARCore has to be paused to re-issue a capture request, and the
 * mosaic has to be thrown away because a canvas holding two exposures has seams in it:
 *
 *  - **It waits for a fresh measurement after every change.** A hint computed from the
 *    old exposure would have it stepping twice for one problem.
 *  - **It only ever steps once per measurement**, so it cannot run away.
 *  - **It stops.** Settled, exhausted, or backed off; there is no state it keeps
 *    adjusting from.
 *
 * The dangerous direction is down, so the only way back up is the timeout: if a step
 * down leaves the wall too dark to read at all, no hint ever arrives, and after a while
 * that silence is itself the signal.
 */
class AutoExposure(
    /**
     * Wall-clock, injectable so the loop's timeouts can be tested without waiting them
     * out. Nothing here needs a monotonic clock: the only thing measured is how long the
     * user has been pointing at nothing, and being an hour out after a clock adjustment
     * costs one extra pass.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Phase {
        /** Nothing to do: no camera control, or no adapter with an opinion. */
        IDLE,

        /** Waiting for a measurement taken at the current exposure. */
        MEASURING,

        /** The wall reads. Nothing further will be changed. */
        SETTLED,

        /** Still blown out, but the camera has no darker setting to offer. */
        EXHAUSTED,

        /** Stepped back up because a darker setting lost the wall, and left there. */
        BACKED_OFF,

        /**
         * The camera is not doing what it is told, so there is no loop to close.
         *
         * Measured, not hypothetical: ARCore's shared camera mode discards the app's
         * capture request on the device this was built against. Standing down is the
         * only correct response -- carrying on would read the wall as blown out, step
         * down, discard the mosaic, and repeat, six scans lost for nothing.
         */
        NOT_HONOURED,
    }

    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) phase = Phase.IDLE
        }

    var phase: Phase = Phase.IDLE
        private set

    /** Net steps below where the camera started, for the HUD and the log. */
    var stepsDown: Int = 0
        private set

    private var awaitingFreshReading = false

    /**
     * How long the current stretch of *lit frame, nothing readable* has been running.
     *
     * Both of these measure the same thing and neither is "since the exposure changed",
     * though they started out that way. The distinction matters: the recovery path is
     * allowed to fire after fifteen seconds of a frame that has light in it and still
     * yields no reading, and time spent pointed at the floor is not that. Left running
     * through a walk across a room, the very first lit frame at the wall arrives with
     * the clock long expired and collects a stop of brightening it never earned.
     */
    private var silentPasses = 0
    private var silentSinceMillis = clock()

    private var onTargetReadings = 0

    private var stepsUp = 0

    /**
     * Once the search has turned around it does not turn back.
     *
     * Down until the wall goes dark, up until it reads, stop. Allowing a second descent
     * would let a board that reads marginally at two adjacent settings oscillate between
     * them forever, and each swing costs a paused session and a discarded mosaic.
     */
    private var hasSteppedUp = false

    fun reset() {
        phase = Phase.IDLE
        stepsDown = 0
        stepsUp = 0
        awaitingFreshReading = false
        silentPasses = 0
        silentSinceMillis = clock()
        onTargetReadings = 0
        hasSteppedUp = false
    }

    /**
     * Tells the loop that something else has darkened the camera.
     *
     * The LED-wall preset is the only caller: it drops the exposure the moment a
     * colour-reading puzzle becomes active, which is usually right and occasionally
     * several stops too far. Without this the loop would have no idea it had happened
     * and no reason to consider stepping back up, so a preset that overshot would leave
     * the app staring at a black wall with a dial it refused to touch.
     */
    fun noteExternalDarkening() {
        stepsDown = PRESET_EQUIVALENT_STEPS
        awaitingFreshReading = true
        silentPasses = 0
        silentSinceMillis = clock()
        onTargetReadings = 0
    }

    /**
     * Records whether the camera is doing what it is told, from the frame loop.
     *
     * Separate from [consider] because it needs no measurement from the solver, and the
     * solver does not run until a wall has been fitted. A camera that ignores the
     * capture request should be called out in the first second, not held back behind a
     * wall fit that may never happen -- especially since a wall fit in a dark room is
     * exactly what a wrong exposure makes hard.
     */
    fun noteHonoured(honoured: Boolean?) {
        if (honoured == false) {
            if (phase != Phase.NOT_HONOURED) {
                Log.w(TAG, "the camera is ignoring the capture request; standing down")
            }
            phase = Phase.NOT_HONOURED
            return
        }
        // And back again. Standing down has to be reversible or the first transient
        // disagreement -- a change still in flight, a camera mid-restart -- silences the
        // loop for the rest of the session and leaves the HUD accusing a camera that is
        // in fact doing exactly what it was told.
        if (honoured == true && phase == Phase.NOT_HONOURED) {
            Log.i(TAG, "the camera is honouring the request after all; resuming")
            phase = Phase.IDLE
        }
    }

    /**
     * One solver step's worth of evidence.
     *
     * @param hint the active adapter's over-exposure fraction, or negative when it has
     *        not seen enough of the wall to have an opinion yet.
     * @param wallInView whether the scanner found something wall-shaped in the frame at
     *        all, independent of whether it could read it. This is what separates the two
     *        things a negative [hint] can mean, and confusing them is what broke a run on
     *        the wall. Defaults to true for a caller with no opinion, which is the
     *        behaviour the recovery path was originally written for.
     * @return true when the exposure was changed, which the caller turns into a rescan.
     */
    fun consider(
        tuning: CameraTuning,
        hint: Float,
        wallInView: Boolean = true,
    ): Boolean {
        if (!enabled || !tuning.capabilities.available) {
            phase = Phase.IDLE
            return false
        }
        noteHonoured(tuning.honoured())
        if (phase == Phase.SETTLED || phase == Phase.EXHAUSTED ||
            phase == Phase.BACKED_OFF || phase == Phase.NOT_HONOURED
        ) {
            return false
        }
        phase = Phase.MEASURING

        // A frame with no wall in it says nothing about the exposure in either
        // direction, so it is folded into the no-opinion path rather than only barring
        // the climb out of it. The scanner cannot publish a hint without having found a
        // lattice first, so the second half of this should be unreachable; it is written
        // anyway because the cost of the two ever disagreeing is a loop that latches.
        if (hint < 0f || !wallInView) {
            // No opinion. Normal for a while after a change -- the mosaic was just
            // thrown away and the user has to pan the wall again before enough gems are
            // readable to measure. Only worrying if it never ends.
            //
            // Whatever it was, it was not three readings in a row agreeing about a wall.
            onTargetReadings = 0
            if (!wallInView) {
                // Not the silence the recovery path is about, so its clock restarts
                // rather than runs.
                //
                // That path exists for one situation: a step down that left the wall too
                // dark to *read*. It fires on silence, and a camera pointed at the floor
                // is equally silent. Frame brightness looked like the way to tell those
                // apart and is not -- an LED wall two stops under is nearly as dark as an
                // empty room, and a black frame full of sensor noise is not as dark as
                // one. What does tell them apart is whether the blobs are arranged like a
                // wall, which the scanner decides already and which holds at any exposure
                // the discs are still visible at. See GemBlobDetector.latticeSpread.
                //
                // Letting the wait count spends a whole walk across a room earning stops
                // of brightening, to be spent the moment the wall appears. Measured on a
                // desk: 1/250 s walked up to 1/31 s over two minutes of looking at
                // nothing at all.
                silentPasses = 0
                silentSinceMillis = clock()
                return false
            }
            silentPasses++
            if (!awaitingFreshReading) return false
            if (silentPasses <= LOST_THE_WALL_STEPS) return false
            // Both, not either. The pass count alone was the bug: it is documented as
            // fifteen seconds at 4 Hz, the live loop runs nearer 7, and on the wall it
            // fired twice in eighteen seconds while the user was still walking up to it
            // -- undoing the LED-wall preset two stops before the wall was ever in
            // frame. The clock is what the timeout was always meant to measure; the
            // count stays so that a stalled scanner cannot age out on time alone.
            if (clock() - silentSinceMillis < LOST_THE_WALL_MILLIS) return false
            // Only ever undoing darkening we are responsible for. Brightening past the
            // camera's own choice would turn "the user has not pointed at the wall yet"
            // into a slow march to an exposure worse than the one we started from -- and
            // by then [hasSteppedUp] would refuse to come back down.
            if (stepsDown <= 0 || !tuning.brighter()) {
                Log.w(TAG, "nothing readable and back at the camera's own exposure; leaving it alone")
                phase = Phase.BACKED_OFF
                return false
            }
            stepsUp++
            stepsDown--
            hasSteppedUp = true
            silentPasses = 0
            silentSinceMillis = clock()
            Log.w(
                TAG,
                "nothing readable for ${LOST_THE_WALL_MILLIS / 1000}s with a lit frame; " +
                    "up to ${tuning.describeRequest()}",
            )
            return true
        }

        // A measurement at the current exposure. Whatever we decide now is decided on
        // evidence rather than on the exposure we used to have.
        awaitingFreshReading = false
        // A reading arrived, so whatever silence there was has ended.
        silentPasses = 0
        silentSinceMillis = clock()

        if (hint <= TARGET) {
            // Not on the first one. SETTLED is terminal, and on the wall it latched onto
            // a single reading taken during a glimpse of the wall in passing -- gems
            // were in frame for that one pass and gone again either side of it. One
            // frame is not evidence about an exposure; it is evidence about one frame.
            onTargetReadings++
            if (onTargetReadings < SETTLE_READINGS) return false
            Log.i(TAG, "exposure settled after $stepsDown step(s), ${(hint * 100).toInt()}% washed out")
            phase = Phase.SETTLED
            return false
        }
        onTargetReadings = 0
        if (hasSteppedUp) {
            // We came back up to find this reading. Going down again would return to a
            // setting already shown to lose the wall.
            phase = Phase.BACKED_OFF
            return false
        }
        if (stepsDown >= MAX_STEPS_DOWN) {
            phase = Phase.EXHAUSTED
            return false
        }
        if (!tuning.darker()) {
            Log.w(TAG, "camera has no darker setting; ${(hint * 100).toInt()}% still washed out")
            phase = Phase.EXHAUSTED
            return false
        }
        stepsDown++
        awaitingFreshReading = true
        silentPasses = 0
        silentSinceMillis = clock()
        Log.i(TAG, "step $stepsDown down: ${(hint * 100).toInt()}% washed out -> ${tuning.describeRequest()}")
        return true
    }

    fun describe(): String = when (phase) {
        Phase.IDLE -> if (enabled) "auto-exposure idle" else "auto-exposure off"
        Phase.MEASURING -> "auto-exposure measuring (${stepsDown} down)"
        Phase.SETTLED -> "auto-exposure settled (${stepsDown} down)"
        Phase.EXHAUSTED -> "auto-exposure exhausted (${stepsDown} down, still blown out)"
        Phase.BACKED_OFF -> "auto-exposure backed off ($stepsUp up, too dark below that)"
        Phase.NOT_HONOURED -> "the camera is ignoring the exposure request"
    }

    companion object {
        private const val TAG = "AutoExposure"

        /**
         * Washed-out fraction we are aiming to get under.
         *
         * Sits in the gap between the two regimes measured on the reference clips --
         * 47%+ when the camera meters for itself, under 4% two stops down -- so
         * anywhere in between converges. Nearer the readable end so a wall that is only
         * partly blown out still gets one more step.
         */
        const val TARGET = 0.25f

        /**
         * Six halvings is six stops, which is the whole useful range of the dial. Past
         * that the wall is dark for a reason the exposure cannot fix.
         */
        const val MAX_STEPS_DOWN = 6

        /**
         * How many stops the LED-wall preset counts as, for the purpose of deciding how
         * far back up the loop may climb. Roughly what the preset is worth against a
         * typical indoor auto-exposure, and it only bounds the recovery path -- being a
         * stop out either way costs one extra pass, not correctness.
         */
        const val PRESET_EQUIVALENT_STEPS = 3

        /**
         * Solver steps of silence before a step down *may* be judged to have lost the
         * wall. A floor on evidence, not on time: paired with [LOST_THE_WALL_MILLIS],
         * which is the one that actually decides.
         */
        const val LOST_THE_WALL_STEPS = 60

        /**
         * Silence, in wall-clock milliseconds, before a step down is judged to have lost
         * the wall rather than to be waiting for the user to point at it.
         *
         * This used to be expressed only as [LOST_THE_WALL_STEPS] on the assumption that
         * the loop ran at 4 Hz. It does not -- on device it runs nearer 7 -- so what was
         * meant as fifteen seconds fired in nine, twice, while the user was still
         * walking to the wall. Fifteen seconds is longer than a sweep of the wall, which
         * is the quantity that matters, and it is now measured rather than assumed.
         */
        const val LOST_THE_WALL_MILLIS = 15_000L

        /**
         * Readings under [TARGET] required before the loop calls it settled.
         *
         * [Phase.SETTLED] is terminal, so it is worth three frames of agreement. One is
         * not enough: on the wall a single pass caught the gems as the camera swung
         * past, measured a wall that was barely in frame, and locked a two-stop error in
         * for the rest of the session.
         */
        const val SETTLE_READINGS = 3
    }
}
