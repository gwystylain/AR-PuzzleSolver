package com.puzzlesolver.core.puzzle.gems

import kotlin.math.hypot

/**
 * Decides which gems to ring from the last few scans rather than the latest one alone.
 *
 * Every scan is read from scratch, which is right for the reading and wrong for the
 * rings drawn from it. A gem whose reading sits near a boundary between two colours
 * reads one way in one scan and the other way in the next, and at ten scans a second
 * its ring blinks: on the third stage of the run of 2026-10-31 the user saw rings
 * flicker on and off over gems that were not changing. A ring that comes and goes is
 * worse than either answer held still -- it draws the eye, and it says nothing about
 * which of the two answers to believe.
 *
 * So each gem is followed from scan to scan by position, its last [HISTORY] readings
 * are kept, and it is ringed for a target only when [QUORUM] of them match it. A single
 * stray reading can neither light a gem nor put one out, and a gem the detector misses
 * for one scan keeps its history and its ring. The cost is that a gem coming into view
 * is ringed one scan later -- a tenth of a second, which nobody panning a camera sees.
 *
 * Readings are kept rather than matched slots, so a change of targets applies at once
 * to everything already in view instead of waiting for the history to turn over.
 *
 * Not thread-safe; it lives on the thread that runs the scans.
 */
class GemHighlightSteadier {

    private class Track(
        var x: Float,
        var y: Float,
        /** Oldest first; null for a scan in which the gem was cut off, or not seen. */
        val readings: ArrayDeque<GemPattern?>,
        var radius: Float,
        var missed: Int = 0,
    )

    private var tracks = ArrayList<Track>()

    /** How far the wall moved between the last two scans, used to predict the next. */
    private var shiftX = 0f
    private var shiftY = 0f

    fun reset() {
        tracks = ArrayList()
        shiftX = 0f
        shiftY = 0f
    }

    /**
     * Takes one scan and returns the gems to ring, each with the slot it is ringed for:
     * 1-based, in the order of [active], the same numbering [GemScanner] uses.
     *
     * Positions are this scan's, so a ring lands where its gem is now. A ringed gem the
     * detector missed this time is returned where its neighbours say it has moved to.
     */
    fun update(result: GemScanner.Result, active: List<GemPattern>): List<GemScanner.Gem> {
        if (result.pitch <= 0f || result.gems.isEmpty()) {
            reset()
            return emptyList()
        }
        val gate = result.pitch * MAX_STEP
        val gems = result.gems

        // Pair each gem with the track nearest where that track should now be, closest
        // pairs first, so two gems can never claim the same history.
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((gi, gem) in gems.withIndex()) {
            for ((ti, track) in tracks.withIndex()) {
                val d = hypot(gem.x - (track.x + shiftX), gem.y - (track.y + shiftY))
                if (d <= gate) pairs.add(Triple(d, gi, ti))
            }
        }
        pairs.sortBy { it.first }
        val trackOf = IntArray(gems.size) { -1 }
        val claimed = BooleanArray(tracks.size)
        for ((_, gi, ti) in pairs) {
            if (trackOf[gi] >= 0 || claimed[ti]) continue
            trackOf[gi] = ti
            claimed[ti] = true
        }

        // How the wall moved, from the gems that paired: the median, so one mispaired
        // gem cannot drag it.
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        for ((gi, ti) in trackOf.withIndex()) {
            if (ti < 0) continue
            dxs.add(gems[gi].x - tracks[ti].x)
            dys.add(gems[gi].y - tracks[ti].y)
        }
        val newShiftX = if (dxs.isEmpty()) 0f else dxs.sorted()[dxs.size / 2]
        val newShiftY = if (dys.isEmpty()) 0f else dys.sorted()[dys.size / 2]

        val next = ArrayList<Track>(gems.size + tracks.size)
        val ringed = ArrayList<GemScanner.Gem>()
        for ((gi, gem) in gems.withIndex()) {
            val ti = trackOf[gi]
            val readings = if (ti >= 0) tracks[ti].readings else ArrayDeque()
            readings.addLast(if (gem.whole) gem.pattern else null)
            while (readings.size > HISTORY) readings.removeFirst()
            next.add(Track(gem.x, gem.y, readings, gem.radius))
            val slot = steadySlot(readings, active)
            if (slot > 0) ringed.add(GemScanner.Gem(gem.x, gem.y, gem.radius, gem.pattern, gem.confidence, slot, gem.whole))
        }
        // A gem the detector lost for one scan keeps its place, moved with the wall, and
        // keeps its ring if its history still earns one. Two scans lost and it is gone.
        for ((ti, track) in tracks.withIndex()) {
            if (claimed[ti] || track.missed >= MAX_MISSED) continue
            track.x += newShiftX
            track.y += newShiftY
            track.missed++
            track.readings.addLast(null)
            while (track.readings.size > HISTORY) track.readings.removeFirst()
            next.add(track)
            val slot = steadySlot(track.readings, active)
            if (slot > 0) {
                ringed.add(GemScanner.Gem(track.x, track.y, track.radius, GemPattern.BLANK, 0f, slot, true))
            }
        }
        tracks = next
        shiftX = newShiftX
        shiftY = newShiftY
        return ringed
    }

    private fun steadySlot(readings: ArrayDeque<GemPattern?>, active: List<GemPattern>): Int {
        for ((i, target) in active.withIndex()) {
            var votes = 0
            for (reading in readings) if (reading != null && reading.isReadable && target.matches(reading)) votes++
            if (votes >= QUORUM) return i + 1
        }
        return 0
    }

    companion object {
        /** Scans of history per gem. */
        const val HISTORY = 3

        /** Of those, how many must match a target for the gem to be ringed. */
        const val QUORUM = 2

        /**
         * Furthest a gem may move between scans and still be the same gem, as a fraction
         * of the pitch. Under half, so a gem is never mistaken for its neighbour; a pan
         * fast enough to beat it -- over six pitches a second at ten scans a second --
         * blurs the rings past reading anyway, and the gems simply start new histories.
         */
        const val MAX_STEP = 0.4f

        /** Scans a gem may go undetected and keep its history. */
        const val MAX_MISSED = 1
    }
}
