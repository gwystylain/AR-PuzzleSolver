package com.puzzlesolver.app.frame

/**
 * Where a camera pixel lands on the screen, and vice versa.
 *
 * Needed because dropping ARCore also dropped `Frame.transformCoordinates2d`, which was
 * quietly doing all of this. Two consumers have to agree exactly or the feature is
 * worthless: the GL background, which needs texture coordinates for the four corners of
 * the screen, and the gem overlay, which needs to put a ring around a gem found at an
 * image coordinate. Both are derived here from one model so they cannot drift apart.
 *
 * The model is: rotate the camera buffer by [rotationDegrees] clockwise to get an
 * upright image, then centre-crop it to fill the viewport. Nothing is letterboxed --
 * a black bar down the side of a viewfinder is worse than losing a little of the edges.
 */
class PreviewGeometry(
    /** Camera buffer dimensions, as delivered. */
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    /** Clockwise rotation to bring the buffer upright: sensor orientation less display. */
    val rotationDegrees: Int,
) {
    private val quarterTurns = ((rotationDegrees % 360) + 360) % 360 / 90

    /** Size of the upright image, before cropping. */
    private val uprightWidth = if (quarterTurns % 2 == 0) imageWidth else imageHeight
    private val uprightHeight = if (quarterTurns % 2 == 0) imageHeight else imageWidth

    private val scale: Float = maxOf(
        viewWidth.toFloat() / uprightWidth,
        viewHeight.toFloat() / uprightHeight,
    )

    /** Left and top of the visible part of the upright image, in upright pixels. */
    private val cropX = (uprightWidth - viewWidth / scale) * 0.5f
    private val cropY = (uprightHeight - viewHeight / scale) * 0.5f

    /** Image pixel to upright pixel. */
    private fun toUpright(ix: Float, iy: Float, out: FloatArray) {
        when (quarterTurns) {
            1 -> { out[0] = imageHeight - 1 - iy; out[1] = ix }
            2 -> { out[0] = imageWidth - 1 - ix; out[1] = imageHeight - 1 - iy }
            3 -> { out[0] = iy; out[1] = imageWidth - 1 - ix }
            else -> { out[0] = ix; out[1] = iy }
        }
    }

    /** Upright pixel back to image pixel. */
    private fun fromUpright(dx: Float, dy: Float, out: FloatArray) {
        when (quarterTurns) {
            1 -> { out[0] = dy; out[1] = imageHeight - 1 - dx }
            2 -> { out[0] = imageWidth - 1 - dx; out[1] = imageHeight - 1 - dy }
            3 -> { out[0] = imageWidth - 1 - dy; out[1] = dx }
            else -> { out[0] = dx; out[1] = dy }
        }
    }

    /**
     * Camera image pixel to viewport pixel, with the origin at the top left of the view.
     *
     * @param out receives x then y. Values outside the viewport are legitimate: a gem
     *        can be detected in a part of the frame the centre crop does not show.
     */
    fun imageToView(ix: Float, iy: Float, out: FloatArray) {
        toUpright(ix, iy, out)
        out[0] = (out[0] - cropX) * scale
        out[1] = (out[1] - cropY) * scale
    }

    /** A length in image pixels, as a length on screen. Isotropic: the crop is uniform. */
    fun scaleLength(pixels: Float): Float = pixels * scale

    /**
     * Texture coordinates for the four corners of the screen quad, in the order the
     * background shader derives its NDC: bottom-left, bottom-right, top-left, top-right.
     *
     * Returned in the SurfaceTexture's own normalised space -- s across the buffer, t up
     * it -- so the caller still has to put them through
     * `SurfaceTexture.getTransformMatrix`, which is what accounts for the producer's own
     * crop and flip.
     */
    fun quadTexCoords(out: FloatArray) {
        // Screen corners in upright pixels. Y grows downward on screen, so the visually
        // bottom corners are the larger dy.
        val corners = floatArrayOf(
            cropX, cropY + viewHeight / scale,                    // bottom-left
            cropX + viewWidth / scale, cropY + viewHeight / scale, // bottom-right
            cropX, cropY,                                         // top-left
            cropX + viewWidth / scale, cropY,                     // top-right
        )
        val tmp = FloatArray(2)
        for (i in 0 until 4) {
            fromUpright(corners[i * 2], corners[i * 2 + 1], tmp)
            out[i * 2] = tmp[0] / imageWidth
            // A texture coordinate's t runs up the image while a pixel row index runs
            // down it. Missing this flips the preview vertically, which on a wall of
            // round buttons looks entirely plausible and is not.
            out[i * 2 + 1] = 1f - tmp[1] / imageHeight
        }
    }

    /** Applies a `SurfaceTexture` transform matrix to four (s, t) pairs, in place. */
    fun applyTransform(matrix: FloatArray, coords: FloatArray) {
        for (i in 0 until 4) {
            val s = coords[i * 2]
            val t = coords[i * 2 + 1]
            coords[i * 2] = matrix[0] * s + matrix[4] * t + matrix[12]
            coords[i * 2 + 1] = matrix[1] * s + matrix[5] * t + matrix[13]
        }
    }
}
