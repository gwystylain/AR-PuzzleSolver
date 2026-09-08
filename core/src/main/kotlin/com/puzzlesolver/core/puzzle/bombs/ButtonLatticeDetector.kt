package com.puzzlesolver.core.puzzle.bombs

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Finds the button lattice, for a wall that has no lines on it.
 *
 * `GridDetector` looks for long runs of contrast, because that is what a ruled puzzle
 * is made of, and it is the right tool for sudoku. Pointed at this wall it fails in the
 * worst available way: handed a real frame it reports a ten-texel pitch at 0.84
 * confidence, an order of magnitude too fine. There are no lines to find, so it locks
 * onto sensor noise and the texture on the button faces and says it is sure. A detector
 * that returned nothing would be far less dangerous.
 *
 * So this one works from what the wall does have -- round buttons, brighter than the
 * panel behind them, on a regular pitch. Find the blobs, then fit a lattice to their
 * centres.
 *
 * The unlit buttons matter more than the lit ones here. During a clean sweep almost
 * every button is unlit, and an unlit button is not dark: it is a pale disc catching
 * the room light, comfortably above the panel but nowhere near the clipped brightness
 * of a lit one. Thresholding against a *local* background rather than a global one is
 * what makes both readable at once, because the room lights the wall very unevenly.
 */
class ButtonLatticeDetector(
    private val spec: CanvasSpec,
    private val cfg: Config = Config(),
) {
    data class Config(
        /** Button spacing we are willing to believe, in metres. */
        val minPitchMetres: Float = 0.03f,
        val maxPitchMetres: Float = 0.40f,
        /**
         * Background window, as a multiple of the largest pitch we would accept. Wide
         * enough that a button never forms its own background, which would erase it.
         */
        val backgroundWindowFactor: Float = 1.5f,
        /** How far above the local background a pixel must sit to count as button. */
        val brightnessBias: Int = 10,
        /**
         * Absolute floor on a component before it is even considered, in texels. Only
         * there to stop single-pixel noise dominating the median below.
         */
        val noiseFloorArea: Int = 20,
        /**
         * Kept blob area, as a multiple of the median component area.
         *
         * Every button on this wall is the same physical size, so the median area *is*
         * the button area -- no need to know the pitch first, which is fortunate since
         * the pitch is what we are trying to measure. Bounds this wide still throw out
         * both speckle and the merged glare blob a cluster of lit buttons produces.
         */
        val minAreaRatio: Float = 0.35f,
        val maxAreaRatio: Float = 3.0f,
        val minButtons: Int = 12,
        /** A centroid counts as on-lattice within this fraction of the pitch. */
        val snapTolerance: Float = 0.25f,
        /**
         * Longest side of the image the search actually runs on.
         *
         * The canvas is 4096 square, so a blur and a connected-components pass over all
         * of it costs about one and a half seconds -- measured on device, where it
         * showed up as a solver step of 1528 ms. Buttons are tens of texels across, so
         * shrinking the working image loses nothing that matters and takes the same
         * work down by more than an order of magnitude.
         */
        val maxWorkingDimension: Int = 768,
    )

    /** Diagnostics, because "no grid" has several very different causes. */
    var lastReport: String = "not run"
        private set

    /** Buttons found, before any lattice is fitted. */
    var lastBlobCount: Int = 0
        private set

    /** Median nearest-neighbour distance in texels: the raw pitch measurement. */
    var lastPitchTexels: Float = 0f
        private set

    /** Median blob area in texels, which for this wall is one button. */
    var lastBlobArea: Int = 0
        private set

    private var workingBuf: GrayImage? = null
    private var maskBuf: GrayImage? = null
    private var scratchBuf: GrayImage? = null
    private var backgroundBuf: GrayImage? = null

    private fun buffer(current: GrayImage?, w: Int, h: Int): GrayImage =
        if (current != null && current.width == w && current.height == h) current else GrayImage(w, h)

    /** So the adapter can reuse one detector instead of rebuilding its buffers. */
    fun specMatches(other: CanvasSpec): Boolean =
        other.widthTexels == spec.widthTexels &&
            other.heightTexels == spec.heightTexels &&
            other.metresPerTexel == spec.metresPerTexel

    fun detect(canvasLuma: GrayImage, coverage: CoverageMap? = null): GridModel? {
        // Only the part of the canvas anyone has actually looked at. Everything else is
        // a cleared buffer, and searching it for buttons is pure cost.
        val roi = observedBounds(canvasLuma, coverage) ?: run {
            lastReport = "nothing observed yet"
            return null
        }
        val roiW = roi[2] - roi[0]
        val roiH = roi[3] - roi[1]
        val step = ((maxOf(roiW, roiH) + cfg.maxWorkingDimension - 1) / cfg.maxWorkingDimension)
            .coerceAtLeast(1)
        val w = roiW / step
        val h = roiH / step
        if (w < 32 || h < 32) {
            lastReport = "observed region is only ${roiW}x$roiH texels"
            return null
        }

        val work = downsample(canvasLuma, roi[0], roi[1], w, h, step)
        val maxPitchTexels = (cfg.maxPitchMetres / spec.metresPerTexel) / step
        val minPitchTexels = (cfg.minPitchMetres / spec.metresPerTexel) / step

        val blobs = findBlobs(work, w, h, maxPitchTexels)
        // Back into canvas texels, so everything downstream is in the usual frame.
        for (i in blobs.indices step 2) {
            blobs[i] = roi[0] + blobs[i] * step
            blobs[i + 1] = roi[1] + blobs[i + 1] * step
        }
        // Two floats per blob.
        if (blobs.size / 2 < cfg.minButtons) {
            lastReport = "only ${blobs.size / 2} button-like blobs found"
            return null
        }

        lastBlobCount = blobs.size / 2
        val pitch = medianNearestNeighbour(blobs) ?: run {
            lastReport = "could not estimate a pitch from ${blobs.size} blobs"
            return null
        }
        lastPitchTexels = pitch
        if (pitch < minPitchTexels * step || pitch > maxPitchTexels * step) {
            lastReport = "pitch $pitch texels is outside the believable range"
            return null
        }

        val theta = estimateRotation(blobs, pitch)
        val cosT = cos(theta)
        val sinT = sin(theta)

        // Into the lattice frame, where the buttons should land on a square grid.
        val n = blobs.size / 2
        val u = FloatArray(n)
        val v = FloatArray(n)
        for (i in 0 until n) {
            val x = blobs[i * 2]
            val y = blobs[i * 2 + 1]
            u[i] = x * cosT + y * sinT
            v[i] = -x * sinT + y * cosT
        }

        val u0 = phaseOf(u, pitch)
        val v0 = phaseOf(v, pitch)

        var minCol = Int.MAX_VALUE
        var maxCol = Int.MIN_VALUE
        var minRow = Int.MAX_VALUE
        var maxRow = Int.MIN_VALUE
        var onLattice = 0
        for (i in 0 until n) {
            val fc = (u[i] - u0) / pitch
            val fr = (v[i] - v0) / pitch
            val c = fc.roundToInt()
            val r = fr.roundToInt()
            if (abs(fc - c) <= cfg.snapTolerance && abs(fr - r) <= cfg.snapTolerance) {
                onLattice++
                if (c < minCol) minCol = c
                if (c > maxCol) maxCol = c
                if (r < minRow) minRow = r
                if (r > maxRow) maxRow = r
            }
        }
        if (onLattice < cfg.minButtons) {
            lastReport = "only $onLattice of ${blobs.size / 2} blobs sit on a common lattice"
            return null
        }

        val cols = maxCol - minCol + 1
        val rows = maxRow - minRow + 1

        // GridModel's origin is the outer corner of cell (0,0), while the lattice is
        // pinned to button centres, so step back half a cell along both axes.
        val nodeU = u0 + minCol * pitch
        val nodeV = v0 + minRow * pitch
        val nodeX = nodeU * cosT - nodeV * sinT
        val nodeY = nodeU * sinT + nodeV * cosT
        val originX = nodeX - 0.5f * (pitch * cosT - pitch * sinT)
        val originY = nodeY - 0.5f * (pitch * sinT + pitch * cosT)

        val fit = onLattice.toFloat() / (blobs.size / 2)
        val fill = onLattice.toFloat() / (cols * rows).coerceAtLeast(1)
        val confidence = (fit * 0.6f + fill.coerceAtMost(1f) * 0.4f).coerceIn(0f, 1f)

        lastReport = "$onLattice/${blobs.size / 2} blobs on lattice, ${cols}x$rows, " +
            "pitch ${"%.1f".format(pitch)}, fill ${"%.2f".format(fill)}"

        return GridModel(
            originX = originX,
            originY = originY,
            rotationDeg = Math.toDegrees(theta.toDouble()).toFloat(),
            pitchX = pitch,
            pitchY = pitch,
            cols = cols,
            rows = rows,
            confidence = confidence,
        )
    }

    /**
     * The bounding box of everything the coverage map says has been seen, in canvas
     * texels. Falls back to the whole image when there is no coverage map, which is the
     * case in tests that hand over a rendered board directly.
     */
    private fun observedBounds(image: GrayImage, coverage: CoverageMap?): IntArray? {
        if (coverage == null) return intArrayOf(0, 0, image.width, image.height)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (cy in 0 until coverage.rows) {
            for (cx in 0 until coverage.cols) {
                if (!coverage.isObserved(cy * coverage.cols + cx)) continue
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
            }
        }
        if (minX > maxX) return null
        val stride = coverage.stride
        return intArrayOf(
            (minX * stride).coerceIn(0, image.width),
            (minY * stride).coerceIn(0, image.height),
            ((maxX + 1) * stride).coerceIn(0, image.width),
            ((maxY + 1) * stride).coerceIn(0, image.height),
        )
    }

    /**
     * Shrinks a region by taking the brightest texel of each block.
     *
     * Max rather than mean, because the thing being looked for is a small bright disc on
     * a dark panel. Averaging dilutes it toward the background exactly when the button is
     * small in the frame, which is when the detector needs the help.
     */
    private fun downsample(src: GrayImage, x0: Int, y0: Int, w: Int, h: Int, step: Int): GrayImage {
        val dst = buffer(workingBuf, w, h).also { workingBuf = it }
        for (y in 0 until h) {
            val sy = y0 + y * step
            for (x in 0 until w) {
                val sx = x0 + x * step
                var best = 0
                for (j in 0 until step) {
                    val py = sy + j
                    if (py >= src.height) break
                    for (i in 0 until step) {
                        val px = sx + i
                        if (px >= src.width) break
                        val v = src[px, py]
                        if (v > best) best = v
                    }
                }
                dst[x, y] = best
            }
        }
        return dst
    }

    /** @return flat array of blob centroids, x then y. */
    private fun findBlobs(src: GrayImage, w: Int, h: Int, maxPitchTexels: Float): FloatArray {
        val bg = buffer(backgroundBuf, w, h).also { backgroundBuf = it }
        val tmp = buffer(scratchBuf, w, h).also { scratchBuf = it }
        val mask = buffer(maskBuf, w, h).also { maskBuf = it }

        val radius = (maxPitchTexels * cfg.backgroundWindowFactor).toInt().coerceIn(4, 128)
        ImageOps.boxBlur(src, bg, tmp, radius)

        // Bright against the local background, the opposite polarity to
        // ImageOps.adaptiveThreshold -- that hunts dark ink on light paper.
        for (i in 0 until w * h) {
            val vv = src.data[i].toInt() and 0xFF
            val bb = bg.data[i].toInt() and 0xFF
            mask.data[i] = if (vv > bb + cfg.brightnessBias) LIT else 0
        }

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val areas = ArrayList<Int>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (mask.data[start] != LIT) continue
            var top = 0
            stack[top++] = start
            mask.data[start] = VISITED
            var count = 0
            var sx = 0.0
            var sy = 0.0
            while (top > 0) {
                val p = stack[--top]
                count++
                sx += p % w
                sy += p / w
                val px = p % w
                val py = p / w
                if (px > 0 && isUnvisited(mask, p - 1)) { mask.data[p - 1] = VISITED; stack[top++] = p - 1 }
                if (px < w - 1 && isUnvisited(mask, p + 1)) { mask.data[p + 1] = VISITED; stack[top++] = p + 1 }
                if (py > 0 && isUnvisited(mask, p - w)) { mask.data[p - w] = VISITED; stack[top++] = p - w }
                if (py < h - 1 && isUnvisited(mask, p + w)) { mask.data[p + w] = VISITED; stack[top++] = p + w }
            }
            if (count < cfg.noiseFloorArea) continue
            xs.add((sx / count).toFloat())
            ys.add((sy / count).toFloat())
            areas.add(count)
        }
        if (areas.isEmpty()) return FloatArray(0)

        val sorted = areas.sorted()
        val medianArea = sorted[sorted.size / 2]
        val lo = medianArea * cfg.minAreaRatio
        val hi = medianArea * cfg.maxAreaRatio

        val out = ArrayList<Float>(areas.size * 2)
        for (i in areas.indices) {
            if (areas[i] < lo || areas[i] > hi) continue
            out.add(xs[i])
            out.add(ys[i])
        }
        lastBlobArea = medianArea
        return out.toFloatArray()
    }

    private fun isUnvisited(mask: GrayImage, p: Int): Boolean = mask.data[p] == LIT

    /**
     * The median distance to a nearest neighbour, which for a lattice of buttons is
     * the pitch. Median rather than mean because a few blobs are always spurious --
     * glare, a reflection, half a button at the frame edge -- and one bad pair would
     * drag a mean badly.
     */
    private fun medianNearestNeighbour(blobs: FloatArray): Float? {
        val n = blobs.size / 2
        if (n < 2) return null
        val nearest = FloatArray(n)
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (j in 0 until n) {
                if (i == j) continue
                val d = hypot(blobs[i * 2] - blobs[j * 2], blobs[i * 2 + 1] - blobs[j * 2 + 1])
                if (d < best) best = d
            }
            nearest[i] = best
        }
        nearest.sort()
        return nearest[n / 2]
    }

    /**
     * In-plane rotation, from the directions between neighbouring buttons.
     *
     * A square lattice looks the same every ninety degrees, so the angles are folded
     * by four before averaging. Averaging them directly would have a neighbour to the
     * east and one to the north cancel out to nothing.
     */
    private fun estimateRotation(blobs: FloatArray, pitch: Float): Float {
        val n = blobs.size / 2
        var sumSin = 0.0
        var sumCos = 0.0
        val tol = pitch * 0.35f
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                val dx = blobs[j * 2] - blobs[i * 2]
                val dy = blobs[j * 2 + 1] - blobs[i * 2 + 1]
                val d = hypot(dx, dy)
                if (abs(d - pitch) > tol) continue
                val a = atan2(dy.toDouble(), dx.toDouble()) * 4.0
                sumSin += sin(a)
                sumCos += cos(a)
            }
        }
        if (sumSin == 0.0 && sumCos == 0.0) return 0f
        return (atan2(sumSin, sumCos) / 4.0).toFloat()
    }

    /**
     * Where the lattice sits along one axis, as a circular mean of the coordinates
     * modulo the pitch. Circular because the offset wraps: values just under the pitch
     * and just over zero are neighbours, and a plain average would put the lattice
     * halfway between them.
     */
    private fun phaseOf(coords: FloatArray, pitch: Float): Float {
        var sumSin = 0.0
        var sumCos = 0.0
        for (c in coords) {
            val a = 2.0 * Math.PI * (c / pitch)
            sumSin += sin(a)
            sumCos += cos(a)
        }
        val angle = atan2(sumSin, sumCos)
        var phase = (angle / (2.0 * Math.PI) * pitch).toFloat()
        // Pull it below the smallest coordinate so lattice indices start at zero or above.
        val min = coords.min()
        while (phase > min) phase -= pitch
        return phase
    }

    private companion object {
        /** Mask values. Distinct from 0 and from each other so one pass can do both. */
        const val LIT: Byte = -1          // 255 unsigned
        const val VISITED: Byte = 1
    }
}
