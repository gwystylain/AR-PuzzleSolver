package com.puzzlesolver.core.image

import kotlin.math.sqrt

/**
 * Local mean and standard deviation over square windows, in O(1) per pixel.
 *
 * Exists to fix a specific failure: thresholding against a local mean alone marks sensor
 * noise as ink wherever a region is flat, so a dark, empty part of the scene comes back
 * roughly half black. That speckle then dominates projection profiles -- a wall's worth
 * of it outweighs the grid rules we actually want, and it does so asymmetrically,
 * depending on how much surround each axis integrates over.
 *
 * Requiring local *contrast* rather than local darkness removes it, because flat regions
 * have near-zero variance regardless of brightness. That is the same reasoning behind
 * Sauvola's method; this is the cheap version of it.
 *
 * Integral images are reused across calls: at canvas ROI sizes the buffers run to several
 * megabytes, and reallocating them per detection pass would be a steady GC drip in the
 * one loop that must not stutter.
 */
class LocalStats {

    private var width = 0
    private var height = 0
    private var sum = LongArray(0)
    private var sumSq = LongArray(0)

    /** Builds the integral images for [img]. Must be called before any query. */
    fun prepare(img: GrayImage) {
        val w = img.width
        val h = img.height
        if (w != width || h != height) {
            // Allocate first, publish the size after.
            //
            // These run to tens of megabytes at canvas ROI sizes and the allocation can
            // genuinely fail. Setting the dimensions first leaves this object claiming a
            // size its buffers do not have, and the guard above then believes it is
            // already prepared -- so it skips the reallocation on every later call and
            // indexes off the end of the old arrays instead. That turned one
            // OutOfMemoryError into a permanent ArrayIndexOutOfBoundsException, on a
            // caller that catches and retries several times a second and therefore never
            // recovers. Assigning last means a failed allocation leaves the previous
            // state intact and the next call simply tries again.
            val newSum = LongArray((w + 1) * (h + 1))
            val newSumSq = LongArray((w + 1) * (h + 1))
            sum = newSum
            sumSq = newSumSq
            width = w
            height = h
        }
        val stride = w + 1
        // Row 0 and column 0 stay zero, which is what makes the four-corner query valid
        // without bounds special-casing.
        java.util.Arrays.fill(sum, 0, stride, 0L)
        java.util.Arrays.fill(sumSq, 0, stride, 0L)
        for (y in 0 until h) {
            var rowSum = 0L
            var rowSumSq = 0L
            val src = y * w
            val cur = (y + 1) * stride
            val prev = y * stride
            sum[cur] = 0L
            sumSq[cur] = 0L
            for (x in 0 until w) {
                val v = (img.data[src + x].toInt() and 0xFF).toLong()
                rowSum += v
                rowSumSq += v * v
                sum[cur + x + 1] = sum[prev + x + 1] + rowSum
                sumSq[cur + x + 1] = sumSq[prev + x + 1] + rowSumSq
            }
        }
    }

    /**
     * Writes the local mean into [outMean] and standard deviation into [outStd], both
     * sized for the prepared image.
     */
    fun compute(radius: Int, outMean: FloatArray, outStd: FloatArray) {
        require(outMean.size >= width * height && outStd.size >= width * height)
        val stride = width + 1
        for (y in 0 until height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius + 1).coerceAtMost(height)
            val rowTop = y0 * stride
            val rowBottom = y1 * stride
            val out = y * width
            for (x in 0 until width) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius + 1).coerceAtMost(width)
                val n = ((x1 - x0) * (y1 - y0)).toLong()
                if (n <= 0L) {
                    outMean[out + x] = 0f
                    outStd[out + x] = 0f
                    continue
                }
                val s = sum[rowBottom + x1] - sum[rowBottom + x0] -
                    sum[rowTop + x1] + sum[rowTop + x0]
                val sq = sumSq[rowBottom + x1] - sumSq[rowBottom + x0] -
                    sumSq[rowTop + x1] + sumSq[rowTop + x0]
                val mean = s.toDouble() / n
                val variance = (sq.toDouble() / n - mean * mean).coerceAtLeast(0.0)
                outMean[out + x] = mean.toFloat()
                outStd[out + x] = sqrt(variance).toFloat()
            }
        }
    }

    /**
     * Contrast-gated adaptive threshold. Output is 255 for ink, 0 for everything else.
     *
     * A pixel is ink when it is meaningfully darker than its surroundings *and* those
     * surroundings actually vary. The second clause is the whole point: without it, flat
     * dark regions come back as half-ink speckle.
     */
    fun threshold(
        src: GrayImage,
        dst: GrayImage,
        radius: Int = 12,
        bias: Int = 8,
        minStdDev: Float = 6f,
        meanBuffer: FloatArray,
        stdBuffer: FloatArray,
    ) {
        prepare(src)
        compute(radius, meanBuffer, stdBuffer)
        val n = src.width * src.height
        for (i in 0 until n) {
            val v = src.data[i].toInt() and 0xFF
            val ink = stdBuffer[i] >= minStdDev && v < meanBuffer[i] - bias
            dst.data[i] = if (ink) 255.toByte() else 0
        }
    }
}
