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
        for (b in shaped) {
            if (b.area < lo || b.area > hi) continue
            kept.add(Box(b.minX * step, b.minY * step, b.width * step, b.height * step))
            if (kept.size >= cfg.maxDisplays) break
        }
        stageMicros[3] = (System.nanoTime() - mark) / 1000
        lastReport = "${kept.size} displays of ${blobs.size} blobs, median area $medianArea"
        return kept
    }

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
    }
}
