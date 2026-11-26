package com.puzzlesolver.core.puzzle.link

import kotlin.math.hypot

/**
 * Names every button on a scanned wall at once, by where this visit's camera put the five
 * colours.
 *
 * The preset locks the camera's white balance wherever it had settled, and that moves
 * between visits and even between sessions of one visit: on the gem wall green has read
 * anywhere from 131 to 178 degrees, and one session put yellow at 167-172, on top of green
 * (docs/GEM_PUZZLE.md). Fixed hue centroids lose the room the day the tint lands on a
 * boundary. Here the whole wall is in hand before anything is named -- some 288 buttons in
 * five colours -- so the five colours are found as five groups on the chroma plane
 * (k-means, started from [LinkColour.HUES]), each allowed to move up to [MAX_DRIFT]
 * degrees from where it started.
 *
 * Then the wall checks itself. Every pattern is lit exactly twice, so of the two namings --
 * each button's own vote with the fixed centroids, and the groups -- the one under which
 * more panels find their exact twin is the one kept. A naming that breaks pairs is wrong
 * somewhere whatever the colour science says.
 */
object LinkPalette {

    /** How far a colour's group may move from its starting hue, in degrees. */
    const val MAX_DRIFT = 45f

    private const val ITERATIONS = 12

    class Named(
        val cells: List<IntArray>,
        val confidence: List<FloatArray>,
        /** Whether the groups were kept over the buttons' own votes. */
        val adapted: Boolean,
        /** Where the groups settled, for the scan log and the board's note. */
        val note: String,
    )

    /**
     * @param votes each panel's nine colours by vote
     * @param voteConfidence how clear each vote was
     * @param features one per button, panel after panel: mean red, green, blue over the
     *        brightest channel, and how many looks went into it (0 for a button never seen lit)
     */
    fun name(votes: List<IntArray>, voteConfidence: List<FloatArray>, features: List<FloatArray>): Named {
        val n = features.size
        val px = FloatArray(n)
        val py = FloatArray(n)
        val w = FloatArray(n)
        for (i in 0 until n) {
            val f = features[i]
            if (f[3] <= 0f) continue
            val h = LinkColour.hueOf(f[0], f[1], f[2])
            val s = LinkColour.saturationOf(f[0], f[1], f[2])
            px[i] = LinkColour.chromaX(h, s)
            py[i] = LinkColour.chromaY(h, s)
            w[i] = f[3]
        }

        val seedX = FloatArray(LinkColour.COUNT) { LinkColour.chromaX(LinkColour.HUES[it], LinkColour.SATURATIONS[it]) }
        val seedY = FloatArray(LinkColour.COUNT) { LinkColour.chromaY(LinkColour.HUES[it], LinkColour.SATURATIONS[it]) }
        val cx = seedX.copyOf()
        val cy = seedY.copyOf()
        val assign = IntArray(n) { LinkColour.UNKNOWN }
        repeat(ITERATIONS) {
            for (i in 0 until n) assign[i] = if (w[i] > 0f) nearest(px[i], py[i], cx, cy) else LinkColour.UNKNOWN
            for (c in 0 until LinkColour.COUNT) {
                var sx = 0f
                var sy = 0f
                var sw = 0f
                for (i in 0 until n) if (assign[i] == c) {
                    sx += px[i] * w[i]
                    sy += py[i] * w[i]
                    sw += w[i]
                }
                if (sw <= 0f) {
                    cx[c] = seedX[c]
                    cy[c] = seedY[c]
                    continue
                }
                val nx = sx / sw
                val ny = sy / sw
                val hue = hueOfPoint(nx, ny)
                if (LinkColour.hueDistance(hue, LinkColour.HUES[c]) > MAX_DRIFT) {
                    cx[c] = seedX[c]
                    cy[c] = seedY[c]
                } else {
                    cx[c] = nx
                    cy[c] = ny
                }
            }
        }
        for (i in 0 until n) assign[i] = if (w[i] > 0f) nearest(px[i], py[i], cx, cy) else LinkColour.UNKNOWN

        val panels = votes.size
        val grouped = List(panels) { p -> IntArray(LinkPanel.CELLS) { assign[p * LinkPanel.CELLS + it] } }
        val groupedConfidence = List(panels) { p ->
            FloatArray(LinkPanel.CELLS) { k ->
                val i = p * LinkPanel.CELLS + k
                if (w[i] <= 0f) 0f else margin(px[i], py[i], cx, cy)
            }
        }

        val note = (0 until LinkColour.COUNT).joinToString(", ") { c ->
            "${LinkColour.name(c)} %.0f".format(hueOfPoint(cx[c], cy[c]))
        }

        // And once more with no starting places at all, for a tint bad enough to put one
        // colour nearer another's starting place than its own: five groups wherever the
        // buttons fall, then named by the one assignment of names to groups that sits
        // nearest the starting hues and saturations.
        val free = freeGroups(px, py, w)
        val freeCells = free?.let { f -> List(panels) { p -> IntArray(LinkPanel.CELLS) { f.assign[p * LinkPanel.CELLS + it] } } }

        // Most twins wins. On a tie, the naming that tells more colours apart: two colours
        // read as one keep every pair of a random wall intact -- a misnaming the same way
        // on both twins -- and only show on the board, which the user checks against the
        // wall. Then the buttons' own votes, then the seeded groups.
        val byVote = score(votes)
        val byGroup = score(grouped)
        val byFree = freeCells?.let { score(it) } ?: -1
        return when {
            byFree > byGroup && byFree > byVote -> Named(
                freeCells!!,
                List(panels) { p ->
                    FloatArray(LinkPanel.CELLS) { k ->
                        val i = p * LinkPanel.CELLS + k
                        if (w[i] <= 0f) 0f else margin(px[i], py[i], free!!.cx, free.cy)
                    }
                },
                true,
                (0 until LinkColour.COUNT).joinToString(", ") { c ->
                    "${LinkColour.name(c)} %.0f".format(hueOfPoint(free!!.cx[c], free.cy[c]))
                },
            )
            byGroup > byVote -> Named(grouped, groupedConfidence, true, note)
            else -> Named(votes, voteConfidence, false, note)
        }
    }

    private class Groups(val cx: FloatArray, val cy: FloatArray, val assign: IntArray)

    /**
     * Five groups found from nothing: the most saturated button first, then each time the
     * button farthest from every group so far, then k-means. Named by trying all 120 ways
     * of giving the five names to the five groups. Null when fewer than five distinct
     * colours were read.
     */
    private fun freeGroups(px: FloatArray, py: FloatArray, w: FloatArray): Groups? {
        val live = px.indices.filter { w[it] > 0f }
        if (live.size < LinkColour.COUNT) return null
        val k = LinkColour.COUNT
        val gx = FloatArray(k)
        val gy = FloatArray(k)
        val first = live.maxByOrNull { hypot(px[it], py[it]) }!!
        gx[0] = px[first]
        gy[0] = py[first]
        for (c in 1 until k) {
            val far = live.maxByOrNull { i -> (0 until c).minOf { hypot(px[i] - gx[it], py[i] - gy[it]) } }!!
            if ((0 until c).minOf { hypot(px[far] - gx[it], py[far] - gy[it]) } < MIN_GROUP_SPACING) return null
            gx[c] = px[far]
            gy[c] = py[far]
        }
        val assign = IntArray(px.size) { -1 }
        repeat(ITERATIONS) {
            for (i in live) assign[i] = nearest(px[i], py[i], gx, gy)
            for (c in 0 until k) {
                var sx = 0f
                var sy = 0f
                var sw = 0f
                for (i in live) if (assign[i] == c) {
                    sx += px[i] * w[i]
                    sy += py[i] * w[i]
                    sw += w[i]
                }
                if (sw > 0f) {
                    gx[c] = sx / sw
                    gy[c] = sy / sw
                }
            }
        }
        for (i in live) assign[i] = nearest(px[i], py[i], gx, gy)

        // Which name each group gets: least total distance from the starting hues, with
        // saturation counted too -- yellow and purple are two LEDs together and read paler
        // than the three that are one.
        val cost = Array(k) { g ->
            val hue = hueOfPoint(gx[g], gy[g])
            val sat = hypot(gx[g], gy[g])
            FloatArray(k) { c ->
                LinkColour.hueDistance(hue, LinkColour.HUES[c]) +
                    SATURATION_WEIGHT * kotlin.math.abs(sat - LinkColour.SATURATIONS[c])
            }
        }
        var bestPerm: IntArray? = null
        var bestCost = Float.MAX_VALUE
        permutations(k) { perm ->
            var total = 0f
            for (g in 0 until k) total += cost[g][perm[g]]
            if (total < bestCost) {
                bestCost = total
                bestPerm = perm.copyOf()
            }
        }
        val perm = bestPerm ?: return null
        val nx = FloatArray(k)
        val ny = FloatArray(k)
        for (g in 0 until k) {
            nx[perm[g]] = gx[g]
            ny[perm[g]] = gy[g]
        }
        val named = IntArray(px.size) { if (assign[it] < 0) LinkColour.UNKNOWN else perm[assign[it]] }
        return Groups(nx, ny, named)
    }

    private fun permutations(n: Int, visit: (IntArray) -> Unit) {
        val a = IntArray(n) { it }
        fun go(i: Int) {
            if (i == n) {
                visit(a)
                return
            }
            for (j in i until n) {
                a[i] = a[j].also { a[j] = a[i] }
                go(i + 1)
                a[i] = a[j].also { a[j] = a[i] }
            }
        }
        go(0)
    }

    /** Degrees of hue a unit of saturation is worth when naming groups. */
    private const val SATURATION_WEIGHT = 100f

    /** Groups nearer than this on the chroma plane are one colour read twice, not two. */
    private const val MIN_GROUP_SPACING = 0.12f

    /** Twins first, distinct colours used to break a tie. */
    private fun score(cells: List<IntArray>): Int =
        twinned(cells) * 8 + cells.flatMap { c -> c.filter { it >= 0 } }.toSet().size

    /** Panels with an exact twin under a naming. */
    fun twinned(cells: List<IntArray>): Int {
        val patterns = cells.map { c -> String(CharArray(c.size) { LinkColour.letter(c[it]) }) }.filter { '?' !in it }
        val counts = patterns.groupingBy { it }.eachCount()
        return patterns.count { counts[it] == 2 }
    }

    private fun nearest(x: Float, y: Float, cx: FloatArray, cy: FloatArray): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (c in cx.indices) {
            val d = hypot(x - cx[c], y - cy[c])
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    /** One minus nearest over next-nearest distance: 0 on a boundary, 1 on a centre. */
    private fun margin(x: Float, y: Float, cx: FloatArray, cy: FloatArray): Float {
        var first = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for (c in cx.indices) {
            val d = hypot(x - cx[c], y - cy[c])
            if (d < first) {
                second = first
                first = d
            } else if (d < second) {
                second = d
            }
        }
        return if (second <= 0f) 0f else (1f - first / second).coerceIn(0f, 1f)
    }

    private fun hueOfPoint(x: Float, y: Float): Float {
        val h = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
        return if (h < 0f) h + 360f else h
    }
}
