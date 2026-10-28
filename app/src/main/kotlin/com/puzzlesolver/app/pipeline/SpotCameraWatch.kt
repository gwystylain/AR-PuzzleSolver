package com.puzzlesolver.app.pipeline

import com.puzzlesolver.app.frame.CameraTuning

/**
 * Hands Spot's camera back to its own exposure when the screen has been out of sight too
 * long.
 *
 * Spot meters the camera on the screen ([com.puzzlesolver.core.puzzle.spot.ScreenMeter],
 * through a [TerminalExposure]), which only works while the screen can be found -- and a
 * camera set too dark to find it never gets a reading that would brighten it. On
 * 2026-10-27 that is what happened in the room: Spot was picked straight after Gems,
 * inherited the LED-wall preset of 1/250 s at ISO 100, and every photo of the round was
 * refused with nothing bright in the middle of the frame. The mode switch now resets the
 * camera on the way in; this is for every other road to the same place, the loop's own
 * steps among them.
 *
 * The camera's own metering is the place to go back to because it fails the other way:
 * in a dark room it over-exposes a lit screen, which is still found, and the meter
 * brings it down from there. Waiting longer than [TerminalExposure.LOST_WALL_MILLIS]
 * leaves the loop the first go at undoing a step of its own that lost the screen.
 */
class SpotCameraWatch(
    /** Wall-clock, injectable so the timings can be tested without waiting them out. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var blindSince = NEVER

    fun reset() {
        blindSince = NEVER
    }

    /**
     * One frame's worth of evidence.
     *
     * @return true when the camera was put back on its own exposure, which the caller
     *         should treat as a fresh start for the loop metering it.
     */
    fun consider(tuning: CameraTuning, screenInView: Boolean): Boolean {
        val now = clock()
        if (screenInView) {
            blindSince = NEVER
            return false
        }
        if (blindSince == NEVER) {
            blindSince = now
            return false
        }
        if (now - blindSince < BLIND_MILLIS) return false
        // Counted again from here, so a camera that cannot find the screen on its own
        // exposure either -- pointed at the floor -- is not reset every frame.
        blindSince = now
        return tuning.update { CameraTuning.Settings() }
    }

    companion object {
        /** No screen for this long and the camera goes back to its own exposure. */
        const val BLIND_MILLIS = 5_000L

        private const val NEVER = Long.MIN_VALUE
    }
}
