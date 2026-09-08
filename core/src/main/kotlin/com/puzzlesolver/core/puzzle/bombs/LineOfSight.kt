package com.puzzlesolver.core.puzzle.bombs

/**
 * What each cell can reach, if nothing else were firing.
 *
 * This is deliberately an *optimistic* model: it answers "which button does a ray
 * from here run into", and knows nothing about rays cancelling each other. That
 * makes it wrong as a predictor and exactly right as a search guide, because
 * annihilation can only ever remove hits. So the real damage of a placement is
 * always a subset of what this table promises, which means:
 *
 *   every placement that really works also passes this filter.
 *
 * The search can therefore enumerate against this cheap table and hand each survivor
 * to [Detonation] for the truth, without any risk of skipping a valid answer.
 *
 * Built with a reverse sweep per direction, so the whole table costs one pass over
 * the board per direction rather than a ray-cast per cell.
 */
class LineOfSight(private val board: BombBoard) {

    private val cells = board.cellCount

    /** For each cell and direction, the first blocking cell along it, or -1. */
    private val firstBlocker = IntArray(cells * Dir.COUNT)

    /** Live targets reachable from each cell, at most one per direction. */
    private val reach: Array<IntArray>

    /** Placeable cells that can reach each target. */
    private val candidates: Array<IntArray>

    init {
        for (d in 0 until Dir.COUNT) sweep(d)

        val tmp = IntArray(Dir.COUNT)
        reach = Array(cells) { p ->
            if (!board.isPlaceable(p)) {
                EMPTY
            } else {
                var n = 0
                for (d in 0 until Dir.COUNT) {
                    val q = firstBlocker[p * Dir.COUNT + d]
                    if (q >= 0 && board.isLiveTarget(q)) tmp[n++] = q
                }
                if (n == 0) EMPTY else tmp.copyOf(n)
            }
        }

        val counts = IntArray(cells)
        for (p in 0 until cells) for (t in reach[p]) counts[t]++
        candidates = Array(cells) { if (counts[it] == 0) EMPTY else IntArray(counts[it]) }
        val fill = IntArray(cells)
        for (p in 0 until cells) for (t in reach[p]) candidates[t][fill[t]++] = p
    }

    /**
     * Walks each line backwards so that the answer for the next cell along is already
     * known when we get here. A cell either runs straight into a blocker one step
     * away, or inherits whatever that neighbour runs into.
     */
    private fun sweep(d: Int) {
        val dx = Dir.DX[d]
        val dy = Dir.DY[d]
        val colRange = if (dx > 0) (board.cols - 1) downTo 0 else 0 until board.cols
        val rowRange = if (dy > 0) (board.rows - 1) downTo 0 else 0 until board.rows
        for (row in rowRange) {
            for (col in colRange) {
                val p = board.index(col, row)
                val nc = col + dx
                val nr = row + dy
                firstBlocker[p * Dir.COUNT + d] = when {
                    !board.inBounds(nc, nr) -> -1
                    else -> {
                        val q = board.index(nc, nr)
                        if (board.blocksRays(q)) q else firstBlocker[q * Dir.COUNT + d]
                    }
                }
            }
        }
    }

    /** Live targets a mine at [cell] would reach with nothing else on the board. */
    fun reachFrom(cell: Int): IntArray = reach[cell]

    /**
     * The first button a ray from [cell] heading [dir] runs into, or -1 if it leaves
     * the board. Not necessarily a target: unrecognised colours block without ever
     * being clearable.
     */
    fun blockerFrom(cell: Int, dir: Int): Int = firstBlocker[cell * Dir.COUNT + dir]

    /** Placeable cells from which [target] is the first button along some direction. */
    fun candidatesFor(target: Int): IntArray = candidates[target]

    /**
     * A mine can hit a given button at most once, because the eight rays leave along
     * eight different lines and only the mine itself lies on more than one of them.
     * That is why a blue button always needs two distinct mines, and the search can
     * treat coverage as a plain count.
     */
    fun maxHitsFromOneMine(): Int = 1

    private companion object {
        val EMPTY = IntArray(0)
    }
}
