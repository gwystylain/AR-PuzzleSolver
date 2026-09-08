package com.puzzlesolver.app

import com.puzzlesolver.app.frame.PreviewGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping between a camera pixel and a screen pixel.
 *
 * Worth testing rather than eyeballing because it fails *plausibly*. A ninety-degree
 * error, a missing vertical flip or a crop applied to the wrong axis all produce a
 * preview that looks like a preview and highlights that look like highlights, landing
 * confidently on the wrong gems. On a wall of near-identical round buttons there is
 * nothing in the picture to notice it by.
 *
 * The property that matters is not any particular number, it is that the two consumers
 * agree: the texture coordinates handed to the background shader and the image-to-screen
 * mapping used to place the rings have to describe the same crop, or the rings drift off
 * the gems by exactly the amount nobody can see until they walk to the wrong button.
 */
class PreviewGeometryTest {

    /** A 1080p sensor mounted at 90 degrees, shown on a tall portrait phone. */
    private fun portrait() = PreviewGeometry(
        imageWidth = 1920,
        imageHeight = 1080,
        viewWidth = 1080,
        viewHeight = 2376,
        rotationDegrees = 90,
    )

    @Test
    fun `the two consumers describe the same crop`() {
        val g = portrait()
        val tex = FloatArray(8)
        g.quadTexCoords(tex)

        // Each texture coordinate is the camera pixel that should appear at one corner
        // of the screen. Putting it back through the forward mapping must return that
        // corner. Bottom-left, bottom-right, top-left, top-right, as the shader orders.
        val corners = arrayOf(
            floatArrayOf(0f, 2376f),
            floatArrayOf(1080f, 2376f),
            floatArrayOf(0f, 0f),
            floatArrayOf(1080f, 0f),
        )
        val out = FloatArray(2)
        for (i in 0 until 4) {
            val ix = tex[i * 2] * 1920f
            // quadTexCoords returns t measured up the image; a pixel row runs down it.
            val iy = (1f - tex[i * 2 + 1]) * 1080f
            g.imageToView(ix, iy, out)
            assertEquals("corner $i x", corners[i][0], out[0], 2f)
            assertEquals("corner $i y", corners[i][1], out[1], 2f)
        }
    }

    @Test
    fun `the image is rotated upright, not merely stretched`() {
        val g = portrait()
        val out = FloatArray(2)

        // The centre stays the centre whatever the rotation, which is the one point a
        // wrong quarter-turn cannot betray -- so it is checked first and then ignored.
        g.imageToView(960f, 540f, out)
        assertEquals(540f, out[0], 2f)
        assertEquals(1188f, out[1], 2f)

        // Moving along the sensor's long axis has to move *down* the portrait screen. If
        // this came out horizontal the preview would be sideways.
        g.imageToView(960f + 400f, 540f, out)
        assertEquals("must not move sideways", 540f, out[0], 2f)
        assertTrue("must move down the screen", out[1] > 1188f)

        // And along the short axis, across the screen.
        g.imageToView(960f, 540f + 200f, out)
        assertEquals("must not move vertically", 1188f, out[1], 2f)
        assertTrue("must move across the screen", out[0] < 540f)
    }

    @Test
    fun `it fills the viewport rather than letterboxing it`() {
        val g = portrait()
        val out = FloatArray(2)
        // A 16:9 sensor on a 22:9 screen has to be cropped left and right to fill it. So
        // the image edges land outside the viewport, never inside: a black bar down the
        // side of a viewfinder is worse than losing a little of the edges.
        g.imageToView(0f, 0f, out)
        val top = out[1]
        g.imageToView(1919f, 0f, out)
        val bottom = out[1]
        // Within a pixel or two: the rotation is expressed over pixel *indices*, so the
        // last row lands a shade inside the edge rather than exactly on it.
        assertTrue("the long axis should just fill the height ($top..$bottom)",
            top <= 2f && bottom >= 2373f)

        g.imageToView(960f, 0f, out)
        val right = out[0]
        g.imageToView(960f, 1079f, out)
        val left = out[0]
        assertTrue("the short axis should overflow the width", right > 1080f && left < 0f)
    }

    @Test
    fun `a length scales the same in both axes`() {
        val g = portrait()
        val out = FloatArray(2)
        g.imageToView(960f, 540f, out)
        val cx = out[0]
        val cy = out[1]
        g.imageToView(960f + 100f, 540f, out)
        val along = out[1] - cy
        g.imageToView(960f, 540f + 100f, out)
        val across = cx - out[0]
        // The crop is uniform, so a gem's radius can be scaled with one number. If these
        // ever diverge, the rings drawn round the gems would be ellipses.
        assertEquals(along, across, 0.5f)
        assertEquals(along, g.scaleLength(100f), 0.5f)
    }

    @Test
    fun `an unrotated sensor is handled too`() {
        // Not the phone this was built for, but the code takes a rotation and must not
        // quietly assume ninety.
        val g = PreviewGeometry(1920, 1080, 1920, 1080, rotationDegrees = 0)
        val out = FloatArray(2)
        g.imageToView(0f, 0f, out)
        assertEquals(0f, out[0], 0.5f)
        assertEquals(0f, out[1], 0.5f)
        g.imageToView(1919f, 1079f, out)
        assertEquals(1919f, out[0], 0.5f)
        assertEquals(1079f, out[1], 0.5f)
    }
    // --- composing with the producer's own transform ----------------------

    /**
     * What this OnePlus actually reports from `SurfaceTexture.getTransformMatrix`:
     * s' = 1 - t, t' = 1 - s. It transposes, which means the camera is applying the
     * sensor's ninety-degree mount itself. Measured at all four display rotations and
     * identical in every one, so it is a property of the producer and not of the display.
     */
    private fun transposingProducer() = FloatArray(16).also {
        it[0] = 0f; it[4] = -1f; it[12] = 1f
        it[1] = -1f; it[5] = 0f; it[13] = 1f
        it[10] = 1f; it[15] = 1f
    }

    /** The ordinary one, and what the code used to assume: a vertical flip, no rotation. */
    private fun flippingProducer() = FloatArray(16).also {
        it[0] = 1f; it[5] = -1f; it[13] = 1f
        it[10] = 1f; it[15] = 1f
    }

    /**
     * Which component of the finished texture coordinate the screen's x axis runs along.
     * 0 is the buffer's width, 1 is its height -- which is the whole question.
     */
    private fun screenXRunsAlong(tex: FloatArray): Int =
        if (kotlin.math.abs(tex[2] - tex[0]) > kotlin.math.abs(tex[3] - tex[1])) 0 else 1

    /**
     * The bug, kept so the composition cannot quietly return to it.
     *
     * Rotating by the sensor mount on top of a producer that has already done so puts
     * two turns in opposite senses against each other, and they cancel: a 1920x1080
     * landscape buffer laid straight down a portrait screen. It looks exactly like a
     * preview, which is why it survived until someone held the phone and said so.
     */
    @Test
    fun `rotating on top of a rotating producer lands sideways`() {
        val tex = FloatArray(8)
        val g = portrait()
        g.quadTexCoords(tex)
        g.applyTransform(transposingProducer(), tex)
        assertEquals(
            "screen-x running along the buffer's long axis in portrait is the sideways preview",
            0,
            screenXRunsAlong(tex),
        )
    }

    /**
     * And the fix: when the producer transposes, the dimensions it presents are the
     * swapped ones and the only turn left to apply is the display's.
     */
    @Test
    fun `a rotating producer is composed with the display rotation alone`() {
        val tex = FloatArray(8)
        // Portrait: display rotation zero, so no turn of our own at all.
        val portraitBg = PreviewGeometry(
            imageWidth = 1080, imageHeight = 1920,
            viewWidth = 1080, viewHeight = 2376,
            rotationDegrees = 0,
        )
        portraitBg.quadTexCoords(tex)
        portraitBg.applyTransform(transposingProducer(), tex)
        assertEquals(
            "in portrait the screen's short axis must run along the buffer's short axis",
            1,
            screenXRunsAlong(tex),
        )

        // Landscape: the display has turned 90, so we undo exactly that.
        val landscapeBg = PreviewGeometry(
            imageWidth = 1080, imageHeight = 1920,
            viewWidth = 2376, viewHeight = 1080,
            rotationDegrees = 270,
        )
        landscapeBg.quadTexCoords(tex)
        landscapeBg.applyTransform(transposingProducer(), tex)
        assertEquals(
            "in landscape the screen's long axis must run along the buffer's long axis",
            0,
            screenXRunsAlong(tex),
        )
    }

    /**
     * The background and the overlay are two different geometry objects once the
     * producer rotates -- one starting from the buffer, one from what the producer
     * presents -- and they still have to describe the same crop, or the rings sit at a
     * different zoom from the image under them and drift off the gems.
     */
    @Test
    fun `the background and overlay geometries agree on the crop`() {
        val overlay = portrait()
        val background = PreviewGeometry(
            imageWidth = 1080, imageHeight = 1920,
            viewWidth = 1080, viewHeight = 2376,
            rotationDegrees = 0,
        )
        assertEquals(
            "a camera pixel must be the same size on screen either way",
            overlay.scaleLength(100f),
            background.scaleLength(100f),
            0.001f,
        )
    }

    /**
     * The other half: a producer that only flips still needs the full rotation from us,
     * which is the path every device that is not this one takes.
     */
    @Test
    fun `a producer that only flips still needs the full rotation`() {
        val tex = FloatArray(8)
        val g = portrait()
        g.quadTexCoords(tex)
        g.applyTransform(flippingProducer(), tex)
        assertEquals(
            "screen-x must run along the buffer's short axis",
            1,
            screenXRunsAlong(tex),
        )
    }
}
