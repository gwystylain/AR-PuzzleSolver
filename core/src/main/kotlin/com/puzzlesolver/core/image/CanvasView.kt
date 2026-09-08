package com.puzzlesolver.core.image

/**
 * Two-channel chroma, Cb then Cr, at canvas resolution.
 *
 * Kept separate from the luma image rather than merged into an RGB one because luma is
 * what every existing adapter wants and what the whole detection stack is built on.
 * Interleaving colour into that path would mean touching code that works and that
 * cannot be re-verified without pointing a phone at a wall.
 */
class ChromaImage(
    @JvmField val width: Int,
    @JvmField val height: Int,
    /** Two bytes per texel: Cb then Cr, both biased by 128. */
    @JvmField val data: ByteArray = ByteArray(width * height * 2),
) {
    init {
        require(data.size >= width * height * 2) {
            "buffer too small: ${data.size} < ${width * height * 2}"
        }
    }

    fun cb(x: Int, y: Int): Int = data[(y * width + x) * 2].toInt() and 0xFF

    fun cr(x: Int, y: Int): Int = data[(y * width + x) * 2 + 1].toInt() and 0xFF

    fun fill(v: Int) = data.fill(v.coerceIn(0, 255).toByte())
}

/**
 * The canvas as the adapters see it: luma always, colour when the capture layer is
 * carrying it.
 *
 * Colour is optional because it costs bandwidth to stream and only one puzzle type
 * needs it. An adapter that wants colour and finds [chroma] null should decline to
 * read rather than guess from brightness -- on a wall of coloured buttons, luma alone
 * cannot tell a red hazard from a white target, and both look identical once the
 * sensor clips.
 */
class CanvasView(
    @JvmField val luma: GrayImage,
    @JvmField val chroma: ChromaImage? = null,
) {
    val hasColour: Boolean get() = chroma != null

    val width: Int get() = luma.width

    val height: Int get() = luma.height

    /**
     * Reconstructs RGB at one texel into [out], as 0..255 floats.
     *
     * BT.601, matching the encoding in the accumulator's fragment shader. Returns grey
     * when there is no chroma, which is the honest answer rather than a fabricated hue.
     */
    fun rgbAt(x: Int, y: Int, out: FloatArray) {
        val yy = luma.getClamped(x, y).toFloat()
        val c = chroma
        if (c == null) {
            out[0] = yy
            out[1] = yy
            out[2] = yy
            return
        }
        val cx = x.coerceIn(0, c.width - 1)
        val cy = y.coerceIn(0, c.height - 1)
        val cb = c.cb(cx, cy) - 128f
        val cr = c.cr(cx, cy) - 128f
        out[0] = (yy + 1.402f * cr).coerceIn(0f, 255f)
        out[1] = (yy - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 255f)
        out[2] = (yy + 1.772f * cb).coerceIn(0f, 255f)
    }

    /**
     * Mean RGB over an annulus centred on a texel, accumulated into [out].
     *
     * This is the primitive the button classifier needs and the reason [rgbAt] exists
     * at all. A lit button clips its own face to white, so its colour is only readable
     * in the glow around it; sampling a disc would average that glow with the clipped
     * middle and wash it out.
     *
     * Texels at or above [clipLevel] on any channel are skipped, because a clipped
     * sample carries no colour and including it drags every reading toward white.
     *
     * @return the number of texels that contributed, so the caller can tell a dark
     *         reading from an absent one.
     */
    fun meanRgbInAnnulus(
        cx: Float,
        cy: Float,
        innerRadius: Float,
        outerRadius: Float,
        out: FloatArray,
        clipLevel: Int = 250,
        scratch: FloatArray = FloatArray(3),
    ): Int {
        out[0] = 0f
        out[1] = 0f
        out[2] = 0f
        var n = 0
        val inner2 = innerRadius * innerRadius
        val outer2 = outerRadius * outerRadius
        val x0 = (cx - outerRadius).toInt().coerceAtLeast(0)
        val x1 = (cx + outerRadius).toInt().coerceAtMost(width - 1)
        val y0 = (cy - outerRadius).toInt().coerceAtLeast(0)
        val y1 = (cy + outerRadius).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            val dy = y - cy
            for (x in x0..x1) {
                val dx = x - cx
                val d2 = dx * dx + dy * dy
                if (d2 < inner2 || d2 > outer2) continue
                rgbAt(x, y, scratch)
                if (scratch[0] >= clipLevel || scratch[1] >= clipLevel || scratch[2] >= clipLevel) continue
                out[0] += scratch[0]
                out[1] += scratch[1]
                out[2] += scratch[2]
                n++
            }
        }
        if (n > 0) {
            out[0] /= n
            out[1] /= n
            out[2] /= n
        }
        return n
    }

    /** Peak luma within a radius, which is what says whether a button is lit at all. */
    fun peakLuma(cx: Float, cy: Float, radius: Float): Int {
        var peak = 0
        val r2 = radius * radius
        val x0 = (cx - radius).toInt().coerceAtLeast(0)
        val x1 = (cx + radius).toInt().coerceAtMost(width - 1)
        val y0 = (cy - radius).toInt().coerceAtLeast(0)
        val y1 = (cy + radius).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            val dy = y - cy
            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy * dy > r2) continue
                val v = luma[x, y]
                if (v > peak) peak = v
            }
        }
        return peak
    }
}
