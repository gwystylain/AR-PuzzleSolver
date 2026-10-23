package com.puzzlesolver.core.puzzle.spot

import com.puzzlesolver.core.image.GrayImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the lit screen in a photo: the four lines its long straight sides lie on.
 *
 * The Spot room shows its pictures on a screen set in a dark bezel, inside rings of neon,
 * in a dark room. The screen is an octagon -- a rectangle with its corners cut off -- so
 * what is found is the rectangle its four straight sides would make if they were carried
 * on through the cut corners. That rectangle is what every photo gets straightened to.
 *
 * How, in order:
 *
 *  1. **The screen is the bright thing in the middle.** Its brightness is taken from the
 *     centre of a decimated frame, and everything at a tenth of that or more is screen,
 *     flooded outward from the centre. A tenth because the bezel sits at 5 to 15 and the
 *     darkest art the two sample pictures put right against the edge -- foliage, a palm
 *     trunk -- sits at 25 to 60. Half, the first thing tried, cut the palm trunk out of
 *     the screen and fitted the right side to it.
 *  2. **Unless it leaks.** If the flood reaches the edge of the frame it has got through the
 *     bezel into the neon somewhere, and the level is raised and the flood run again.
 *  3. **Holes are filled**, so a dark boat in the middle is screen too.
 *  4. **Each side is voted for.** Every row gives a left and right extent and every column
 *     a top and bottom one. Each set is put to a Hough-style vote over lines within 19
 *     degrees of the axis, which is what keeps out the cut corners: a chamfer is a line
 *     too, and a plain least-squares fit through the side and both chamfers tilts the side
 *     towards them. The octagon's short sides are barely half its height, so this was not
 *     a refinement -- the first fits were off by a dozen pixels at the corners.
 *  5. **Then refined at full resolution**, each sample moved to where the frame itself
 *     crosses the level, and the line fitted again with outliers trimmed.
 *
 * This only has to be close. [SpotFinder] lines the photos up on their content after
 * this, and needs this to within a few percent of the screen's size, not to a pixel --
 * the bezel stands proud of the screen, so its edge moves against the picture between
 * two photos taken from different places, and no outline could be better than that.
 *
 * Works on any [GrayImage], upright or not: in the live preview it runs on the camera
 * buffer as delivered, sideways, and a rectangle sideways is still a rectangle.
 */
class ScreenLocator {

    /** A located screen. Coordinates are pixels of the image it was found in. */
    class Screen(
        /** Corners of the screen's rectangle: top-left, top-right, bottom-right, bottom-left, as x, y pairs. */
        @JvmField val corners: FloatArray,
        /** The screen's own pixels, holes filled, at 1/[step] resolution. */
        @JvmField val mask: BooleanArray,
        @JvmField val maskWidth: Int,
        @JvmField val maskHeight: Int,
        @JvmField val step: Int,
        /** Median luma at the middle of the screen. */
        @JvmField val level: Int,
    ) {
        /** Whether an image pixel is on the screen itself, chamfers excluded. */
        fun contains(x: Double, y: Double): Boolean {
            val mx = (x / step).toInt()
            val my = (y / step).toInt()
            if (mx < 0 || my < 0 || mx >= maskWidth || my >= maskHeight) return false
            return mask[my * maskWidth + mx]
        }

        /** Area of the corner quadrilateral, in image pixels. */
        val area: Double
            get() {
                var s = 0.0
                for (i in 0 until 4) {
                    val j = (i + 1) % 4
                    s += corners[i * 2].toDouble() * corners[j * 2 + 1] - corners[j * 2].toDouble() * corners[i * 2 + 1]
                }
                return abs(s) / 2
            }

        /** Mean of the top and bottom side lengths over the mean of the left and right. */
        val aspect: Double
            get() {
                fun len(a: Int, b: Int): Double {
                    val dx = (corners[a * 2] - corners[b * 2]).toDouble()
                    val dy = (corners[a * 2 + 1] - corners[b * 2 + 1]).toDouble()
                    return kotlin.math.sqrt(dx * dx + dy * dy)
                }
                val across = (len(0, 1) + len(3, 2)) / 2
                val down = (len(0, 3) + len(1, 2)) / 2
                return if (down > 0) across / down else 0.0
            }
    }

    // Reused between calls: the live preview runs this ten times a second.
    private var small = GrayImage(1, 1)
    private var bright = BooleanArray(0)
    private var inside = BooleanArray(0)
    private var outside = BooleanArray(0)
    private var queue = IntArray(0)
    private val histogram = IntArray(256)
    private val votes = IntArray(VOTE_BINS)

    /** Why the last call returned null, for the HUD and the log. */
    var lastFailure: String = ""
        private set

    fun locate(luma: GrayImage): Screen? {
        val step = max(1, (max(luma.width, luma.height) + WORKING_SIDE - 1) / WORKING_SIDE)
        val sw = luma.width / step
        val sh = luma.height / step
        if (sw < 32 || sh < 32) return fail("frame too small")
        if (small.width != sw || small.height != sh) small = GrayImage(sw, sh)
        luma.subImageDecimated(0, 0, sw * step, sh * step, step, small)
        val n = sw * sh
        if (bright.size < n) {
            bright = BooleanArray(n)
            inside = BooleanArray(n)
            outside = BooleanArray(n)
            queue = IntArray(n)
        }

        val cx = sw / 2
        val cy = sh / 2
        val level = centreLevel(cx, cy, sw, sh)
        if (level < MIN_LEVEL) return fail("nothing bright in the middle of the frame")

        var factor = THRESHOLD_FACTOR
        var threshold = 0f
        var leaked = true
        for (attempt in 0 until LEAK_RETRIES) {
            threshold = level * factor
            for (i in 0 until n) bright[i] = (small.data[i].toInt() and 0xFF) >= threshold
            val touched = floodFromCentre(sw, sh, cx, cy)
            if (touched < 0) return fail("nothing bright in the middle of the frame")
            if (touched == 0) {
                leaked = false
                break
            }
            factor *= LEAK_RAISE
        }
        if (leaked) return fail("the screen runs into its surround or off the frame")

        // Holes: whatever the outside cannot reach from the frame's edge is screen.
        fillHoles(sw, sh)
        var count = 0
        var x0 = sw
        var x1 = -1
        var y0 = sh
        var y1 = -1
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (!inside[y * sw + x]) continue
                count++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (count < n * MIN_AREA_FRACTION) return fail("the bright patch is too small to be the screen")

        // Extent samples, one per row and column of the screen's box.
        val rows = y1 - y0 + 1
        val cols = x1 - x0 + 1
        val leftU = IntArray(rows); val leftV = IntArray(rows)
        val rightU = IntArray(rows); val rightV = IntArray(rows)
        var nr = 0
        for (y in y0..y1) {
            var first = -1
            var last = -1
            for (x in x0..x1) {
                if (inside[y * sw + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            leftU[nr] = y; leftV[nr] = first
            rightU[nr] = y; rightV[nr] = last + 1
            nr++
        }
        val topU = IntArray(cols); val topV = IntArray(cols)
        val bottomU = IntArray(cols); val bottomV = IntArray(cols)
        var nc = 0
        for (x in x0..x1) {
            var first = -1
            var last = -1
            for (y in y0..y1) {
                if (inside[y * sw + x]) {
                    if (first < 0) first = y
                    last = y
                }
            }
            if (first < 0) continue
            topU[nc] = x; topV[nc] = first
            bottomU[nc] = x; bottomV[nc] = last + 1
            nc++
        }

        val left = side(luma, leftU, leftV, nr, step, threshold, horizontal = true, outward = -1)
            ?: return fail("no straight left side")
        val right = side(luma, rightU, rightV, nr, step, threshold, horizontal = true, outward = 1)
            ?: return fail("no straight right side")
        val top = side(luma, topU, topV, nc, step, threshold, horizontal = false, outward = -1)
            ?: return fail("no straight top")
        val bottom = side(luma, bottomU, bottomV, nc, step, threshold, horizontal = false, outward = 1)
            ?: return fail("no straight bottom")

        val corners = FloatArray(8)
        if (!intersect(left, top, corners, 0) || !intersect(right, top, corners, 1) ||
            !intersect(right, bottom, corners, 2) || !intersect(left, bottom, corners, 3)
        ) {
            return fail("sides do not meet")
        }
        // The rectangle's corners are virtual -- past the chamfers -- so a screen that fills
        // the frame can legitimately put them a little outside it. Far outside means the
        // sides found are not the screen's.
        val slackX = luma.width * CORNER_SLACK
        val slackY = luma.height * CORNER_SLACK
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            if (x < -slackX || y < -slackY || x > luma.width + slackX || y > luma.height + slackY) {
                return fail("the screen's corners fall outside the frame")
            }
        }
        if (corners[2] <= corners[0] || corners[4] <= corners[6] || corners[7] <= corners[1] || corners[5] <= corners[3]) {
            return fail("the sides found do not make a rectangle")
        }

        lastFailure = ""
        return Screen(corners, inside.copyOf(n), sw, sh, step, level)
    }

    private fun fail(why: String): Screen? {
        lastFailure = why
        return null
    }

    private fun centreLevel(cx: Int, cy: Int, sw: Int, sh: Int): Int {
        java.util.Arrays.fill(histogram, 0)
        var total = 0
        for (y in cy - sh / 8 until cy + sh / 8) {
            for (x in cx - sw / 8 until cx + sw / 8) {
                histogram[small[x, y]]++
                total++
            }
        }
        var acc = 0
        for (v in 0..255) {
            acc += histogram[v]
            if (acc * 2 > total) return v
        }
        return 255
    }

    /**
     * Floods [bright] from seeds near the centre into [inside].
     *
     * @return 0 when it stayed inside the frame, 1 when it reached the frame's edge, and
     *         -1 when there was nothing to start from.
     */
    private fun floodFromCentre(sw: Int, sh: Int, cx: Int, cy: Int): Int {
        java.util.Arrays.fill(inside, 0, sw * sh, false)
        var head = 0
        var tail = 0
        var y = cy - sh / 10
        while (y < cy + sh / 10) {
            var x = cx - sw / 10
            while (x < cx + sw / 10) {
                val i = y * sw + x
                if (bright[i] && !inside[i]) {
                    inside[i] = true
                    queue[tail++] = i
                }
                x += 2
            }
            y += 2
        }
        if (tail == 0) return -1
        var touched = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % sw
            val yy = i / sw
            if (x == 0 || yy == 0 || x == sw - 1 || yy == sh - 1) touched = 1
            if (x > 0) tail = push(i - 1, tail, bright, inside)
            if (x < sw - 1) tail = push(i + 1, tail, bright, inside)
            if (yy > 0) tail = push(i - sw, tail, bright, inside)
            if (yy < sh - 1) tail = push(i + sw, tail, bright, inside)
        }
        return touched
    }

    private fun push(i: Int, tail: Int, allowed: BooleanArray, seen: BooleanArray): Int {
        if (!allowed[i] || seen[i]) return tail
        seen[i] = true
        queue[tail] = i
        return tail + 1
    }

    private fun fillHoles(sw: Int, sh: Int) {
        val n = sw * sh
        // `bright` is free now; reuse it as "not screen".
        for (i in 0 until n) bright[i] = !inside[i]
        java.util.Arrays.fill(outside, 0, n, false)
        var head = 0
        var tail = 0
        for (x in 0 until sw) {
            tail = push(x, tail, bright, outside)
            tail = push((sh - 1) * sw + x, tail, bright, outside)
        }
        for (y in 0 until sh) {
            tail = push(y * sw, tail, bright, outside)
            tail = push(y * sw + sw - 1, tail, bright, outside)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % sw
            val y = i / sw
            if (x > 0) tail = push(i - 1, tail, bright, outside)
            if (x < sw - 1) tail = push(i + 1, tail, bright, outside)
            if (y > 0) tail = push(i - sw, tail, bright, outside)
            if (y < sh - 1) tail = push(i + sw, tail, bright, outside)
        }
        for (i in 0 until n) inside[i] = !outside[i]
    }

    /**
     * One side of the screen as `v = a*u + b` in full-resolution pixels, where u runs along
     * the side and v across it: for the left and right sides u is y and v is x.
     */
    private fun side(
        luma: GrayImage,
        us: IntArray,
        vs: IntArray,
        count: Int,
        step: Int,
        threshold: Float,
        horizontal: Boolean,
        outward: Int,
    ): DoubleArray? {
        if (count < MIN_SIDE_SAMPLES) return null
        val keep = BooleanArray(count)
        val line = vote(us, vs, count, keep) ?: return null

        // Each inlier moved to where the full frame crosses the level, near where the
        // decimated one said.
        val fu = DoubleArray(count)
        val fv = DoubleArray(count)
        var m = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            val u = us[i] * step + step / 2
            val coarse = vs[i] * step
            val v = crossing(luma, u, coarse, step, threshold, horizontal, outward) ?: continue
            fu[m] = u.toDouble()
            fv[m] = v
            m++
        }
        if (m < MIN_SIDE_SAMPLES) {
            // Nothing crossed cleanly at full resolution; the decimated line will do. A
            // decimated sample at u sits at u*step + step/2 in the frame, and at v*step
            // across it.
            return doubleArrayOf(line[0], line[1] * step - line[0] * step / 2.0)
        }
        return trimmedFit(fu, fv, m)
    }

    /** The dominant near-axis line through the samples; marks its inliers in [keep]. */
    private fun vote(us: IntArray, vs: IntArray, count: Int, keep: BooleanArray): DoubleArray? {
        var bestCount = -1
        var bestA = 0.0
        var bestB = 0.0
        for (k in 0..SLOPE_STEPS * 2) {
            val a = (k - SLOPE_STEPS) * SLOPE_RESOLUTION
            java.util.Arrays.fill(votes, 0)
            for (i in 0 until count) {
                val bin = Math.round(vs[i] - a * us[i]).toInt() + VOTE_OFFSET
                if (bin in 1 until VOTE_BINS - 1) votes[bin]++
            }
            for (bin in 1 until VOTE_BINS - 1) {
                val c = votes[bin - 1] + votes[bin] + votes[bin + 1]
                if (c > bestCount) {
                    bestCount = c
                    bestA = a
                    bestB = (bin - VOTE_OFFSET).toDouble()
                }
            }
        }
        if (bestCount < MIN_SIDE_SAMPLES) return null
        var a = bestA
        var b = bestB
        repeat(2) {
            var sn = 0.0; var su = 0.0; var sv = 0.0; var suu = 0.0; var suv = 0.0
            for (i in 0 until count) {
                keep[i] = abs(vs[i] - (a * us[i] + b)) <= VOTE_TOLERANCE
                if (!keep[i]) continue
                val u = us[i].toDouble()
                val v = vs[i].toDouble()
                sn++; su += u; sv += v; suu += u * u; suv += u * v
            }
            val den = sn * suu - su * su
            if (sn < 2 || abs(den) < 1e-9) return@repeat
            a = (sn * suv - su * sv) / den
            b = (sv - a * su) / sn
        }
        var kept = 0
        for (i in 0 until count) {
            keep[i] = abs(vs[i] - (a * us[i] + b)) <= VOTE_TOLERANCE
            if (keep[i]) kept++
        }
        return if (kept >= MIN_SIDE_SAMPLES) doubleArrayOf(a, b) else null
    }

    /**
     * Where the frame crosses [threshold] going from screen to bezel, along one row (or
     * column) at [u], within two decimated pixels of [coarse]. Sub-pixel, by linear
     * interpolation between the two samples either side of the crossing.
     */
    private fun crossing(
        luma: GrayImage,
        u: Int,
        coarse: Int,
        step: Int,
        threshold: Float,
        horizontal: Boolean,
        outward: Int,
    ): Double? {
        val limit = if (horizontal) luma.width else luma.height
        if (u < 0 || u >= (if (horizontal) luma.height else luma.width)) return null
        fun at(p: Int): Int = if (horizontal) luma[p, u] else luma[u, p]
        var best: Double? = null
        for (p in max(1, coarse - 2 * step) until min(limit - 1, coarse + 2 * step)) {
            val a: Int
            val b: Int
            if (outward > 0) { a = at(p - 1); b = at(p) } else { a = at(p); b = at(p - 1) }
            if (a < threshold || b >= threshold) continue
            val t = (a - threshold) / (a - b)
            val pos = if (outward > 0) p - 1 + t.toDouble() else p - t.toDouble()
            if (best == null || abs(pos - coarse) < abs(best - coarse)) best = pos
        }
        return best
    }

    /** Least squares, refitted four times without the points furthest off. */
    private fun trimmedFit(us: DoubleArray, vs: DoubleArray, count: Int): DoubleArray? {
        val keep = BooleanArray(count) { true }
        val residual = DoubleArray(count)
        var a = 0.0
        var b = 0.0
        repeat(4) {
            var sn = 0.0; var su = 0.0; var sv = 0.0; var suu = 0.0; var suv = 0.0
            for (i in 0 until count) {
                if (!keep[i]) continue
                sn++; su += us[i]; sv += vs[i]; suu += us[i] * us[i]; suv += us[i] * vs[i]
            }
            val den = sn * suu - su * su
            if (sn < 2 || abs(den) < 1e-9) return null
            a = (sn * suv - su * sv) / den
            b = (sv - a * su) / sn
            var kept = 0
            for (i in 0 until count) {
                residual[i] = abs(vs[i] - (a * us[i] + b))
                if (keep[i]) kept++
            }
            val sorted = DoubleArray(kept)
            var k = 0
            for (i in 0 until count) if (keep[i]) sorted[k++] = residual[i]
            sorted.sort()
            val limit = max(3.0 * sorted[kept / 2], 0.75)
            for (i in 0 until count) keep[i] = residual[i] <= limit
        }
        return doubleArrayOf(a, b)
    }

    /**
     * Where a left or right side (x = a*y + b) meets a top or bottom (y = a*x + b), into
     * corner [index] of [out].
     */
    private fun intersect(vertical: DoubleArray, horizontal: DoubleArray, out: FloatArray, index: Int): Boolean {
        val av = vertical[0]
        val bv = vertical[1]
        val ah = horizontal[0]
        val bh = horizontal[1]
        val den = 1 - av * ah
        if (abs(den) < 1e-6) return false
        val x = (av * bh + bv) / den
        out[index * 2] = x.toFloat()
        out[index * 2 + 1] = (ah * x + bh).toFloat()
        return true
    }

    private companion object {
        /** The decimated frame's long side, roughly. 1920 goes down by four. */
        const val WORKING_SIDE = 480

        /** The middle of the frame must be at least this bright to be a lit screen. */
        const val MIN_LEVEL = 40

        /** Screen is anything at this fraction of the centre's brightness or more. */
        const val THRESHOLD_FACTOR = 0.10f

        /** How much the level rises each time the flood leaks, and how often it may. */
        const val LEAK_RAISE = 1.6f
        const val LEAK_RETRIES = 4

        /** The screen must cover at least this much of the frame. */
        const val MIN_AREA_FRACTION = 0.04f

        /** Samples a side needs, before and after voting. */
        const val MIN_SIDE_SAMPLES = 8

        /** Slopes voted over: +-0.35, i.e. within 19 degrees of the axis, in 0.01 steps. */
        const val SLOPE_STEPS = 35
        const val SLOPE_RESOLUTION = 0.01

        /** How far, in decimated pixels, a sample may sit off the line and still be on it. */
        const val VOTE_TOLERANCE = 1.5

        const val VOTE_BINS = 2048
        const val VOTE_OFFSET = 512

        /** How far outside the frame a virtual corner may fall, as a fraction of the frame. */
        const val CORNER_SLACK = 0.15f
    }
}
