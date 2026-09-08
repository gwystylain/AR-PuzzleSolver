package com.puzzlesolver.core.grid

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import com.puzzlesolver.core.image.LocalStats
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the puzzle grid sits on the canvas.
 *
 * Because the canvas is metrically rectified, this is an honest similarity
 * transform -- origin, rotation, pitch -- rather than a homography. Cell (col,row)
 * maps to canvas texels by walking [pitchX] along the row axis and [pitchY] down
 * the column axis. That is why detection can stay this simple: the hard projective
 * work already happened in the surface unwrap.
 */
data class GridModel(
    /** Canvas texel coordinate of the outer corner of cell (0, 0). */
    val originX: Float,
    val originY: Float,
    /** Residual in-plane rotation of the grid on the canvas, degrees. */
    val rotationDeg: Float,
    /** Cell pitch in canvas texels. */
    val pitchX: Float,
    val pitchY: Float,
    val cols: Int,
    val rows: Int,
    /** 0..1 quality of the periodic fit; feeds the UI and gates the solver. */
    val confidence: Float,
) {
    private val c = cos(Math.toRadians(rotationDeg.toDouble())).toFloat()
    private val s = sin(Math.toRadians(rotationDeg.toDouble())).toFloat()

    /** Canvas texel coordinate of a point given in fractional cell coordinates. */
    fun cellToCanvas(col: Float, row: Float): FloatArray {
        val lx = col * pitchX
        val ly = row * pitchY
        return floatArrayOf(originX + lx * c - ly * s, originY + lx * s + ly * c)
    }

    fun cellCentre(col: Int, row: Int): FloatArray = cellToCanvas(col + 0.5f, row + 0.5f)

    /** Axis-aligned canvas bounds of a cell, inset to avoid bleeding in the grid lines. */
    fun cellBounds(col: Int, row: Int, insetFraction: Float = 0.12f): IntArray {
        val a = cellToCanvas(col + insetFraction, row + insetFraction)
        val b = cellToCanvas(col + 1f - insetFraction, row + insetFraction)
        val d = cellToCanvas(col + insetFraction, row + 1f - insetFraction)
        val e = cellToCanvas(col + 1f - insetFraction, row + 1f - insetFraction)
        val xs = floatArrayOf(a[0], b[0], d[0], e[0])
        val ys = floatArrayOf(a[1], b[1], d[1], e[1])
        return intArrayOf(
            xs.min().roundToInt(), ys.min().roundToInt(),
            xs.max().roundToInt(), ys.max().roundToInt(),
        )
    }

    val cellCount: Int get() = cols * rows

    /** Physical cell size in metres, handy for sanity checks and for the UI. */
    fun cellSizeMetres(spec: CanvasSpec): FloatArray =
        floatArrayOf(pitchX * spec.metresPerTexel, pitchY * spec.metresPerTexel)

    /**
     * Whether the grid has moved enough to invalidate cell observations. Sub-texel
     * drift as the fit refines is normal and must not reset the solver.
     */
    fun isCompatibleWith(other: GridModel): Boolean =
        cols == other.cols && rows == other.rows &&
            abs(pitchX - other.pitchX) < 0.5f && abs(pitchY - other.pitchY) < 0.5f &&
            abs(originX - other.originX) < pitchX * 0.25f &&
            abs(originY - other.originY) < pitchY * 0.25f &&
            abs(rotationDeg - other.rotationDeg) < 0.5f
}

/**
 * Recovers a [GridModel] from the accumulated canvas.
 *
 * Runs on the pipeline thread at a few hertz, not per frame -- the grid does not
 * move, so re-detecting it every frame would be pure waste. It is re-run when
 * coverage grows meaningfully, which is exactly when the estimate can improve.
 */
class GridDetector(
    private val spec: CanvasSpec,
    private val cfg: Config = Config(),
) {
    data class Config(
        /** Smallest plausible cell, metres. Below this we are chasing print texture. */
        val minCellMetres: Float = 0.01f,
        val maxCellMetres: Float = 0.60f,
        /** Ignore grid hypotheses that imply fewer cells than this in either axis. */
        val minCells: Int = 3,
        val maxCells: Int = 256,
        val edgeMagnitudeThreshold: Int = 40,
        /**
         * Local standard deviation required before a pixel can be called ink.
         *
         * Camera noise on a flat surface sits well under this; genuine print edges sit far
         * above it. Raise it if dark scenery still speckles, lower it for faint pencil.
         */
        val minLocalStdDev: Float = 6f,
    )

    private companion object {
        /** A tooth counts as a grid line above this fraction of the strongest tooth. */
        const val STRONG_TOOTH_FRACTION = 0.35f

        /** Half-width, in profile samples, of the window each tooth is read over. */
        const val TOOTH_WINDOW = 2

        /** End lines below this fraction of the run's median strength are scenery. */
        const val WEAK_END_FRACTION = 0.55f

        /**
         * How much better a finer lattice must score to beat a coarser one. Keeps the
         * search on the fundamental pitch rather than a harmonic of it.
         */
        const val SCORE_TIE_MARGIN = 0.02f

        /** Offset samples per pitch in the coarse sweep. */
        const val COARSE_STEPS_PER_PITCH = 8f

        /** Pitch increment for the coarse sweep, in texels. */
        const val COARSE_PITCH_STEP = 1f

        /** Mean on-line ink required, as a fraction of the profile's peak. */
        const val MIN_ONLINE_FRACTION = 0.20f

        /** The faintest single rule allowed, relative to the ink floor. */
        const val MIN_WEAKEST_LINE_FRACTION = 0.25f

        /**
         * At or below this luma a canvas texel counts as never observed rather than dark.
         * Not zero exactly, to absorb any rounding in the GPU readback path.
         */
        const val NO_DATA_LUMA = 2

        /**
         * Sampled texels the detector will work over in one pass.
         *
         * Chosen from the memory it implies rather than from anything optical: the
         * working set is ~29 bytes per sample, so two million of them is about 60 MB on
         * top of the ~96 MB of canvas mirrors that are resident for the whole session.
         * That leaves real headroom inside a 256 MB heap, which is the budget the crash
         * this was written for proved we do not have to spare.
         *
         * It is not a limit on how much wall can be scanned -- only on how finely the
         * scanned region is resampled before the profiles are taken.
         */
        const val MAX_DETECT_SAMPLES = 2_097_152L

        /** Samples per axis below which detection is pointless; also the decimation floor. */
        const val MIN_DETECT_EXTENT = 64
    }

    // Scratch buffers, allocated once. Detection is called repeatedly for the whole
    // session, and this is the difference between a steady 4 Hz and a sawtooth of
    // GC pauses.
    private var work: GrayImage? = null
    private var scratchA: GrayImage? = null
    private var scratchB: GrayImage? = null
    private var edges: GrayImage? = null
    private var orient: ByteArray? = null
    private val localStats = LocalStats()
    private var meanBuffer: FloatArray = FloatArray(0)
    private var stdBuffer: FloatArray = FloatArray(0)
    private var profileX: FloatArray = FloatArray(0)
    private var profileY: FloatArray = FloatArray(0)

    /**
     * Why the last [detect] returned null, or null when it succeeded.
     *
     * Detection has half a dozen independent ways to legitimately decline, and from the
     * outside they are indistinguishable -- "no grid" tells you nothing about whether
     * the canvas is blank, the region too small, or the ink genuinely aperiodic. Each of
     * those points at a different fix.
     */
    var lastRejection: String? = null
        private set

    /**
     * When set, the detector fits a lattice with exactly this many cells per axis
     * instead of inferring the count from where the grid lines stop.
     *
     * A testing aid, and an honest one: knowing the puzzle is 9x9 is real information,
     * and supplying it separates "can we count grid lines" from "can we read cells and
     * solve". Those fail for unrelated reasons, and line counting is by far the more
     * fragile of the two -- it is the part that has to cope with page margins, picture
     * frames and window chrome sharing the wall. Leave null for normal operation.
     */
    var expectedCells: Int? = null

    /**
     * @param canvasLuma the accumulated canvas, single channel
     * @param coverage   used to restrict detection to the region we have actually seen
     */
    fun detect(canvasLuma: GrayImage, coverage: CoverageMap): GridModel? {
        val roi = observedBounds(coverage) ?: run {
            lastRejection = "nothing observed yet"
            return null
        }
        val x0 = roi[0]
        val y0 = roi[1]
        val w = roi[2] - x0
        val h = roi[3] - y0
        if (w < 64 || h < 64) {
            lastRejection = "observed region only ${w}x${h} texels, need 64x64"
            return null
        }

        // Detect on a decimated copy once the observed region gets large.
        //
        // The working set here is about 29 bytes per sampled texel -- the seven buffers
        // in [ensureBuffers] plus the two integral images inside [LocalStats] -- against
        // a Java heap this app is capped at 256 MB for. The ROI is the bounding box of
        // everything scanned so far, so it grows for as long as the user keeps panning,
        // and a full 4096-square canvas would ask for roughly 490 MB. It used to ask,
        // fail, and take the detector down with it for the rest of the session.
        //
        // Decimating costs nothing that matters here. Detection needs enough samples
        // across a cell to see the periodicity, not the canvas's full 1.5 mm resolution:
        // a cell is tens of texels across, so even at step 4 there are several samples
        // per cell and the projection profiles keep their shape. Everything below
        // therefore works in sampled texels, and the pitch and origin are scaled back to
        // canvas texels on the way out.
        val step = decimationFor(w, h)
        val dw = w / step
        val dh = h / step
        val metresPerSample = spec.metresPerTexel * step

        val sub = ensureBuffers(dw, dh)
        canvasLuma.subImageDecimated(x0, y0, w, h, step, sub)

        val bin = scratchB!!
        // Contrast-gated, not just mean-gated. A plain local-mean threshold returns flat
        // dark areas as roughly half ink, and that speckle then dominates the projection
        // profiles -- worse, asymmetrically, since each axis integrates over a different
        // amount of surround.
        localStats.threshold(
            sub, bin,
            radius = 12,
            bias = 8,
            minStdDev = cfg.minLocalStdDev,
            meanBuffer = meanBuffer,
            stdBuffer = stdBuffer,
        )

        // Blank anything outside observed coverage.
        //
        // The ROI is quantised to coverage cells, so it always carries a margin of canvas
        // that was never scanned. That margin is zero-valued, and the step between it and
        // the observed region is a full-ROI-width edge -- stronger than any grid rule,
        // since a rule only spans the puzzle. Left in, the lattice fit ends up describing
        // the boundary of my own region of interest rather than the puzzle inside it.
        val stride = coverage.stride
        for (yy in 0 until dh) {
            val cy = (y0 + yy * step) / stride
            val rowBase = yy * dw
            val covRow = cy * coverage.cols
            for (xx in 0 until dw) {
                val cx = (x0 + xx * step) / stride
                val ci = covRow + cx
                val observed = cx < coverage.cols && cy < coverage.rows &&
                    ci >= 0 && ci < coverage.confidence.size &&
                    coverage.confidence[ci] > CoverageMap.MIN_USEFUL_CONFIDENCE
                // Also treat a zero-luma texel as no-data regardless of what coverage
                // claims. The accumulator clears the canvas to zero and only writes where
                // a frame actually projected, so zero means "never seen" -- and unlike the
                // coverage map, that signal is per-texel rather than per-16-texel-cell, so
                // it catches the quantisation margin exactly.
                val noData = !observed || (sub.data[rowBase + xx].toInt() and 0xFF) <= NO_DATA_LUMA
                if (noData) bin.data[rowBase + xx] = 0
            }
        }

        val mag = edges!!
        val ori = orient!!
        ImageOps.sobel(sub, mag, ori)
        val rotation = ImageOps.estimateGridRotation(mag, ori, cfg.edgeMagnitudeThreshold)

        // Profiles of the binarised image: grid lines are ink, so they show up as
        // periodic maxima once we project along the (de-rotated) axes.
        val diag = (kotlin.math.sqrt((dw * dw + dh * dh).toDouble())).toInt() + 2
        if (profileX.size != diag) {
            profileX = FloatArray(diag)
            profileY = FloatArray(diag)
        }
        ImageOps.projectionProfile(bin, rotation, vertical = true, out = profileX)
        ImageOps.projectionProfile(bin, rotation, vertical = false, out = profileY)

        val minPeriod = (cfg.minCellMetres / metresPerSample).toInt().coerceAtLeast(4)
        val maxPeriod = (cfg.maxCellMetres / metresPerSample).toInt().coerceAtMost(diag / cfg.minCells)
        if (minPeriod >= maxPeriod) {
            lastRejection = "cell-size window empty (min=$minPeriod max=$maxPeriod texels)"
            return null
        }

        val expected = expectedCells
        val extentX: Triple<Float, Int, Float>
        val extentY: Triple<Float, Int, Float>
        val pitchX: Float
        val pitchY: Float

        // Where the ROI actually projects into each profile. Everything outside is
        // structurally zero, never observed-and-empty, and the two must not be confused:
        // a lattice straddling the boundary scores a perfect line-to-gap contrast against
        // those zeros, which is how the fit ends up parked off the edge of the image.
        val rangeX = validProfileRange(dw, dh, rotation, vertical = true, profileSize = diag)
        val rangeY = validProfileRange(dw, dh, rotation, vertical = false, profileSize = diag)

        if (expected != null) {
            val fx = fitFixedCellCount(profileX, expected, minPeriod, maxPeriod, rangeX)
            val fy = fitFixedCellCount(profileY, expected, minPeriod, maxPeriod, rangeY)
            if (fx == null || fy == null) {
                lastRejection = "no lattice of $expected cells fits the ink"
                return null
            }
            pitchX = fx[1]
            pitchY = fy[1]
            extentX = Triple(fx[0], expected, fx[2])
            extentY = Triple(fy[0], expected, fy[2])
        } else {
            pitchX = ImageOps.dominantPeriod(profileX, minPeriod, maxPeriod)
            pitchY = ImageOps.dominantPeriod(profileY, minPeriod, maxPeriod)
            if (pitchX <= 0f || pitchY <= 0f) {
                lastRejection = "no periodic structure (pitchX=$pitchX pitchY=$pitchY)"
                return null
            }
            // Phase: where the first grid line falls. Correlate the profile against a
            // comb of the detected period and take the best shift.
            extentX = measureExtent(profileX, pitchX, bestPhase(profileX, pitchX, rangeX), rangeX)
            extentY = measureExtent(profileY, pitchY, bestPhase(profileY, pitchY, rangeY), rangeY)
        }

        val cols = extentX.second
        val rows = extentY.second
        if (cols < cfg.minCells || rows < cfg.minCells) {
            lastRejection = "only ${cols}x${rows} cells found, need at least ${cfg.minCells}"
            return null
        }
        if (cols > cfg.maxCells || rows > cfg.maxCells) {
            lastRejection = "${cols}x${rows} cells exceeds the ${cfg.maxCells} cap"
            return null
        }

        // Convert the profile-space origin back into canvas texels. The profile was
        // taken about the ROI centre, so undo that offset and the rotation.
        val rad = Math.toRadians(rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val cx = dw * 0.5f
        val cy = dh * 0.5f
        val tx = extentX.first - diag * 0.5f
        val ty = extentY.first - diag * 0.5f
        // Sampled texels back to canvas texels. Everything above is measured on the
        // decimated image, so the whole ROI-space displacement scales by [step] before
        // the ROI's own origin -- which is already in canvas texels -- is added back.
        val originX = x0 + (cx + tx * c - ty * s) * step
        val originY = y0 + (cy + tx * s + ty * c) * step

        val confidence = ((extentX.third + extentY.third) * 0.5f).coerceIn(0f, 1f)

        lastRejection = null
        return GridModel(
            originX = originX,
            originY = originY,
            rotationDeg = rotation,
            // Rotation is invariant under a uniform scale and the cell counts are counts,
            // so the pitches are the only other thing measured in sampled texels.
            pitchX = pitchX * step,
            pitchY = pitchY * step,
            cols = cols,
            rows = rows,
            confidence = confidence,
        )
    }

    /**
     * Fits a lattice of exactly [cells] cells by searching pitch and offset directly.
     *
     * Two free parameters and a cheap score, so brute force is the right tool: for each
     * candidate pitch and offset, sum the profile at the [cells] + 1 line positions the
     * lattice implies and keep the best. More robust than inferring the count from a run
     * of strong teeth, because it never has to decide where the grid *stops* -- the
     * caller has already said how big it is.
     *
     * @return [firstLineOffset, pitch, confidence], or null when nothing scores.
     */
    private fun fitFixedCellCount(
        profile: FloatArray,
        cells: Int,
        minPeriod: Int,
        maxPeriod: Int,
        validRange: IntArray,
    ): FloatArray? {
        val first = validRange[0]
        val last = validRange[1]
        val usable = last - first
        if (usable < cells * minPeriod) return null

        // A lattice must sit on ink, not merely beat the background. Without a floor, a
        // faint speckled region can out-score the real grid on relative contrast alone.
        var profileMax = 0f
        for (i in first..last) if (profile[i] > profileMax) profileMax = profile[i]
        if (profileMax <= 0f) return null
        val inkFloor = profileMax * MIN_ONLINE_FRACTION

        val maxUsablePitch = (usable.toFloat() / cells).coerceAtMost(maxPeriod.toFloat())
        if (maxUsablePitch < minPeriod) return null

        // Stage 1: coarse sweep.
        //
        // The offset step must never exceed what a tooth can see, or the correct
        // alignment is simply never sampled -- and the winner then depends on the
        // sampling lattice rather than the image, which makes the result eerily
        // insensitive to what is actually in frame. So the sampling window is widened to
        // half the step, guaranteeing every position is covered by some sample.
        var bestScore = 0f
        var bestPitch = 0f
        var bestOffset = 0f
        var pitch = maxUsablePitch
        while (pitch >= minPeriod) {
            val span = pitch * cells
            val maxOffset = last - span
            if (maxOffset > first) {
                val step = (pitch / COARSE_STEPS_PER_PITCH).coerceAtLeast(1f)
                val window = Math.max(TOOTH_WINDOW, Math.ceil(step / 2.0).toInt())
                var offset = first.toFloat()
                while (offset <= maxOffset) {
                    val score = latticeScore(profile, offset, pitch, cells, window, inkFloor)
                    // Coarse-to-fine in pitch, so on near-ties the larger pitch wins: a
                    // lattice at half the true pitch also lands on every rule, it just
                    // adds lines through cell interiors.
                    if (score > bestScore + SCORE_TIE_MARGIN) {
                        bestScore = score
                        bestPitch = pitch
                        bestOffset = offset
                    }
                    offset += step
                }
            }
            pitch -= COARSE_PITCH_STEP
        }
        if (bestPitch <= 0f) return null

        // Stage 2: refine with a tight window, now that we know roughly where to look.
        var refinedScore = 0f
        var refinedPitch = bestPitch
        var refinedOffset = bestOffset
        val pitchSpan = COARSE_PITCH_STEP
        var rp = bestPitch - pitchSpan
        while (rp <= bestPitch + pitchSpan) {
            if (rp >= minPeriod && rp * cells <= usable) {
                val lo = (bestOffset - rp / 2f).coerceAtLeast(first.toFloat())
                val hi = (bestOffset + rp / 2f).coerceAtMost(last - rp * cells)
                var ro = lo
                while (ro <= hi) {
                    val score = latticeScore(profile, ro, rp, cells, TOOTH_WINDOW, inkFloor)
                    if (score > refinedScore) {
                        refinedScore = score
                        refinedPitch = rp
                        refinedOffset = ro
                    }
                    ro += 0.5f
                }
            }
            rp += 0.1f
        }
        if (refinedScore > 0f) {
            bestPitch = refinedPitch
            bestOffset = refinedOffset
        }

        var mean = 0f
        for (line in 0..cells) mean += toothAt(profile, bestOffset + line * bestPitch)
        mean /= (cells + 1)
        if (mean <= 1e-3f) return null
        var deviation = 0f
        for (line in 0..cells) {
            deviation += abs(toothAt(profile, bestOffset + line * bestPitch) - mean)
        }
        deviation /= (cells + 1)
        val confidence = (1f - deviation / mean).coerceIn(0f, 1f)
        return floatArrayOf(bestOffset, bestPitch, confidence)
    }

    /**
     * Reads a comb tooth, taking the strongest sample in a small window.
     *
     * Necessary because [bestPhase] only quantises the phase to a fraction of the
     * period, and grid lines are a couple of texels wide: an exact single-sample read
     * can fall in the gap beside a line and report it as absent, which then breaks the
     * contiguity test in [measureExtent].
     */
    /**
     * The span of profile bins that the ROI actually projects onto, as [firstBin, lastBin].
     *
     * Mirrors the geometry in [ImageOps.projectionProfile]: bins are measured from the
     * ROI centre and biased by half the profile length, so only a window in the middle of
     * the array ever receives samples. Bins outside it are zero because nothing was
     * projected there -- which is a different statement from "this part of the wall is
     * blank", and conflating the two makes empty space look like a perfect grid gap.
     */
    private fun validProfileRange(
        w: Int,
        h: Int,
        angleDeg: Float,
        vertical: Boolean,
        profileSize: Int,
    ): IntArray {
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val dx = sx * halfW
                val dy = sy * halfH
                val t = if (vertical) dx * c + dy * sn else -dx * sn + dy * c
                if (t < lo) lo = t
                if (t > hi) hi = t
            }
        }
        val bias = profileSize * 0.5f
        val first = Math.max(0, Math.ceil((lo + bias).toDouble()).toInt())
        val last = Math.min(profileSize - 1, Math.floor((hi + bias).toDouble()).toInt())
        return intArrayOf(first, last)
    }

    /**
     * How much a lattice looks like a grid: ink on the rules, paper between them.
     *
     * Scoring only the on-line samples rises monotonically as the lattice shrinks into
     * the inkiest cluster, so a bare sum collapses onto the smallest permitted pitch.
     * Contrast against the cell interiors has no such bias -- a lattice crushed into a
     * dense region has both its on-line and off-line samples in ink and scores near zero.
     */
    private fun latticeScore(
        profile: FloatArray,
        offset: Float,
        pitch: Float,
        cells: Int,
        window: Int,
        inkFloor: Float,
    ): Float {
        var onLine = 0f
        var weakestLine = Float.MAX_VALUE
        for (line in 0..cells) {
            val v = toothAt(profile, offset + line * pitch, window)
            onLine += v
            if (v < weakestLine) weakestLine = v
        }
        onLine /= (cells + 1)
        if (onLine < inkFloor) return 0f
        // Every rule of a real grid is drawn; a lattice with one missing line is
        // explaining something else that merely happens to be periodic.
        if (weakestLine < inkFloor * MIN_WEAKEST_LINE_FRACTION) return 0f

        // Off-line probes avoid the middle of the cell, because that is where the glyph
        // is. Sampling the centre makes a filled cell look like a rule, which flatters a
        // half-pitch lattice: its extra lines land exactly on those glyphs. Quarter
        // positions are the blank part of a filled cell.
        var offLine = 0f
        for (cell in 0 until cells) {
            val a = toothAt(profile, offset + (cell + 0.25f) * pitch, window)
            val b = toothAt(profile, offset + (cell + 0.75f) * pitch, window)
            offLine += Math.min(a, b)
        }
        offLine /= cells

        if (onLine <= offLine) return 0f
        val contrast = (onLine - offLine) / (onLine + offLine + 1e-3f)

        // Uniformity across the rules, which is what separates the true pitch from its
        // harmonics. A half-pitch lattice alternates real rule, glyph row, real rule --
        // strong contrast, but wildly uneven lines. A real grid's rules match each other.
        var deviation = 0f
        for (line in 0..cells) {
            deviation += Math.abs(toothAt(profile, offset + line * pitch, window) - onLine)
        }
        deviation /= (cells + 1)
        val uniformity = (1f - deviation / onLine).coerceIn(0f, 1f)

        return contrast * uniformity
    }

    private fun toothAt(
        profile: FloatArray,
        position: Float,
        halfWindow: Int = TOOTH_WINDOW,
    ): Float {
        val centre = position.toInt()
        var best = 0f
        for (d in -halfWindow..halfWindow) {
            val i = centre + d
            if (i < 0 || i >= profile.size) continue
            if (profile[i] > best) best = profile[i]
        }
        return best
    }

    /**
     * Aligns a comb of spacing [period] to the profile. Returns the offset of the
     * first tooth, in samples.
     */
    private fun bestPhase(profile: FloatArray, period: Float, validRange: IntArray): Float {
        var bestScore = Float.NEGATIVE_INFINITY
        var bestPhase = 0f
        val steps = 64
        for (k in 0 until steps) {
            val phase = validRange[0] + period * k / steps
            var acc = 0f
            var t = phase
            while (t <= validRange[1]) {
                acc += toothAt(profile, t)
                t += period
            }
            if (acc > bestScore) {
                bestScore = acc
                bestPhase = phase
            }
        }
        return bestPhase
    }

    /**
     * Finds the longest contiguous run of grid lines along the comb.
     *
     * Returns (offset of first line, number of cells, confidence). This is what makes
     * a partially scanned puzzle detectable: we only claim the cells whose bounding
     * lines we have actually seen ink for.
     *
     * The threshold is a fraction of the *strongest* tooth, not of the median. A grid
     * usually occupies only part of the canvas, so most teeth land on blank paper and
     * the median is zero -- against which every tooth counts as strong and the whole
     * canvas gets reported as one enormous grid. Requiring contiguity matters for the
     * same reason: it rejects stray periodic ink elsewhere on the wall instead of
     * stretching the grid to reach it.
     */
    private fun measureExtent(
        profile: FloatArray,
        period: Float,
        phase: Float,
        validRange: IntArray,
    ): Triple<Float, Int, Float> {
        val teeth = ArrayList<Float>()
        var t = phase
        while (t <= validRange[1]) {
            teeth.add(toothAt(profile, t))
            t += period
        }
        if (teeth.size < 2) return Triple(phase, 0, 0f)

        var peak = 0f
        for (v in teeth) if (v > peak) peak = v
        if (peak <= 1e-3f) return Triple(phase, 0, 0f)
        val strong = peak * STRONG_TOOTH_FRACTION

        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < teeth.size) {
            if (teeth[i] < strong) {
                i++
                continue
            }
            var j = i
            while (j + 1 < teeth.size && teeth[j + 1] >= strong) j++
            if (j - i + 1 > bestLength) {
                bestLength = j - i + 1
                bestStart = i
            }
            i = j + 1
        }
        if (bestLength < 2) return Triple(phase, 0, 0f)

        // Trim ends that are much weaker than the body of the run.
        //
        // A puzzle rarely sits alone: window chrome, page margins and picture frames all
        // put straight lines next to the grid, and any of them that happens to land near
        // a comb tooth extends the run and inflates the cell count. Real grid rules are
        // drawn alike, so an end line at a third of the median strength is far more
        // likely to be scenery than part of the grid.
        val body = ArrayList<Float>(bestLength)
        for (k in bestStart until bestStart + bestLength) body.add(teeth[k])
        body.sort()
        val runMedian = body[body.size / 2]
        val keep = runMedian * WEAK_END_FRACTION
        var start = bestStart
        var end = bestStart + bestLength - 1
        while (end - start >= 2 && teeth[start] < keep) start++
        while (end - start >= 2 && teeth[end] < keep) end--
        bestStart = start
        bestLength = end - start + 1

        // Confidence from how uniform the accepted lines are. A real ruled grid draws
        // its lines alike, so scatter is the signal that we have latched onto
        // something that merely happens to be periodic.
        var mean = 0f
        for (k in bestStart until bestStart + bestLength) mean += teeth[k]
        mean /= bestLength
        var deviation = 0f
        for (k in bestStart until bestStart + bestLength) deviation += abs(teeth[k] - mean)
        deviation /= bestLength
        val confidence = (1f - deviation / mean.coerceAtLeast(1e-3f)).coerceIn(0f, 1f)

        return Triple(phase + bestStart * period, bestLength - 1, confidence)
    }

    /** Bounding box of observed coverage, in canvas texels. */
    /**
     * Smallest power-of-two decimation that brings the region under [MAX_DETECT_SAMPLES].
     *
     * Powers of two only, so a sampled texel always covers a whole number of canvas
     * texels and the scale back out is exact. It never decimates so far that either axis
     * drops below the 64-sample floor detection needs, because a region that small cannot
     * be read at any resolution and silently shrinking it further would only swap one
     * failure for a more confusing one.
     */
    private fun decimationFor(w: Int, h: Int): Int {
        var step = 1
        while ((w / step).toLong() * (h / step).toLong() > MAX_DETECT_SAMPLES &&
            w / (step * 2) >= MIN_DETECT_EXTENT &&
            h / (step * 2) >= MIN_DETECT_EXTENT
        ) {
            step *= 2
        }
        return step
    }

    private fun observedBounds(coverage: CoverageMap): IntArray? {
        var minCx = Int.MAX_VALUE
        var minCy = Int.MAX_VALUE
        var maxCx = -1
        var maxCy = -1
        for (cy in 0 until coverage.rows) {
            val row = cy * coverage.cols
            for (cx in 0 until coverage.cols) {
                if (coverage.confidence[row + cx] > CoverageMap.MIN_USEFUL_CONFIDENCE) {
                    if (cx < minCx) minCx = cx
                    if (cx > maxCx) maxCx = cx
                    if (cy < minCy) minCy = cy
                    if (cy > maxCy) maxCy = cy
                }
            }
        }
        if (maxCx < 0) return null
        val s = coverage.stride
        return intArrayOf(
            minCx * s,
            minCy * s,
            ((maxCx + 1) * s).coerceAtMost(spec.widthTexels),
            ((maxCy + 1) * s).coerceAtMost(spec.heightTexels),
        )
    }

    /**
     * Allocates the working set for an exactly [w] x [h] region.
     *
     * Exact, not "at least": every [ImageOps] routine walks its whole buffer, so
     * handing them an oversized one from a previous, larger ROI would mix stale
     * pixels into the projection profiles and shift the detected phase. Reallocating
     * on a size change is cheap next to that, and the ROI settles quickly anyway.
     */
    private fun ensureBuffers(w: Int, h: Int): GrayImage {
        val cur = work
        if (cur == null || cur.width != w || cur.height != h) {
            // Every buffer is built before any of them is published, for the same reason
            // [LocalStats.prepare] does it: the guard above keys on `work`, so assigning
            // that first and then failing to allocate one of the rest would leave this
            // object holding a mixture of sizes that it believes is consistent. The
            // mismatch is not detectable afterwards -- it surfaces as an index off the
            // end of whichever buffer was left behind.
            val newWork = GrayImage(w, h)
            val newScratchA = GrayImage(w, h)
            val newScratchB = GrayImage(w, h)
            val newEdges = GrayImage(w, h)
            val newOrient = ByteArray(w * h)
            val newMean = FloatArray(w * h)
            val newStd = FloatArray(w * h)
            work = newWork
            scratchA = newScratchA
            scratchB = newScratchB
            edges = newEdges
            orient = newOrient
            meanBuffer = newMean
            stdBuffer = newStd
        }
        return work!!
    }
}
