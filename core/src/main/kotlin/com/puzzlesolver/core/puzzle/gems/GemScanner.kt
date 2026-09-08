package com.puzzlesolver.core.puzzle.gems

import com.puzzlesolver.core.image.CanvasView

/**
 * One camera frame in, a list of gems to highlight out.
 *
 * The whole of Gems, once the AR pipeline was dropped: no wall fit, no canvas, no pose,
 * no solver. Each frame stands alone, which is exactly right for this puzzle — whether a
 * gem matches depends on that gem and nothing else, and the wall is rearranged between
 * rounds anyway, so there was never anything worth accumulating.
 *
 * The parts that were expensive to get right are unchanged and carried over whole:
 * [GemPalette] still reads the three rings, [GemPattern] still decides what matches, and
 * both were measured and tested against real frames from the room. What changed is only
 * where the coordinates come from.
 */
class GemScanner(
    private val targets: GemTargets,
    private val detector: GemBlobDetector = GemBlobDetector(),
) {
    private val reader = GemReader()

    /**
     * Where the last scan spent its time, in microseconds: detect, pitch, read.
     *
     * Plus the detector's own four stages, which it keeps itself. Published rather than
     * inferred because "the scan is sometimes slow" is not something to guess at, and
     * the two candidates -- finding the gems and reading them -- want opposite fixes.
     */
    @JvmField
    val stageMicros = LongArray(3)

    /** One line of profile, for the HUD and the heartbeat. */
    fun describeProfile(): String {
        val d = detector.stageMicros
        return "detect ${stageMicros[0] / 1000}ms (down ${d[0] / 1000} blur ${d[1] / 1000} " +
            "cc ${d[2] / 1000} filt ${d[3] / 1000}) pitch ${stageMicros[1] / 1000}ms " +
            "read ${stageMicros[2] / 1000}ms"
    }

    /** A gem as read, in full-resolution image pixels. */
    class Gem(
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val radius: Float,
        @JvmField val pattern: GemPattern,
        @JvmField val confidence: Float,
        /** 1-based target slot this gem matched, or 0 for none. */
        @JvmField val matchedSlot: Int,
    )

    class Result(
        @JvmField val gems: List<Gem>,
        @JvmField val matchCount: Int,
        /**
         * Discs the detector found, before any of them were read.
         *
         * Separate from `gems.size`, which counts the ones that came back lit, because
         * the two failures they distinguish want opposite fixes: nothing found at all is
         * a detection or framing problem, and thirteen found with none lit is an
         * exposure or threshold one. Conflating them sends the next hour the wrong way.
         */
        @JvmField val blobCount: Int,
        /**
         * Median button pitch in image pixels, or -1 when too few gems were in frame.
         *
         * The frame's median, for the HUD and the heartbeat. The rings are not read
         * against it -- each gem is read against its own local pitch, which on a curved
         * wall differs from this by up to a quarter.
         */
        @JvmField val pitch: Float,
        /** Fraction of gem area carrying no usable hue, or -1 when nothing was read. */
        @JvmField val washedOut: Float,
        /** One line for the HUD, saying what to do about it. */
        @JvmField val status: String,
        /**
         * Whether the blobs are arranged like the gem wall rather than scattered.
         *
         * The only consumer is the auto-exposure loop, which must not read a silent
         * frame as "too dark" when the truth is "not pointed at anything". Frame
         * brightness cannot tell those apart -- an LED wall two stops under is nearly as
         * dark as an empty room, and a black frame full of sensor noise is not as dark
         * as one -- but the arrangement of the blobs can, at any exposure the discs are
         * still visible at.
         */
        @JvmField val looksLikeAWall: Boolean = false,
        /** The measurement behind [looksLikeAWall], for the heartbeat. */
        @JvmField val latticeSpread: Float = -1f,
    ) {
        val matches: List<Gem> get() = gems.filter { it.matchedSlot > 0 }

        companion object {
            val EMPTY = Result(emptyList(), 0, 0, -1f, -1f, "looking for gems")
        }
    }

    fun scan(view: CanvasView): Result {
        var mark = System.nanoTime()
        val blobs = detector.detect(view.luma)
        stageMicros[0] = (System.nanoTime() - mark) / 1000
        if (blobs.isEmpty()) {
            return Result(emptyList(), 0, 0, -1f, -1f, "no gems in view")
        }
        mark = System.nanoTime()
        val pitch = detector.estimatePitch(blobs)
        stageMicros[1] = (System.nanoTime() - mark) / 1000
        if (pitch <= 0f) {
            // Deliberately not falling back to guessing the pitch from a blob's size.
            // Lit-disc radius moves with exposure; the spacing does not, and every ring
            // constant is expressed against the spacing.
            return Result(
                emptyList(), 0, blobs.size, -1f, -1f,
                if (blobs.size == 1) "only one gem in view -- take a step back"
                else "only ${blobs.size} gems in view -- take a step back",
            )
        }
        // Are these blobs a wall, or just things? The same nearest-neighbour distances
        // the pitch came from, read a second way: the pitch is their middle and this is
        // how much they disagree. See [GemBlobDetector.latticeSpread].
        //
        // It gates the exposure hint and nothing else, which is the whole of the
        // judgement here. The two mistakes it can make are not remotely equal. Calling a
        // real wall "not a wall" and refusing to read it would stop the feature dead
        // while the user stands in front of it -- and the measurement is only good to
        // about one blob in ten of glare before it says that. Withholding one frame's
        // exposure hint costs nothing at all, because the next frame asks again. So the
        // rings are read and matched exactly as before whatever this says, and all it
        // decides is whether this frame gets a vote on the camera.
        val spread = detector.latticeSpread(blobs)
        val looksLikeAWall = spread >= 0f && spread <= GemBlobDetector.MAX_LATTICE_SPREAD
        if (!view.hasColour) {
            return Result(emptyList(), 0, blobs.size, pitch, -1f, "no colour in this frame")
        }

        mark = System.nanoTime()
        val active = targets.active()
        // One pitch per gem rather than one for the frame. The wall is curved and the
        // camera is never square to it, so the spacing changes across a single frame by
        // more than the width of a ring band. See [GemBlobDetector.localPitches].
        val localPitch = detector.localPitches(blobs, pitch)
        val gems = ArrayList<Gem>(blobs.size)
        var matchCount = 0
        var washedSum = 0f
        var washedCount = 0
        var readable = 0

        for ((index, blob) in blobs.withIndex()) {
            val read = reader.readAt(view, blob.x, blob.y, localPitch?.get(index) ?: pitch)
            if (!read.lit) continue
            washedSum += read.washedOut
            washedCount++
            if (read.pattern.isReadable) readable++

            var slot = 0
            for (i in active.indices) {
                if (active[i].matches(read.pattern)) {
                    slot = i + 1
                    matchCount++
                    break
                }
            }
            gems.add(Gem(blob.x, blob.y, blob.radius, read.pattern, read.confidence, slot))
        }

        stageMicros[2] = (System.nanoTime() - mark) / 1000
        // A quorum, for the same reason the pitch needs one. This number is a mean over
        // gems, and a mean over one gem is not a measurement of a wall -- but it is
        // published as `exposureHint` and the auto-exposure loop closes on it, and
        // SETTLED is terminal.
        //
        // Measured, not hypothetical: pointed at a dark room at 1/250 s, sensor noise
        // clears the local-background threshold often enough to yield ten to twenty
        // blobs, one to six of which read as lit, and their handful of saturated texels
        // average to a confident 0% washed out. On a phone left face down for eight
        // seconds that was enough to settle the loop, which would then have refused to
        // darken anything when the wall finally came into frame. The giveaway in the
        // log is the pitch: 43 px one frame and 436 px the next, where a wall holds
        // steady near 130.
        val washed =
            if (looksLikeAWall && washedCount >= MIN_GEMS_FOR_EXPOSURE_HINT) {
                washedSum / washedCount
            } else {
                -1f
            }
        return Result(
            gems, matchCount, blobs.size, pitch, washed,
            describe(gems.size, readable, matchCount, washed, active.size),
            looksLikeAWall, spread,
        )
    }

    private fun describe(
        seen: Int,
        readable: Int,
        matches: Int,
        washed: Float,
        activeTargets: Int,
    ): String = when {
        // Said first and in plain terms, because it is the one failure with a cause the
        // user can do something about, and every other symptom of it is misleading.
        washed > OVEREXPOSED && seen >= 4 ->
            "over-exposed: ${(washed * 100).toInt()}% of the gems has no colour -- darken the camera"
        seen == 0 -> "no gems in view"
        activeTargets == 0 -> "$seen gems in view -- tap a target above to start matching"
        matches == 0 -> "no match in view  ·  $readable of $seen gems read"
        matches == 1 -> "1 match  ·  $readable of $seen gems read"
        else -> "$matches matches  ·  $readable of $seen gems read"
    }

    private companion object {
        /**
         * Sits in the gap between the two exposure regimes measured on the reference
         * clips: 47%+ when the camera meters for itself, under 4% two stops down.
         */
        const val OVEREXPOSED = 0.35f

        /**
         * Lit gems required before this frame has an opinion about the exposure.
         *
         * The same six the pitch needs, and for the same reason: below that the figure
         * is one or two measurements and is as likely to describe sensor noise as a
         * wall. Declining to answer is free -- the next frame asks again -- while
         * answering wrongly is acted on and then latched.
         */
        const val MIN_GEMS_FOR_EXPOSURE_HINT = 6
    }
}
