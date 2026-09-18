package com.puzzlesolver.core.puzzle.terminal

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps

/**
 * Finds the number displays in a camera frame.
 *
 * The terminal wall is a scatter of small lit panels, each carrying three digit tiles,
 * on an otherwise dark wall. Two things about that shape decided the whole approach:
 *
 *  - **The panels are not on a lattice.** Rows hold six or seven of them and are offset
 *    against each other like brickwork, so there is no consistent pitch to fit and
 *    nothing for [com.puzzlesolver.core.grid.GridDetector] or the gem lattice detector
 *    to lock onto. Every display therefore has to be found on its own evidence.
 *  - **A panel is the brightest thing anywhere near it.** Face, border and digits all
 *    sit well above the wall behind them, so at a working resolution coarse enough to
 *    blur the digits away a display is simply a solid bright rectangle -- which is a far
 *    easier thing to find than three tiles and their contents.
 *
 * So this thresholds against a local background, takes connected components, and keeps
 * the ones shaped like a display. Local rather than global because the room is lit by
 * the wall itself: a panel at the near end of a pan is several times brighter than one
 * at the far end, and a single threshold that finds both also finds the glow between
 * them.
 *
 * Everything is in image pixels. There is no pose, no wall fit and no canvas here, for
 * the same reason as in Gems: reading a number depends on that display alone, and a
 * rectangle drawn around it only has to land where the display is on screen right now.
 */
class DisplayDetector(private val cfg: Config = Config()) {

    data class Config(
        /**
         * Longest side of the image the search runs on.
         *
         * Thresholding and connected components are both linear in area, and at 1080p
         * that is two million pixels of work for panels two hundred pixels across.
         * Measured on the reference clip the detector finds all 32 displays unchanged
         * from full resolution down to a 640-pixel frame, so there is nothing to be
         * gained by looking for them large. The digits are still read at full
         * resolution -- see [DigitReader].
         */
        val maxWorkingDimension: Int = 480,
        /** How far above the local background a pixel must sit to count as panel. */
        val brightnessBias: Int = 12,
        /** Absolute floor, in working-resolution pixels, to keep speckle out. */
        val noiseFloorArea: Int = 24,
        /**
         * Width over height of a display.
         *
         * Three tiles side by side, so a little under two. Measured across the
         * reference clip every display lands in 1.77 to 1.96, including the ones seen
         * most obliquely; the bounds are wide enough to survive a good deal more
         * perspective than that and still reject everything else in the room.
         */
        val minAspect: Float = 1.4f,
        val maxAspect: Float = 2.6f,
        /**
         * Filled area as a fraction of the bounding box.
         *
         * A display is solid at working resolution -- the tile faces are above the local
         * background as well as the borders and the digits -- and measures 0.85 to 0.99.
         * A reflection, a strip of glow along a wall edge, or a partly occluded panel
         * does not, and this is what separates them. Deliberately no upper bound: a
         * perfect rectangle is exactly what a display is.
         */
        val minFill: Float = 0.55f,
        /**
         * Kept area as a multiple of the median.
         *
         * Every display on the wall is the same size, so the median area *is* a
         * display. Perspective across one frame moves it by well under this.
         */
        val minAreaRatio: Float = 0.45f,
        val maxAreaRatio: Float = 2.0f,
        val maxDisplays: Int = 96,
    )

    /** A display as found, in full-resolution image pixels. */
    class Box(
        @JvmField val x: Int,
        @JvmField val y: Int,
        @JvmField val width: Int,
        @JvmField val height: Int,
    )

    /** Why the last pass found what it did, for the HUD and the log. */
    var lastReport: String = "not run"
        private set

    /** Microseconds in each stage of the last pass: decimate, blur, threshold, filter. */
    @JvmField
    val stageMicros = LongArray(4)

    private var workBuf: GrayImage? = null
    private var bgBuf: GrayImage? = null
    private var scratchBuf: GrayImage? = null
    private var maskBuf: GrayImage? = null
    private var stack = IntArray(0)

    private fun buffer(current: GrayImage?, w: Int, h: Int): GrayImage =
        if (current != null && current.width == w && current.height == h) current else GrayImage(w, h)

    fun detect(luma: GrayImage): List<Box> {
        val step = ((maxOf(luma.width, luma.height) + cfg.maxWorkingDimension - 1) /
            cfg.maxWorkingDimension).coerceAtLeast(1)
        val w = luma.width / step
        val h = luma.height / step
        if (w < 32 || h < 32) {
            lastReport = "frame is only ${luma.width}x${luma.height}"
            return emptyList()
        }

        var mark = System.nanoTime()
        val work = buffer(workBuf, w, h).also { workBuf = it }
        // Block mean rather than the gem detector's max-pool. A gem is a small bright
        // disc that a mean would dilute; a display is a large bright rectangle, and the
        // mean is what keeps its *interior* solid so it comes back as one component
        // rather than as an outline with holes in it.
        luma.subImageDecimated(0, 0, w * step, h * step, step, work)
        stageMicros[0] = (System.nanoTime() - mark) / 1000

        val bg = buffer(bgBuf, w, h).also { bgBuf = it }
        val tmp = buffer(scratchBuf, w, h).also { scratchBuf = it }
        val mask = buffer(maskBuf, w, h).also { maskBuf = it }

        // Wide enough that a display cannot form its own background and erase itself,
        // which is the failure that leaves a detector finding only edges.
        mark = System.nanoTime()
        val blurRadius = (minOf(w, h) / 12).coerceIn(6, 64)
        ImageOps.boxBlur(work, bg, tmp, blurRadius)
        stageMicros[1] = (System.nanoTime() - mark) / 1000

        mark = System.nanoTime()
        for (i in 0 until w * h) {
            val v = work.data[i].toInt() and 0xFF
            val b = bg.data[i].toInt() and 0xFF
            mask.data[i] = if (v > b + cfg.brightnessBias) LIT else 0
        }
        val blobs = components(mask, w, h)
        stageMicros[2] = (System.nanoTime() - mark) / 1000

        mark = System.nanoTime()
        if (blobs.isEmpty()) {
            lastReport = "nothing bright enough to be a display"
            return emptyList()
        }

        val shaped = ArrayList<Component>(blobs.size)
        for (b in blobs) {
            // A blob against the edge of the frame is either a display cut off by it,
            // which cannot be read in full, or something bright at the edge of the room
            // that is not a display at all. On the captured round it was the second --
            // the lit rim of a side panel -- and it reported as one unreadable display
            // in every frame, which is one more than the wall has.
            if (b.minX == 0 || b.minY == 0 || b.minX + b.width >= w || b.minY + b.height >= h) continue
            val aspect = b.width.toFloat() / b.height
            if (aspect < cfg.minAspect || aspect > cfg.maxAspect) continue
            if (b.area.toFloat() / (b.width * b.height) < cfg.minFill) continue
            shaped.add(b)
        }
        if (shaped.isEmpty()) {
            lastReport = "${blobs.size} blobs, none shaped like a display"
            return emptyList()
        }

        val areas = IntArray(shaped.size) { shaped[it].area }
        areas.sort()
        val medianArea = areas[areas.size / 2]
        val lo = medianArea * cfg.minAreaRatio
        val hi = medianArea * cfg.maxAreaRatio

        val kept = ArrayList<Box>(shaped.size)
        var refined = 0
        for (b in shaped) {
            if (b.area < lo || b.area > hi) continue
            val coarse = Box(b.minX * step, b.minY * step, b.width * step, b.height * step)
            val tight = refine(luma, coarse)
            if (tight !== coarse) refined++
            kept.add(tight)
            if (kept.size >= cfg.maxDisplays) break
        }
        stageMicros[3] = (System.nanoTime() - mark) / 1000
        lastReport = "${kept.size} displays of ${blobs.size} blobs, median area $medianArea, " +
            "$refined boxes tightened"
        return kept
    }

    /**
     * Shrinks a coarse box to the lit tiles inside it, at full resolution.
     *
     * Each display sits in a dark octagonal housing, and the coarse pass cannot always
     * tell the two apart. It thresholds against a local background at a twelfth of the
     * resolution, and whether the housing clears that threshold depends on how it is lit:
     * on the reference clip none of them did, and on the first captured round in the room
     * -- with the sensor two stops brighter, and at the ends of the wall where the housings
     * face the camera -- eight of them did. A box that includes the housing is up to a
     * third too wide, the three equal tiles it is then cut into land in the wrong places,
     * and every digit that straddles a cut is lost. That, and not motion blur, was the
     * quarter of the wall that went unread: `066` read as `??6`, `080` read as `000`.
     *
     * The tiles are the brightest thing in the box by a wide margin -- borders and digits
     * sit at the top of the range where the housing sits near the bottom -- so this cuts at
     * a fixed fraction of the box's own brightest content and trims the box to the columns
     * and rows that carry a real run of it. Measured on that capture, the swallowed boxes
     * come down from 148-164 wide to 121-122, which is what the clean ones were all along;
     * on the clip, where nothing was swallowed, boxes move by two or three pixels.
     *
     * Returns [coarse] itself when the trim would remove more than looks like housing,
     * which is how a box that was right to begin with is left alone.
     */
    private fun refine(luma: GrayImage, coarse: Box): Box {
        val x0 = coarse.x.coerceAtLeast(0)
        val y0 = coarse.y.coerceAtLeast(0)
        val x1 = (coarse.x + coarse.width).coerceAtMost(luma.width)
        val y1 = (coarse.y + coarse.height).coerceAtMost(luma.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 8) return coarse

        // The brightest content, taken as a high percentile rather than the maximum so a
        // single specular pixel cannot set the scale for the whole box.
        java.util.Arrays.fill(histogram, 0)
        for (y in y0 until y1) {
            val row = y * luma.width
            for (x in x0 until x1) histogram[luma.data[row + x].toInt() and 0xFF]++
        }
        var seen = 0
        var peak = 255
        val target = (w * h * PEAK_PERCENTILE).toInt()
        for (v in 255 downTo 0) {
            seen += histogram[v]
            if (seen >= target) {
                peak = v
                break
            }
        }
        val threshold = (peak * TILE_FRACTION).toInt()
        if (threshold <= 0) return coarse

        if (columnRuns.size < w) columnRuns = IntArray(w)
        if (rowRuns.size < h) rowRuns = IntArray(h)
        java.util.Arrays.fill(columnRuns, 0, w, 0)
        java.util.Arrays.fill(rowRuns, 0, h, 0)
        for (y in y0 until y1) {
            val row = y * luma.width
            for (x in x0 until x1) {
                if ((luma.data[row + x].toInt() and 0xFF) > threshold) {
                    columnRuns[x - x0]++
                    rowRuns[y - y0]++
                }
            }
        }

        // A column belongs to the display when a real run of it is lit -- a tile's side
        // border spans most of the height -- and a housing margin has next to nothing.
        val minColumn = (h * MIN_RUN_FRACTION).toInt()
        val minRow = (w * MIN_RUN_FRACTION).toInt()
        var left = 0
        while (left < w && columnRuns[left] < minColumn) left++
        var right = w - 1
        while (right > left && columnRuns[right] < minColumn) right--
        var top = 0
        while (top < h && rowRuns[top] < minRow) top++
        var bottom = h - 1
        while (bottom > top && rowRuns[bottom] < minRow) bottom--

        val newW = right - left + 1
        val newH = bottom - top + 1
        // More than a housing's worth gone means this box was not a display with a housing
        // round it, and cutting it further is guesswork.
        if (newW < w * MIN_KEPT_FRACTION || newH < h * MIN_KEPT_FRACTION) return coarse
        if (newW == w && newH == h) return coarse
        return Box(x0 + left, y0 + top, newW, newH)
    }

    private val histogram = IntArray(256)
    private var columnRuns = IntArray(0)
    private var rowRuns = IntArray(0)

    private class Component(
        val minX: Int,
        val minY: Int,
        val width: Int,
        val height: Int,
        val area: Int,
    )

    private fun components(mask: GrayImage, w: Int, h: Int): List<Component> {
        if (stack.size < w * h) stack = IntArray(w * h)
        val out = ArrayList<Component>()
        for (start in 0 until w * h) {
            if (mask.data[start] != LIT) continue
            var top = 0
            stack[top++] = start
            mask.data[start] = VISITED
            var count = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                count++
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0 && mask.data[p - 1] == LIT) { mask.data[p - 1] = VISITED; stack[top++] = p - 1 }
                if (px < w - 1 && mask.data[p + 1] == LIT) { mask.data[p + 1] = VISITED; stack[top++] = p + 1 }
                if (py > 0 && mask.data[p - w] == LIT) { mask.data[p - w] = VISITED; stack[top++] = p - w }
                if (py < h - 1 && mask.data[p + w] == LIT) { mask.data[p + w] = VISITED; stack[top++] = p + w }
            }
            if (count < cfg.noiseFloorArea) continue
            out.add(Component(minX, minY, maxX - minX + 1, maxY - minY + 1, count))
        }
        return out
    }

    private companion object {
        private const val LIT: Byte = -1
        private const val VISITED: Byte = 1

        /** The box's brightest content, as the level 2% of its pixels reach. */
        private const val PEAK_PERCENTILE = 0.02f

        /**
         * Fraction of that peak above which a pixel is tile rather than housing.
         *
         * Measured: tile borders and digits sit at 0.95-1.0 of the peak, the tile face
         * at 0.45-0.65, and the housing at 0.25-0.40. Cutting at 0.55 keeps the borders
         * whatever the exposure, which is all the trim needs -- the outer border *is*
         * the display's extent.
         */
        private const val TILE_FRACTION = 0.55f

        /**
         * How much of a column or row must be lit for it to count as display.
         *
         * A tile's side border spans 85-95% of the box height and the hinge gap between
         * tiles still 60%; a housing margin is nothing, and a lit reflection on the
         * housing's corner -- which is what kept one display's box 25 pixels too wide
         * through a whole captured round -- is 15-20%. Thirty sits between the two
         * populations with room on both sides. Fifteen did not: it let that reflection
         * through by a single pixel.
         */
        private const val MIN_RUN_FRACTION = 0.30f

        /** Below this much of the coarse box left, the trim is distrusted. */
        private const val MIN_KEPT_FRACTION = 0.6f
    }
}
