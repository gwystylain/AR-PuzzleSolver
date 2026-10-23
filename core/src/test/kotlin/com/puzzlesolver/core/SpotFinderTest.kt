package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.spot.Homography
import com.puzzlesolver.core.puzzle.spot.ScreenLocator
import com.puzzlesolver.core.puzzle.spot.SpotFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Spot, end to end, against two rounds photographed in the room and one taken with the app.
 *
 * The two from the room are hand-held phone photos of the screen, 720x960 here, framed a
 * little differently each time and in one case from twice as far away. The first set is
 * seven photos, each with something changed: a moon added, a cloud taken away, a row of
 * windows on the castle taken away, a mast gone, a boat gone, a lighthouse gone, an
 * airship added. The windows are three pixels wide each; that photo was taken for the
 * unchanged original until the app was used on it. The second is six, and the room drew
 * its own green ring round each difference before they were taken, which makes it an
 * answer key: each box must land on the ring.
 *
 * The expected places are where each difference sits as a fraction of the screen,
 * checked by eye against the photos. A box has to contain that point, and be the only
 * box in its photo.
 */
class SpotFinderTest {

    private val finder = SpotFinder()

    /** Where each photo's difference is, as a fraction of the screen; null for none. */
    private val set1 = listOf(
        0.385f to 0.083f,   // a crescent moon, added
        0.262f to 0.103f,   // a cloud, taken away
        0.183f to 0.472f,   // the row of five windows on the castle, taken away
        0.498f to 0.735f,   // the big boat's mast, taken away
        0.357f to 0.728f,   // the middle boat, taken away
        0.579f to 0.505f,   // the small lighthouse, taken away
        0.926f to 0.229f,   // an airship, added
    )

    private val set2 = listOf(
        0.466f to 0.719f,   // on the beach
        0.461f to 0.152f,   // in the sky, top middle
        0.659f to 0.116f,   // in the sky, top right
        0.420f to 0.116f,   // a balloon
        0.478f to 0.493f,   // a sailing boat
        0.159f to 0.316f,   // the sun's glow, left
    )

    /**
     * The first round taken with the app itself: six of the first set's pictures, put up on
     * a monitor and photographed off it, in the order 7, 6, 5, 4, 3, 2. Positions are in this
     * round's own rectangle, not the first set's.
     *
     * This is the round that came back wrong before alignment stopped trusting the outlines:
     * two were drawn round the neon instead of the screen and a third was twenty pixels out
     * along one side, and only one difference -- the wrong one -- was found. Its fifth photo
     * is the one whose missing windows showed that the comparison could not see a small
     * thing taken away.
     */
    private val set3 = listOf(
        0.877f to 0.294f,   // the airship
        0.554f to 0.585f,   // the small lighthouse, taken away
        0.352f to 0.780f,   // the middle boat, taken away
        0.480f to 0.780f,   // the big boat's mast, taken away
        0.190f to 0.528f,   // the castle's windows, taken away
        0.258f to 0.172f,   // the cloud, taken away
    )

    @Test
    fun `finds the difference in each photo of a round taken with the app`() {
        check(SpotFixture.set("set3"), set3)
    }

    @Test
    fun `finds the one difference in each photo of the first set`() {
        check(SpotFixture.set("set1"), set1)
    }

    @Test
    fun `finds the ringed difference in each photo of the second set`() {
        check(SpotFixture.set("set2"), set2)
    }

    @Test
    fun `six photos without the windows`() {
        // The round the app actually takes: six photos, each with its own difference.
        val photos = SpotFixture.set("set1")
        val keep = listOf(0, 1, 3, 4, 5, 6)
        check(keep.map { photos[it] }, keep.map { set1[it] })
    }

    @Test
    fun `six photos without the far one`() {
        val photos = SpotFixture.set("set1")
        val keep = (1..6).toList()
        check(keep.map { photos[it] }, keep.map { set1[it] })
    }

    @Test
    fun `finds the screen in every photo`() {
        val locator = ScreenLocator()
        for (name in listOf("set1", "set2")) {
            for ((i, photo) in SpotFixture.set(name).withIndex()) {
                val screen = locator.locate(photo.luma())
                assertNotNull("$name photo ${i + 1}: ${locator.lastFailure}", screen)
                // The screen is wider than tall, and most of the frame's width.
                assertTrue("$name photo ${i + 1} aspect ${screen!!.aspect}", screen.aspect in 1.4..2.1)
            }
        }
    }

    @Test
    fun `a frame with no screen in it is not located`() {
        val dark = RgbImage(720, 960)
        for (i in dark.data.indices) dark.data[i] = 12
        val locator = ScreenLocator()
        assertNull(locator.locate(dark.luma()))
        assertTrue(locator.lastFailure.isNotEmpty())
    }

    @Test
    fun `a photo without the screen is left out and the rest still compared`() {
        val photos = SpotFixture.set("set2").toMutableList()
        val dark = RgbImage(720, 960)
        for (i in dark.data.indices) dark.data[i] = 12
        photos[2] = dark
        val result = finder.find(photos)
        assertNull(result.photos[2].screen)
        assertTrue(result.photos[2].differences.isEmpty())
        assertTrue(result.status, result.status.contains("not found in photo 3"))
        // The other five still find theirs; photo 3's own difference is simply absent from
        // the median, which is what a median is for.
        for (k in listOf(0, 1, 3, 4, 5)) assertHit("set2 less photo 3, photo ${k + 1}", result.photos[k], set2[k])
    }

    @Test
    fun `too few photos with the screen in them says so`() {
        val photos = SpotFixture.set("set2").take(2)
        val result = finder.find(photos)
        assertTrue(result.status, result.status.startsWith("Need the screen"))
        assertTrue(result.photos.all { it.differences.isEmpty() })
    }

    @Test
    fun `a camera frame is turned upright the way the preview turns it`() {
        // A 3x2 buffer whose luma is its own index, no chroma: grey RGB equal to the index.
        val luma = GrayImage(3, 2)
        for (i in 0 until 6) luma.data[i] = (i * 10).toByte()
        val view = CanvasView(luma)
        // A quarter turn clockwise: the buffer's bottom-left pixel comes to the top-left,
        // as PreviewGeometry puts it -- upright (x, y) is buffer (y, height - 1 - x).
        val turned = RgbImage.fromCanvasView(view, 1)
        assertEquals(2, turned.width)
        assertEquals(3, turned.height)
        assertEquals(luma[0, 1], turned.r(0, 0))
        assertEquals(luma[0, 0], turned.r(1, 0))
        assertEquals(luma[2, 1], turned.r(0, 2))
        val half = RgbImage.fromCanvasView(view, 2)
        assertEquals(luma[2, 1], half.r(0, 0))
        val three = RgbImage.fromCanvasView(view, 3)
        assertEquals(luma[2, 0], three.r(0, 0))
        assertEquals(luma[0, 0], three.r(0, 2))
    }

    @Test
    fun `homography fits four points exactly and composes`() {
        val src = doubleArrayOf(0.0, 0.0, 640.0, 0.0, 640.0, 360.0, 0.0, 360.0)
        val dst = doubleArrayOf(31.0, 402.5, 702.0, 388.0, 690.5, 811.0, 44.0, 790.0)
        val h = Homography.fit(src, dst)!!
        val p = DoubleArray(2)
        for (i in 0 until 4) {
            h.map(src[i * 2], src[i * 2 + 1], p)
            assertEquals(dst[i * 2], p[0], 1e-6)
            assertEquals(dst[i * 2 + 1], p[1], 1e-6)
        }
        val back = Homography.invert(h)!!
        val round = back * h
        round.map(123.0, 45.0, p)
        assertEquals(123.0, p[0], 1e-6)
        assertEquals(45.0, p[1], 1e-6)
    }

    private fun check(photos: List<RgbImage>, expected: List<Pair<Float, Float>?>) {
        val result = finder.find(photos)
        println("spot: ${result.status} in ${result.millis} ms")
        for ((k, photo) in result.photos.withIndex()) {
            println(
                "  photo ${k + 1}: " + photo.differences.joinToString { d ->
                    "[%.3f,%.3f - %.3f,%.3f] %.0f".format(d.left, d.top, d.right, d.bottom, d.score)
                }
            )
        }
        assertEquals(photos.size, result.photos.size)
        for ((k, photo) in result.photos.withIndex()) {
            assertNotNull("photo ${k + 1} lost its screen", photo.screen)
            val want = expected[k]
            if (want == null) {
                assertTrue("photo ${k + 1} has nothing changed and should have no box", photo.differences.isEmpty())
            } else {
                assertHit("photo ${k + 1}", photo, want)
            }
        }
    }

    private fun assertHit(label: String, photo: SpotFinder.Photo, want: Pair<Float, Float>) {
        assertEquals("$label: one box", 1, photo.differences.size)
        val d = photo.differences[0]
        // A little slack, so the test is about the box being on the thing rather than about
        // its exact edge.
        val slack = 0.01f
        val (x, y) = want
        assertTrue(
            "$label: box [%.3f,%.3f - %.3f,%.3f] misses %.3f,%.3f".format(d.left, d.top, d.right, d.bottom, x, y),
            x >= d.left - slack && x <= d.right + slack && y >= d.top - slack && y <= d.bottom + slack,
        )
        // And is about the size of the thing, not most of the screen.
        assertTrue("$label: box too big", abs(d.right - d.left) < 0.45f && abs(d.bottom - d.top) < 0.75f)
    }
}
