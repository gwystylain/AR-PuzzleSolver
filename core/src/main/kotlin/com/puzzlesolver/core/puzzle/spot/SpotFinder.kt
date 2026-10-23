package com.puzzlesolver.core.puzzle.spot

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spot the difference, across a run of photos of the same screen.
 *
 * The room shows one picture after another, each the same scene with one thing changed --
 * something added, taken away or recoloured -- and a different thing in each. Photograph
 * them all and every pixel of the scene is the original in all but at most one photo. So
 * the per-pixel **median** of the photos is the original picture, without anyone having
 * to photograph it, and what each photo has that the median does not is its difference.
 * That holds from three photos up, and for a photo that turns out to be the original
 * itself, which simply has nothing to box.
 *
 * Everything else here is getting the photos to agree about where each pixel is and how
 * bright it is, because a median of misaligned photos is a blur and every edge in the
 * scene would come up as a difference:
 *
 *  1. **Find the screen** in each photo ([ScreenLocator]) and straighten it to a common
 *     rectangle. Only a rough start, and treated as one: the bezel stands proud of the
 *     screen, so its edge slides against the picture from one viewpoint to the next, and
 *     on the phone the outline can come back round the neon instead of the screen.
 *  2. **Line the pictures up on their content.** A coarse search over scale and shift
 *     first, then a grid of patches found by normalised cross-correlation at three
 *     resolutions, each correction the homography most patches agree on. The rectangle is
 *     then the median of every photo's outline, so a wrong one is outvoted, and the
 *     patches are matched once more against the median of all the photos. See [align].
 *  3. **Even out the exposure**, per colour channel, as a gain and offset fitted to the
 *     median with outliers trimmed. The camera's exposure can move between photos and
 *     the photos are taken a few seconds apart by someone who has just pressed a button.
 *  4. **Compare**: each photo against the median of all of them, pixel by pixel, taking
 *     the best match within two pixels so that what alignment left behind is forgiven --
 *     both ways round, so that a small thing taken away is not forgiven with it.
 *  5. **Box** what is left: everything well above the photo's own noise, pieces within a
 *     few pixels of each other merged, the strongest kept, and any others kept only if
 *     they are a good fraction as strong.
 *
 * Measured on four rounds -- two shot in the room on the phone's camera app, two taken
 * with this app off a monitor -- every photo comes back with exactly one box, on the
 * thing that changed, down to a row of windows three pixels wide.
 */
class SpotFinder(
    private val locator: ScreenLocator = ScreenLocator(),
    /** One line per step of the alignment, for the app's log and the replay tool. */
    private val log: (String) -> Unit = {},
) {

    /** One thing that differs, as fractions of the straightened screen, left to right and top to bottom. */
    class Difference(
        @JvmField val left: Float,
        @JvmField val top: Float,
        @JvmField val right: Float,
        @JvmField val bottom: Float,
        /** How much it differs, summed over its area; only meaningful against another's. */
        @JvmField val score: Float,
    )

    class Photo(
        /** Position in the list handed to [find]. */
        @JvmField val index: Int,
        /** The screen, straightened. Null when it could not be found in this photo. */
        @JvmField val screen: RgbImage?,
        /** Strongest first. Empty when this photo is the original picture, or was not used. */
        @JvmField val differences: List<Difference>,
    )

    class Result(
        @JvmField val photos: List<Photo>,
        /** One line on what happened, for the screen and the log. */
        @JvmField val status: String,
        @JvmField val millis: Long,
    )

    /**
     * @param screens the screen in each photo when the caller already found it, as the
     *        app does at the moment a photo is taken so that a bad one can be retaken
     *        there and then; null entries, or a null list, are located here.
     */
    fun find(photos: List<RgbImage>, screens: List<ScreenLocator.Screen?>? = null): Result {
        val started = System.nanoTime()
        val count = photos.size
        val lumas = photos.map { it.luma() }
        val located = Array(count) { k -> screens?.getOrNull(k) ?: locator.locate(lumas[k]) }
        val used = (0 until count).filter { located[it] != null }
        for (k in 0 until count) {
            log("photo ${k + 1}: " + (located[k]?.corners?.joinToString(" ") { "%.0f".format(it) }?.let { "screen at $it" } ?: "no screen"))
        }

        val aspect = used.map { located[it]!!.aspect }.sorted()
            .let { if (it.isEmpty()) DEFAULT_ASPECT else it[it.size / 2] }
            .coerceIn(MIN_ASPECT, MAX_ASPECT)
        val w = ANALYSIS_WIDTH
        val h0 = (w / aspect).roundToInt()
        val homs = arrayOfNulls<Homography>(count)
        val canon = rectangle(w, h0)
        for (k in used) {
            val c = located[k]!!.corners
            homs[k] = Homography.fit(canon, DoubleArray(8) { c[it].toDouble() })
        }
        val aligned = used.filter { homs[it] != null }

        if (aligned.size < MIN_PHOTOS) {
            val missing = (0 until count).filter { homs[it] == null }.map { it + 1 }
            return Result(
                photos = (0 until count).map { k -> Photo(k, homs[k]?.let { display(photos[k], it, w, h0) }, emptyList()) },
                status = "Need the screen in at least $MIN_PHOTOS photos to compare them; " +
                    "not found in photo${if (missing.size == 1) "" else "s"} ${missing.joinToString()}",
                millis = (System.nanoTime() - started) / 1_000_000,
            )
        }

        // The rectangle the photos are compared in is settled by alignment, by majority: it
        // can come back a different shape from the one the outlines first suggested.
        val h = align(aligned, lumas, located, homs, w, h0)

        // Final images, and the part of the rectangle that is screen in every photo, pulled
        // in from its edge: the bezel's parallax lives there.
        val n = w * h
        val planes = HashMap<Int, Array<FloatArray>>()
        val mask = BooleanArray(n) { true }
        for (k in aligned) {
            planes[k] = warpRgb(photos[k], homs[k]!!, w, h)
            andScreenMask(mask, located[k]!!, homs[k]!!, w, h)
        }
        erode(mask, w, h, (w * MARGIN_FRACTION).roundToInt())
        if (mask.count { it } < n * MIN_MASK_FRACTION) {
            return Result(
                photos = (0 until count).map { k -> Photo(k, homs[k]?.let { display(photos[k], it, w, h) }, emptyList()) },
                status = "The photos do not show enough of the same screen to compare",
                millis = (System.nanoTime() - started) / 1_000_000,
            )
        }

        val stacks = aligned.map { planes.getValue(it) }
        val median0 = Array(3) { c -> medianOf(stacks.map { it[c] }, n) }
        for (p in stacks) for (c in 0 until 3) normalise(p[c], median0[c], mask, w, h)
        for (p in stacks) for (c in 0 until 3) boxBlur(p[c], w, h)
        val median = Array(3) { c -> medianOf(stacks.map { it[c] }, n) }

        val differences = HashMap<Int, List<Difference>>()
        val diff = FloatArray(n)
        val back = FloatArray(n)
        for ((i, k) in aligned.withIndex()) {
            difference(stacks[i], median, mask, w, h, diff, back)
            differences[k] = blobs(diff, mask, w, h)
        }

        val found = differences.values.count { it.isNotEmpty() }
        val skipped = (0 until count).filter { homs[it] == null }.map { it + 1 }
        val status = buildString {
            append("${aligned.size} photos compared; a difference in $found")
            if (skipped.isNotEmpty()) append("; screen not found in photo ${skipped.joinToString()}")
        }
        return Result(
            photos = (0 until count).map { k ->
                Photo(k, homs[k]?.let { display(photos[k], it, w, h) }, differences[k] ?: emptyList())
            },
            status = status,
            millis = (System.nanoTime() - started) / 1_000_000,
        )
    }

    /**
     * Straightens one photo's screen on its outline alone, for a thumbnail taken before
     * the rest of the set exists to line it up against.
     */
    fun straighten(photo: RgbImage, screen: ScreenLocator.Screen, width: Int): RgbImage {
        val height = (width / screen.aspect.coerceIn(MIN_ASPECT, MAX_ASPECT)).roundToInt()
        val c = screen.corners
        val canon = doubleArrayOf(0.0, 0.0, width.toDouble(), 0.0, width.toDouble(), height.toDouble(), 0.0, height.toDouble())
        val hom = Homography.fit(canon, DoubleArray(8) { c[it].toDouble() }) ?: Homography.IDENTITY
        return warpRgbImage(photo, hom, width, height)
    }

    // --- Alignment -------------------------------------------------------

    /**
     * Lines every photo up with the others, in place in [homs], and returns the height of
     * the rectangle they are now all mapped to.
     *
     * Built for outlines that are wrong, because on the phone they were: the first round
     * taken with the app put two of six outlines round the neon instead of the screen, and
     * a third out by twenty pixels on one side. So no photo's outline is trusted beyond
     * getting it roughly the right way up:
     *
     *  1. Each photo is first found in the reference by a coarse search over scale and
     *     shift, at an eighth of the resolution, which is what brings an outline a third too
     *     big back into range.
     *  2. Then by patches, at a quarter, half and full resolution, with each correction the
     *     homography that the most patches agree on (RANSAC). Not a least-squares fit with
     *     the worst trimmed: when one side of a photo is out and the rest is not, the
     *     patches on that side are a minority, and trimming throws away exactly the ones
     *     that would have fixed it.
     *  3. With every photo in the reference's frame, each one's outline is carried into it,
     *     and the rectangle used from then on is the median of them, corner by corner. A
     *     wrong outline is outvoted, including the reference's own.
     *  4. Once more by patches, against the median of all the photos.
     */
    private fun align(
        aligned: List<Int>,
        lumas: List<GrayImage>,
        located: Array<ScreenLocator.Screen?>,
        homs: Array<Homography?>,
        w: Int,
        h0: Int,
    ): Int {
        var h = h0
        var mask = BooleanArray(w * h) { true }
        for (k in aligned) andScreenMask(mask, located[k]!!, homs[k]!!, w, h)

        val reference = aligned.maxBy { located[it]!!.area }
        val first = warpLuma(lumas[reference], homs[reference]!!, w, h)
        val firstPyramid = Pyramid(first, mask, w, h)
        for (k in aligned) {
            if (k == reference) continue
            var hom = homs[k]!!
            globalSimilarity(firstPyramid, warpLuma(lumas[k], hom, w, h), w, h, k)?.let { hom = hom * it }
            homs[k] = refine(lumas[k], hom, firstPyramid, w, h, "photo ${k + 1} to photo ${reference + 1}")
        }

        // The rectangle, by majority.
        val xs = Array(8) { DoubleArray(aligned.size) }
        val p = DoubleArray(2)
        for ((i, k) in aligned.withIndex()) {
            val back = Homography.invert(homs[k]!!) ?: continue
            val c = located[k]!!.corners
            for (j in 0 until 4) {
                back.map(c[j * 2].toDouble(), c[j * 2 + 1].toDouble(), p)
                xs[j * 2][i] = p[0]
                xs[j * 2 + 1][i] = p[1]
            }
        }
        val consensus = DoubleArray(8) { j -> median(xs[j]) }
        fun side(a: Int, b: Int) = kotlin.math.hypot(consensus[a * 2] - consensus[b * 2], consensus[a * 2 + 1] - consensus[b * 2 + 1])
        val aspect = ((side(0, 1) + side(3, 2)) / (side(0, 3) + side(1, 2))).coerceIn(MIN_ASPECT, MAX_ASPECT)
        val reframe = Homography.fit(rectangle(w, (w / aspect).roundToInt()), consensus)
        if (reframe != null) {
            h = (w / aspect).roundToInt()
            for (k in aligned) homs[k] = homs[k]!! * reframe
            mask = BooleanArray(w * h) { true }
            for (k in aligned) andScreenMask(mask, located[k]!!, homs[k]!!, w, h)
        }

        val median = Pyramid(medianOf(aligned.map { warpLuma(lumas[it], homs[it]!!, w, h) }, w * h), mask, w, h)
        log("rectangle by majority: " + consensus.joinToString(" ") { "%.1f".format(it) } + " -> ${w}x$h")
        for (k in aligned) homs[k] = refine(lumas[k], homs[k]!!, median, w, h, "photo ${k + 1} to the median")
        return h
    }

    /** A reference image and its mask at full, half, quarter and eighth resolution. */
    private inner class Pyramid(full: FloatArray, mask: BooleanArray, w: Int, h: Int) {
        val images = ArrayList<FloatArray>()
        val masks = ArrayList<BooleanArray>()
        val widths = ArrayList<Int>()
        val heights = ArrayList<Int>()

        init {
            var image = full
            var m = mask
            var iw = w
            var ih = h
            for (level in 0 until 4) {
                images.add(image); masks.add(m); widths.add(iw); heights.add(ih)
                image = decimate(image, iw, ih)
                m = decimateMask(m, iw, ih)
                iw /= 2
                ih /= 2
            }
        }

        /** Index of the level at [factor] times smaller: 1, 2, 4 or 8. */
        fun at(factor: Int) = Integer.numberOfTrailingZeros(factor)
    }

    /** Patch corrections at a quarter, half and full resolution, in that order. */
    private fun refine(luma: GrayImage, start: Homography, ref: Pyramid, w: Int, h: Int, label: String): Homography {
        var hom = start
        val matches = Matches(GRID_X * GRID_Y)
        for (level in LEVELS) {
            val i = ref.at(level.factor)
            var mov = warpLuma(luma, hom, w, h)
            var mw = w
            var mh = h
            repeat(i) {
                mov = decimate(mov, mw, mh)
                mw /= 2
                mh /= 2
            }
            matchPatches(ref.images[i], mov, mw, mh, ref.masks[i], level.patch, level.search, level.factor, matches)
            val correction = fitMatches(matches, w, h, MATCH_TOLERANCE * level.factor)
            log("$label at 1/${level.factor}: ${matches.count} matches, ${lastAgreed} agree" + if (correction == null) ", no correction" else "")
            if (correction != null) hom = hom * correction
        }
        return hom
    }

    /**
     * The scale about the centre and the shift that best put the middle of [ref] onto
     * [mov], searched exhaustively at an eighth of the resolution: scale from 0.7 to 1/0.7
     * and shift up to 15% of the width either way. Null when nothing correlates well.
     *
     * As a map from the reference's rectangle into the photo's, so it composes onto the
     * photo's homography like any other correction.
     */
    private fun globalSimilarity(ref: Pyramid, movFull: FloatArray, w: Int, h: Int, k: Int): Homography? {
        val i = ref.at(GLOBAL_FACTOR)
        val r = ref.images[i]
        val mask = ref.masks[i]
        val dw = ref.widths[i]
        val dh = ref.heights[i]
        var mov = movFull
        var mw = w
        var mh = h
        repeat(i) {
            mov = decimate(mov, mw, mh)
            mw /= 2
            mh /= 2
        }
        // The template: the middle half of the reference each way, screen pixels only,
        // zero-mean. Half, not more, so that it stays inside the photo across the whole
        // search: at 1.4 times and shifted 15%, a template reaching past the middle half
        // runs off the edge, and every candidate near the right answer is skipped. That
        // is exactly the case the search is for -- one outline round the neon, the other
        // round the screen, a scale of 1.27 between them.
        val x0 = (dw * (0.5 - GLOBAL_TEMPLATE / 2)).toInt()
        val x1 = (dw * (0.5 + GLOBAL_TEMPLATE / 2)).toInt()
        val y0 = (dh * (0.5 - GLOBAL_TEMPLATE / 2)).toInt()
        val y1 = (dh * (0.5 + GLOBAL_TEMPLATE / 2)).toInt()
        val tx = ArrayList<Int>()
        val ty = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) if (mask[y * dw + x]) { tx.add(x); ty.add(y) }
        val m = tx.size
        if (m < 48) return null
        val a = DoubleArray(m) { r[ty[it] * dw + tx[it]].toDouble() }
        val mean = a.average()
        var na = 0.0
        for (j in 0 until m) { a[j] -= mean; na += a[j] * a[j] }
        na = sqrt(na)
        if (na < 1e-6) return null

        val shift = (dw * GLOBAL_SHIFT).roundToInt()
        val gw = x1 - x0 + 2 * shift
        val gh = y1 - y0 + 2 * shift
        val grid = FloatArray(gw * gh)
        val cx = dw / 2.0
        val cy = dh / 2.0
        var best = -2.0
        var bestScale = 1.0
        var bestDx = 0
        var bestDy = 0
        for (step in 0 until GLOBAL_SCALES) {
            val s = Math.pow(GLOBAL_MIN_SCALE, 1.0 - 2.0 * step / (GLOBAL_SCALES - 1))
            // The photo resampled at this scale over the template's area and every shift.
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val u = cx + s * (x0 - shift + gx + 0.5 - cx) - 0.5
                    val v = cy + s * (y0 - shift + gy + 0.5 - cy) - 0.5
                    grid[gy * gw + gx] = if (u < 0 || v < 0 || u > mw - 1.001 || v > mh - 1.001) {
                        Float.NaN
                    } else {
                        val ix = u.toInt()
                        val iy = v.toInt()
                        val fx = (u - ix).toFloat()
                        val fy = (v - iy).toFloat()
                        val q = iy * mw + ix
                        (mov[q] * (1 - fx) + mov[q + 1] * fx) * (1 - fy) + (mov[q + mw] * (1 - fx) + mov[q + mw + 1] * fx) * fy
                    }
                }
            }
            for (dy in -shift..shift) {
                for (dx in -shift..shift) {
                    var sb = 0.0
                    var sbb = 0.0
                    var sab = 0.0
                    var outside = false
                    for (j in 0 until m) {
                        val b = grid[(ty[j] - y0 + shift + dy) * gw + (tx[j] - x0 + shift + dx)]
                        if (b.isNaN()) { outside = true; break }
                        sb += b; sbb += b * b; sab += a[j] * b
                    }
                    if (outside) continue
                    val varB = sbb - sb * sb / m
                    if (varB <= 1e-6) continue
                    val score = sab / (na * sqrt(varB))
                    if (score > best) {
                        best = score
                        bestScale = s
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }
        log("photo ${k + 1}: coarse search %.3f at scale %.3f, shift %d,%d".format(best, bestScale, bestDx, bestDy))
        if (best < GLOBAL_MIN_CORRELATION) return null
        // In the decimated frame a reference point p lands at c + s * (p + d - c); scaled up,
        // c and p by the factor, and d with them.
        val f = GLOBAL_FACTOR.toDouble()
        val c0 = cx * f
        val c1 = cy * f
        val s = bestScale
        return Homography(
            doubleArrayOf(
                s, 0.0, c0 - s * c0 + s * bestDx * f,
                0.0, s, c1 - s * c1 + s * bestDy * f,
                0.0, 0.0, 1.0,
            )
        )
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.copyOf()
        sorted.sort()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    private fun rectangle(w: Int, h: Int) =
        doubleArrayOf(0.0, 0.0, w.toDouble(), 0.0, w.toDouble(), h.toDouble(), 0.0, h.toDouble())

    private class Matches(capacity: Int) {
        val src = DoubleArray(capacity * 2)
        val dst = DoubleArray(capacity * 2)
        var count = 0
    }

    /**
     * Finds a grid of [patch]-square patches of [ref] in [mov], each within [search]
     * pixels of where it started, by zero-mean normalised cross-correlation -- which does
     * not care that one photo is brighter than the other. Matches are recorded at
     * [scale] times their coordinates, i.e. in full analysis pixels.
     */
    private fun matchPatches(
        ref: FloatArray,
        mov: FloatArray,
        w: Int,
        h: Int,
        mask: BooleanArray,
        patch: Int,
        search: Int,
        scale: Int,
        out: Matches,
    ) {
        out.count = 0
        val span = 2 * search + 1
        val scores = DoubleArray(span * span)
        val a0 = DoubleArray(patch * patch)
        val area = (patch * patch).toDouble()
        for (j in 0 until GRID_Y) {
            for (i in 0 until GRID_X) {
                val cx = ((i + 0.5) * w / GRID_X).toInt()
                val cy = ((j + 0.5) * h / GRID_Y).toInt()
                val x0 = cx - patch / 2
                val y0 = cy - patch / 2
                if (x0 - search < 0 || y0 - search < 0 || x0 + patch + search > w || y0 + patch + search > h) continue
                if (!allTrue(mask, w, x0, y0, patch)) continue
                var mean = 0.0
                for (py in 0 until patch) for (px in 0 until patch) mean += ref[(y0 + py) * w + x0 + px]
                mean /= area
                var na = 0.0
                for (py in 0 until patch) {
                    for (px in 0 until patch) {
                        val v = ref[(y0 + py) * w + x0 + px] - mean
                        a0[py * patch + px] = v
                        na += v * v
                    }
                }
                na = sqrt(na)
                // A patch of flat sky matches anywhere equally well, so it says nothing.
                if (na / patch < MIN_TEXTURE) continue
                var best = -2.0
                var bx = 0
                var by = 0
                for (sy in -search..search) {
                    for (sx in -search..search) {
                        var sb = 0.0
                        var sbb = 0.0
                        var sab = 0.0
                        for (py in 0 until patch) {
                            var m = (y0 + py + sy) * w + x0 + sx
                            var a = py * patch
                            for (px in 0 until patch) {
                                val b = mov[m].toDouble()
                                sb += b
                                sbb += b * b
                                sab += a0[a] * b
                                m++
                                a++
                            }
                        }
                        val varB = sbb - sb * sb / area
                        val score = if (varB <= 1e-6) -2.0 else sab / (na * sqrt(varB))
                        scores[(sy + search) * span + sx + search] = score
                        if (score > best) {
                            best = score
                            bx = sx
                            by = sy
                        }
                    }
                }
                if (best < MIN_CORRELATION) continue
                val fx = bx + parabolicPeak(scores, span, bx + search, by + search, 1)
                val fy = by + parabolicPeak(scores, span, bx + search, by + search, span)
                out.src[out.count * 2] = cx.toDouble() * scale
                out.src[out.count * 2 + 1] = cy.toDouble() * scale
                out.dst[out.count * 2] = (cx + fx) * scale
                out.dst[out.count * 2 + 1] = (cy + fy) * scale
                out.count++
            }
        }
    }

    private fun allTrue(mask: BooleanArray, w: Int, x0: Int, y0: Int, size: Int): Boolean {
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) if (!mask[y * w + x]) return false
        return true
    }

    /** Sub-sample offset of a peak from its neighbours [stride] apart either side. */
    private fun parabolicPeak(scores: DoubleArray, span: Int, ix: Int, iy: Int, stride: Int): Double {
        val along = if (stride == 1) ix else iy
        if (along <= 0 || along >= span - 1) return 0.0
        val c = iy * span + ix
        val l = scores[c - stride]
        val m = scores[c]
        val r = scores[c + stride]
        val d = l - 2 * m + r
        return if (d < 0) (0.5 * (l - r) / d).coerceIn(-0.5, 0.5) else 0.0
    }

    /**
     * The homography from reference to photo that the most matches agree on, to within
     * [tolerance] pixels: RANSAC over four-match samples, then refitted on everything that
     * agreed. Null when too few agree, or when what they agree on is too large a move to be
     * a correction rather than a mistake.
     *
     * The sampling is seeded, so the same photos always give the same answer.
     */
    /** How many matches agreed with the last [fitMatches], for the log. */
    private var lastAgreed = 0

    private fun fitMatches(matches: Matches, w: Int, h: Int, tolerance: Double): Homography? {
        val total = matches.count
        lastAgreed = 0
        if (total < MIN_MATCHES) return null
        val random = java.util.Random(RANSAC_SEED)
        val src = DoubleArray(8)
        val dst = DoubleArray(8)
        val pick = IntArray(4)
        val p = DoubleArray(2)
        var bestCount = 0
        var best = BooleanArray(total)
        val agree = BooleanArray(total)
        fun inliers(hom: Homography, out: BooleanArray): Int {
            var n = 0
            for (i in 0 until total) {
                hom.map(matches.src[i * 2], matches.src[i * 2 + 1], p)
                val dx = p[0] - matches.dst[i * 2]
                val dy = p[1] - matches.dst[i * 2 + 1]
                out[i] = dx * dx + dy * dy <= tolerance * tolerance
                if (out[i]) n++
            }
            return n
        }
        repeat(RANSAC_ITERATIONS) {
            for (j in 0 until 4) {
                var i: Int
                do { i = random.nextInt(total) } while ((0 until j).any { pick[it] == i })
                pick[j] = i
                src[j * 2] = matches.src[i * 2]; src[j * 2 + 1] = matches.src[i * 2 + 1]
                dst[j * 2] = matches.dst[i * 2]; dst[j * 2 + 1] = matches.dst[i * 2 + 1]
            }
            val hom = Homography.fit(src, dst, 4) ?: return@repeat
            val n = inliers(hom, agree)
            if (n > bestCount) {
                bestCount = n
                best = agree.copyOf()
            }
        }
        if (bestCount < MIN_MATCHES) return null
        var fitted: Homography? = null
        repeat(2) {
            val kept = best.count { it }
            if (kept < MIN_MATCHES) return null
            val ks = DoubleArray(kept * 2)
            val kd = DoubleArray(kept * 2)
            var j = 0
            for (i in 0 until total) {
                if (!best[i]) continue
                ks[j * 2] = matches.src[i * 2]; ks[j * 2 + 1] = matches.src[i * 2 + 1]
                kd[j * 2] = matches.dst[i * 2]; kd[j * 2 + 1] = matches.dst[i * 2 + 1]
                j++
            }
            val hom = Homography.fit(ks, kd, kept) ?: return null
            fitted = hom
            lastAgreed = inliers(hom, best)
        }
        val hom = fitted ?: return null
        // A correction, not a new answer: no corner of the rectangle may move further
        // than this, or the matches have locked onto something else.
        val limit = w * MAX_CORRECTION
        for (corner in 0 until 4) {
            val x = if (corner == 1 || corner == 2) w.toDouble() else 0.0
            val y = if (corner >= 2) h.toDouble() else 0.0
            hom.map(x, y, p)
            if (abs(p[0] - x) > limit || abs(p[1] - y) > limit) return null
        }
        return hom
    }

    // --- Comparison ------------------------------------------------------

    /**
     * Scales [channel] onto [target] as a gain and an offset, fitted on a sparse lattice of
     * screen pixels, refitted three times without the pixels furthest off -- which is
     * where this photo's difference is, and exactly what must not drag the fit.
     */
    private fun normalise(channel: FloatArray, target: FloatArray, mask: BooleanArray, w: Int, h: Int) {
        val xs = ArrayList<Float>()
        val ts = ArrayList<Float>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                if (mask[i]) {
                    xs.add(channel[i])
                    ts.add(target[i])
                }
                x += NORMALISE_STRIDE
            }
            y += NORMALISE_STRIDE
        }
        val m = xs.size
        if (m < 16) return
        val keep = BooleanArray(m) { true }
        val residual = FloatArray(m)
        var gain = 1.0
        var offset = 0.0
        repeat(3) {
            var sn = 0.0; var sx = 0.0; var st = 0.0; var sxx = 0.0; var sxt = 0.0
            for (i in 0 until m) {
                if (!keep[i]) continue
                val x = xs[i].toDouble()
                val t = ts[i].toDouble()
                sn++; sx += x; st += t; sxx += x * x; sxt += x * t
            }
            val den = sn * sxx - sx * sx
            if (sn < 16 || abs(den) < 1e-9) return@repeat
            gain = (sn * sxt - sx * st) / den
            offset = (st - gain * sx) / sn
            for (i in 0 until m) residual[i] = abs((gain * xs[i] + offset - ts[i]).toFloat())
            val sorted = residual.copyOf()
            sorted.sort()
            val limit = max(3f * sorted[m / 2], 2f)
            for (i in 0 until m) keep[i] = residual[i] < limit
        }
        val g = gain.toFloat()
        val o = offset.toFloat()
        for (i in channel.indices) channel[i] = g * channel[i] + o
    }

    /**
     * How far each pixel of [photo] is from the median, as the summed absolute difference
     * of its three channels, forgiving misalignment of up to [SHIFT_TOLERANCE] pixels --
     * both ways round. Zero off the mask.
     *
     * Both ways, because one way only forgives in one direction. Matching each photo pixel
     * to the best median pixel near it catches anything the photo has that the median
     * does not, but a small thing the photo has *lost* disappears: the photo's plain wall
     * finds plain wall in the median a pixel or two from the missing detail. That is how
     * a row of five windows, each three pixels wide, went missing from a castle and the
     * photo came back as unchanged -- in all three rounds that had it. Matching each median
     * pixel to the best photo pixel near it catches exactly that, so the difference at a
     * pixel is the larger of the two. An edge both have is still forgiven either way.
     */
    private fun difference(
        photo: Array<FloatArray>,
        median: Array<FloatArray>,
        mask: BooleanArray,
        w: Int,
        h: Int,
        out: FloatArray,
        back: FloatArray,
    ) {
        java.util.Arrays.fill(out, Float.MAX_VALUE)
        java.util.Arrays.fill(back, Float.MAX_VALUE)
        val (pr, pg, pb) = photo
        val (mr, mg, mb) = median
        for (dy in -SHIFT_TOLERANCE..SHIFT_TOLERANCE) {
            for (dx in -SHIFT_TOLERANCE..SHIFT_TOLERANCE) {
                for (y in 0 until h) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (!mask[i]) continue
                        val j = sy * w + (x + dx).coerceIn(0, w - 1)
                        // The photo pixel against a median pixel nearby, and the median
                        // pixel against a photo pixel nearby.
                        val d = abs(pr[i] - mr[j]) + abs(pg[i] - mg[j]) + abs(pb[i] - mb[j])
                        if (d < out[i]) out[i] = d
                        val e = abs(pr[j] - mr[i]) + abs(pg[j] - mg[i]) + abs(pb[j] - mb[i])
                        if (e < back[i]) back[i] = e
                    }
                }
            }
        }
        for (i in out.indices) out[i] = if (mask[i]) max(out[i], back[i]) else 0f
    }

    /**
     * The differences in one photo's difference map.
     *
     * The threshold is the photo's own noise -- its median difference plus ten median
     * absolute deviations -- but never under [ABSOLUTE_THRESHOLD]. The sample sets put the
     * changed objects at 200 to 500 and alignment residue under 60, the far, soft photo of
     * the first set worst at 40; the unchanged photo of that set has nothing over 70.
     */
    private fun blobs(diff: FloatArray, mask: BooleanArray, w: Int, h: Int): List<Difference> {
        val n = w * h
        val histogram = IntArray(HISTOGRAM_BINS)
        var total = 0
        for (i in 0 until n) {
            if (!mask[i]) continue
            histogram[diff[i].toInt().coerceIn(0, HISTOGRAM_BINS - 1)]++
            total++
        }
        if (total == 0) return emptyList()
        val med = percentile(histogram, total, 0.5f)
        val deviation = IntArray(HISTOGRAM_BINS)
        for (i in 0 until n) {
            if (!mask[i]) continue
            deviation[abs(diff[i].toInt() - med).coerceIn(0, HISTOGRAM_BINS - 1)]++
        }
        val mad = percentile(deviation, total, 0.5f)
        val threshold = max(ABSOLUTE_THRESHOLD, med + NOISE_DEVIATIONS * mad).toFloat()

        val core = BooleanArray(n) { mask[it] && diff[it] > threshold }
        // Pieces of one thing -- a crescent and its star, the two halves of a sail either
        // side of the mast -- are merged by growing each piece a little before labelling.
        val grown = core.copyOf()
        erodeOrDilate(grown, w, h, MERGE_DISTANCE, dilate = true)
        // What is near the edge of the compared area, for telling a difference from the
        // bezel sliding over the picture.
        val inner = mask.copyOf()
        erode(inner, w, h, EDGE_BAND)

        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        val found = ArrayList<Difference>()
        for (start in 0 until n) {
            if (!grown[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            label[start] = found.size
            queue[tail++] = start
            var score = 0.0
            var area = 0
            var nearEdge = 0
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (core[i]) {
                    score += diff[i] - threshold
                    area++
                    if (!inner[i]) nearEdge++
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val j = ny * w + nx
                        if (grown[j] && label[j] < 0) {
                            label[j] = label[start]
                            queue[tail++] = j
                        }
                    }
                }
            }
            if (x1 < 0) continue
            // A strip along the edge of the compared area is the bezel's edge, seen from a
            // different place in this photo than in the others: parallax, not a difference.
            // Real ones near the edge -- the moon in the first set, a few pixels under the
            // top -- have at most two fifths of themselves there; the strips are three
            // quarters to all of it.
            if (nearEdge > area * EDGE_FRACTION) continue
            found.add(
                Difference(
                    left = x0.toFloat() / w,
                    top = y0.toFloat() / h,
                    right = (x1 + 1).toFloat() / w,
                    bottom = (y1 + 1).toFloat() / h,
                    score = score.toFloat(),
                )
            )
        }
        found.sortByDescending { it.score }
        val best = found.firstOrNull()?.score ?: return emptyList()
        val floor = max(MIN_SCORE, best * RELATIVE_SCORE)
        return found.filter { it.score >= floor }.take(MAX_DIFFERENCES)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val rank = (total * fraction).toInt()
        var acc = 0
        for (v in histogram.indices) {
            acc += histogram[v]
            if (acc > rank) return v
        }
        return histogram.size - 1
    }

    // --- Image plumbing --------------------------------------------------

    /** The straightened screen at [DISPLAY_WIDTH], for showing. */
    private fun display(photo: RgbImage, hom: Homography, w: Int, h: Int): RgbImage {
        val dh = (DISPLAY_WIDTH.toDouble() * h / w).roundToInt()
        return warpRgbImage(photo, hom * Homography.scale(w.toDouble() / DISPLAY_WIDTH, h.toDouble() / dh), DISPLAY_WIDTH, dh)
    }

    /** Clears every [mask] pixel that does not land on [screen]'s own pixels through [hom]. */
    private fun andScreenMask(mask: BooleanArray, screen: ScreenLocator.Screen, hom: Homography, w: Int, h: Int) {
        val p = DoubleArray(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                hom.map(x + 0.5, y + 0.5, p)
                if (!screen.contains(p[0], p[1])) mask[i] = false
            }
        }
    }

    private fun warpLuma(src: GrayImage, hom: Homography, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val p = DoubleArray(2)
        val sw = src.width
        val sh = src.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                hom.map(x + 0.5, y + 0.5, p)
                val u = (p[0] - 0.5).coerceIn(0.0, sw - 1.001)
                val v = (p[1] - 0.5).coerceIn(0.0, sh - 1.001)
                val ix = u.toInt()
                val iy = v.toInt()
                val fx = (u - ix).toFloat()
                val fy = (v - iy).toFloat()
                val i = iy * sw + ix
                val a = (src.data[i].toInt() and 0xFF) * (1 - fx) + (src.data[i + 1].toInt() and 0xFF) * fx
                val b = (src.data[i + sw].toInt() and 0xFF) * (1 - fx) + (src.data[i + sw + 1].toInt() and 0xFF) * fx
                out[y * w + x] = a * (1 - fy) + b * fy
            }
        }
        return out
    }

    private fun warpRgb(src: RgbImage, hom: Homography, w: Int, h: Int): Array<FloatArray> {
        val planes = Array(3) { FloatArray(w * h) }
        sampleRgb(src, hom, w, h) { i, r, g, b ->
            planes[0][i] = r
            planes[1][i] = g
            planes[2][i] = b
        }
        return planes
    }

    private fun warpRgbImage(src: RgbImage, hom: Homography, w: Int, h: Int): RgbImage {
        val out = RgbImage(w, h)
        sampleRgb(src, hom, w, h) { i, r, g, b ->
            out.data[i * 3] = (r + 0.5f).toInt().coerceIn(0, 255).toByte()
            out.data[i * 3 + 1] = (g + 0.5f).toInt().coerceIn(0, 255).toByte()
            out.data[i * 3 + 2] = (b + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private inline fun sampleRgb(src: RgbImage, hom: Homography, w: Int, h: Int, sink: (Int, Float, Float, Float) -> Unit) {
        val p = DoubleArray(2)
        val sw = src.width
        val sh = src.height
        val d = src.data
        for (y in 0 until h) {
            for (x in 0 until w) {
                hom.map(x + 0.5, y + 0.5, p)
                val u = (p[0] - 0.5).coerceIn(0.0, sw - 1.001)
                val v = (p[1] - 0.5).coerceIn(0.0, sh - 1.001)
                val ix = u.toInt()
                val iy = v.toInt()
                val fx = (u - ix).toFloat()
                val fy = (v - iy).toFloat()
                val i00 = (iy * sw + ix) * 3
                val i01 = i00 + 3
                val i10 = i00 + sw * 3
                val i11 = i10 + 3
                val w00 = (1 - fx) * (1 - fy)
                val w01 = fx * (1 - fy)
                val w10 = (1 - fx) * fy
                val w11 = fx * fy
                val r = (d[i00].toInt() and 0xFF) * w00 + (d[i01].toInt() and 0xFF) * w01 +
                    (d[i10].toInt() and 0xFF) * w10 + (d[i11].toInt() and 0xFF) * w11
                val g = (d[i00 + 1].toInt() and 0xFF) * w00 + (d[i01 + 1].toInt() and 0xFF) * w01 +
                    (d[i10 + 1].toInt() and 0xFF) * w10 + (d[i11 + 1].toInt() and 0xFF) * w11
                val b = (d[i00 + 2].toInt() and 0xFF) * w00 + (d[i01 + 2].toInt() and 0xFF) * w01 +
                    (d[i10 + 2].toInt() and 0xFF) * w10 + (d[i11 + 2].toInt() and 0xFF) * w11
                sink(y * w + x, r, g, b)
            }
        }
    }

    /** Per-pixel median of equally sized planes; the mean of the middle two for an even count. */
    private fun medianOf(planes: List<FloatArray>, n: Int): FloatArray {
        val out = FloatArray(n)
        val k = planes.size
        val v = FloatArray(k)
        for (i in 0 until n) {
            for (j in 0 until k) {
                // Insertion sort; k is a handful.
                val x = planes[j][i]
                var p = j - 1
                while (p >= 0 && v[p] > x) {
                    v[p + 1] = v[p]
                    p--
                }
                v[p + 1] = x
            }
            out[i] = if (k % 2 == 1) v[k / 2] else (v[k / 2 - 1] + v[k / 2]) * 0.5f
        }
        return out
    }

    /** Halves a plane by averaging each 2x2 block. */
    private fun decimate(src: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2
        val hh = h / 2
        val out = FloatArray(hw * hh)
        for (y in 0 until hh) {
            for (x in 0 until hw) {
                val i = 2 * y * w + 2 * x
                out[y * hw + x] = (src[i] + src[i + 1] + src[i + w] + src[i + w + 1]) * 0.25f
            }
        }
        return out
    }

    /** Halves a mask, keeping a block only when all four of its pixels are set. */
    private fun decimateMask(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val hw = w / 2
        val hh = h / 2
        return BooleanArray(hw * hh) { j ->
            val i = 2 * (j / hw) * w + 2 * (j % hw)
            src[i] && src[i + 1] && src[i + w] && src[i + w + 1]
        }
    }

    /** Box blur of radius one, in place, edges clamped. */
    private fun boxBlur(plane: FloatArray, w: Int, h: Int) {
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = plane[row + max(0, x - 1)]
                val r = plane[row + min(w - 1, x + 1)]
                tmp[row + x] = (l + plane[row + x] + r) / 3f
            }
        }
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val down = min(h - 1, y + 1) * w
            val row = y * w
            for (x in 0 until w) plane[row + x] = (tmp[up + x] + tmp[row + x] + tmp[down + x]) / 3f
        }
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, times: Int) = erodeOrDilate(mask, w, h, times, dilate = false)

    /**
     * Four-neighbour erosion or dilation, [times] over. Outside the rectangle counts as
     * unset: its edges are the screen's sides, and the margin has to come in from them too.
     */
    private fun erodeOrDilate(mask: BooleanArray, w: Int, h: Int, times: Int, dilate: Boolean) {
        val tmp = BooleanArray(w * h)
        repeat(times) {
            System.arraycopy(mask, 0, tmp, 0, w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val l = x > 0 && tmp[i - 1]
                    val r = x < w - 1 && tmp[i + 1]
                    val u = y > 0 && tmp[i - w]
                    val d = y < h - 1 && tmp[i + w]
                    mask[i] = if (dilate) tmp[i] || l || r || u || d else tmp[i] && l && r && u && d
                }
            }
        }
    }

    companion object {
        /** The mode's id on the landing page and in the pipeline. */
        const val ID = "spot"

        /** Photos the room shows, and the app takes, per round. */
        const val PHOTOS = 6

        /** Fewest photos a median can name the original from. */
        const val MIN_PHOTOS = 3

        /**
         * Width of the straightened screen everything is compared at. About the screen's
         * own width in a 1080p frame held at arm's length, so nothing is invented by
         * upsampling and little thrown away.
         */
        const val ANALYSIS_WIDTH = 640

        /** Width of the straightened screen handed back for showing. */
        const val DISPLAY_WIDTH = 960

        private const val DEFAULT_ASPECT = 1.75
        private const val MIN_ASPECT = 0.5
        private const val MAX_ASPECT = 4.0

        /** Patches matched, across and down. */
        private const val GRID_X = 12
        private const val GRID_Y = 8

        private class Level(val factor: Int, val patch: Int, val search: Int)

        /**
         * Patch size and search radius at a quarter, a half and full resolution. The
         * quarter's eight pixels reach 32 at full size, which is what a side the outline put
         * twenty pixels out needs.
         */
        private val LEVELS = listOf(Level(4, 16, 8), Level(2, 24, 6), Level(1, 40, 3))

        /** A patch must vary at least this much, as a standard deviation in luma, to be matched. */
        private const val MIN_TEXTURE = 12.0

        /** Correlation a match must reach to be believed. */
        private const val MIN_CORRELATION = 0.8

        private const val MIN_MATCHES = 6

        /** How far off a match may be and still agree with a correction, in pixels at full resolution, per level factor. */
        private const val MATCH_TOLERANCE = 1.5

        private const val RANSAC_ITERATIONS = 400
        private const val RANSAC_SEED = 20261023L

        /** Largest move of a rectangle corner a correction may make, as a fraction of the width. */
        private const val MAX_CORRECTION = 0.15

        /** The coarse search: at an eighth of the resolution, over these scales and shifts. */
        private const val GLOBAL_FACTOR = 8
        private const val GLOBAL_MIN_SCALE = 0.7
        private const val GLOBAL_SCALES = 31
        private const val GLOBAL_SHIFT = 0.15
        private const val GLOBAL_MIN_CORRELATION = 0.5
        private const val GLOBAL_TEMPLATE = 0.5

        /** How far in from the screen's outline comparison starts, as a fraction of the width. */
        private const val MARGIN_FRACTION = 0.022

        /** The common screen must be at least this much of the rectangle. */
        private const val MIN_MASK_FRACTION = 0.3

        private const val NORMALISE_STRIDE = 3

        /** Misalignment forgiven when comparing, in pixels either way. */
        private const val SHIFT_TOLERANCE = 2

        /** Summed over three channels, so 0..765. */
        private const val HISTOGRAM_BINS = 766
        private const val ABSOLUTE_THRESHOLD = 80
        private const val NOISE_DEVIATIONS = 10

        /** Width of the band along the compared area's edge, in pixels, and how much of a difference may lie in it. */
        private const val EDGE_BAND = 10
        private const val EDGE_FRACTION = 0.6

        /** Pieces closer than about twice this, in pixels, are one difference. */
        private const val MERGE_DISTANCE = 3

        /**
         * Least score a difference needs. The faintest real one, the castle's missing
         * windows in the first set, scores about 2,300 there and 9,000 to 10,000 in the
         * rounds taken with the app. The strongest stray that is neither on the edge nor
         * beside a real difference scores about 700.
         */
        private const val MIN_SCORE = 1000f

        /** A second difference in one photo must be at least this fraction of the first. */
        private const val RELATIVE_SCORE = 0.25f

        private const val MAX_DIFFERENCES = 3
    }
}
