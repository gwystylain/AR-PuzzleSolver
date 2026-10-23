package com.puzzlesolver.core.image

/**
 * A colour image, three bytes a pixel, red then green then blue, rows top to bottom.
 *
 * Every other reader in the app wants luma, or luma with chroma beside it in
 * [CanvasView], because that is what the camera delivers and what their walls are read
 * from. Spot is the exception: its answer is a picture shown back to the user, and its
 * comparison is between colours -- a red flag on a red roof is the same luma as the roof
 * and not the same picture. So it keeps RGB, and converts once, when a photo is taken.
 *
 * Interleaved bytes rather than packed ints: six full-resolution photos are held at once,
 * and a quarter less memory for each of them is worth one more index calculation.
 */
class RgbImage(
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val data: ByteArray = ByteArray(width * height * 3),
) {
    init {
        require(data.size >= width * height * 3) { "buffer too small: ${data.size} < ${width * height * 3}" }
    }

    fun r(x: Int, y: Int): Int = data[(y * width + x) * 3].toInt() and 0xFF

    fun g(x: Int, y: Int): Int = data[(y * width + x) * 3 + 1].toInt() and 0xFF

    fun b(x: Int, y: Int): Int = data[(y * width + x) * 3 + 2].toInt() and 0xFF

    fun set(x: Int, y: Int, r: Int, g: Int, b: Int) {
        val i = (y * width + x) * 3
        data[i] = r.coerceIn(0, 255).toByte()
        data[i + 1] = g.coerceIn(0, 255).toByte()
        data[i + 2] = b.coerceIn(0, 255).toByte()
    }

    /** Packed 0xAARRGGBB, opaque, for handing to a platform bitmap. */
    fun toArgb(): IntArray {
        val out = IntArray(width * height)
        var s = 0
        for (i in out.indices) {
            val r = data[s].toInt() and 0xFF
            val g = data[s + 1].toInt() and 0xFF
            val b = data[s + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            s += 3
        }
        return out
    }

    /** BT.601 luma, the same weights the camera encodes Y with. */
    fun luma(dst: GrayImage = GrayImage(width, height)): GrayImage {
        require(dst.width == width && dst.height == height)
        var s = 0
        for (i in 0 until width * height) {
            val r = data[s].toInt() and 0xFF
            val g = data[s + 1].toInt() and 0xFF
            val b = data[s + 2].toInt() and 0xFF
            dst.data[i] = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
            s += 3
        }
        return dst
    }

    companion object {
        /**
         * Converts a camera frame to RGB, turned upright.
         *
         * [quarterTurns] is the clockwise rotation that brings the buffer upright, as
         * `PreviewGeometry` works it out for the preview -- so the photo comes out the way
         * the user was looking at it, which is the way they will want to see it back.
         * BT.601 full range, matching [CanvasView.rgbAt]. Grey when there is no chroma.
         *
         * [step] keeps every step-th pixel each way, for a reader that wants the frame
         * smaller and would only shrink it again. [into] is reused when it is the right
         * size, for a reader taking thirty frames a second that would otherwise hand the
         * collector a frame's worth of garbage each time.
         */
        fun fromCanvasView(view: CanvasView, quarterTurns: Int, step: Int = 1, into: RgbImage? = null): RgbImage {
            val turns = ((quarterTurns % 4) + 4) % 4
            val iw = view.width
            val ih = view.height
            val fw = if (turns % 2 == 0) iw else ih
            val fh = if (turns % 2 == 0) ih else iw
            val ow = fw / step
            val oh = fh / step
            val out = into?.takeIf { it.width == ow && it.height == oh } ?: RgbImage(ow, oh)
            val luma = view.luma.data
            val chroma = view.chroma?.data
            // Upright pixel back to buffer pixel; the inverse of PreviewGeometry's
            // toUpright. Each turn is a start per row and a fixed stride along it, so the
            // loop carries no branch per pixel.
            val along = when (turns) {
                1 -> -step * iw
                2 -> -step
                3 -> step * iw
                else -> step
            }
            var o = 0
            for (sy in 0 until oh) {
                val uy = sy * step
                var p = when (turns) {
                    1 -> (ih - 1) * iw + uy
                    2 -> (ih - 1 - uy) * iw + iw - 1
                    3 -> iw - 1 - uy
                    else -> uy * iw
                } - along
                for (sx in 0 until ow) {
                    p += along
                    val y = (luma[p].toInt() and 0xFF).toFloat()
                    if (chroma == null) {
                        out.data[o] = y.toInt().toByte()
                        out.data[o + 1] = y.toInt().toByte()
                        out.data[o + 2] = y.toInt().toByte()
                    } else {
                        val cb = (chroma[p * 2].toInt() and 0xFF) - 128f
                        val cr = (chroma[p * 2 + 1].toInt() and 0xFF) - 128f
                        out.data[o] = (y + 1.402f * cr).toInt().coerceIn(0, 255).toByte()
                        out.data[o + 1] = (y - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255).toByte()
                        out.data[o + 2] = (y + 1.772f * cb).toInt().coerceIn(0, 255).toByte()
                    }
                    o += 3
                }
            }
            return out
        }
    }
}
