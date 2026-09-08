package com.puzzlesolver.app.frame

import java.util.concurrent.atomic.AtomicReference

/**
 * What the app is asking the camera to do, what the camera says it did, and what it
 * is capable of.
 *
 * Split into those three because they disagree, routinely and silently. A device may
 * clamp exposure compensation to its own range, ignore a manual exposure request
 * because ARCore re-issued the capture request, or advertise manual sensor support and
 * then round the exposure time to something else. Every one of those looks identical
 * from the app's side -- the picture is still too bright -- so [Reported] is read back
 * off the frame metadata rather than assumed, and the HUD shows requested and actual
 * side by side.
 *
 * The whole thing exists because of one measurement: on the gem wall, at the exposure
 * the camera picks for itself, 47% to 69% of every gem carries no colour at all. The
 * rings are simply not in the image. No amount of care downstream recovers them, and
 * the only fix is upstream of the sensor. See docs/GEM_PUZZLE.md.
 */
class CameraTuning {

    enum class Mode {
        /** The camera meters for itself; [Settings.evSteps] biases the result. */
        AUTO,

        /**
         * Fixed exposure time and sensitivity. Only offered when the device advertises
         * `MANUAL_SENSOR`, and much the better option when it does -- a dark room full
         * of bright LEDs is exactly the scene auto-exposure gets wrong, because the
         * metering sees mostly black wall and opens up until the LEDs are white discs.
         */
        MANUAL,
    }

    /**
     * @param evSteps exposure compensation in the device's own steps, not in EV. The
     *        step size is a device property; [Capabilities.evStepsPerEv] converts.
     * @param exposureNanos manual shutter time.
     * @param iso manual sensitivity.
     * @param lockAe freezes metering. Worth having even in [Mode.AUTO]: the canvas
     *        keeps the best look at each texel across a whole pan, so exposure drifting
     *        mid-pan writes neighbouring texels from different exposures and leaves
     *        seams the colour classifier reads as real.
     * @param lockAwb freezes white balance. The gem classifier decides on *hue*, and
     *        auto white balance chasing a room washed in shifting purple rotates every
     *        hue in the frame. This is the cheapest accuracy win available -- but it is
     *        off by default and turned on by the LED-wall preset rather than at
     *        startup, because locking before the camera has converged freezes whatever
     *        white balance it happened to begin with.
     */
    data class Settings(
        val mode: Mode = Mode.AUTO,
        val evSteps: Int = 0,
        val exposureNanos: Long = DEFAULT_EXPOSURE_NANOS,
        val iso: Int = DEFAULT_ISO,
        val lockAe: Boolean = false,
        val lockAwb: Boolean = false,
        val diagnosticMono: Boolean = false,
    )

    /** What the device says it can do. All defaults are the "cannot" answer. */
    data class Capabilities(
        val available: Boolean = false,
        val manualSensor: Boolean = false,
        val minEvSteps: Int = 0,
        val maxEvSteps: Int = 0,
        /** Compensation steps per whole EV; 1 when the device does not say. */
        val evStepsPerEv: Int = 1,
        val minExposureNanos: Long = 100_000L,
        val maxExposureNanos: Long = 100_000_000L,
        val minIso: Int = 50,
        val maxIso: Int = 3200,
        /** Why control is unavailable, when it is. */
        val issue: String? = null,
    ) {
        fun describe(): String = when {
            !available -> "camera control unavailable${issue?.let { ": $it" } ?: ""}"
            manualSensor -> "manual ${minExposureNanos / 1000}us-${maxExposureNanos / 1_000_000}ms " +
                "iso $minIso-$maxIso, ev $minEvSteps..$maxEvSteps"
            else -> "ev $minEvSteps..$maxEvSteps only (no manual sensor)"
        }
    }

    /** What the frame metadata says actually happened. All nullable: it may not say. */
    data class Reported(
        val exposureNanos: Long? = null,
        val iso: Int? = null,
        val evSteps: Int? = null,
        val aeMode: Int? = null,
        val aeLocked: Boolean? = null,
        val awbMode: Int? = null,
        val awbLocked: Boolean? = null,
    ) {
        fun describe(): String {
            if (exposureNanos == null && iso == null && evSteps == null) return "camera: no metadata"
            val shutter = exposureNanos?.let { "1/${(1_000_000_000.0 / it).toInt()}s" } ?: "?"
            val sensitivity = iso?.let { "iso$it" } ?: "iso?"
            val ev = evSteps?.let { if (it == 0) "ev0" else "ev%+d".format(it) } ?: ""
            val locks = buildString {
                if (aeLocked == true) append(" AE-lock")
                if (awbLocked == true) append(" AWB-lock")
            }
            // The AE mode is what settles an argument the other numbers only hint at:
            // a shutter that will not move is a mode that never left manual, and that
            // reads identically to a request that was ignored.
            val mode = when (aeMode) {
                0 -> " ae:off"
                1 -> " ae:on"
                null -> ""
                else -> " ae:$aeMode"
            }
            return "$shutter $sensitivity $ev$mode$locks"
        }
    }

    private val settingsRef = AtomicReference(Settings())

    @Volatile
    var capabilities: Capabilities = Capabilities()

    @Volatile
    var reported: Reported = Reported()

    /**
     * Bumped on every settings change.
     *
     * The camera layer polls this rather than being pushed to, because a change has to
     * be applied on the camera's own background thread and from a state where ARCore is
     * paused -- pushing would mean doing that work on whatever thread the user's tap
     * arrived on.
     */
    @Volatile
    var generation: Int = 0
        private set

    val settings: Settings get() = settingsRef.get()

    fun update(transform: (Settings) -> Settings): Boolean {
        while (true) {
            val current = settingsRef.get()
            val next = clamp(transform(current))
            if (next == current) return false
            if (settingsRef.compareAndSet(current, next)) {
                generation++
                return true
            }
        }
    }

    /** Keeps a request inside what the device admits to, so the HUD shows the truth. */
    private fun clamp(s: Settings): Settings {
        val c = capabilities
        if (!c.available) return s
        return s.copy(
            mode = if (s.mode == Mode.MANUAL && !c.manualSensor) Mode.AUTO else s.mode,
            evSteps = s.evSteps.coerceIn(c.minEvSteps, c.maxEvSteps),
            exposureNanos = s.exposureNanos.coerceIn(c.minExposureNanos, c.maxExposureNanos),
            iso = s.iso.coerceIn(c.minIso, c.maxIso),
        )
    }

    /**
     * Applies the preset for a wall of bright LEDs in a dark room.
     *
     * Chosen the moment a colour-reading puzzle becomes active, because the mode choice
     * *is* the evidence: nothing else in this app reads a wall that lights itself. The
     * numbers are not a guess either -- 4 ms at the sensor's floor sensitivity is close
     * to what the two readable reference clips were shot at, and the four unreadable
     * ones are what the camera picks when left alone.
     *
     * On a device without manual sensor control this falls back to the largest negative
     * exposure compensation available, which is weaker but points the same way.
     */
    fun applyLedWallPreset(): Boolean = update {
        val c = capabilities
        if (c.manualSensor) {
            it.copy(
                mode = Mode.MANUAL,
                exposureNanos = LED_WALL_EXPOSURE_NANOS,
                iso = c.minIso.coerceAtLeast(50),
                lockAwb = true,
            )
        } else {
            it.copy(
                mode = Mode.AUTO,
                evSteps = (-3 * c.evStepsPerEv).coerceAtLeast(c.minEvSteps),
                lockAe = true,
                lockAwb = true,
            )
        }
    }

    /**
     * Halves the light, whichever dial this device gives us.
     *
     * @return false when already at the bottom, which is what tells the auto-tuner to
     *         stop rather than keep spending a rescan per step on nothing.
     */
    fun darker(): Boolean = update {
        if (it.mode == Mode.MANUAL) {
            it.copy(exposureNanos = (it.exposureNanos / 2).coerceAtLeast(capabilities.minExposureNanos))
        } else {
            it.copy(evSteps = (it.evSteps - capabilities.evStepsPerEv).coerceAtLeast(capabilities.minEvSteps))
        }
    }

    fun brighter(): Boolean = update {
        if (it.mode == Mode.MANUAL) {
            it.copy(exposureNanos = (it.exposureNanos * 2).coerceAtMost(capabilities.maxExposureNanos))
        } else {
            it.copy(evSteps = (it.evSteps + capabilities.evStepsPerEv).coerceAtMost(capabilities.maxEvSteps))
        }
    }

    fun reset() {
        update { Settings() }
    }

    /**
     * Whether the camera is doing what it was asked, or null while that is not yet
     * knowable.
     *
     * This is not defensive programming, it is a measured necessity. On the device this
     * was developed against, ARCore's shared camera mode discards the app's capture
     * request entirely -- a request for 1/250 s at ISO 100 reads back as 1/100 s at
     * ISO 2008, and even `CONTROL_EFFECT_MODE_MONO` leaves the preview in colour. The
     * same request through plain Camera2, with no ARCore in the picture, is honoured
     * exactly. See docs/CAMERA_CONTROL.md.
     *
     * Detecting it matters beyond honesty in the HUD: the auto-exposure loop closes
     * through the solver, so against a camera that ignores it, it would read the wall as
     * still blown out, step down, and discard the mosaic -- six times over, achieving
     * nothing but six lost scans. A dial that does nothing is a nuisance; a dial that
     * does nothing while restarting the scan is worse than no dial.
     *
     * Only asked of a setting far enough from the automatic answer to be distinguishable
     * from it: exposure compensation of zero tells us nothing either way.
     */
    fun honoured(): Boolean? {
        val agrees = compare() ?: return null
        // Debounced, because a change takes a moment to reach the sensor and the frames
        // in between legitimately still carry the old settings. Without this, every
        // adjustment briefly accuses the camera of ignoring it.
        mismatchStreak = if (agrees) 0 else mismatchStreak + 1
        if (agrees) return true
        return if (mismatchStreak >= MISMATCH_SAMPLES) false else null
    }

    private fun compare(): Boolean? {
        val s = settings
        val r = reported
        if (s.mode == Mode.MANUAL) {
            val actual = r.exposureNanos ?: return null
            // A quarter is generous. Devices round the exposure to a row time, which is
            // a percent or two, not a factor of two.
            return kotlin.math.abs(actual - s.exposureNanos) <= s.exposureNanos / 4
        }
        if (s.evSteps == 0) return null
        val actual = r.evSteps ?: return null
        return actual == s.evSteps
    }

    @Volatile
    private var mismatchStreak = 0

    /** One line for the HUD: what was asked for. */
    fun describeRequest(): String {
        val s = settings
        val head = if (s.mode == Mode.MANUAL) {
            "manual 1/${(1_000_000_000.0 / s.exposureNanos).toInt()}s iso${s.iso}"
        } else {
            "auto ev%+d".format(s.evSteps)
        }
        return head + (if (s.lockAe) " AE-lock" else "") + (if (s.lockAwb) " AWB-lock" else "")
    }

    companion object {
        /** 1/250 s. A sane hand-held default when manual is switched on cold. */
        const val DEFAULT_EXPOSURE_NANOS = 4_000_000L
        const val DEFAULT_ISO = 200

        /** 1/250 s, which is roughly where the readable reference clips sit. */
        const val LED_WALL_EXPOSURE_NANOS = 4_000_000L

        /**
         * Disagreeing read-backs needed before the camera is accused of ignoring us.
         * Metadata arrives a few times a second, so this is about a second of it.
         */
        const val MISMATCH_SAMPLES = 4
    }
}
