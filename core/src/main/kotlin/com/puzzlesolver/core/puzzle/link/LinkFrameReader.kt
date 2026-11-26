package com.puzzlesolver.core.puzzle.link

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.gems.GemBlobDetector
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the Link panels in one camera frame and reads their colours.
 *
 * At the LED-wall preset -- the exposure Gems reads at, 1/250 s at floor ISO -- an unlit
 * button is black and a lit one is a disc of its own colour, so on this wall the lit
 * buttons are everything there is to find, and they come in threes: a panel is nine lit
 * discs a button apart, and the one-button gutter round it puts two buttons between its
 * discs and the next panel's. So:
 *
 *  1. find the bright discs, as Gems does, on the brightest channel rather than luma so a
 *     blue or purple LED is as easy to find as a yellow one;
 *  2. keep the ones that are lit -- the practice game draws the unlit buttons grey, and a
 *     phone pointed at it finds them too;
 *  3. join discs nearer than 1.3 buttons: a panel is one cluster, and the gutter keeps
 *     neighbours apart;
 *  4. fit each cluster to a 3x3 grid -- its own two axes, from the steps between its own
 *     discs, so a panel seen at an angle on a curved wall still fits -- and read all nine
 *     places, including any whose disc was not found.
 *
 * A panel cut off by the edge of the frame is left for a frame that has it whole.
 */
class LinkFrameReader {

    /** One button's face, as read. */
    class Look(
        /** The colour by the starting centroids, or [LinkColour.UNKNOWN]. */
        @JvmField val colour: Int,
        /** Margin of the winning colour's vote over the next, 0..1. */
        @JvmField val confidence: Float,
        /** Whether enough of the face was lit to call the button on. */
        @JvmField val lit: Boolean,
        /** Mean colour of the lit face, each channel over the brightest, so 0..1. */
        @JvmField val r: Float,
        @JvmField val g: Float,
        @JvmField val b: Float,
    ) {
        val hue: Float get() = LinkColour.hueOf(r, g, b)
        val saturation: Float get() = LinkColour.saturationOf(r, g, b)
    }

    /** A whole panel in the frame, in image pixels. */
    class FramePanel(
        /** Its middle button. */
        @JvmField val cx: Float,
        @JvmField val cy: Float,
        /** One button along a row, left to right. */
        @JvmField val ux: Float,
        @JvmField val uy: Float,
        /** One button down a column. */
        @JvmField val vx: Float,
        @JvmField val vy: Float,
        /** Its nine buttons, row by row from the top left. */
        val looks: Array<Look>,
    ) {
        val pitch: Float get() = (hypot(ux, uy) + hypot(vx, vy)) / 2f

        fun cellX(k: Int): Float = cx + (k % 3 - 1) * ux + (k / 3 - 1) * vx
        fun cellY(k: Int): Float = cy + (k % 3 - 1) * uy + (k / 3 - 1) * vy

        val pattern: String get() = String(CharArray(LinkPanel.CELLS) { LinkColour.letter(looks[it].colour) })
    }

    class Result(
        val panels: List<FramePanel>,
        /** Panels seen but not whole: cut by the frame's edge, or with a row of buttons missing. */
        val partial: Int,
        /** Lit discs found. */
        val lit: Int,
        /** Button pitch in pixels, or -1. */
        val pitch: Float,
        /** Buttons of nothing lit between the leftmost panel and the frame's left edge, and the same on the right; -1 with no panels. */
        val blankLeft: Float,
        val blankRight: Float,
        val report: String,
    ) {
        companion object {
            fun empty(report: String) = Result(emptyList(), 0, 0, -1f, -1f, -1f, report)
        }
    }

    private val detector = GemBlobDetector(
        GemBlobDetector.Config(
            // A whole wall of panels is up to 288 lit buttons, and the practice game's grey
            // unlit ones are found as well until they are filtered out.
            maxBlobs = 600,
        )
    )

    private var value: GrayImage? = null

    fun read(frame: RgbImage): Result {
        val w = frame.width
        val h = frame.height
        val v = value?.takeIf { it.width == w && it.height == h } ?: GrayImage(w, h).also { value = it }
        brightest(frame, v)

        val found = detector.detect(v)
        val lit = found.filter { meanAround(v, it.x, it.y, (it.radius * 0.5f).coerceAtLeast(1f)) >= LIT_VALUE }
        if (lit.size < MIN_DISCS) return Result.empty("${lit.size} lit buttons of ${found.size} discs")

        val n = lit.size
        val xs = FloatArray(n) { lit[it].x }
        val ys = FloatArray(n) { lit[it].y }
        val pitch = medianNearest(xs, ys)
        if (pitch <= 0f) return Result.empty("no spacing to measure")
        val local = detector.localPitches(lit, pitch) ?: FloatArray(n) { pitch }

        // Join neighbours, and note each join's direction for the frame's roll.
        val parent = IntArray(n) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var k = i
            while (parent[k] != r) {
                val next = parent[k]
                parent[k] = r
                k = next
            }
            return r
        }
        val edgesA = ArrayList<Int>()
        val edgesB = ArrayList<Int>()
        var c4 = 0.0
        var s4 = 0.0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = xs[j] - xs[i]
                val dy = ys[j] - ys[i]
                val d = hypot(dx, dy)
                val p = (local[i] + local[j]) / 2f
                if (d >= LINK * p) continue
                if (d < MIN_STEP * p) continue
                edgesA.add(i)
                edgesB.add(j)
                val ri = root(i)
                val rj = root(j)
                if (ri != rj) parent[ri] = rj
                val a = atan2(dy.toDouble(), dx.toDouble())
                c4 += cos(4 * a)
                s4 += sin(4 * a)
            }
        }
        val roll = (atan2(s4, c4) / 4).toFloat()
        val cr = cos(roll)
        val sr = sin(roll)

        val members = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) members.getOrPut(root(i)) { ArrayList() }.add(i)
        val edgesOf = HashMap<Int, MutableList<Int>>()
        for (e in edgesA.indices) edgesOf.getOrPut(root(edgesA[e])) { ArrayList() }.add(e)

        val panels = ArrayList<FramePanel>()
        var partial = 0
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for ((r, idx) in members) {
            if (idx.size < MIN_DISCS) continue
            for (i in idx) {
                minX = minOf(minX, xs[i])
                maxX = maxOf(maxX, xs[i])
            }
            val panel = fit(frame, idx, edgesOf[r].orEmpty(), edgesA, edgesB, xs, ys, cr, sr)
            if (panel == null) partial++ else panels.add(panel)
        }
        panels.sortBy { it.cx }

        val blankLeft = if (panels.isEmpty() && partial == 0) -1f else minX / pitch
        val blankRight = if (panels.isEmpty() && partial == 0) -1f else (w - maxX) / pitch
        return Result(
            panels = panels,
            partial = partial,
            lit = n,
            pitch = pitch,
            blankLeft = blankLeft,
            blankRight = blankRight,
            report = "${panels.size} panels, $partial partial, $n lit, pitch %.1f, roll %.1f".format(
                pitch, Math.toDegrees(roll.toDouble()),
            ),
        )
    }

    /**
     * Fits one cluster to a 3x3 grid, or null when it is not a whole panel in the frame.
     */
    private fun fit(
        frame: RgbImage,
        idx: List<Int>,
        edges: List<Int>,
        edgesA: List<Int>,
        edgesB: List<Int>,
        xs: FloatArray,
        ys: FloatArray,
        cr: Float,
        sr: Float,
    ): FramePanel? {
        // The panel's own axes: every join is a step along a row or down a column. Turned
        // by the frame's roll to tell which, then folded to point right or down.
        var ux = 0f
        var uy = 0f
        var nu = 0
        var vx = 0f
        var vy = 0f
        var nv = 0
        for (e in edges) {
            var dx = xs[edgesB[e]] - xs[edgesA[e]]
            var dy = ys[edgesB[e]] - ys[edgesA[e]]
            val along = dx * cr + dy * sr
            val across = -dx * sr + dy * cr
            if (abs(along) >= abs(across)) {
                if (along < 0) { dx = -dx; dy = -dy }
                ux += dx; uy += dy; nu++
            } else {
                if (across < 0) { dx = -dx; dy = -dy }
                vx += dx; vy += dy; nv++
            }
        }
        if (nu == 0 && nv == 0) return null
        if (nu > 0) { ux /= nu; uy /= nu }
        if (nv > 0) { vx /= nv; vy /= nv }
        if (nu == 0) { ux = vy; uy = -vx }
        if (nv == 0) { vx = -uy; vy = ux }

        // Every disc's place in the grid, from the disc nearest the cluster's middle.
        var mx = 0f
        var my = 0f
        for (i in idx) { mx += xs[i]; my += ys[i] }
        mx /= idx.size
        my /= idx.size
        val origin = idx.minByOrNull { hypot(xs[it] - mx, ys[it] - my) }!!
        val det = ux * vy - vx * uy
        if (abs(det) < 1e-3f) return null
        val gridA = IntArray(idx.size)
        val gridB = IntArray(idx.size)
        val keep = BooleanArray(idx.size)
        var kept = 0
        for ((k, i) in idx.withIndex()) {
            val dx = xs[i] - xs[origin]
            val dy = ys[i] - ys[origin]
            val a = (dx * vy - vx * dy) / det
            val b = (ux * dy - dx * uy) / det
            val ra = a.roundToInt()
            val rb = b.roundToInt()
            if (abs(a - ra) > GRID_SLACK || abs(b - rb) > GRID_SLACK) continue
            gridA[k] = ra
            gridB[k] = rb
            keep[k] = true
            kept++
        }
        if (kept < MIN_DISCS) return null
        var aMin = Int.MAX_VALUE
        var aMax = Int.MIN_VALUE
        var bMin = Int.MAX_VALUE
        var bMax = Int.MIN_VALUE
        for (k in idx.indices) {
            if (!keep[k]) continue
            aMin = minOf(aMin, gridA[k]); aMax = maxOf(aMax, gridA[k])
            bMin = minOf(bMin, gridB[k]); bMax = maxOf(bMax, gridB[k])
        }
        // Wider than a panel: two panels joined by glare, or not a panel at all.
        if (aMax - aMin != 2 || bMax - bMin != 2) return null

        // Least squares for the middle and both axes: x = cx + a*ux + b*vx, the same for y,
        // with (a, b) counted from the panel's middle.
        val m = DoubleArray(9)
        val rx = DoubleArray(3)
        val ry = DoubleArray(3)
        for (k in idx.indices) {
            if (!keep[k]) continue
            val a = (gridA[k] - aMin - 1).toDouble()
            val b = (gridB[k] - bMin - 1).toDouble()
            val basis = doubleArrayOf(1.0, a, b)
            val x = xs[idx[k]].toDouble()
            val y = ys[idx[k]].toDouble()
            for (p in 0..2) {
                for (q in 0..2) m[p * 3 + q] += basis[p] * basis[q]
                rx[p] += basis[p] * x
                ry[p] += basis[p] * y
            }
        }
        val sx = solve3(m, rx) ?: return null
        val sy = solve3(m, ry) ?: return null
        val cx = sx[0].toFloat()
        val cy = sy[0].toFloat()
        val fux = sx[1].toFloat()
        val fuy = sy[1].toFloat()
        val fvx = sx[2].toFloat()
        val fvy = sy[2].toFloat()

        val pitch = (hypot(fux, fuy) + hypot(fvx, fvy)) / 2f
        val margin = pitch * EDGE_MARGIN
        val looks = Array(LinkPanel.CELLS) { k ->
            val x = cx + (k % 3 - 1) * fux + (k / 3 - 1) * fvx
            val y = cy + (k % 3 - 1) * fuy + (k / 3 - 1) * fvy
            if (x < margin || y < margin || x > frame.width - 1 - margin || y > frame.height - 1 - margin) return null
            readFace(frame, x, y, pitch * FACE_FRACTION)
        }
        if (looks.count { it.lit } < MIN_LIT_CELLS) return null
        return FramePanel(cx, cy, fux, fuy, fvx, fvy, looks)
    }

    companion object {
        /** On the brightest channel, a lit button's middle is at least this. */
        const val LIT_VALUE = 60f

        /** Below this on the brightest channel a texel is not LED light. */
        const val MIN_VALUE = 60

        /** All three channels at or above this and a texel says only "bright". */
        const val CLIP = 250

        /** Discs nearer than this many buttons are in the same panel. Across a gutter they are two apart; diagonal neighbours 1.41. */
        const val LINK = 1.3f

        /** Nearer than this many buttons is one disc found twice, not two. */
        const val MIN_STEP = 0.6f

        /** How far off the grid a disc may sit, in buttons, and still be placed on it. */
        const val GRID_SLACK = 0.3f

        /** Fewest discs a cluster needs to be fitted as a panel, of its nine. */
        const val MIN_DISCS = 5

        /** Fewest of a panel's nine places that must read lit: every button of a panel is. */
        const val MIN_LIT_CELLS = 7

        /** Every place read has to be this many buttons inside the frame. */
        const val EDGE_MARGIN = 0.3f

        /** Radius of the face read, in buttons. A button's face is about 0.26. */
        const val FACE_FRACTION = 0.22f

        /** A quarter of the face lit and the button is on. */
        const val MIN_LIT_FRACTION = 0.25f

        private const val TARGET_SAMPLES = 160.0

        /** The brightest of the three channels, which treats a blue LED as fairly as a yellow one. */
        fun brightest(frame: RgbImage, out: GrayImage) {
            val d = frame.data
            var s = 0
            for (i in 0 until frame.width * frame.height) {
                val r = d[s].toInt() and 0xFF
                val g = d[s + 1].toInt() and 0xFF
                val b = d[s + 2].toInt() and 0xFF
                out.data[i] = maxOf(r, g, b).toByte()
                s += 3
            }
        }

        private fun meanAround(img: GrayImage, cx: Float, cy: Float, radius: Float): Float {
            val r = radius.toInt().coerceAtLeast(1)
            val x0 = (cx.toInt() - r).coerceAtLeast(0)
            val x1 = (cx.toInt() + r).coerceAtMost(img.width - 1)
            val y0 = (cy.toInt() - r).coerceAtLeast(0)
            val y1 = (cy.toInt() + r).coerceAtMost(img.height - 1)
            var sum = 0
            var count = 0
            for (y in y0..y1) for (x in x0..x1) {
                sum += img[x, y]
                count++
            }
            return if (count == 0) 0f else sum.toFloat() / count
        }

        private fun medianNearest(xs: FloatArray, ys: FloatArray): Float {
            val n = xs.size
            if (n < 2) return -1f
            val nearest = FloatArray(n)
            for (i in 0 until n) {
                var best = Float.MAX_VALUE
                for (j in 0 until n) {
                    if (i == j) continue
                    val d = hypot(xs[i] - xs[j], ys[i] - ys[j])
                    if (d < best) best = d
                }
                nearest[i] = best
            }
            nearest.sort()
            return nearest[n / 2]
        }

        /**
         * Reads one face: every texel bright enough to be LED votes for the colour nearest
         * its hue, weighted by how saturated it is -- a vote, as the gem and mines faces
         * are read, so the bloom at a disc's rim cannot average its colour into something
         * else. The mean colour of the lit texels is kept too, for the scan to name it
         * afresh once it knows where this visit's white balance has put the five colours.
         */
        fun readFace(image: RgbImage, cx: Float, cy: Float, radius: Float): Look {
            val r2 = radius * radius
            val x0 = (cx - radius).toInt().coerceAtLeast(0)
            val x1 = (cx + radius).toInt().coerceAtMost(image.width - 1)
            val y0 = (cy - radius).toInt().coerceAtLeast(0)
            val y1 = (cy + radius).toInt().coerceAtMost(image.height - 1)
            val stride = maxOf(1, sqrt(Math.PI * r2 / TARGET_SAMPLES).toInt())
            val votes = FloatArray(LinkColour.COUNT)
            var total = 0
            var lit = 0
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var coloured = 0
            val data = image.data
            var y = y0
            while (y <= y1) {
                val dy = y - cy
                var x = x0
                while (x <= x1) {
                    val dx = x - cx
                    if (dx * dx + dy * dy <= r2) {
                        val i = (y * image.width + x) * 3
                        val r = data[i].toInt() and 0xFF
                        val g = data[i + 1].toInt() and 0xFF
                        val b = data[i + 2].toInt() and 0xFF
                        val max = maxOf(r, g, b)
                        val min = minOf(r, g, b)
                        total++
                        if (max >= MIN_VALUE) {
                            lit++
                            // A texel clipped on all three says nothing about colour.
                            if (min < CLIP) {
                                val fr = r.toFloat() / max
                                val fg = g.toFloat() / max
                                val fb = b.toFloat() / max
                                sr += fr
                                sg += fg
                                sb += fb
                                coloured++
                                val s = (max - min).toFloat() / max
                                votes[LinkColour.nearestHue(LinkColour.hueOf(fr, fg, fb))] += s
                            }
                        }
                    }
                    x += stride
                }
                y += stride
            }
            val isLit = total > 0 && lit >= total * MIN_LIT_FRACTION && coloured > 0
            var best = LinkColour.UNKNOWN
            var bestV = 0f
            var second = 0f
            for (k in votes.indices) {
                val vk = votes[k]
                if (vk > bestV) {
                    second = bestV
                    bestV = vk
                    best = k
                } else if (vk > second) {
                    second = vk
                }
            }
            val c = coloured.coerceAtLeast(1)
            return Look(
                colour = if (isLit && bestV > 0f) best else LinkColour.UNKNOWN,
                confidence = if (bestV > 0f) (bestV - second) / bestV else 0f,
                lit = isLit,
                r = sr / c,
                g = sg / c,
                b = sb / c,
            )
        }

        /** Solves the symmetric 3x3 system [m] x = [r] by Cramer's rule. */
        private fun solve3(m: DoubleArray, r: DoubleArray): DoubleArray? {
            fun det(a: DoubleArray) =
                a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
            val d = det(m)
            if (abs(d) < 1e-9) return null
            return DoubleArray(3) { col ->
                val c = m.copyOf()
                for (row in 0..2) c[row * 3 + col] = r[row]
                det(c) / d
            }
        }
    }
}
