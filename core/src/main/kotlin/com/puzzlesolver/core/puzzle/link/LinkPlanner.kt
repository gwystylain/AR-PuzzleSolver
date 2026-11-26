package com.puzzlesolver.core.puzzle.link

import kotlin.math.hypot

/**
 * The order to match the pairs in, and which of each pair to press first, so that the
 * whole round is the shortest walk along the wall.
 *
 * The rules are the practice game's (activate.ryflix.ca/link.html, read 2026-11-26): press
 * any panel, then its twin, and the pair is gone; any pair may go next. A wrong second
 * press costs a life and nothing else, and the game does not care what order the pairs
 * come in -- only the clock does. So the round is a walk visiting every panel, each pair's
 * two panels back to back, and the only thing to choose is the order of the pairs and the
 * end each is started from.
 *
 * The walk inside a pair is the same however the pairs are ordered, so what is being
 * minimised is the walk *between* pairs: from where one pair ends to where the next
 * starts. That is a travelling-salesman path over the pairs with two ways round each, and
 * up to [MAX_EXACT_PAIRS] it is solved exactly by dynamic programming over subsets
 * (Held-Karp): 2^16 sets times 32 places to stand is two million states, a fraction of a
 * second. Beyond that, which no level has, the best of a nearest-neighbour walk improved
 * by moving one pair at a time.
 *
 * Distances are straight lines between panel middles, in buttons. Most of the walk along
 * a 64-button wall is sideways, and reaching from one band to the other is a short step
 * up or down, which a straight line prices about right.
 */
object LinkPlanner {

    const val MAX_EXACT_PAIRS = 16

    /** Where the walk starts. */
    enum class Start(val label: String) {
        /** Wherever makes the walk shortest. */
        ANYWHERE("Anywhere"),

        /** At the left end of the wall: where a scan panned left to right begins. */
        LEFT("Left end"),

        /** At the right end: where a scan panned left to right finishes. */
        RIGHT("Right end"),
    }

    class Step(
        /** The panel to press first. */
        val first: Int,
        /** Its twin, pressed straight after. */
        val second: Int,
    )

    class Path(
        val steps: List<Step>,
        /** The whole walk, start included, in buttons. */
        val length: Float,
        /** The part of it spent between pairs, which is all the order changes. */
        val between: Float,
        /** Whether it is the shortest there is, rather than the best a search found. */
        val optimal: Boolean,
        val start: Start,
    ) {
        /** The press order as panel indices, 2 per step. */
        val presses: List<Int> get() = steps.flatMap { listOf(it.first, it.second) }

        companion object {
            val EMPTY = Path(emptyList(), 0f, 0f, true, Start.ANYWHERE)
        }
    }

    /** Plans [wall]'s pairs as [pairing] has them. Panels left unpaired are left out. */
    fun plan(wall: LinkWall, pairing: LinkPairing, start: Start = Start.ANYWHERE): Path {
        val xs = FloatArray(wall.size) { wall.panels[it].centreX }
        val ys = FloatArray(wall.size) { wall.panels[it].centreY }
        return plan(xs, ys, pairing.pairs, start)
    }

    fun plan(xs: FloatArray, ys: FloatArray, pairs: List<IntArray>, start: Start = Start.ANYWHERE): Path {
        val n = pairs.size
        if (n == 0) return Path(emptyList(), 0f, 0f, true, start)
        val startPoint: FloatArray? = when (start) {
            Start.ANYWHERE -> null
            Start.LEFT -> floatArrayOf(xs.min(), (ys.min() + ys.max()) / 2f)
            Start.RIGHT -> floatArrayOf(xs.max(), (ys.min() + ys.max()) / 2f)
        }
        fun d(i: Int, j: Int) = hypot(xs[i] - xs[j], ys[i] - ys[j])
        fun fromStart(i: Int) = if (startPoint == null) 0f else hypot(xs[i] - startPoint[0], ys[i] - startPoint[1])

        // End e = 2p + s: pair p taken from its first panel (s = 0) or its second (s = 1).
        val m = 2 * n
        val firstOf = IntArray(m) { e -> pairs[e / 2][e % 2] }
        val lastOf = IntArray(m) { e -> pairs[e / 2][1 - e % 2] }
        val inside = pairs.sumOf { d(it[0], it[1]).toDouble() }.toFloat()

        val order: IntArray
        val optimal: Boolean
        if (n <= MAX_EXACT_PAIRS) {
            order = exact(n, m, firstOf, lastOf, ::d, ::fromStart)
            optimal = true
        } else {
            order = heuristic(n, firstOf, lastOf, ::d, ::fromStart)
            optimal = false
        }
        var between = fromStart(firstOf[order[0]])
        for (k in 1 until order.size) between += d(lastOf[order[k - 1]], firstOf[order[k]])
        return Path(
            steps = order.map { Step(firstOf[it], lastOf[it]) },
            length = between + inside,
            between = between,
            optimal = optimal,
            start = start,
        )
    }

    /** Held-Karp over pairs, each with two ways round. Returns the ends in walking order. */
    private fun exact(
        n: Int,
        m: Int,
        firstOf: IntArray,
        lastOf: IntArray,
        d: (Int, Int) -> Float,
        fromStart: (Int) -> Float,
    ): IntArray {
        val hop = FloatArray(m * m)
        for (e in 0 until m) for (f in 0 until m) hop[e * m + f] = d(lastOf[e], firstOf[f])

        val sets = 1 shl n
        val cost = FloatArray(sets * m) { Float.POSITIVE_INFINITY }
        val from = ByteArray(sets * m) { -1 }
        for (e in 0 until m) cost[(1 shl (e / 2)) * m + e] = fromStart(firstOf[e])

        for (mask in 1 until sets) {
            val base = mask * m
            for (e in 0 until m) {
                if (mask and (1 shl (e / 2)) == 0) continue
                val here = cost[base + e]
                if (here == Float.POSITIVE_INFINITY) continue
                val row = e * m
                for (q in 0 until n) {
                    if (mask and (1 shl q) != 0) continue
                    val next = (mask or (1 shl q)) * m
                    for (s in 0..1) {
                        val f = 2 * q + s
                        val c = here + hop[row + f]
                        if (c < cost[next + f]) {
                            cost[next + f] = c
                            from[next + f] = e.toByte()
                        }
                    }
                }
            }
        }

        val full = sets - 1
        var end = 0
        for (e in 1 until m) if (cost[full * m + e] < cost[full * m + end]) end = e
        val order = IntArray(n)
        var mask = full
        for (k in n - 1 downTo 0) {
            order[k] = end
            val prev = from[mask * m + end].toInt()
            mask = mask and (1 shl (end / 2)).inv()
            end = prev
        }
        return order
    }

    /** Nearest-neighbour from every starting end, then moves of one pair until none helps. */
    private fun heuristic(
        n: Int,
        firstOf: IntArray,
        lastOf: IntArray,
        d: (Int, Int) -> Float,
        fromStart: (Int) -> Float,
    ): IntArray {
        fun length(order: IntArray): Float {
            var t = fromStart(firstOf[order[0]])
            for (k in 1 until order.size) t += d(lastOf[order[k - 1]], firstOf[order[k]])
            return t
        }

        var best: IntArray? = null
        var bestLength = Float.POSITIVE_INFINITY
        for (e0 in 0 until 2 * n) {
            val used = BooleanArray(n)
            val order = IntArray(n)
            order[0] = e0
            used[e0 / 2] = true
            for (k in 1 until n) {
                var pick = -1
                var pickD = Float.POSITIVE_INFINITY
                for (f in 0 until 2 * n) {
                    if (used[f / 2]) continue
                    val c = d(lastOf[order[k - 1]], firstOf[f])
                    if (c < pickD) {
                        pickD = c
                        pick = f
                    }
                }
                order[k] = pick
                used[pick / 2] = true
            }
            val l = length(order)
            if (l < bestLength) {
                bestLength = l
                best = order
            }
        }

        var order = best!!.toMutableList()
        var improved = true
        var rounds = 0
        while (improved && rounds++ < 200) {
            improved = false
            outer@ for (i in 0 until n) {
                val moved = order[i]
                val rest = order.toMutableList().also { it.removeAt(i) }
                for (j in 0..rest.size) {
                    for (flip in 0..1) {
                        val e = if (flip == 0) moved else moved xor 1
                        val candidate = rest.toMutableList().also { it.add(j, e) }.toIntArray()
                        val l = length(candidate)
                        if (l < bestLength - 1e-4f) {
                            bestLength = l
                            order = candidate.toMutableList()
                            improved = true
                            continue@outer
                        }
                    }
                }
            }
        }
        return order.toIntArray()
    }
}
