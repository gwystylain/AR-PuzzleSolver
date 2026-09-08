package com.puzzlesolver.core.puzzle

import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a cell of the canvas into something classifiable.
 *
 * The canvas is already rectified and metric, so a cell is an axis-aligned box up
 * to the grid's residual roll. That means no per-cell warp is needed -- we sample
 * with the grid's rotation applied and get an upright glyph out.
 */
class CellReader(private val size: Int = 28) {

    /** Reused across every cell of every detection pass. */
    private val patch = GrayImage(size, size)
    private val normalized = GrayImage(size, size)

    /**
     * Samples a cell into an upright [size] x [size] patch.
     *
     * @param inset fraction of the cell trimmed on each side to keep grid lines out
     */
    fun samplePatch(
        canvas: GrayImage,
        grid: GridModel,
        col: Int,
        row: Int,
        inset: Float = 0.14f,
    ): GrayImage {
        val lo = inset
        val hi = 1f - inset
        for (py in 0 until size) {
            val fy = lo + (hi - lo) * (py + 0.5f) / size
            for (px in 0 until size) {
                val fx = lo + (hi - lo) * (px + 0.5f) / size
                val c = grid.cellToCanvas(col + fx, row + fy)
                patch[px, py] = canvas.sampleBilinear(c[0], c[1]).roundToInt()
            }
        }
        return patch
    }

    /**
     * Ink statistics for a patch, using Otsu's threshold so we adapt to whatever
     * contrast this part of the wall happens to have.
     *
     * @return [inkFraction, threshold, inkCentroidX, inkCentroidY, strokeContrast]
     */
    fun inkStats(p: GrayImage): FloatArray {
        val hist = IntArray(256)
        val n = p.width * p.height
        for (i in 0 until n) hist[p.data[i].toInt() and 0xFF]++
        val threshold = ImageOps.otsuThreshold(hist, n)

        var ink = 0
        var sx = 0.0
        var sy = 0.0
        var darkSum = 0.0
        var lightSum = 0.0
        var lightCount = 0
        for (y in 0 until p.height) {
            for (x in 0 until p.width) {
                val v = p[x, y]
                if (v < threshold) {
                    ink++
                    sx += x
                    sy += y
                    darkSum += v
                } else {
                    lightSum += v
                    lightCount++
                }
            }
        }
        if (ink == 0) return floatArrayOf(0f, threshold.toFloat(), 0.5f, 0.5f, 0f)
        val meanDark = darkSum / ink
        val meanLight = if (lightCount > 0) lightSum / lightCount else 255.0
        return floatArrayOf(
            ink.toFloat() / n,
            threshold.toFloat(),
            (sx / ink / p.width).toFloat(),
            (sy / ink / p.height).toFloat(),
            ((meanLight - meanDark) / 255.0).toFloat(),
        )
    }

    /**
     * Size- and position-normalises the glyph in a patch: crop to the ink bounding
     * box, scale the long side to fill, centre by mass. Classification accuracy on
     * wall-sized targets lives or dies on this step, because apparent glyph size
     * varies with how close the user happened to be standing.
     */
    fun normalize(p: GrayImage, threshold: Int): GrayImage {
        var x0 = p.width
        var y0 = p.height
        var x1 = -1
        var y1 = -1
        for (y in 0 until p.height) {
            for (x in 0 until p.width) {
                if (p[x, y] < threshold) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        normalized.fill(255)
        if (x1 < x0 || y1 < y0) return normalized

        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val margin = 2
        val target = size - 2 * margin
        val scale = target.toFloat() / maxOf(bw, bh)
        val outW = (bw * scale).roundToInt().coerceAtLeast(1)
        val outH = (bh * scale).roundToInt().coerceAtLeast(1)
        val offX = (size - outW) / 2
        val offY = (size - outH) / 2

        for (y in 0 until outH) {
            val sy = y0 + y / scale
            for (x in 0 until outW) {
                val sx = x0 + x / scale
                normalized[offX + x, offY + y] = p.sampleBilinear(sx, sy).roundToInt()
            }
        }
        return normalized
    }
}

/**
 * Classifies a normalised glyph patch.
 *
 * Implementations live outside :core when they need platform facilities -- the app
 * builds its templates by rendering digits with the platform font engine at
 * startup, which is both cheaper and more accurate than shipping bitmaps that may
 * not match the puzzle's typeface.
 */
interface GlyphClassifier {
    /** @return [label, confidence]; label -1 means "no confident match". */
    fun classify(normalized: GrayImage): Pair<Int, Float>
}

/**
 * Nearest-template matcher using zero-mean normalised cross-correlation.
 *
 * NCC rather than raw SSD because the canvas mosaic can carry a residual exposure
 * offset between regions captured at different distances, and NCC is invariant to
 * exactly that.
 */
class TemplateGlyphClassifier(
    private val templates: List<Template>,
    private val minCorrelation: Float = 0.55f,
    /** How far ahead of the runner-up the winner must be to be trusted. */
    private val minMargin: Float = 0.06f,
    /**
     * Quarter turns of the patch to try: four for a canvas whose up is unknown, one for
     * a caller that knows which way up its glyph is.
     *
     * Four is the right default and is explained on [classify]. One exists because
     * trying all four is not free of *risk*, only of cost: a six turned upside down is a
     * nine, so on a source where up is known -- a camera frame read in image space, as
     * the terminal wall is -- the extra orientations can only turn a correct reading
     * into a wrong one.
     */
    private val orientations: Int = 4,
) : GlyphClassifier {

    class Template(val label: Int, image: GrayImage) {
        val values: FloatArray
        val norm: Float
        val width = image.width
        val height = image.height

        init {
            val n = width * height
            values = FloatArray(n)
            var mean = 0f
            for (i in 0 until n) {
                values[i] = (image.data[i].toInt() and 0xFF).toFloat()
                mean += values[i]
            }
            mean /= n
            var acc = 0f
            for (i in 0 until n) {
                values[i] -= mean
                acc += values[i] * values[i]
            }
            norm = sqrt(acc).coerceAtLeast(1e-6f)
        }
    }

    private var scratch = FloatArray(0)
    private var rotated = FloatArray(0)

    /**
     * Matches at all four 90-degree orientations.
     *
     * The canvas is only rectified up to a quarter turn: the grid detector recovers
     * rotation modulo 90 -- a grid looks like a grid whichever way up it is -- and the
     * surface's own axes come from whatever basis the tracker supplied, which on a phone
     * is the sensor's, not the display's. So a perfectly good mosaic can present its
     * glyphs sideways, and correlation against upright templates lands around 0.5:
     * suggestive, never convincing. Trying all four costs three extra dot products per
     * template and removes the failure entirely.
     */
    override fun classify(normalized: GrayImage): Pair<Int, Float> {
        if (templates.isEmpty()) return -1 to 0f
        val w = normalized.width
        val h = normalized.height
        if (w != h) return -1 to 0f             // rotation below assumes a square patch
        val n = w * h
        if (scratch.size != n) {
            scratch = FloatArray(n)
            rotated = FloatArray(n)
        }

        for (i in 0 until n) scratch[i] = (normalized.data[i].toInt() and 0xFF).toFloat()

        var best = -1
        var bestScore = -1f
        var secondScore = -1f

        for (turn in 0 until orientations.coerceIn(1, 4)) {
            if (turn > 0) rotate90(scratch, rotated, w)
            if (turn > 0) System.arraycopy(rotated, 0, scratch, 0, n)

            var mean = 0f
            for (i in 0 until n) mean += scratch[i]
            mean /= n
            var acc = 0f
            for (i in 0 until n) acc += (scratch[i] - mean) * (scratch[i] - mean)
            val norm = sqrt(acc)
            if (norm < 1e-3f) continue          // blank at this orientation too

            for (t in templates) {
                if (t.width != w || t.height != h) continue
                var dot = 0f
                for (i in 0 until n) dot += (scratch[i] - mean) * t.values[i]
                val score = dot / (norm * t.norm)
                if (score > bestScore) {
                    // Only treat a *different label* as the runner-up. The same digit
                    // scoring well at two orientations is agreement, not ambiguity.
                    if (t.label != best) secondScore = bestScore
                    bestScore = score
                    best = t.label
                } else if (score > secondScore && t.label != best) {
                    secondScore = score
                }
            }
        }

        if (bestScore < minCorrelation) return -1 to bestScore.coerceAtLeast(0f)
        if (secondScore > 0 && abs(bestScore - secondScore) < minMargin) {
            // Two different digits fit equally well; report the winner but flag the doubt
            // so the solver treats it as provisional rather than as a hard given.
            return best to (bestScore * 0.5f)
        }
        return best to bestScore
    }

    /** Quarter turn of a square patch, clockwise. */
    private fun rotate90(src: FloatArray, dst: FloatArray, size: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                dst[x * size + (size - 1 - y)] = src[y * size + x]
            }
        }
    }
}
