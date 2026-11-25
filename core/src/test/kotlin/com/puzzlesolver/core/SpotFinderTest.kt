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
     *
     * Since 2026-11-25 the locator floods again from the very middle when its first outline
     * may be the neon's, and here, in set 4 and in set 5 that put those outlines on the
     * screen, and the compared area -- which had reached into the bezel and the neon -- on
     * the picture alone. The places below are where each thing is in that, checked by eye.
     */
    private val set3 = listOf(
        0.924f to 0.225f,   // the airship
        0.572f to 0.510f,   // the small lighthouse, taken away
        0.357f to 0.752f,   // the middle boat, taken away
        0.509f to 0.737f,   // the big boat's mast, taken away
        0.179f to 0.474f,   // the castle's windows, taken away
        0.260f to 0.102f,   // the cloud, taken away
    )

    /**
     * The first round in the room with the app, 2026-10-31: a beach. Photos 3 and 5 were
     * taken in the room's answer phase, green neon and its own ring round the difference;
     * photo 6 is the plain green screen the room shows between pictures. On the phone the
     * blank one was the biggest screen, so it became the reference and nothing lined up.
     */
    private val set4 = listOf(
        0.259f to 0.805f,   // the umbrella and sun lounger, taken away
        0.631f to 0.535f,   // a swimmer, taken away
        0.637f to 0.229f,   // a bird, added (ringed)
        0.298f to 0.164f,   // a flag on the lighthouse, added
        0.300f to 0.128f,   // a plane, added (ringed)
        null,               // no picture
    )

    /**
     * The second, a minute later: a desert town. Photo 1 is far off and in the answer phase;
     * two outlines went round the neon, and on the phone one of them was the reference.
     */
    private val set5 = listOf(
        0.512f to 0.685f,   // the walker on the path, taken away (ringed)
        0.931f to 0.368f,   // the palm on the wall, taken away
        0.404f to 0.093f,   // a moon, added
        0.195f to 0.614f,   // something in the bushes, added
        0.585f to 0.829f,   // a second walker, added
        0.838f to 0.647f,   // a flower on a bush, taken away
    )

    /**
     * Three of the five seven-photo rounds taken in the room on 2026-11-19, the first with
     * the app at seven. Every picture has a difference; where it could not be told by eye
     * the photo is in the round's `unknown` set and only has to come back with one box.
     *
     * Round 1, a coast: photo 1 was taken as the exposure was still coming down and is a
     * stop and a half over; photo 7's moon is at the very top of the screen, and was lost
     * to the old edge rule.
     */
    private val set6 = listOf(
        null,               // over-exposed; not told by eye
        0.913f to 0.438f,   // a chimney, added
        0.339f to 0.672f,   // a sail, turned dark
        0.425f to 0.624f,   // a sailboat, taken away
        0.207f to 0.472f,   // a white wave, taken away
        0.305f to 0.322f,   // a white wave, added
        0.711f to 0.065f,   // a moon at the top edge, added
    )

    /** Round 4, a studio: small things, one beside the screen's edge. */
    private val set7 = listOf(
        null,               // taken half a stop off the rest; not told by eye
        0.924f to 0.525f,   // a candle on the shelf, added
        0.680f to 0.823f,   // a robot vacuum, added
        0.886f to 0.493f,   // a shelf ornament, changed
        0.208f to 0.870f,   // a line on the floor, taken away
        0.226f to 0.337f,   // something by the window, added
        null,               // taken during an exposure change; not told by eye
    )

    /** Round 5, the studio again, where nearly every photo was missed on the phone. */
    private val set8 = listOf(
        0.919f to 0.511f,   // a sailboat on the shelf, added -- in a photo a stop over
        0.878f to 0.798f,   // a mark on the floor, added
        0.418f to 0.383f,   // a thin cloud, taken away
        0.701f to 0.449f,   // the camera on the tripod, changed
        null, null, null,   // not told by eye
    )

    /**
     * The first round of 2026-11-24, the studio again. In every photo the locator's flood
     * crossed the bezel's glow into the neon round the screen, so the screen was compared
     * as the middle third of the neon and four of the seven boxes were on the neon. Photo
     * 6 is the room's brighter picture of the round.
     */
    private val set9 = listOf(
        0.749f to 0.607f,   // a creature behind the tripod, added
        0.227f to 0.342f,   // a lamp arm by the window, taken away
        0.104f to 0.817f,   // the floor lamp's base, taken away
        0.169f to 0.525f,   // the plant pot, turned white
        0.549f to 0.632f,   // a phone on the table, added
        null, null,         // not told by eye
    )

    @Test
    fun `finds the moon at the top edge and the rest of a seven photo round`() {
        check(SpotFixture.set("set6"), set6, unknown = setOf(0))
    }

    @Test
    fun `finds small differences in a seven photo round`() {
        check(SpotFixture.set("set7"), set7, unknown = setOf(0, 6))
    }

    @Test
    fun `finds the difference in a photo taken at another exposure`() {
        check(SpotFixture.set("set8"), set8, unknown = setOf(4, 5, 6))
    }

    @Test
    fun `finds the differences in a round whose every outline went round the neon`() {
        check(SpotFixture.set("set9"), set9, unknown = setOf(5, 6))
    }

    @Test
    fun `the outline is the screen and not the neon round it`() {
        // Each checked by eye, 2026-11-25. The first is set 9's first photo, where the
        // bezel glowed just over the flood's level all round. The other two are first
        // photos of rounds that day with the screen small and low in the frame, where the
        // flood's seeds reached the neon itself. Every one was outlined round the neon,
        // 100 to 170 pixels out on each side.
        val cases = listOf(
            "set9/1.jpg" to floatArrayOf(216f, 769f, 783f, 768f, 776f, 1069f, 236f, 1094f),
            "neon/round2-1.jpg" to floatArrayOf(169f, 699f, 873f, 693f, 853f, 1086f, 188f, 1090f),
            "neon/round4-1.jpg" to floatArrayOf(244f, 693f, 882f, 678f, 868f, 1037f, 261f, 1037f),
        )
        val locator = ScreenLocator()
        for ((name, want) in cases) {
            val stream = javaClass.classLoader.getResourceAsStream("spot/$name")!!
            val screen = locator.locate(SpotFixture.read(stream).luma())
            assertNotNull("$name: ${locator.lastFailure}", screen)
            for (k in 0 until 8) {
                assertTrue(
                    "$name corner ${k / 2} at ${screen!!.corners.joinToString(" ") { "%.0f".format(it) }}",
                    abs(screen.corners[k] - want[k]) <= 30f,
                )
            }
        }
    }

    @Test
    fun `finds the difference in each photo of a round taken with the app`() {
        check(SpotFixture.set("set3"), set3)
    }

    @Test
    fun `leaves out the blank screen between pictures and finds the rest`() {
        check(SpotFixture.set("set4"), set4, blank = setOf(5))
    }

    @Test
    fun `finds the differences in a round with outlines round the neon`() {
        check(SpotFixture.set("set5"), set5)
    }

    @Test
    fun `tells a picture from the blank screen between pictures`() {
        val locator = ScreenLocator()
        for (name in listOf("set1", "set2", "set3", "set4", "set5", "set6", "set7", "set8", "set9")) {
            for ((i, photo) in SpotFixture.set(name).withIndex()) {
                val screen = locator.locate(photo.luma())!!
                val blank = name == "set4" && i == 5
                assertEquals(
                    "$name photo ${i + 1} detail ${finder.detail(photo, screen)}",
                    !blank,
                    finder.hasPicture(photo, screen),
                )
            }
        }
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
        for (name in listOf("set1", "set2", "set3", "set4", "set5", "set6", "set7", "set8", "set9")) {
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
        assertTrue(result.status, result.status.startsWith("Need at least"))
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

    private fun check(
        photos: List<RgbImage>,
        expected: List<Pair<Float, Float>?>,
        blank: Set<Int> = emptySet(),
        unknown: Set<Int> = emptySet(),
    ) {
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
            if (k in blank) {
                assertEquals("photo ${k + 1} is blank", SpotFinder.NOTE_BLANK, photo.note)
                assertTrue("photo ${k + 1} is blank and should have no box", photo.differences.isEmpty())
                continue
            }
            assertNull("photo ${k + 1} was left out: ${photo.note}", photo.note)
            if (k in unknown) {
                // Every picture has a difference, so every photo gets one box.
                assertEquals("photo ${k + 1}: one box", 1, photo.differences.size)
                continue
            }
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
