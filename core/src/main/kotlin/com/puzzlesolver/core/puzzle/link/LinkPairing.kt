package com.puzzlesolver.core.puzzle.link

/**
 * Which panel goes with which.
 *
 * Every pattern on a Link wall is lit exactly twice -- the practice game draws each pair's
 * pattern from a set with no repeats -- so on a wall read right the pairs fall straight out:
 * group the panels by pattern and every group is two. That is also the scan's best check
 * on itself. A panel with no exact twin has a misread cell somewhere in it or in its twin,
 * and the board says so before anyone presses the first panel, which is the last moment
 * the wall can still be looked at.
 *
 * Panels that do not pair exactly are still paired, as well as they can be: the
 * assignment with the fewest differing cells in total, weighted by how sure each reading
 * was, so a cell the scan was unsure of costs less to disagree on. Found exactly by a
 * search over subsets while there are few enough of them, which a scan with a handful of
 * misreads always has. A wall with an odd panel out leaves one unpaired.
 */
class LinkPairing(
    /** For each panel, the index of the one it is paired with, or -1. */
    val partner: IntArray,
    /** For each panel, how many of its cells differ from its partner's; 0 is an exact twin. */
    val differences: IntArray,
) {
    /** Each pair once, lower index first. */
    val pairs: List<IntArray> = partner.indices.filter { partner[it] > it }.map { intArrayOf(it, partner[it]) }

    val exactPairs: Int get() = pairs.count { differences[it[0]] == 0 }

    val unpaired: List<Int> get() = partner.indices.filter { partner[it] < 0 }

    /** Panels paired with something that is not quite their twin. */
    val doubtful: List<Int> get() = partner.indices.filter { partner[it] >= 0 && differences[it] > 0 }

    /** Every panel has its exact twin. */
    val complete: Boolean get() = partner.isNotEmpty() && partner.all { it >= 0 } && differences.all { it == 0 }

    companion object {
        val EMPTY = LinkPairing(IntArray(0), IntArray(0))
    }
}

object LinkPairer {

    /**
     * Above this many panels without an exact twin, the leftovers are paired greedily
     * rather than searched: the search keeps a table of 2^n entries.
     */
    const val MAX_SEARCHED = 20

    /** What leaving a panel unpaired costs: more than any pairing can, so it happens only when the count is odd. */
    private const val UNPAIRED_COST = 100f

    fun pair(wall: LinkWall): LinkPairing =
        pair(wall.panels.map { it.cells }, wall.panels.map { it.confidence })

    fun pair(cells: List<IntArray>, confidence: List<FloatArray>? = null): LinkPairing {
        val n = cells.size
        val partner = IntArray(n) { -1 }
        if (n == 0) return LinkPairing(partner, IntArray(0))

        // Exact twins first. A pattern with an unknown cell in it is never an exact twin of
        // anything: the unknown cell could be what tells two patterns apart.
        val byPattern = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            if (cells[i].any { it == LinkColour.UNKNOWN }) continue
            byPattern.getOrPut(key(cells[i])) { ArrayList() }.add(i)
        }
        for (group in byPattern.values) {
            if (group.size == 2) {
                partner[group[0]] = group[1]
                partner[group[1]] = group[0]
            }
        }

        val left = (0 until n).filter { partner[it] < 0 }
        if (left.size >= 2) {
            val cost = Array(left.size) { a ->
                FloatArray(left.size) { b -> if (a == b) 0f else mismatch(left[a], left[b], cells, confidence) }
            }
            val matched = if (left.size <= MAX_SEARCHED) search(cost) else greedy(cost)
            for (a in matched.indices) {
                val b = matched[a]
                if (b >= 0) partner[left[a]] = left[b]
            }
        }

        val differences = IntArray(n) { i ->
            val j = partner[i]
            if (j < 0) 0 else (0 until LinkPanel.CELLS).count { k ->
                cells[i][k] != cells[j][k] || cells[i][k] == LinkColour.UNKNOWN
            }
        }
        return LinkPairing(partner, differences)
    }

    private fun key(cells: IntArray): String = String(CharArray(cells.size) { LinkColour.letter(cells[it]) })

    /**
     * How badly two panels disagree. A differing cell costs a quarter on its own and the
     * rest by how sure both readings of it were; an unknown cell costs half, since it is
     * as likely to agree as not.
     */
    private fun mismatch(i: Int, j: Int, cells: List<IntArray>, confidence: List<FloatArray>?): Float {
        var cost = 0f
        for (k in 0 until LinkPanel.CELLS) {
            val a = cells[i][k]
            val b = cells[j][k]
            if (a == LinkColour.UNKNOWN || b == LinkColour.UNKNOWN) {
                cost += 0.5f
            } else if (a != b) {
                val sure = if (confidence == null) 1f else minOf(confidence[i][k], confidence[j][k]).coerceIn(0f, 1f)
                cost += 0.25f + 0.75f * sure
            }
        }
        return cost
    }

    /**
     * The cheapest matching over [cost], exactly: always pair the lowest unmatched panel,
     * with each of the others in turn or with nothing, remembering the best finish from
     * every set of panels already matched.
     */
    private fun search(cost: Array<FloatArray>): IntArray {
        val n = cost.size
        val full = (1 shl n) - 1
        val best = FloatArray(1 shl n) { Float.NaN }
        val choice = IntArray(1 shl n) { -2 }

        fun solve(mask: Int): Float {
            if (mask == full) return 0f
            if (!best[mask].isNaN()) return best[mask]
            val i = Integer.numberOfTrailingZeros(mask.inv())
            var bestCost = UNPAIRED_COST + solve(mask or (1 shl i))
            var bestJ = -1
            for (j in i + 1 until n) {
                if (mask and (1 shl j) != 0) continue
                val c = cost[i][j] + solve(mask or (1 shl i) or (1 shl j))
                if (c < bestCost) {
                    bestCost = c
                    bestJ = j
                }
            }
            best[mask] = bestCost
            choice[mask] = bestJ
            return bestCost
        }
        solve(0)

        val out = IntArray(n) { -1 }
        var mask = 0
        while (mask != full) {
            val i = Integer.numberOfTrailingZeros(mask.inv())
            val j = choice[mask]
            mask = mask or (1 shl i)
            if (j >= 0) {
                out[i] = j
                out[j] = i
                mask = mask or (1 shl j)
            }
        }
        return out
    }

    /** Cheapest pairs first, for a scan too badly read to search. */
    private fun greedy(cost: Array<FloatArray>): IntArray {
        val n = cost.size
        val edges = ArrayList<IntArray>()
        for (i in 0 until n) for (j in i + 1 until n) edges.add(intArrayOf(i, j))
        edges.sortBy { cost[it[0]][it[1]] }
        val out = IntArray(n) { -1 }
        for (e in edges) {
            if (out[e[0]] < 0 && out[e[1]] < 0) {
                out[e[0]] = e[1]
                out[e[1]] = e[0]
            }
        }
        return out
    }
}
