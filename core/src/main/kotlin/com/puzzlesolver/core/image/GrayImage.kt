package com.puzzlesolver.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A single-channel 8-bit image, backed by a caller-owned ByteArray.
 *
 * Everything downstream of the GPU readback works on one of these. All operations
 * take an explicit destination so the pipeline can preallocate its buffers once
 * and never allocate again while scanning -- with power efficiency off the table,
 * the thing we are actually protecting is frame-time consistency, and GC pauses
 * are the main threat to that.
 */
class GrayImage(
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val data: ByteArray = ByteArray(width * height),
) {
    init {
        require(data.size >= width * height) { "buffer too small: ${data.size} < ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, v: Int) {
        data[y * width + x] = v.coerceIn(0, 255).toByte()
    }

    fun getClamped(x: Int, y: Int): Int =
        this[x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)]

    /** Bilinear sample. Out-of-bounds coordinates clamp to the edge. */
    fun sampleBilinear(fx: Float, fy: Float): Float {
        val x0 = fx.toInt().coerceIn(0, width - 1)
        val y0 = fy.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val a = this[x0, y0] * (1 - tx) + this[x1, y0] * tx
        val b = this[x0, y1] * (1 - tx) + this[x1, y1] * tx
        return a * (1 - ty) + b * ty
    }

    fun subImage(x0: Int, y0: Int, w: Int, h: Int, dst: GrayImage): GrayImage {
        require(dst.width >= w && dst.height >= h)
        for (y in 0 until h) {
            val src = (y0 + y) * width + x0
            System.arraycopy(data, src, dst.data, y * dst.width, w)
        }
        return dst
    }

    /**
     * Copies a region while decimating it by [step], averaging each step x step block.
     *
     * Averaged rather than point-sampled, and that is the whole of the choice. Grid rules
     * are often a single texel wide, so nearest-neighbour sampling hits them one time in
     * [step] and turns the one periodicity the detector is looking for into aliasing
     * noise. A block mean keeps every rule as a consistent dip in the mean -- weaker, but
     * present in every block it passes through, which is what a projection profile
     * integrates. Taking the block *minimum* would preserve the ink better still, but the
     * detector reads near-zero luma as "never observed", and a minimum would spread a
     * single dark texel across a whole block and have real ink blanked as no-data.
     *
     * [dst] must be at least `w / step` by `h / step`; the trailing partial block on each
     * axis is dropped rather than averaged over fewer samples, since a block built from a
     * different number of texels is not comparable to its neighbours.
     */
    fun subImageDecimated(x0: Int, y0: Int, w: Int, h: Int, step: Int, dst: GrayImage): GrayImage {
        if (step <= 1) return subImage(x0, y0, w, h, dst)
        val dw = w / step
        val dh = h / step
        require(dst.width >= dw && dst.height >= dh)
        val area = step * step
        // One accumulator row, filled by sweeping each source row start to finish.
        //
        // The obvious nesting -- walk the output pixels, and for each one gather its
        // step x step block -- reads a few bytes from one row, jumps to the next row, and
        // comes back, which is a cache line fetched per handful of bytes used. Over a
        // canvas-sized region that difference is most of the runtime, and this runs on
        // the solver's clock where it shows up directly as a slower scan.
        val acc = IntArray(dw)
        for (dy in 0 until dh) {
            java.util.Arrays.fill(acc, 0)
            for (by in 0 until step) {
                var src = (y0 + dy * step + by) * width + x0
                for (dx in 0 until dw) {
                    var s = 0
                    val end = src + step
                    while (src < end) {
                        s += data[src].toInt() and 0xFF
                        src++
                    }
                    acc[dx] += s
                }
            }
            val out = dy * dst.width
            for (dx in 0 until dw) dst.data[out + dx] = (acc[dx] / area).toByte()
        }
        return dst
    }

    fun fill(v: Int) = data.fill(v.coerceIn(0, 255).toByte())

    companion object {
        fun like(other: GrayImage) = GrayImage(other.width, other.height)
    }
}

/**
 * Image operations used by grid detection. Kept as free functions on plain arrays
 * so they stay trivially portable to a JNI/NEON or GPU implementation later --
 * see docs/ARCHITECTURE.md for where that becomes worthwhile.
 */
object ImageOps {

    /** Box blur via two separable passes. [scratch] must match [src] dimensions. */
    fun boxBlur(src: GrayImage, dst: GrayImage, scratch: GrayImage, radius: Int) {
        if (radius <= 0) {
            System.arraycopy(src.data, 0, dst.data, 0, src.width * src.height)
            return
        }
        val w = src.width
        val h = src.height
        val norm = 1f / (2 * radius + 1)

        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -radius..radius) sum += src[i.coerceIn(0, w - 1), y]
            for (x in 0 until w) {
                scratch.data[row + x] = (sum * norm).toInt().coerceIn(0, 255).toByte()
                val out = (x - radius).coerceIn(0, w - 1)
                val inn = (x + radius + 1).coerceIn(0, w - 1)
                sum += src[inn, y] - src[out, y]
            }
        }
        // The vertical pass sweeps rows carrying a running sum per column, rather than
        // walking each column top to bottom. Same arithmetic, same output, but every
        // read and write is sequential: walking columns of a 480-wide image strides a
        // cache line per pixel and measured 16 ms a frame on device for what is only a
        // quarter of a million additions.
        val columnSums = IntArray(w)
        for (i in -radius..radius) {
            val row = i.coerceIn(0, h - 1) * w
            for (x in 0 until w) columnSums[x] += scratch.data[row + x].toInt() and 0xFF
        }
        for (y in 0 until h) {
            val outRow = y * w
            for (x in 0 until w) {
                dst.data[outRow + x] = (columnSums[x] * norm).toInt().coerceIn(0, 255).toByte()
            }
            val leaving = (y - radius).coerceIn(0, h - 1) * w
            val entering = (y + radius + 1).coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                columnSums[x] += (scratch.data[entering + x].toInt() and 0xFF) -
                    (scratch.data[leaving + x].toInt() and 0xFF)
            }
        }
    }

    /**
     * Adaptive threshold against a local mean, which is what makes this robust to
     * the lighting gradient you inevitably get across a wall-sized target lit from
     * one side. Output is 255 for ink, 0 for paper.
     */
    fun adaptiveThreshold(
        src: GrayImage,
        dst: GrayImage,
        scratch: GrayImage,
        scratch2: GrayImage,
        radius: Int = 12,
        bias: Int = 8,
    ) {
        boxBlur(src, scratch2, scratch, radius)
        val n = src.width * src.height
        for (i in 0 until n) {
            val v = src.data[i].toInt() and 0xFF
            val m = scratch2.data[i].toInt() and 0xFF
            dst.data[i] = if (v < m - bias) 255.toByte() else 0
        }
    }

    /**
     * Sobel gradient magnitude and orientation.
     *
     * @param mag  destination for magnitude, saturated to 255
     * @param ori  destination for orientation quantised to 0..179 degrees (mod 180,
     *             since we only care about line direction, not which side is dark)
     */
    fun sobel(src: GrayImage, mag: GrayImage, ori: ByteArray) {
        val w = src.width
        val h = src.height
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = src[x - 1, y - 1]; val tc = src[x, y - 1]; val tr = src[x + 1, y - 1]
                val ml = src[x - 1, y]; val mr = src[x + 1, y]
                val bl = src[x - 1, y + 1]; val bc = src[x, y + 1]; val br = src[x + 1, y + 1]
                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                val m = sqrt((gx * gx + gy * gy).toFloat()).toInt()
                val idx = y * w + x
                mag.data[idx] = min(255, m).toByte()
                var deg = Math.toDegrees(kotlin.math.atan2(gy.toFloat(), gx.toFloat()).toDouble()).toInt()
                deg = ((deg % 180) + 180) % 180
                ori[idx] = deg.toByte()
            }
        }
    }

    /**
     * Estimates the in-plane rotation of a grid, in degrees within [-45, 45).
     *
     * The canvas is metrically rectified but nothing guarantees the puzzle was hung
     * square, so we recover the residual roll from a gradient-orientation histogram.
     * Grid lines produce two peaks 90 degrees apart; folding the histogram mod 90
     * turns that into one unambiguous peak.
     */
    fun estimateGridRotation(mag: GrayImage, ori: ByteArray, minMagnitude: Int = 40): Float {
        val hist = DoubleArray(90)
        val n = mag.width * mag.height
        for (i in 0 until n) {
            val m = mag.data[i].toInt() and 0xFF
            if (m < minMagnitude) continue
            hist[(ori[i].toInt() and 0xFF) % 90] += m.toDouble()
        }
        // Smooth circularly; raw histograms are spiky enough to pick the wrong bin.
        val sm = DoubleArray(90)
        for (i in 0 until 90) {
            var s = 0.0
            for (d in -2..2) s += hist[((i + d) % 90 + 90) % 90] * (3 - abs(d))
            sm[i] = s
        }
        var best = 0
        for (i in 1 until 90) if (sm[i] > sm[best]) best = i
        // Parabolic interpolation for sub-degree accuracy.
        val yl = sm[(best + 89) % 90]
        val yc = sm[best]
        val yr = sm[(best + 1) % 90]
        val denom = yl - 2 * yc + yr
        val offset = if (abs(denom) < 1e-9) 0.0 else 0.5 * (yl - yr) / denom
        val angle = best + offset
        // Gradients are perpendicular to the lines they belong to; either way the
        // result folds into the same [-45, 45) roll.
        return (((angle + 45.0) % 90.0) - 45.0).toFloat()
    }

    /**
     * Sums pixel values along columns after rotating by [angleDeg], producing the
     * projection profile whose periodic minima are the grid lines.
     */
    fun projectionProfile(
        src: GrayImage,
        angleDeg: Float,
        vertical: Boolean,
        out: FloatArray,
        x0: Int = 0,
        y0: Int = 0,
        x1: Int = src.width,
        y1: Int = src.height,
    ) {
        out.fill(0f)
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        val cxi = (x0 + x1) * 0.5f
        val cyi = (y0 + y1) * 0.5f
        val half = out.size * 0.5f
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dx = x - cxi
                val dy = y - cyi
                val t = if (vertical) dx * c + dy * s else -dx * s + dy * c
                val bin = (t + half).toInt()
                if (bin in out.indices) out[bin] += (src.data[y * src.width + x].toInt() and 0xFF).toFloat()
            }
        }
    }

    /**
     * Finds the dominant period of a 1D signal by autocorrelation.
     *
     * Used to recover grid pitch. Returns the period in samples, or -1 when the
     * signal has no convincing periodicity -- which is itself useful information:
     * it usually means the canvas region is not actually a grid.
     */
    fun dominantPeriod(signal: FloatArray, minPeriod: Int, maxPeriod: Int): Float {
        val n = signal.size
        if (n < 4 || minPeriod >= maxPeriod || maxPeriod >= n) return -1f
        var mean = 0f
        for (v in signal) mean += v
        mean /= n
        var bestLag = -1
        var bestScore = 0f
        var energy = 0f
        for (v in signal) energy += (v - mean) * (v - mean)
        if (energy < 1e-6f) return -1f
        for (lag in minPeriod..maxPeriod) {
            var acc = 0f
            for (i in 0 until n - lag) acc += (signal[i] - mean) * (signal[i + lag] - mean)
            val score = acc / energy
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        // A real grid autocorrelates strongly; noise does not.
        if (bestLag < 0 || bestScore < 0.25f) return -1f
        return refinePeakParabolic(signal, bestLag, mean)
    }

    private fun refinePeakParabolic(signal: FloatArray, lag: Int, mean: Float): Float {
        fun corr(l: Int): Float {
            if (l < 1 || l >= signal.size) return 0f
            var acc = 0f
            for (i in 0 until signal.size - l) acc += (signal[i] - mean) * (signal[i + l] - mean)
            return acc
        }
        val yl = corr(lag - 1)
        val yc = corr(lag)
        val yr = corr(lag + 1)
        val denom = yl - 2 * yc + yr
        val off = if (abs(denom) < 1e-9f) 0f else 0.5f * (yl - yr) / denom
        return lag + off.coerceIn(-1f, 1f)
    }

    /**
     * Otsu's threshold from a 256-bin histogram: the level that best splits the pixels
     * into two populations.
     *
     * Here rather than in one reader because two of them need exactly this and neither
     * can hardcode a level. A puzzle on paper and a lit panel in a dark room have
     * nothing in common except that each has ink and ground, and Otsu is the statement
     * of that and nothing more.
     */
    fun otsuThreshold(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 128
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /** Mean and standard deviation of a rectangular region; used for ink/paper decisions. */
    fun regionStats(src: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        val xa = max(0, x0); val ya = max(0, y0)
        val xb = min(src.width, x1); val yb = min(src.height, y1)
        for (y in ya until yb) {
            val row = y * src.width
            for (x in xa until xb) {
                val v = (src.data[row + x].toInt() and 0xFF).toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        if (n == 0) return floatArrayOf(0f, 0f)
        val mean = sum / n
        val varr = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return floatArrayOf(mean.toFloat(), sqrt(varr).toFloat())
    }
}
