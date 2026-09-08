package com.puzzlesolver.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.TemplateGlyphClassifier

/**
 * Renders the characters we need into a texture atlas, and -- from the same
 * rasterisations -- builds the classifier templates.
 *
 * Doing both from one source is the point. Matching wall glyphs against templates
 * drawn by the device's own font engine at the device's own resolution beats
 * shipping baked bitmaps: no resampling artefacts, no mismatch between the
 * template's stroke weight and the atlas's, and the templates track whatever
 * typeface we choose for the overlay.
 */
class GlyphAtlas(
    private val characters: String = "0123456789",
    private val cellPixels: Int = 64,
    private val templateSize: Int = 28,
) {
    private var textureId = 0
    private var columns = 0
    private var rows = 0

    val texture: Int get() = textureId

    /** Templates for [TemplateGlyphClassifier], labelled by the digit they show. */
    val templates: List<TemplateGlyphClassifier.Template> by lazy { buildTemplates() }

    fun initialize() {
        columns = 4
        rows = (characters.length + columns - 1) / columns
        val bmp = Bitmap.createBitmap(columns * cellPixels, rows * cellPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = cellPixels * 0.78f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        characters.forEachIndexed { index, ch ->
            val cx = (index % columns) * cellPixels + cellPixels / 2f
            val cy = (index / columns) * cellPixels
            val s = ch.toString()
            paint.getTextBounds(s, 0, 1, bounds)
            // Centre on the glyph's ink box rather than the font baseline, so a "1"
            // and an "8" land in the same place.
            val baseline = cy + cellPixels / 2f - bounds.exactCenterY()
            canvas.drawText(s, cx, baseline, paint)
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bmp.recycle()
    }

    /** Atlas sub-rectangle for a character, as [u0, v0, u1, v1]. Null if not present. */
    fun uvFor(ch: Char): FloatArray? {
        val index = characters.indexOf(ch)
        if (index < 0) return null
        val col = index % columns
        val row = index / columns
        val du = 1f / columns
        val dv = 1f / rows
        return floatArrayOf(col * du, row * dv, (col + 1) * du, (row + 1) * dv)
    }

    /**
     * Rasterises each character at [templateSize], normalised the same way
     * [com.puzzlesolver.core.puzzle.CellReader.normalize] normalises wall cells:
     * cropped to the ink box, scaled to fill, dark ink on a light ground.
     *
     * The two normalisations have to agree or the correlation scores are meaningless,
     * which is why this mirrors the reader's margin and polarity exactly.
     */
    private fun buildTemplates(): List<TemplateGlyphClassifier.Template> {
        val out = ArrayList<TemplateGlyphClassifier.Template>(characters.length)
        val margin = 2
        val target = templateSize - 2 * margin

        // Both weights, because stroke weight is what normalised cross-correlation is
        // most sensitive to after shape: a bold template against a light printed digit
        // scores around 0.5, which is neither a match nor a clean rejection. Carrying two
        // weights per digit costs one extra dot product each and covers most print.
        val paints = listOf(Typeface.NORMAL, Typeface.BOLD).map { weight ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, weight)
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
            }
        }

        for (paint in paints) characters.forEachIndexed { index, ch ->
            // Render large, then measure and downsample, so the ink box is accurate.
            val big = 128
            paint.textSize = big * 0.8f
            val bounds = Rect()
            paint.getTextBounds(ch.toString(), 0, 1, bounds)
            if (bounds.isEmpty) return@forEachIndexed

            val bmp = Bitmap.createBitmap(bounds.width() + 4, bounds.height() + 4, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawText(ch.toString(), (-bounds.left + 2).toFloat(), (-bounds.top + 2).toFloat(), paint)
            }

            val scale = target.toFloat() / maxOf(bmp.width, bmp.height)
            val outW = (bmp.width * scale).toInt().coerceAtLeast(1)
            val outH = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)

            val image = GrayImage(templateSize, templateSize)
            image.fill(255)
            val offX = (templateSize - outW) / 2
            val offY = (templateSize - outH) / 2
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    val p = scaled.getPixel(x, y)
                    val luma = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p))
                    image[offX + x, offY + y] = luma.toInt()
                }
            }
            bmp.recycle()
            scaled.recycle()

            // Label is the digit itself; sudoku uses 1..9 and treats 0 as unused.
            out.add(TemplateGlyphClassifier.Template(ch - '0', image))
        }
        return out
    }

    fun release() {
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
    }
}
