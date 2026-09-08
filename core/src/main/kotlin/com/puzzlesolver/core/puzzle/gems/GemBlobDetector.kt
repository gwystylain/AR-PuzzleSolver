package com.puzzlesolver.core.puzzle.gems

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Finds the gems in a camera frame, with no geometry and no world at all.
 *
 * This is the half of Gems that replaced the AR pipeline. The canvas approach fits a
 * wall, unwraps it to a metric raster, and finds a lattice on it — all of which needs
 * a camera pose, and a pose means ARCore, and ARCore means no exposure control, and no
 * exposure control means the gem rings are not in the image at all. See
 * docs/CAMERA_CONTROL.md for how that was established.
 *
 * Dropping the pose turned out to cost less than it sounds, because **matching gems
 * never needed one**. Whether a gem matches depends on that gem alone, and a highlight
 * only has to land where the gem is on screen right now. So this works frame by frame in
 * plain image coordinates: find the bright discs, and let [GemScanner] read and match
 * them where they lie.
 *
 * Two things make that as reliable as the lattice version, not less:
 *
 *  - **Scale comes from the spacing, not from the discs.** The ring radii are fixed
 *    fractions of the button pitch, and the pitch is recoverable without a lattice fit:
 *    it is the median distance from a gem to its nearest neighbour. That is the same
 *    quantity the ring constants were measured against, so they transfer exactly. The
 *    apparent *size* of a lit disc would not have — it grows and shrinks with exposure.
 *  - **The wall being off-axis stops mattering.** A rectified canvas had to be right
 *    before anything could be read. Here a gem twenty degrees off-square is still a
 *    roughly circular blob with its rings still concentric, and it reads.
 */
class GemBlobDetector(private val cfg: Config = Config()) {

    data class Config(
        /**
         * Longest side of the image the search actually runs on.
         *
         * Blob finding is a blur plus connected components, both linear in area, and at
         * 1080p that is two million pixels of work per frame for discs tens of pixels
         * across. Nothing is lost by looking for them small and reading them large.
         */
        val maxWorkingDimension: Int = 480,
        /** How far above the local background a pixel must sit to count as gem. */
        val brightnessBias: Int = 12,
        /** Absolute floor, in working-resolution pixels, to keep speckle out. */
        val noiseFloorArea: Int = 12,
        /**
         * Kept blob area as a multiple of the median.
         *
         * Every gem on the wall is the same size, so the median area *is* a gem, and
         * anything far off it is glare, a reflection, or two gems merged. Bounds this
         * wide still throw all three out.
         */
        val minAreaRatio: Float = 0.30f,
        val maxAreaRatio: Float = 2.60f,
        /**
         * Area as a fraction of the bounding box's inscribed circle. A gem is a disc;
         * a smear of glare down the panel is not.
         */
        val minCircularity: Float = 0.55f,
        /** Bounding box squareness, as the smaller side over the larger. */
        val minSquareness: Float = 0.62f,
        val maxBlobs: Int = 128,
    )

    /** A gem as found in the frame, in full-resolution image pixels. */
    class Blob(
        @JvmField val x: Float,
        @JvmField val y: Float,
        /** Radius of the lit area, which is *not* the gem's physical radius. */
        @JvmField val radius: Float,
    )

    /** Why the last pass found nothing, for the HUD. */
    var lastReport: String = "not run"
        private set

    /**
     * Microseconds spent in each stage of the last pass: downsample, blur, threshold and
     * connected components, filter.
     *
     * Here rather than in a benchmark harness because the interesting number is the one
     * from the device, on a real frame, with whatever else the app is doing at the time.
     * Four counters cost nothing and turn "the scan is sometimes slow" into a stage.
     */
    @JvmField
    val stageMicros = LongArray(4)

    private var workBuf: GrayImage? = null
    private var bgBuf: GrayImage? = null
    private var scratchBuf: GrayImage? = null
    private var maskBuf: GrayImage? = null
    private var stack = IntArray(0)

    private fun buffer(current: GrayImage?, w: Int, h: Int): GrayImage =
        if (current != null && current.width == w && current.height == h) current else GrayImage(w, h)

    fun detect(luma: GrayImage): List<Blob> {
        val step = ((maxOf(luma.width, luma.height) + cfg.maxWorkingDimension - 1) /
            cfg.maxWorkingDimension).coerceAtLeast(1)
        val w = luma.width / step
        val h = luma.height / step
        if (w < 32 || h < 32) {
            lastReport = "frame is only ${luma.width}x${luma.height}"
            return emptyList()
        }

        var mark = System.nanoTime()
        val work = downsample(luma, w, h, step)
        stageMicros[0] = (System.nanoTime() - mark) / 1000

        val bg = buffer(bgBuf, w, h).also { bgBuf = it }
        val tmp = buffer(scratchBuf, w, h).also { scratchBuf = it }
        val mask = buffer(maskBuf, w, h).also { maskBuf = it }

        // Wide enough that a gem cannot form its own background and erase itself, which
        // is the failure that makes a detector find only the edges of things.
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
            lastReport = "nothing bright enough to be a gem"
            return emptyList()
        }

        val areas = blobs.map { it.area }.sorted()
        val medianArea = areas[areas.size / 2]
        val lo = medianArea * cfg.minAreaRatio
        val hi = medianArea * cfg.maxAreaRatio

        val kept = ArrayList<Blob>(blobs.size)
        for (b in blobs) {
            if (b.area < lo || b.area > hi) continue
            val longSide = maxOf(b.width, b.height).toFloat()
            val shortSide = minOf(b.width, b.height).toFloat()
            if (longSide <= 0f || shortSide / longSide < cfg.minSquareness) continue
            val inscribed = Math.PI.toFloat() * (longSide / 2f) * (longSide / 2f)
            if (b.area / inscribed < cfg.minCircularity) continue
            kept.add(
                Blob(
                    x = b.cx * step,
                    y = b.cy * step,
                    // From the area rather than the bounding box: a gem clipped by the
                    // frame edge has a truncated box but its area still says how big it
                    // was going to be.
                    radius = sqrt(b.area / Math.PI).toFloat() * step,
                )
            )
            if (kept.size >= cfg.maxBlobs) break
        }
        stageMicros[3] = (System.nanoTime() - mark) / 1000
        lastReport = "${kept.size} gems of ${blobs.size} blobs, median area $medianArea"
        return kept
    }

    /**
     * Median distance from a gem to its nearest neighbour: the button pitch, in pixels.
     *
     * This is what makes the ring constants in [GemPalette] usable without a lattice.
     * They are fractions of the pitch, the pitch is a physical property of the wall, and
     * nearest-neighbour spacing recovers it directly from any handful of gems.
     *
     * Median rather than mean because a few blobs are always spurious, and because gems
     * at the edge of the frame have neighbours outside it.
     *
     * @return the pitch, or -1 when too few gems are in frame to be sure of it.
     */
    fun estimatePitch(blobs: List<Blob>, minGems: Int = MIN_GEMS_FOR_PITCH): Float {
        if (blobs.size < minGems) return -1f
        val nearest = FloatArray(blobs.size)
        for (i in blobs.indices) {
            var best = Float.MAX_VALUE
            for (j in blobs.indices) {
                if (i == j) continue
                val d = hypot(blobs[i].x - blobs[j].x, blobs[i].y - blobs[j].y)
                if (d < best) best = d
            }
            nearest[i] = best
        }
        nearest.sort()
        return nearest[nearest.size / 2]
    }

    /**
     * How irregularly the blobs are spaced: the interquartile range of the
     * nearest-neighbour distances over their median.
     *
     * This is what says whether the detector has found a *wall* or just found things.
     * Gems sit on a lattice, so their nearest-neighbour distances all agree to within
     * the perspective across the frame; anything scattered at random does not, and the
     * two are nowhere near each other. Measured on eleven frames off the real wall the
     * figure runs 0.06 to 0.14, and on random points at the same density it runs 0.51
     * to 0.85.
     *
     * It earns its place because of what a black frame does. At 1/250 s in a dark room
     * sensor noise clears the local-background threshold often enough to yield twenty
     * or thirty blobs, a third of them bright enough to read, and every stage downstream
     * treats them as gems: a pitch is computed, rings are sampled, and an exposure hint
     * is published from a frame with nothing in it. The pitch is the giveaway -- 82 px
     * one frame and 186 px the next -- and this is that giveaway as a number. Neither
     * blob count nor frame brightness separates the two cases; noise reaches thirty
     * blobs and a wall two stops under is nearly as dark as an empty room.
     *
     * @return the spread, or -1 when there are too few blobs to say.
     */
    fun latticeSpread(blobs: List<Blob>, minGems: Int = MIN_GEMS_FOR_PITCH): Float {
        if (blobs.size < minGems) return -1f
        val nearest = FloatArray(blobs.size)
        for (i in blobs.indices) {
            var best = Float.MAX_VALUE
            for (j in blobs.indices) {
                if (i == j) continue
                val d = hypot(blobs[i].x - blobs[j].x, blobs[i].y - blobs[j].y)
                if (d < best) best = d
            }
            nearest[i] = best
        }
        nearest.sort()
        val median = nearest[nearest.size / 2]
        if (median <= 0f) return -1f
        val q1 = nearest[nearest.size / 4]
        val q3 = nearest[(nearest.size * 3) / 4]
        return (q3 - q1) / median
    }

    /**
     * The pitch *at each gem*, rather than one number for the whole frame.
     *
     * The wall is curved and the camera is never square to it, so the spacing is not
     * constant across a frame: on a run off the real wall the nearest-neighbour distance
     * ranged 117 to 161 px while [estimatePitch] returned a single 132. Every ring radius
     * is a fraction of the pitch, so a gem 20% wider-spaced than the median had all three
     * of its bands 20% too far in -- on one measured gem the outer ring sat at 0.245 of
     * the global pitch, outside the 0.175-0.230 band entirely, and the band it landed in
     * instead was filled with the middle ring's wash.
     *
     * **The statistic has to be the same one [estimatePitch] uses**, or the ring
     * constants stop meaning what they were fitted to mean. They were calibrated against
     * a median of *nearest-neighbour* distances, and a nearest-neighbour distance on an
     * anisotropic lattice measures the shorter of the two axes. Taking, say, the median
     * of each gem's three nearest instead measures something between the two axes, which
     * is a systematic 2-6% larger -- enough, measured on the labelled fixture, to turn
     * three correct rings into misreads. So this smooths each gem's *own* nearest
     * neighbour distance over its neighbourhood: locally adaptive, same ruler.
     *
     * Clamped either side of the global pitch because a pair of spurious blobs sitting
     * on top of each other would otherwise hand their neighbours a pitch near zero, and
     * a band of radius zero reads the panel.
     *
     * @return one pitch per blob, in the same order, or null when there are too few
     *         gems for [estimatePitch] to have an opinion in the first place.
     */
    fun localPitches(blobs: List<Blob>, globalPitch: Float): FloatArray? {
        if (globalPitch <= 0f || blobs.size < MIN_GEMS_FOR_PITCH) return null
        val n = blobs.size
        val own = FloatArray(n)
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (j in 0 until n) {
                if (i == j) continue
                val d = hypot(blobs[i].x - blobs[j].x, blobs[i].y - blobs[j].y)
                if (d < best) best = d
            }
            own[i] = best
        }

        val lo = globalPitch * MIN_LOCAL_PITCH_RATIO
        val hi = globalPitch * MAX_LOCAL_PITCH_RATIO
        val out = FloatArray(n)
        // The gem itself plus its neighbours, so the median is over an odd, small pool
        // that is still entirely local. Insertion into a fixed array rather than a sort
        // per gem: this runs on every frame and n is up to maxBlobs.
        val pool = FloatArray(LOCAL_PITCH_NEIGHBOURS + 1)
        val poolD = FloatArray(LOCAL_PITCH_NEIGHBOURS + 1)
        val sorted = FloatArray(LOCAL_PITCH_NEIGHBOURS + 1)
        for (i in 0 until n) {
            var filled = 1
            pool[0] = own[i]
            poolD[0] = -1f                              // the gem itself, nearest of all
            for (j in 0 until n) {
                if (i == j) continue
                val d = hypot(blobs[i].x - blobs[j].x, blobs[i].y - blobs[j].y)
                if (filled == pool.size && d >= poolD[filled - 1]) continue
                var k = if (filled == pool.size) filled - 1 else filled++
                while (k > 0 && poolD[k - 1] > d) {
                    poolD[k] = poolD[k - 1]
                    pool[k] = pool[k - 1]
                    k--
                }
                poolD[k] = d
                pool[k] = own[j]
            }
            // Median of the pool by value, which is a different order from the distance
            // order it was gathered in.
            System.arraycopy(pool, 0, sorted, 0, filled)
            java.util.Arrays.sort(sorted, 0, filled)
            out[i] = sorted[filled / 2].coerceIn(lo, hi)
        }
        return out
    }

    /** Max-pool, because the target is a small bright disc on a dark panel. */
    private fun downsample(src: GrayImage, w: Int, h: Int, step: Int): GrayImage {
        val dst = buffer(workBuf, w, h).also { workBuf = it }
        for (y in 0 until h) {
            val sy = y * step
            for (x in 0 until w) {
                val sx = x * step
                var best = 0
                for (j in 0 until step) {
                    val py = sy + j
                    if (py >= src.height) break
                    val row = py * src.width
                    for (i in 0 until step) {
                        val px = sx + i
                        if (px >= src.width) break
                        val v = src.data[row + px].toInt() and 0xFF
                        if (v > best) best = v
                    }
                }
                dst.data[y * w + x] = best.toByte()
            }
        }
        return dst
    }

    private class Component(
        val cx: Float,
        val cy: Float,
        val area: Int,
        val width: Int,
        val height: Int,
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
            var sx = 0.0
            var sy = 0.0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                count++
                sx += px
                sy += py
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
            out.add(
                Component(
                    cx = (sx / count).toFloat(),
                    cy = (sy / count).toFloat(),
                    area = count,
                    width = maxX - minX + 1,
                    height = maxY - minY + 1,
                )
            )
        }
        return out
    }

    companion object {
        /**
         * Gems needed in frame before the pitch estimate is trusted.
         *
         * With fewer than this the median is one or two measurements, and a pitch that
         * is wrong by a third puts every ring band on the wrong part of the gem. Better
         * to tell the user to bring more of the wall into frame than to read it wrong.
         */
        const val MIN_GEMS_FOR_PITCH = 6

        /**
         * Gems either side of one gem that its local pitch is smoothed over.
         *
         * Four neighbours plus the gem itself is five samples: enough that a single
         * spurious blob cannot carry the median, few enough that the pool is still one
         * neighbourhood on a wall whose spacing changes across the frame.
         */
        const val LOCAL_PITCH_NEIGHBOURS = 4

        /**
         * How far a gem's local pitch may sit from the frame's median before it is
         * treated as a measurement failure rather than as perspective.
         *
         * Perspective across one frame of this wall moves the spacing by about a
         * quarter. Anything beyond these is two blobs found where there is one gem, or
         * one found where there are two, and the global pitch is the better guess.
         */
        const val MIN_LOCAL_PITCH_RATIO = 0.6f
        const val MAX_LOCAL_PITCH_RATIO = 1.6f

        /**
         * Above this [latticeSpread], the blobs are not a lattice and this is not the
         * wall. Sits in the middle of a fourfold gap: 0.06 to 0.14 measured on the real
         * wall, 0.51 to 0.85 on random points at the same density.
         */
        const val MAX_LATTICE_SPREAD = 0.30f

        private const val LIT: Byte = -1
        private const val VISITED: Byte = 1
    }
}
