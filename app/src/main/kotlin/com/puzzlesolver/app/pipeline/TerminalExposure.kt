package com.puzzlesolver.app.pipeline

import android.util.Log
import com.puzzlesolver.app.frame.CameraTuning
import com.puzzlesolver.core.puzzle.terminal.DisplayMeter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Keeps the terminal wall exposed where the reader reads it best, by metering the
 * displays rather than the room.
 *
 * What it replaces is a one-shot preset that held whatever brightness the camera's own
 * metering had chosen and bought a fast shutter with gain. That was right about the
 * shutter and wrong about the brightness: the camera meters a dark room, opens up, and
 * the wall arrives over-exposed. Both captures from the room were, by one and nearly two
 * stops, and at nearly two the digits bloomed until the reader took `058` for `008` in
 * every frame. [DisplayMeter] measures how far off the wall is, in stops, from the lit
 * displays alone; this turns that number into a new exposure.
 *
 * **One move, not a walk.** On this phone every change rebuilds the capture session and
 * costs a few hundred milliseconds of no frames, so stepping a stop at a time would be
 * five gaps in the preview to cross a wall two stops over. The meter says how far, so
 * the correction is made in one go and then checked.
 *
 * It is careful about when it moves, because the wall is wide and a user pans across it:
 *
 *  - **Only on evidence.** Nothing happens until the meter has seen ten lit displays,
 *    so walking up to the wall or pointing at the floor never moves the dial.
 *  - **Only on agreement.** The median of three consecutive readings, so one frame with
 *    the bright end of the wall in it does not decide anything.
 *  - **Only when it matters.** Every display reads from two stops under the target to
 *    two over it, so anything within half a stop is left alone -- that is precision the
 *    reader has no use for, bought with a gap in the preview.
 *  - **Only on fresh readings.** After a change it waits until the frame metadata shows
 *    the sensor running the new settings, then skips one more scan, so it never acts
 *    twice on one problem.
 *  - **Not too often.** Two seconds between changes at least.
 *
 * Unlike the gem loop it does not settle and stop. There is no mosaic here to throw
 * away, so a change costs only its gap, and the loop's own caution is what keeps it
 * still once it is on target.
 *
 * Two ways it can go wrong, and what it does about each:
 *
 *  - **A change loses the wall.** If no reading arrives within three seconds of the new
 *    settings reaching the sensor, it puts the old ones back and will not go that far
 *    in that direction again. A user who happened to look away at that moment costs one
 *    extra change, not a wrong exposure.
 *  - **Its correction is the wrong size.** Stops are counted on a plain gamma curve that
 *    is only roughly the phone's. Too small is harmless -- the next reading asks for the
 *    rest. Too big would swing past the target and back, so an overshoot halves how much
 *    of the next correction it applies.
 *
 * The brightness is paid for as the old preset paid for it: the shutter at 1/250 s to
 * freeze a hand-held pan, and gain for the rest, because this reader barely notices
 * noise and would be ruined by smear. Gain only runs out on a dim wall, and then the
 * shutter may lengthen -- but not past 1/60 s. Past that the wall stays under, which the
 * reader handles far better than blur.
 */
class TerminalExposure(
    /** Wall-clock, injectable so the timings can be tested without waiting them out. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Phase {
        /** Switched off, because the user took the dials. */
        OFF,

        /** No manual sensor, or the camera has not reported anything to scale from. */
        IDLE,

        /** No wall in view to meter. */
        LOOKING,

        /** Metering; not yet enough readings to act on. */
        MEASURING,

        /** Within half a stop of the target. */
        ON_TARGET,

        /** A change has been asked for and has not reached the sensor yet. */
        APPLYING,

        /** Off target, but the dials have nothing further to give in that direction. */
        AT_LIMIT,

        /** The sensor never showed what was asked for, so there is no loop to close. */
        NOT_HONOURED,
    }

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) phase = Phase.OFF
        }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Changes made since the last reset, for the heartbeat. */
    @Volatile
    var changes: Int = 0
        private set

    /** The median error the last change was made on, in stops; for the heartbeat. */
    @Volatile
    var lastError: Float = 0f
        private set

    private val window = FloatArray(AGREE_READINGS)
    private var windowSize = 0
    private var windowNext = 0

    /** Settings asked for and not yet seen on the sensor. */
    private var pending: CameraTuning.Settings? = null
    private var pendingSinceMillis = 0L

    /** Readings still to skip after a change arrives: frames straddling it. */
    private var skipReadings = 0

    /** When the settings now running reached the sensor. */
    private var freshSinceMillis = 0L

    private var lastChangeMillis = Long.MIN_VALUE / 2

    /**
     * The settings before the last change, until a reading at the new ones proves the
     * change kept the wall in view. What a change that lost the wall is undone to.
     */
    private var unconfirmed: CameraTuning.Settings? = null
    private var unconfirmedTotal = 0.0
    private var unconfirmedDarker = false

    /** Bounds on total exposure left behind by a change that lost the wall. */
    private var darkest = 0.0
    private var brightest = Double.MAX_VALUE

    /** Fraction of the metered error applied; halved on an overshoot. */
    private var gain = 1f
    private var lastMoveSign = 0

    fun reset() {
        if (enabled) phase = Phase.IDLE
        changes = 0
        lastError = 0f
        clearWindow()
        pending = null
        skipReadings = 0
        freshSinceMillis = clock()
        lastChangeMillis = Long.MIN_VALUE / 2
        unconfirmed = null
        darkest = 0.0
        brightest = Double.MAX_VALUE
        gain = 1f
        lastMoveSign = 0
    }

    /**
     * One scan's worth of evidence.
     *
     * @param reading the wall metered on its lit displays, or null when too few of them
     *        were in frame to trust.
     * @return true when the exposure was changed.
     */
    fun consider(tuning: CameraTuning, reading: DisplayMeter.Reading?): Boolean {
        if (!enabled) {
            phase = Phase.OFF
            return false
        }
        val caps = tuning.capabilities
        if (!caps.available || !caps.manualSensor) {
            phase = Phase.IDLE
            return false
        }
        val exposure = tuning.reported.exposureNanos
        val iso = tuning.reported.iso
        if (exposure == null || iso == null) {
            phase = Phase.IDLE
            return false
        }
        val now = clock()

        pending?.let { asked ->
            if (!running(asked, exposure, iso)) {
                if (now - pendingSinceMillis >= NOT_HONOURED_MILLIS) {
                    if (phase != Phase.NOT_HONOURED) {
                        Log.w(TAG, "the camera never showed ${tuning.describeRequest()}; standing down")
                    }
                    phase = Phase.NOT_HONOURED
                } else {
                    phase = Phase.APPLYING
                }
                return false
            }
            if (phase == Phase.NOT_HONOURED) Log.i(TAG, "the camera caught up with the request; resuming")
            pending = null
            freshSinceMillis = now
            skipReadings = SKIP_AFTER_CHANGE
        }

        if (reading == null) {
            clearWindow()
            val before = unconfirmed
            if (before != null && now - freshSinceMillis >= LOST_WALL_MILLIS) {
                return undo(tuning, before, now)
            }
            phase = Phase.LOOKING
            return false
        }
        if (skipReadings > 0) {
            skipReadings--
            phase = Phase.MEASURING
            return false
        }
        // A reading at the settings now running: whatever the last change was, it kept
        // the wall in view.
        unconfirmed = null

        push(reading.stopsOver)
        if (windowSize < AGREE_READINGS) {
            phase = Phase.MEASURING
            return false
        }
        val error = median()
        val sign = if (error > 0f) 1 else -1
        if (abs(error) <= DEADBAND_STOPS) {
            // The last move landed. A move later on in the other direction is the wall
            // or the room changing, not this one having overshot.
            lastMoveSign = 0
            phase = Phase.ON_TARGET
            return false
        }
        if (now - lastChangeMillis < MIN_INTERVAL_MILLIS) {
            phase = Phase.MEASURING
            return false
        }
        // Still off, and on the other side of the target from the last move: that move
        // was too big for this phone's tone curve, so take less of the next.
        if (lastMoveSign != 0 && sign != lastMoveSign) gain = (gain * 0.5f).coerceAtLeast(MIN_GAIN)

        val current = exposure.toDouble() * iso
        // Never less than MIN_MOVE_STOPS, however far the gain has been cut: a change
        // smaller than that is inside the tolerance a reading is checked against, and
        // could not be told apart from the settings it replaced.
        val magnitude = (abs(error) * gain).coerceIn(MIN_MOVE_STOPS, MAX_MOVE_STOPS)
        // Not coerceIn: two undone changes, one each way, can leave the bounds crossed,
        // and that must cost a clamp rather than an exception on the solver thread.
        val wanted = (current * 2.0.pow(-(sign * magnitude).toDouble()))
            .coerceAtLeast(darkest)
            .coerceAtMost(brightest)
        val (shutter, gainIso) = split(wanted, caps)
        val next = tuning.settings.copy(
            mode = CameraTuning.Mode.MANUAL,
            exposureNanos = shutter,
            iso = gainIso,
            // Nothing for either lock to do once both dials are fixed, and this reader
            // never looks at colour.
            lockAe = false,
            lockAwb = false,
        )
        val achieved = shutter.toDouble() * gainIso
        if (abs(kotlin.math.ln(achieved / current) / LN2) < MIN_MOVE_STOPS / 2) {
            phase = Phase.AT_LIMIT
            return false
        }
        val before = tuning.settings
        if (!tuning.update { next }) {
            phase = Phase.AT_LIMIT
            return false
        }
        unconfirmed = before
        unconfirmedTotal = achieved
        unconfirmedDarker = achieved < current
        lastMoveSign = sign
        lastError = error
        startChange(tuning, now)
        Log.i(
            TAG,
            "wall metered ${"%+.2f".format(error)} stops (${reading.describe()}); " +
                "${describeRunning(exposure, iso)} -> ${tuning.describeRequest()}",
        )
        return true
    }

    fun describe(): String = when (phase) {
        Phase.OFF -> "wall metering off"
        Phase.IDLE -> "wall metering idle"
        Phase.LOOKING -> "wall metering: no wall in view"
        Phase.MEASURING -> "wall metering: measuring"
        Phase.ON_TARGET -> "wall metering: on target"
        Phase.APPLYING -> "wall metering: applying"
        Phase.AT_LIMIT -> "wall metering: at the limit of the dials"
        Phase.NOT_HONOURED -> "wall metering: the camera is ignoring the request"
    } + " (${changes} changes)"

    /** Puts back the settings from before a change that lost the wall. */
    private fun undo(tuning: CameraTuning, before: CameraTuning.Settings, now: Long): Boolean {
        unconfirmed = null
        // Half a stop short of where it failed, so the next correction in that direction
        // can still get most of the way there.
        if (unconfirmedDarker) {
            darkest = maxOf(darkest, unconfirmedTotal * HALF_STOP)
        } else {
            brightest = minOf(brightest, unconfirmedTotal / HALF_STOP)
        }
        lastMoveSign = 0
        val lost = tuning.describeRequest()
        tuning.update { before }
        startChange(tuning, now)
        Log.w(TAG, "no wall for ${LOST_WALL_MILLIS / 1000}s at $lost; back to ${tuning.describeRequest()}")
        return true
    }

    private fun startChange(tuning: CameraTuning, now: Long) {
        // An automatic setting has nothing to check the sensor against: the camera
        // chooses. Treat it as arrived.
        pending = tuning.settings.takeIf { it.mode == CameraTuning.Mode.MANUAL }
        pendingSinceMillis = now
        if (pending == null) {
            freshSinceMillis = now
            skipReadings = SKIP_AFTER_CHANGE
        }
        lastChangeMillis = now
        changes++
        clearWindow()
        phase = Phase.APPLYING
    }

    private fun push(value: Float) {
        window[windowNext] = value
        windowNext = (windowNext + 1) % AGREE_READINGS
        if (windowSize < AGREE_READINGS) windowSize++
    }

    private fun median(): Float {
        val sorted = window.copyOf(windowSize)
        sorted.sort()
        return sorted[windowSize / 2]
    }

    private fun clearWindow() {
        windowSize = 0
        windowNext = 0
    }

    private fun describeRunning(exposure: Long, iso: Int) =
        "1/${(1_000_000_000.0 / exposure).roundToInt()}s iso$iso"

    companion object {
        private const val TAG = "TerminalExposure"

        /**
         * Half a stop either side of the target is on target. The reader reads the
         * whole wall from two stops under to two over, so this is still a stop and a
         * half from either edge.
         */
        const val DEADBAND_STOPS = 0.5f

        /** Consecutive readings whose median is acted on. */
        const val AGREE_READINGS = 3

        /** At least this long between changes: each one is a gap in the preview. */
        const val MIN_INTERVAL_MILLIS = 2_000L

        /** No wall this long after a change reaches the sensor, and the change is undone. */
        const val LOST_WALL_MILLIS = 3_000L

        /** A change the sensor has not shown after this long is not coming. */
        const val NOT_HONOURED_MILLIS = 3_000L

        /** Scans skipped once a change arrives, for frames exposed across it. */
        const val SKIP_AFTER_CHANGE = 1

        /** The most one change will move, in stops: as far as the meter can see. */
        const val MAX_MOVE_STOPS = DisplayMeter.MAX_STOPS

        /** The least of the error ever applied, however often it has overshot. */
        const val MIN_GAIN = 0.25f

        /** The smallest change made, in stops: 23%, clear of [MATCH_TOLERANCE]. */
        const val MIN_MOVE_STOPS = 0.3f

        /**
         * 1/250 s: fast enough to freeze a hand-held pan. Blur scales with exposure time,
         * and 15 pixels of smear cost the reader 28% of the wall where sensor noise at
         * sigma 16 cost it 0.4%, so the shutter is held here and gain pays for the rest.
         */
        const val TARGET_SHUTTER_NANOS = 4_000_000L

        /**
         * 1/60 s: the longest the shutter goes on this loop's account when gain has run
         * out. A dim wall is left under-exposed past this rather than smeared.
         */
        const val SLOWEST_SHUTTER_NANOS = 16_666_667L

        /**
         * Readings are compared with the request within this fraction, both dials.
         * Devices round the shutter to a sensor row time, which is a percent or two.
         */
        private const val MATCH_TOLERANCE = 0.10

        private val LN2 = kotlin.math.ln(2.0)

        private val HALF_STOP = 2.0.pow(0.5)

        /**
         * A total exposure, in nanoseconds times ISO, as a shutter and a gain.
         *
         * Shutter first: as fast as [TARGET_SHUTTER_NANOS] unless the gain ceiling
         * cannot pay for that, and never slower than [SLOWEST_SHUTTER_NANOS]. Faster
         * than the target when even the lowest gain would be too bright at it -- a
         * shorter shutter is only ever better here.
         */
        fun split(total: Double, caps: CameraTuning.Capabilities): Pair<Long, Int> {
            val fastest = caps.minExposureNanos.toDouble()
            val slowest = minOf(caps.maxExposureNanos, SLOWEST_SHUTTER_NANOS).toDouble()
                .coerceAtLeast(fastest)
            var shutter = maxOf(TARGET_SHUTTER_NANOS.toDouble(), total / caps.maxIso)
                .coerceIn(fastest, slowest)
            var iso = total / shutter
            if (iso < caps.minIso) {
                shutter = (total / caps.minIso).coerceIn(fastest, slowest)
                iso = total / shutter
            }
            return shutter.roundToLong() to iso.roundToInt().coerceIn(caps.minIso, caps.maxIso)
        }

        /** Whether the sensor is running [asked], near enough. */
        private fun running(asked: CameraTuning.Settings, exposure: Long, iso: Int): Boolean {
            if (asked.mode != CameraTuning.Mode.MANUAL) return true
            return abs(exposure - asked.exposureNanos) <= asked.exposureNanos * MATCH_TOLERANCE &&
                abs(iso - asked.iso) <= asked.iso * MATCH_TOLERANCE
        }
    }
}
