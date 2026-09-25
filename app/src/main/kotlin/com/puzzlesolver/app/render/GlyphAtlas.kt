package com.puzzlesolver.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils

/** Renders the characters the overlays draw into a texture atlas. */
class GlyphAtlas(
    private val characters: String = "0123456789",
    private val cellPixels: Int = 64,
) {
    private var textureId = 0
    private var columns = 0
    private var rows = 0

    val texture: Int get() = textureId

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

    fun release() {
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
    }
}
