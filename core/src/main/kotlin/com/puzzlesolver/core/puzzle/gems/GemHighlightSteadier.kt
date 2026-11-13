package com.puzzlesolver.core.puzzle.gems

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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
 * So each gem is followed from scan to scan, its last [HISTORY] readings are kept, and
 * it is ringed for a target only when [QUORUM] of them match it. A single stray reading
 * can neither light a gem nor put one out, and a gem the detector misses for a scan
 * keeps its history and its ring. The cost is that a gem coming into view is ringed one
 * scan later -- a tenth of a second, which nobody panning a camera sees.
 *
 * **Following a gem means knowing how far the wall moved**, and the first version got
 * that wrong. It paired each gem with the nearest one from the scan before, within 0.4
 * of a pitch of where the last scan's motion put it. On 2026-11-12 the user swept the
 * wall at three and a half to five pitches a second, 0.35 to 0.5 of a pitch a scan with
 * hand shake on top; past the 0.4, no gem paired, every gem began a new history every
 * scan, and none ever reached two readings. The scanner matched five gems while none
 * was ringed, and the round was played as if the app could not find anything. A gate
 * any wider pairs a gem with its neighbour, because the lattice is the same everywhere.
 *
 * What is not the same everywhere is the colours. So the motion is found from the
 * readings: every gem in this scan and every gem in the last with the same reading
 * vote for the shift between them. The true shift collects a vote from nearly every
 * gem in view; pairs that agree by chance scatter over every shift there is. Then each
 * gem pairs with the one that shift puts nearest it. That holds up to [MAX_SHIFT]
 * pitches a scan, whatever the lattice, and needs no motion from the scan before. Only
 * when too few readings agree to say -- a wall of one colour, a handful of gems -- does
 * it fall back to the last scan's motion and the old gate.
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
        /** The latest complete reading, which is what the shift is voted on. */
        var last: GemPattern?,
        var missed: Int = 0,
    )

    private var tracks = ArrayList<Track>()

    /** How far the wall moved between the last two scans. */
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
     * detector missed this time is returned where the wall's motion says it has gone.
     */
    fun update(result: GemScanner.Result, active: List<GemPattern>): List<GemScanner.Gem> {
        val gems = result.gems
        if (result.pitch <= 0f || gems.isEmpty()) {
            // Nothing to follow this scan -- a blurred frame, the wall dipping out of
            // view. Everything coasts rather than being forgotten.
            val (coasting, rings) = coast(emptyList(), BooleanArray(tracks.size), shiftX, shiftY, active)
            tracks = coasting
            return rings
        }
        val pitch = result.pitch

        val voted = voteShift(gems, pitch)
        val sx = voted?.get(0) ?: shiftX
        val sy = voted?.get(1) ?: shiftY
        val gate = pitch * (if (voted != null) PAIR_RADIUS else MAX_STEP)

        // Pair each gem with the track the shift puts nearest it, closest pairs first,
        // so two gems can never claim the same history.
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((gi, gem) in gems.withIndex()) {
            for ((ti, track) in tracks.withIndex()) {
                val d = hypot(gem.x - (track.x + sx), gem.y - (track.y + sy))
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

        // The motion actually seen, from the gems that paired: the median, so one
        // mispaired gem cannot drag it.
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        for ((gi, ti) in trackOf.withIndex()) {
            if (ti < 0) continue
            dxs.add(gems[gi].x - tracks[ti].x)
            dys.add(gems[gi].y - tracks[ti].y)
        }
        val newShiftX = if (dxs.isEmpty()) sx else dxs.sorted()[dxs.size / 2]
        val newShiftY = if (dys.isEmpty()) sy else dys.sorted()[dys.size / 2]

        val next = ArrayList<Track>(gems.size + tracks.size)
        val ringed = ArrayList<GemScanner.Gem>()
        for ((gi, gem) in gems.withIndex()) {
            val ti = trackOf[gi]
            val readings = if (ti >= 0) tracks[ti].readings else ArrayDeque()
            val reading = if (gem.whole) gem.pattern else null
            readings.addLast(reading)
            while (readings.size > HISTORY) readings.removeFirst()
            val last = if (reading != null && reading.isReadable) reading else if (ti >= 0) tracks[ti].last else null
            next.add(Track(gem.x, gem.y, readings, gem.radius, last))
            val slot = steadySlot(readings, active)
            if (slot > 0) ringed.add(GemScanner.Gem(gem.x, gem.y, gem.radius, gem.pattern, gem.confidence, slot, gem.whole))
        }
        val (coasting, coastRings) = coast(next, claimed, newShiftX, newShiftY, active)
        ringed.addAll(coastRings)
        tracks = coasting
        shiftX = newShiftX
        shiftY = newShiftY
        return ringed
    }

    /**
     * Carries the tracks no gem claimed this scan forward by the wall's motion, for at
     * most [MAX_MISSED] scans, and rings the ones whose history still earns it. Appended
     * to [next], which is returned with the rings.
     */
    private fun coast(
        next: List<Track>,
        claimed: BooleanArray,
        dx: Float,
        dy: Float,
        active: List<GemPattern>,
    ): Pair<ArrayList<Track>, List<GemScanner.Gem>> {
        val out = ArrayList<Track>(next)
        val rings = ArrayList<GemScanner.Gem>()
        for ((ti, track) in tracks.withIndex()) {
            if (claimed[ti] || track.missed >= MAX_MISSED) continue
            track.x += dx
            track.y += dy
            track.missed++
            track.readings.addLast(null)
            while (track.readings.size > HISTORY) track.readings.removeFirst()
            out.add(track)
            val slot = steadySlot(track.readings, active)
            if (slot > 0) rings.add(GemScanner.Gem(track.x, track.y, track.radius, GemPattern.BLANK, 0f, slot, true))
        }
        return out to rings
    }

    /**
     * How far the wall moved since the last scan, voted on by every pair of gems -- one
     * from each scan -- that read the same. Null when the vote is too thin to trust.
     *
     * Votes go into bins a fifth of a pitch across, and a bin's score is its own votes
     * and its eight neighbours', so a true shift lying on a bin boundary is not split.
     * The answer is the median of the votes around the winner.
     */
    private fun voteShift(gems: List<GemScanner.Gem>, pitch: Float): FloatArray? {
        if (tracks.isEmpty()) return null
        val bin = pitch * BIN
        val half = (MAX_SHIFT / BIN).roundToInt()
        val side = 2 * half + 1
        val counts = IntArray(side * side)
        val range = pitch * MAX_SHIFT
        val votesX = ArrayList<Float>()
        val votesY = ArrayList<Float>()
        for (gem in gems) {
            if (!gem.whole || !gem.pattern.isReadable) continue
            for (track in tracks) {
                if (track.last != gem.pattern) continue
                val dx = gem.x - track.x
                val dy = gem.y - track.y
                if (abs(dx) > range || abs(dy) > range) continue
                counts[((dy / bin).roundToInt() + half) * side + (dx / bin).roundToInt() + half]++
                votesX.add(dx)
                votesY.add(dy)
            }
        }
        var best = 0
        var bestX = 0
        var bestY = 0
        for (by in 0 until side) for (bx in 0 until side) {
            var score = 0
            for (ny in maxOf(0, by - 1)..minOf(side - 1, by + 1)) {
                for (nx in maxOf(0, bx - 1)..minOf(side - 1, bx + 1)) score += counts[ny * side + nx]
            }
            if (score > best) {
                best = score
                bestX = bx
                bestY = by
            }
        }
        val seen = gems.count { it.whole && it.pattern.isReadable }
        if (best < maxOf(MIN_VOTES, (seen * MIN_VOTE_SHARE).toInt())) return null
        val nearX = ArrayList<Float>()
        val nearY = ArrayList<Float>()
        for (i in votesX.indices) {
            if (abs((votesX[i] / bin).roundToInt() + half - bestX) <= 1 &&
                abs((votesY[i] / bin).roundToInt() + half - bestY) <= 1
            ) {
                nearX.add(votesX[i])
                nearY.add(votesY[i])
            }
        }
        nearX.sort()
        nearY.sort()
        return floatArrayOf(nearX[nearX.size / 2], nearY[nearY.size / 2])
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
         * Furthest the wall may move between scans and still be followed, in pitches.
         * Three a scan is thirty a second -- a flick past the whole wall, which blurs
         * the rings past reading at the preset's shutter anyway.
         */
        const val MAX_SHIFT = 3f

        /** Width of a vote bin, in pitches. */
        const val BIN = 0.2f

        /**
         * Votes the winning shift needs: at least this many, and at least this share of
         * the gems read this scan. Chance agreements on a four-pattern wall put a few
         * votes in any one neighbourhood of bins; the true shift has most of the wall.
         */
        const val MIN_VOTES = 4
        const val MIN_VOTE_SHARE = 0.25f

        /**
         * Once the wall's motion is known, how far a gem may sit from where it puts the
         * gem's track, in pitches: hand shake within one scan, and the detector's own
         * scatter.
         */
        const val PAIR_RADIUS = 0.3f

        /**
         * The gate when the readings could not say how the wall moved, around where the
         * last scan's motion predicts. Under half a pitch, so a gem is never taken for
         * its neighbour.
         */
        const val MAX_STEP = 0.4f

        /** Scans a gem may go undetected and keep its history. */
        const val MAX_MISSED = 1
    }
}
