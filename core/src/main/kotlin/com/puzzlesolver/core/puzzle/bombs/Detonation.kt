package com.puzzlesolver.core.puzzle.bombs

/**
 * Fires every mine on a board and reports exactly what got hit.
 *
 * This is a tick-by-tick simulation rather than eight ray-casts per mine, and it has
 * to be. Rays interact: two mines on a common line send rays toward each other, the
 * rays meet in open space and vaporise, and neither reaches the far side. That is a
 * property of *when* rays arrive, not of what is standing in the way, so there is no
 * static line-of-sight table that captures it.
 *
 * One ordering decision inside the tick is load-bearing: damage resolves *before*
 * annihilation. Two opposing rays converging on the same white button both land on
 * it and both deal damage -- they never get the chance to meet. If it were the other
 * way round, a blue button flanked by two mines could never be cleared in a single
 * detonation, which would be wrong.
 *
 * Not thread-safe; the planner holds one per search thread and reuses it.
 */
class Detonation(private val maxRays: Int = 1024) {

    /**
     * @param damage hits landed per cell, row-major.
     * @param rayPaths cells traversed by each ray, in order, starting at its mine.
     *        Used by the overlay to show why a plan works, and by the tests.
     * @param annihilated how many rays died meeting an opposing ray rather than
     *        landing on something. A high count means the mines are shadowing each
     *        other and the plan is wasting explosions.
     */
    class Result(
        @JvmField val damage: IntArray,
        @JvmField val rayPaths: List<IntArray>,
        @JvmField val annihilated: Int,
        @JvmField val landed: Int,
        @JvmField val flewOffBoard: Int,
        /**
         * Rays that landed on a red hazard. Any number above zero means the plan
         * loses the game, so this is a veto rather than a cost -- no amount of
         * damage elsewhere makes up for it.
         */
        @JvmField val hazardHits: Int,
    ) {
        val losesGame: Boolean get() = hazardHits > 0

        fun damageAt(index: Int): Int = damage[index]

        val totalDamage: Int get() = damage.sum()
    }

    // Reused across calls so a search that fires thousands of plans does not allocate.
    private var cur = IntArray(maxRays)
    private var nxt = IntArray(maxRays)
    private var dir = IntArray(maxRays)
    private var alive = BooleanArray(maxRays)
    private var nextEnter = IntArray(maxRays)
    private var nextOccupy = IntArray(maxRays)
    private var headEnter = IntArray(0)
    private var headOccupy = IntArray(0)
    private var touched = IntArray(0)

    private fun ensure(cellCount: Int) {
        if (headEnter.size < cellCount) {
            headEnter = IntArray(cellCount) { -1 }
            headOccupy = IntArray(cellCount) { -1 }
            touched = IntArray(cellCount * 2)
        }
    }

    /** Fires every mine currently on [board]. */
    fun fire(board: BombBoard, tracePaths: Boolean = false): Result =
        fire(board, board.placedMines(), tracePaths)

    /**
     * Fires [mines] on [board]. Mines already on the board are included automatically,
     * because pressing green fires everything that is red, not only what we just placed.
     */
    fun fire(board: BombBoard, mines: IntArray, tracePaths: Boolean = false): Result {
        val all = mergeMines(board, mines)
        val cells = board.cellCount
        ensure(cells)

        val rayCount = all.size * Dir.COUNT
        if (rayCount > cur.size) grow(rayCount)

        val damage = IntArray(cells)
        val paths = if (tracePaths) ArrayList<IntArray>(rayCount) else null
        val pathBuf = if (tracePaths) Array(rayCount) { ArrayList<Int>(8) } else null

        var r = 0
        for (m in all) {
            for (d in 0 until Dir.COUNT) {
                cur[r] = m
                dir[r] = d
                alive[r] = true
                pathBuf?.get(r)?.add(m)
                r++
            }
        }

        val rules = board.rules
        var annihilated = 0
        var landed = 0
        var offBoard = 0
        var hazardHits = 0
        val maxTicks = board.cols + board.rows

        var tick = 0
        while (tick < maxTicks) {
            tick++
            var anyAlive = false

            // 1. Where would each live ray go next? Off-board rays stop here.
            for (i in 0 until rayCount) {
                if (!alive[i]) continue
                val c = board.colOf(cur[i]) + Dir.DX[dir[i]]
                val row = board.rowOf(cur[i]) + Dir.DY[dir[i]]
                if (!board.inBounds(c, row)) {
                    alive[i] = false
                    offBoard++
                    continue
                }
                nxt[i] = board.index(c, row)
                anyAlive = true
            }
            if (!anyAlive) break

            // 2. Rays arriving at a blocker land on it and stop. Several rays can land
            //    on the same button in one tick, and each one counts -- that is how a
            //    blue button gets cleared without a second round.
            for (i in 0 until rayCount) {
                if (!alive[i]) continue
                val target = nxt[i]
                if (!board.blocksRays(target)) continue
                if (board.isLiveTarget(target)) {
                    damage[target]++
                    landed++
                } else if (board.isHazard(target)) {
                    hazardHits++
                }
                alive[i] = false
                pathBuf?.get(i)?.add(target)
            }

            // 3. What is left is in open space, so opposing rays can now meet.
            if (rules.opposingRaysAnnihilate) {
                annihilated += resolveCollisions(rayCount, rules.crossingRaysAnnihilate)
            }

            // 4. Survivors move.
            for (i in 0 until rayCount) {
                if (!alive[i]) continue
                cur[i] = nxt[i]
                pathBuf?.get(i)?.add(nxt[i])
            }
        }

        if (pathBuf != null) for (p in pathBuf) paths!!.add(p.toIntArray())
        return Result(damage, paths ?: emptyList(), annihilated, landed, offBoard, hazardHits)
    }

    /**
     * Kills rays that meet head-on, in both parities of the gap between them.
     *
     * An even gap lands the pair on the same cell; an odd gap has them trade cells,
     * which is the same collision happening halfway between. Missing the second case
     * would let rays tunnel through each other whenever two mines sat an odd distance
     * apart.
     *
     * **The traded-cell pass has to run first**, and that ordering is not a tiebreak
     * -- it is when the collisions happen. A trade is two rays crossing midway
     * between cells, at t - 1/2; a shared cell is two rays arriving at t. Half a tick
     * earlier wins.
     *
     * It matters whenever three mines sit adjacent on one line. Rays from the outer
     * two both enter the middle cell on the same tick and look like a head-on pair,
     * but each has already met a ray coming out of that middle mine half a tick
     * before. Pairing the outer two instead leaves the middle mine firing freely in
     * both directions, and the solver would count on damage the room never deals.
     */
    private fun resolveCollisions(rayCount: Int, crossingToo: Boolean): Int {
        var touchedCount = 0

        // Index live rays by the cell they are entering and the cell they are leaving.
        for (i in 0 until rayCount) {
            if (!alive[i]) continue
            val e = nxt[i]
            if (headEnter[e] == -1) touched[touchedCount++] = e
            nextEnter[i] = headEnter[e]
            headEnter[e] = i
            val o = cur[i]
            if (headOccupy[o] == -1) touched[touchedCount++] = o
            nextOccupy[i] = headOccupy[o]
            headOccupy[o] = i
        }

        var killed = 0

        // Traded cells, at t - 1/2: ray i moves a -> b while ray j moves b -> a.
        for (i in 0 until rayCount) {
            if (!alive[i]) continue
            var j = headOccupy[nxt[i]]
            while (j != -1) {
                if (alive[j] && j != i && nxt[j] == cur[i] && collides(dir[i], dir[j], crossingToo)) {
                    alive[i] = false
                    alive[j] = false
                    killed += 2
                    break
                }
                j = nextOccupy[j]
            }
        }

        // Shared cell, at t: only for rays that survived the crossing above.
        for (t in 0 until touchedCount) {
            var i = headEnter[touched[t]]
            while (i != -1) {
                if (alive[i]) {
                    var j = nextEnter[i]
                    while (j != -1) {
                        if (alive[j] && collides(dir[i], dir[j], crossingToo)) {
                            alive[i] = false
                            alive[j] = false
                            killed += 2
                            break
                        }
                        j = nextEnter[j]
                    }
                }
                i = nextEnter[i]
            }
        }

        for (t in 0 until touchedCount) {
            headEnter[touched[t]] = -1
            headOccupy[touched[t]] = -1
        }
        return killed
    }

    private fun collides(a: Int, b: Int, crossingToo: Boolean): Boolean =
        if (crossingToo) a != b else b == Dir.opposite(a)

    private fun mergeMines(board: BombBoard, mines: IntArray): IntArray {
        val onBoard = board.placedMines()
        if (mines.isEmpty()) return onBoard
        if (onBoard.isEmpty()) return mines
        val set = LinkedHashSet<Int>(onBoard.size + mines.size)
        for (m in onBoard) set.add(m)
        for (m in mines) set.add(m)
        return set.toIntArray()
    }

    private fun grow(n: Int) {
        cur = IntArray(n)
        nxt = IntArray(n)
        dir = IntArray(n)
        alive = BooleanArray(n)
        nextEnter = IntArray(n)
        nextOccupy = IntArray(n)
    }
}
