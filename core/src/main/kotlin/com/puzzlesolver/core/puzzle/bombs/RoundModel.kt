package com.puzzlesolver.core.puzzle.bombs

/**
 * Exact damage for a set of mines, maintained incrementally as mines go down and
 * come back up.
 *
 * The point of this class is that annihilation has a closed form. A mine's ray in
 * direction `d` is lost **exactly when** another mine sits along `d` with no button
 * blocking the way between them -- and when that happens the neighbour loses its
 * ray back along `-d` as well. Nothing else in the tick-by-tick behaviour affects
 * the outcome:
 *
 *  - Only opposing rays cancel, and opposing rays travel the same line, so the
 *    partner in any cancellation is always collinear.
 *  - Along one line the mines cancel in consecutive pairs. Each mine looks only at
 *    its *nearest* neighbour in a direction, so three in a row leave the middle one
 *    with nothing and the outer two with one outward ray each, which is what the
 *    room does.
 *  - A button standing between two mines stops both rays before they can meet, so
 *    it takes two hits and no cancellation happens. That is the "no blocker in
 *    between" clause.
 *
 * So the search never has to run the tick loop. [Detonation] stays the definition of
 * the mechanic and this stays the fast path, and `RoundModelAgreementTest` fires
 * random boards through both to keep them honest about each other.
 */
class RoundModel(
    private val board: BombBoard,
    val los: LineOfSight,
) {
    private val cells = board.cellCount

    /** Hits landed on each cell by the current mine set, annihilation included. */
    @JvmField
    val hits = IntArray(cells)

    /** FREE / CHOSEN / BANNED, indexed by cell. */
    private val state = ByteArray(cells)

    /** Per chosen mine and direction: is that ray still flying? */
    private val rayLive = BooleanArray(cells * Dir.COUNT)

    private val chosen = IntArray(cells)
    var chosenCount = 0
        private set

    /** Remaining hit points not yet accounted for, summed over live targets. */
    var shortfall = 0
        private set

    /**
     * Rays currently landing on a red hazard. Tracked as a running count rather than
     * a flag because it has to survive backtracking, and because a placement that
     * lights up a hazard is not necessarily dead: a later mine on the same line can
     * cancel the offending ray and make the pair legal together. So the search treats
     * this as something to be zero at a solution, not as a reason to refuse a branch.
     */
    var hazardHits = 0
        private set

    private val targets = board.liveTargets()

    init {
        for (t in targets) shortfall += board.hp[t]
    }

    fun isFree(cell: Int): Boolean = state[cell].toInt() == FREE && board.isPlaceable(cell)

    fun isChosen(cell: Int): Boolean = state[cell].toInt() == CHOSEN

    fun isBanned(cell: Int): Boolean = state[cell].toInt() == BANNED

    fun ban(cell: Int) {
        state[cell] = BANNED.toByte()
    }

    fun unban(cell: Int) {
        state[cell] = FREE.toByte()
    }

    fun mines(): IntArray = chosen.copyOf(chosenCount)

    /** Hits still owed on [target] before it clears. */
    fun needOf(target: Int): Int {
        val n = board.hp[target] - hits[target]
        return if (n > 0) n else 0
    }

    /** Every button down and no hazard lit: the only state that actually wins. */
    val isCleared: Boolean get() = shortfall == 0 && hazardHits == 0

    /** True when some ray is currently landing on a hazard. */
    val losesGame: Boolean get() = hazardHits > 0

    /**
     * Places a mine and returns an undo token.
     *
     * The token records which neighbours had a ray killed by this placement, because
     * that is not recoverable by inspection afterwards: a neighbour whose ray was
     * already dead when we arrived must not have it handed back on undo.
     */
    fun choose(cell: Int): Long {
        var token = 0L
        state[cell] = CHOSEN.toByte()
        chosen[chosenCount++] = cell
        for (d in 0 until Dir.COUNT) {
            val neighbour = nearestMine(cell, d)
            if (neighbour < 0) {
                rayLive[cell * Dir.COUNT + d] = true
                addHit(los.blockerFrom(cell, d), +1)
            } else {
                rayLive[cell * Dir.COUNT + d] = false
                val back = neighbour * Dir.COUNT + Dir.opposite(d)
                if (rayLive[back]) {
                    rayLive[back] = false
                    addHit(los.blockerFrom(neighbour, Dir.opposite(d)), -1)
                    token = token or (1L shl d)
                }
            }
        }
        return token
    }

    /** Undoes the matching [choose]. Placements must be undone in reverse order. */
    fun unchoose(cell: Int, token: Long) {
        for (d in 0 until Dir.COUNT) {
            if (rayLive[cell * Dir.COUNT + d]) {
                rayLive[cell * Dir.COUNT + d] = false
                addHit(los.blockerFrom(cell, d), -1)
            } else if ((token shr d) and 1L == 1L) {
                val neighbour = nearestMine(cell, d)
                val back = neighbour * Dir.COUNT + Dir.opposite(d)
                rayLive[back] = true
                addHit(los.blockerFrom(neighbour, Dir.opposite(d)), +1)
            }
        }
        state[cell] = FREE.toByte()
        chosenCount--
    }

    private fun addHit(target: Int, delta: Int) {
        if (target < 0) return
        if (board.isHazard(target)) {
            hazardHits += delta
            return
        }
        if (!board.isLiveTarget(target)) return
        val before = hits[target]
        val after = before + delta
        hits[target] = after
        val hp = board.hp[target]
        // Hits past the hit points of a button are wasted, so they must not count
        // toward progress -- otherwise the search happily over-kills one white
        // button and calls it two points of work.
        val owedBefore = if (hp > before) hp - before else 0
        val owedAfter = if (hp > after) hp - after else 0
        shortfall += owedAfter - owedBefore
    }

    /**
     * The nearest mine along [d] with clear air between, or -1.
     *
     * Stopping at the first blocking button is the whole subtlety: a white button
     * between two mines absorbs both rays, so those mines never cancel and the
     * button takes two hits.
     */
    private fun nearestMine(cell: Int, d: Int): Int {
        val stop = los.blockerFrom(cell, d)
        var col = board.colOf(cell) + Dir.DX[d]
        var row = board.rowOf(cell) + Dir.DY[d]
        while (board.inBounds(col, row)) {
            val p = board.index(col, row)
            if (p == stop) return -1
            if (state[p].toInt() == CHOSEN) return p
            col += Dir.DX[d]
            row += Dir.DY[d]
        }
        return -1
    }

    /** Live targets still owed hits, cheapest-to-satisfy first is the caller's job. */
    fun unsatisfiedTargets(): IntArray {
        var n = 0
        for (t in targets) if (needOf(t) > 0) n++
        val out = IntArray(n)
        var w = 0
        for (t in targets) if (needOf(t) > 0) out[w++] = t
        return out
    }

    fun allTargets(): IntArray = targets

    private companion object {
        const val FREE = 0
        const val CHOSEN = 1
        const val BANNED = 2
    }
}
