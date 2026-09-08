package com.puzzlesolver.core.puzzle.terminal

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import com.puzzlesolver.core.puzzle.GlyphClassifier

/**
 * Reads the three digits off one display.
 *
 * A display is three split-flap tiles in a row, and each tile is a rounded rectangle
 * with a bright border, a seam across the middle, a small hinge nub at each side, and a
 * bright digit drawn over all of it. That last part is the whole difficulty: the digit
 * is the same brightness as the border, so a threshold cannot separate them, and the
 * digit *touches* the border and the nubs -- a zero is at its widest exactly where the
 * nubs are -- so connected components cannot either.
 *
 * Two steps deal with it, and both were chosen by measurement against the reference
 * clip rather than by taste. Together they take the digit error rate over the clip's
 * 1860 legible digits from about one in twenty to two.
 *
 *  - **A morphological opening removes the tile and leaves the digit.** The border, the
 *    seam and the nubs are all thin -- three or four pixels where a digit stroke is a
 *    dozen -- so eroding by a disc erases them and eroding back restores the digit
 *    unharmed. It is the one operation that separates two things of identical
 *    brightness by their *thickness*, which is the only property that actually differs.
 *  - **The glyph is normalised by its height, not by its bounding box.** Every digit on
 *    this wall is drawn at one cap height, so height is a reliable ruler. Width is not:
 *    when a nub survives the opening still attached to a zero, it widens the bounding
 *    box, the box-normalised glyph is squeezed, and it correlates as a six. Measured on
 *    the reference clip, box normalisation misreads 8 to 12 digits where height
 *    normalisation misreads 2.
 *
 * The digits are read at full resolution. [DisplayDetector] finds the panels on a
 * decimated frame because a panel is large; a digit stroke is not, and decimating first
 * would throw away the only detail this stage has.
 */
class DigitReader(private val classifier: GlyphClassifier) {

    /** What one display turned out to hold. */
    class Reading(
        /** One entry per tile: 0..9, or -1 when the tile was legible but ambiguous. */
        @JvmField val digits: IntArray,
        /** Tiles that hold no digit at all. Three of these means a cleared display. */
        @JvmField val blankTiles: Int,
        /** The least confident of the three classifications, 0 when nothing was read. */
        @JvmField val confidence: Float,
    ) {
        val isBlank: Boolean get() = blankTiles == digits.size

        /** The number shown, or null when the display is blank or was not fully read. */
        val value: Int?
            get() {
                if (blankTiles > 0) return null
                var v = 0
                for (d in digits) {
                    if (d < 0) return null
                    v = v * 10 + d
                }
                return v
            }

        /** Human-readable, with a dash per blank tile and a question mark per doubt. */
        fun describe(): String {
            val sb = StringBuilder(digits.size)
            for (d in digits) sb.append(if (d < 0) '?' else ('0' + d))
            return if (isBlank) "-".repeat(digits.size) else sb.toString()
        }
    }

    private val patch = GrayImage(PATCH_WIDTH, PATCH_HEIGHT)
    private val mask = GrayImage(PATCH_WIDTH, PATCH_HEIGHT)
    private val normalised = GrayImage(NORMALISED_SIZE, NORMALISED_SIZE)
    private val histogram = IntArray(256)

    private val rows = LongArray(PATCH_HEIGHT)
    private val eroded = LongArray(PATCH_HEIGHT)
    private val narrow = LongArray(PATCH_HEIGHT)
    private val wide = LongArray(PATCH_HEIGHT)
    private val stack = IntArray(PATCH_WIDTH * PATCH_HEIGHT)
    private val labels = IntArray(PATCH_WIDTH * PATCH_HEIGHT)

    fun read(luma: GrayImage, box: DisplayDetector.Box, tiles: Int = TILES): Reading {
        val digits = IntArray(tiles) { -1 }
        var blank = 0
        var confidence = 1f
        for (k in 0 until tiles) {
            if (isolate(luma, box, k, tiles) == null) {
                blank++
                continue
            }
            val (label, score) = classifier.classify(normalised)
            digits[k] = label
            if (score < confidence) confidence = score
        }
        return Reading(digits, blank, if (blank == tiles) 0f else confidence)
    }

    /**
     * The normalised glyph for one tile, or null when the tile carries no digit.
     *
     * This is the reader's own intermediate result, exposed because the tool that builds
     * the shipped templates has to normalise a wall glyph *exactly* the way reading one
     * does -- see [TerminalDigits]. The returned image is reused on the next call.
     */
    fun isolate(
        luma: GrayImage,
        box: DisplayDetector.Box,
        tile: Int,
        tiles: Int = TILES,
    ): GrayImage? {
        // A hair off the top and bottom. The tile borders run right along those edges
        // and the opening deals with them, but the *frame* around the whole display
        // sometimes carries a thicker highlight that does not open away; two per cent is
        // enough to clear it and nowhere near enough to reach a digit, which stops well
        // inside the tile.
        val inset = (box.height * VERTICAL_INSET).toInt()
        val y0 = box.y + inset
        val y1 = box.y + box.height - inset
        val x0 = box.x + box.width * tile / tiles
        val x1 = box.x + box.width * (tile + 1) / tiles
        if (x1 - x0 < MIN_TILE_PIXELS || y1 - y0 < MIN_TILE_PIXELS) return null
        return if (isolateDigit(luma, x0, y0, x1, y1)) normalised else null
    }

    /**
     * Samples one tile, strips the tile away and normalises what is left into
     * [normalised].
     *
     * @return false when the tile holds no digit -- a cleared display, which is a
     *         reading in its own right and not a failure.
     */
    private fun isolateDigit(luma: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        resample(luma, x0, y0, x1, y1, patch)

        java.util.Arrays.fill(histogram, 0)
        for (i in 0 until PATCH_WIDTH * PATCH_HEIGHT) histogram[patch.data[i].toInt() and 0xFF]++
        val threshold = ImageOps.otsuThreshold(histogram, PATCH_WIDTH * PATCH_HEIGHT)

        // Ink is *bright* here, the opposite of the printed puzzles: these are lit
        // panels in a dark room, not paper.
        for (y in 0 until PATCH_HEIGHT) {
            var bits = 0L
            val row = y * PATCH_WIDTH
            for (x in 0 until PATCH_WIDTH) {
                if ((patch.data[row + x].toInt() and 0xFF) > threshold) bits = bits or (1L shl x)
            }
            rows[y] = bits
        }
        open()
        return largestComponent() && GlyphNormaliser.byHeight(mask, normalised)
    }

    /**
     * Box-filtered resample of an image rectangle into [dst].
     *
     * Averaged rather than point-sampled because this is a downscale -- a tile is around
     * fifty by eighty pixels at the distance the wall is read from, and the patch is
     * forty by fifty-six. Point sampling a stroke a dozen pixels wide through a
     * three-quarter scale produces ragged edges, and the whole of the next stage is
     * deciding whether an edge is a digit or a border.
     */
    private fun resample(src: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int, dst: GrayImage) {
        val sw = (x1 - x0).toFloat() / dst.width
        val sh = (y1 - y0).toFloat() / dst.height
        for (py in 0 until dst.height) {
            val ya = y0 + py * sh
            val yb = ya + sh
            val iy0 = ya.toInt().coerceIn(0, src.height - 1)
            val iy1 = (yb.toInt()).coerceIn(iy0, src.height - 1)
            for (px in 0 until dst.width) {
                val xa = x0 + px * sw
                val xb = xa + sw
                val ix0 = xa.toInt().coerceIn(0, src.width - 1)
                val ix1 = (xb.toInt()).coerceIn(ix0, src.width - 1)
                var sum = 0
                var n = 0
                for (y in iy0..iy1) {
                    val row = y * src.width
                    for (x in ix0..ix1) {
                        sum += src.data[row + x].toInt() and 0xFF
                        n++
                    }
                }
                dst.data[py * dst.width + px] = (sum / n).toByte()
            }
        }
    }

    /**
     * Morphological opening of [rows] by a seven-pixel disc, in place.
     *
     * One `Long` per row, because the patch is forty pixels wide and forty bits fit in
     * one. That turns erosion by a disc from forty-nine comparisons per pixel -- which
     * over ninety-six tiles a frame is twenty million operations and tens of
     * milliseconds -- into a handful of shifts and masks per *row*. Same answer, three
     * orders of magnitude less work, and it is the difference between this running on
     * the solver thread at ten hertz and not.
     *
     * Pixels outside the patch count as unset, so the border of the patch erodes. That
     * is the right way round: it costs nothing, since the digit is central, and the
     * alternative would preserve exactly the tile edges this is here to remove.
     */
    private fun open() {
        horizontal(rows, narrow, NARROW_HALF, erode = true)
        horizontal(rows, wide, WIDE_HALF, erode = true)
        for (y in 0 until PATCH_HEIGHT) {
            var acc = ALL_BITS
            for (i in SE_ROWS.indices) {
                val dy = y + i - SE_CENTRE
                if (dy < 0 || dy >= PATCH_HEIGHT) {
                    acc = 0L
                    break
                }
                acc = acc and rowAt(SE_ROWS[i], dy, rows)
            }
            eroded[y] = acc
        }
        horizontal(eroded, narrow, NARROW_HALF, erode = false)
        horizontal(eroded, wide, WIDE_HALF, erode = false)
        for (y in 0 until PATCH_HEIGHT) {
            var acc = 0L
            for (i in SE_ROWS.indices) {
                val dy = y + i - SE_CENTRE
                if (dy < 0 || dy >= PATCH_HEIGHT) continue
                acc = acc or rowAt(SE_ROWS[i], dy, eroded)
            }
            rows[y] = acc and ALL_BITS
        }
    }

    /**
     * The horizontally-processed row for one row of the structuring element.
     *
     * The two half-widths are precomputed once per pass; the disc's top and bottom rows
     * are a single pixel wide and want the source row untouched, which is why this is a
     * three-way choice and not a two-way one.
     */
    private fun rowAt(half: Int, y: Int, source: LongArray): Long = when (half) {
        WIDE_HALF -> wide[y]
        NARROW_HALF -> narrow[y]
        else -> source[y]
    }

    /** One row of the structuring element, applied across the width. */
    private fun horizontal(src: LongArray, dst: LongArray, half: Int, erode: Boolean) {
        for (y in 0 until PATCH_HEIGHT) {
            val v = src[y]
            var acc = v
            for (s in 1..half) {
                val left = (v shl s) and ALL_BITS
                val right = v ushr s
                acc = if (erode) acc and left and right else acc or left or right
            }
            dst[y] = acc
        }
    }

    /**
     * Keeps only the largest four-connected blob of [rows] and writes it into [mask].
     *
     * The opening removes the tile but not everything: a hinge nub can survive as its
     * own small blob, and so can a thicker corner of the border. Those are separate
     * components and the digit is by a wide margin the biggest, so taking the largest
     * removes them. It cannot remove a nub still *joined* to the digit -- that is what
     * height normalisation is for.
     *
     * @return false when nothing here is big enough to be a digit.
     */
    private fun largestComponent(): Boolean {
        val w = PATCH_WIDTH
        val h = PATCH_HEIGHT
        java.util.Arrays.fill(labels, 0)
        var bestLabel = 0
        var bestArea = 0
        var bestMinX = 0
        var bestMaxX = 0
        var bestMinY = 0
        var bestMaxY = 0
        var label = 0
        for (start in 0 until w * h) {
            if (labels[start] != 0) continue
            if (!isSet(start, w)) continue
            label++
            var top = 0
            stack[top++] = start
            labels[start] = label
            var area = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                area++
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0 && labels[p - 1] == 0 && isSet(p - 1, w)) {
                    labels[p - 1] = label; stack[top++] = p - 1
                }
                if (px < w - 1 && labels[p + 1] == 0 && isSet(p + 1, w)) {
                    labels[p + 1] = label; stack[top++] = p + 1
                }
                if (py > 0 && labels[p - w] == 0 && isSet(p - w, w)) {
                    labels[p - w] = label; stack[top++] = p - w
                }
                if (py < h - 1 && labels[p + w] == 0 && isSet(p + w, w)) {
                    labels[p + w] = label; stack[top++] = p + w
                }
            }
            if (area > bestArea) {
                bestArea = area
                bestLabel = label
                bestMinX = minX
                bestMaxX = maxX
                bestMinY = minY
                bestMaxY = maxY
            }
        }
        if (bestLabel == 0) return false

        val blobWidth = bestMaxX - bestMinX + 1
        val blobHeight = bestMaxY - bestMinY + 1
        // A cleared tile is not empty -- it still has a border and a seam, and after the
        // opening a fragment of them survives. What it does not have is anything the
        // size and shape of a digit. Measured over the reference clip the two
        // populations do not come close to touching: digits fill 23 to 40 per cent of
        // the patch and span 47 to 80 per cent of its width, cleared tiles fill 3 to 8
        // per cent and span 12 to 15. Each threshold sits in the middle of its gap.
        if (bestArea < MIN_DIGIT_AREA * PATCH_WIDTH * PATCH_HEIGHT) return false
        if (blobWidth < MIN_DIGIT_WIDTH * PATCH_WIDTH) return false
        if (blobHeight < MIN_DIGIT_HEIGHT * PATCH_HEIGHT) return false

        for (i in 0 until w * h) mask.data[i] = if (labels[i] == bestLabel) INK else 0
        return true
    }

    private fun isSet(index: Int, width: Int): Boolean =
        (rows[index / width] ushr (index % width)) and 1L != 0L

    companion object {
        /** Digit tiles on one display. */
        const val TILES = 3

        /**
         * Working size of one tile.
         *
         * Forty wide because that is what lets a row of the mask live in a single
         * `Long`, which is what makes the opening cheap. Fifty-six tall keeps the
         * tile's own aspect, so the digit is not distorted before it is normalised.
         */
        const val PATCH_WIDTH = 40
        const val PATCH_HEIGHT = 56

        const val NORMALISED_SIZE = GlyphNormaliser.SIZE

        private const val VERTICAL_INSET = 0.02f
        private const val MIN_TILE_PIXELS = 6

        private const val MIN_DIGIT_AREA = 0.14f
        private const val MIN_DIGIT_WIDTH = 0.30f
        private const val MIN_DIGIT_HEIGHT = 0.45f

        private const val INK: Byte = -1

        /**
         * The structuring element: a seven-pixel disc, as a half-width per row.
         *
         * Seven and not five or nine, measured: five leaves enough of a nub attached to
         * misread five digits over the reference clip, nine erodes a nine's bowl closed
         * and turns it into a zero eight times. Seven misreads two.
         */
        private const val NARROW_HALF = 2
        private const val WIDE_HALF = 3

        /**
         * Half-width of each row of the disc, top to bottom:
         *
         * ```
         *    0001000
         *    0111110
         *    1111111
         *    1111111
         *    1111111
         *    0111110
         *    0001000
         * ```
         */
        private val SE_ROWS = intArrayOf(0, NARROW_HALF, WIDE_HALF, WIDE_HALF, WIDE_HALF, NARROW_HALF, 0)
        private const val SE_CENTRE = 3

        private const val ALL_BITS = (1L shl PATCH_WIDTH) - 1L
    }
}

/**
 * Puts a glyph into a fixed frame so it can be correlated against a template.
 *
 * Shared by the reader and by whatever builds the templates, and that sharing is the
 * point: two normalisations that disagree by even a few per cent of scale produce
 * correlation scores that mean nothing. [com.puzzlesolver.core.puzzle.CellReader] makes
 * the same argument about its own pairing with the glyph atlas.
 *
 * The frame is set by the glyph's ink *height* and its centre of mass. Not by its
 * bounding box, which is the obvious choice and the wrong one here -- see [DigitReader]
 * for the measurements. Height works because every digit on this wall is drawn at one
 * cap height, so it is a ruler that does not move; and centre of mass, unlike a bounding
 * box, is barely shifted by a small blob stuck to one side of the glyph.
 */
object GlyphNormaliser {

    /** Side of the square patch the classifier correlates. */
    const val SIZE = 28

    /** Fraction of that square the glyph's height is scaled to fill. */
    const val FILL = 0.78f

    /** Above this, a pixel is ink. Templates arrive anti-aliased; masks do not. */
    const val INK_THRESHOLD = 128

    /**
     * Writes [src] into [dst], scaled so its ink is [FILL] of the patch height and
     * centred on the ink's centre of mass.
     *
     * @return false when [src] holds no ink, in which case [dst] is left blank.
     */
    fun byHeight(src: GrayImage, dst: GrayImage): Boolean {
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until src.height) {
            val row = y * src.width
            for (x in 0 until src.width) {
                if ((src.data[row + x].toInt() and 0xFF) <= INK_THRESHOLD) continue
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sumX += x
                sumY += y
                count++
            }
        }
        dst.fill(0)
        if (count == 0) return false

        val inkHeight = (maxY - minY + 1).toFloat()
        val scale = SIZE * FILL / inkHeight
        val centreX = (sumX / count).toFloat()
        val centreY = (sumY / count).toFloat()
        // Half the source footprint of one destination pixel. Averaging over it is what
        // gives the shrunken glyph the same soft edge the templates have; nearest
        // sampling here costs several points of correlation on every digit.
        val halfBox = 0.5f / scale
        for (py in 0 until SIZE) {
            val sy = centreY + (py + 0.5f - SIZE * 0.5f) / scale
            val y0 = (sy - halfBox).toInt().coerceIn(0, src.height - 1)
            val y1 = (sy + halfBox).toInt().coerceIn(y0, src.height - 1)
            for (px in 0 until SIZE) {
                val sx = centreX + (px + 0.5f - SIZE * 0.5f) / scale
                val x0 = (sx - halfBox).toInt().coerceIn(0, src.width - 1)
                val x1 = (sx + halfBox).toInt().coerceIn(x0, src.width - 1)
                var sum = 0
                var n = 0
                for (y in y0..y1) {
                    val row = y * src.width
                    for (x in x0..x1) {
                        sum += src.data[row + x].toInt() and 0xFF
                        n++
                    }
                }
                dst.data[py * SIZE + px] = (sum / n).toByte()
            }
        }
        return true
    }
}
