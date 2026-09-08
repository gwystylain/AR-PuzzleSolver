package com.puzzlesolver.core.canvas

import kotlin.math.floor

/**
 * The flattened puzzle canvas: a fixed metric raster in (u, v) wall coordinates.
 *
 * Fixed rather than growing, deliberately. Reallocating a GPU texture mid-scan
 * would stall the pipeline and force us to re-warp every past frame. Instead we
 * allocate once, generously, and anchor the origin at the wall point the camera
 * was looking at when tracking started.
 *
 * At the default 1.5 mm/texel a 4096-wide canvas covers 6.1 m of wall, which is
 * plenty for a "large" wall puzzle while still resolving printed detail. Trade
 * the two against each other if your puzzle is bigger or finer.
 */
data class CanvasSpec(
    val widthTexels: Int = 4096,
    val heightTexels: Int = 4096,
    val metresPerTexel: Float = 0.0015f,
    /** Wall coordinate that maps to the canvas centre. */
    val originU: Float = 0f,
    val originV: Float = 0f,
) {
    val widthMetres: Float get() = widthTexels * metresPerTexel
    val heightMetres: Float get() = heightTexels * metresPerTexel

    val uMin: Float get() = originU - widthMetres * 0.5f
    val uMax: Float get() = originU + widthMetres * 0.5f
    val vMin: Float get() = originV - heightMetres * 0.5f
    val vMax: Float get() = originV + heightMetres * 0.5f

    fun uToTexel(u: Float): Float = (u - uMin) / metresPerTexel
    fun vToTexel(v: Float): Float = (v - vMin) / metresPerTexel
    fun texelToU(x: Float): Float = uMin + x * metresPerTexel
    fun texelToV(y: Float): Float = vMin + y * metresPerTexel

    fun containsUv(u: Float, v: Float): Boolean =
        u >= uMin && u < uMax && v >= vMin && v < vMax

    fun texelIndex(u: Float, v: Float): Int {
        val x = floor(uToTexel(u)).toInt()
        val y = floor(vToTexel(v)).toInt()
        if (x < 0 || y < 0 || x >= widthTexels || y >= heightTexels) return -1
        return y * widthTexels + x
    }

    /** Re-centres the canvas on a wall coordinate, keeping resolution and size. */
    fun centredOn(u: Float, v: Float) = copy(originU = u, originV = v)
}

/**
 * Which parts of the canvas we have actually seen, and how well.
 *
 * Stored at a coarse stride (default 16 canvas texels per coverage cell) because
 * this is read back from the GPU every frame and consulted by the solver loop.
 * A full-resolution coverage mask would dominate the readback budget for no gain
 * -- nothing downstream cares about coverage at sub-centimetre granularity.
 */
class CoverageMap(
    val spec: CanvasSpec,
    val stride: Int = 16,
) {
    val cols: Int = (spec.widthTexels + stride - 1) / stride
    val rows: Int = (spec.heightTexels + stride - 1) / stride

    /** Best observation confidence per cell, 0..1. Zero means never seen. */
    val confidence = FloatArray(cols * rows)

    /** Monotonic counter of the last update that touched each cell. */
    val lastTouchedSeq = IntArray(cols * rows)

    var observedCells: Int = 0
        private set

    fun clear() {
        confidence.fill(0f)
        lastTouchedSeq.fill(0)
        observedCells = 0
    }

    fun cellIndexForTexel(x: Int, y: Int): Int {
        val cx = x / stride
        val cy = y / stride
        if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) return -1
        return cy * cols + cx
    }

    fun update(index: Int, conf: Float, seq: Int) {
        if (index < 0 || index >= confidence.size) return
        if (confidence[index] <= 0f && conf > MIN_USEFUL_CONFIDENCE) observedCells++
        if (conf > confidence[index]) confidence[index] = conf
        lastTouchedSeq[index] = seq
    }

    /**
     * Bulk update from a GPU readback of the confidence channel, one byte per cell.
     * Called on the pipeline thread; allocates nothing.
     */
    fun updateFromBytes(bytes: ByteArray, seq: Int) {
        val n = minOf(bytes.size, confidence.size)
        var seen = 0
        for (i in 0 until n) {
            val c = (bytes[i].toInt() and 0xFF) / 255f
            if (c > confidence[i]) {
                confidence[i] = c
                lastTouchedSeq[i] = seq
            }
            if (confidence[i] > MIN_USEFUL_CONFIDENCE) seen++
        }
        observedCells = seen
    }

    fun isObserved(index: Int): Boolean =
        index >= 0 && index < confidence.size && confidence[index] > MIN_USEFUL_CONFIDENCE

    /**
     * Fraction of a canvas-space rectangle that has been observed. This is how a
     * puzzle adapter asks "have I seen this cell yet?" without knowing anything
     * about the coverage representation.
     */
    fun coverageOfRect(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val cx0 = (x0 / stride).coerceIn(0, cols - 1)
        val cy0 = (y0 / stride).coerceIn(0, rows - 1)
        val cx1 = (x1 / stride).coerceIn(0, cols - 1)
        val cy1 = (y1 / stride).coerceIn(0, rows - 1)
        var total = 0
        var seen = 0
        for (cy in cy0..cy1) {
            val row = cy * cols
            for (cx in cx0..cx1) {
                total++
                if (confidence[row + cx] > MIN_USEFUL_CONFIDENCE) seen++
            }
        }
        return if (total == 0) 0f else seen.toFloat() / total
    }

    companion object {
        /**
         * Below this, a texel was seen at such a grazing angle or such low resolution
         * that we would be classifying noise.
         */
        const val MIN_USEFUL_CONFIDENCE = 0.15f
    }
}
