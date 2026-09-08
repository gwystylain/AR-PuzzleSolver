package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.gems.GemBlobDetector
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this frame is allowed to have an opinion about the exposure.
 *
 * `washedOut` leaves here as `PuzzleAdapter.exposureHint` and the auto-exposure loop
 * closes on it, and the loop's settled state is terminal. That makes a wrong answer
 * considerably worse than no answer: no answer costs one frame, because the next frame
 * asks again, while a wrong one is acted on and then latched for the session.
 *
 * The failure that put this here was found by leaving the phone face down on a desk with
 * Gems selected. At 1/250 s in a dark room the frame is black, but sensor noise still
 * clears the local-background threshold often enough to yield ten to twenty blobs, one
 * to six of which read as lit, and the handful of saturated texels inside them average
 * to a confident *0% washed out*. Eight seconds of that settled the loop, which would
 * then have declined to darken anything when the wall finally came into view. The
 * giveaway in the heartbeat is the pitch: 43 px one frame, 436 px the next, where a real
 * wall holds steady near 130.
 */
class GemExposureHintTest {

    /**
     * A frame of evenly spaced discs, [bright] of which are lit enough to read.
     *
     * The rest sit above the detector's local-background threshold but below the value
     * at which a texel counts as gem rather than panel, which is exactly the shape noise
     * takes: found, but carrying nothing.
     */
    private fun discs(count: Int, bright: Int, pitch: Int = 120): CanvasView {
        val cols = 4
        val rows = (count + cols - 1) / cols
        val w = pitch * (cols + 1)
        val h = pitch * (rows + 1)
        val luma = GrayImage(w, h)
        val chroma = ChromaImage(w, h)
        chroma.fill(128)
        val radius = pitch * 0.22f
        for (i in 0 until count) {
            val cx = pitch * (1 + i % cols).toFloat()
            val cy = pitch * (1 + i / cols).toFloat()
            val value = if (i < bright) 220 else 40
            for (y in (cy - radius).toInt()..(cy + radius).toInt()) {
                for (x in (cx - radius).toInt()..(cx + radius).toInt()) {
                    if (x !in 0 until w || y !in 0 until h) continue
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy > radius * radius) continue
                    val j = y * w + x
                    luma.data[j] = value.toByte()
                    if (i >= bright) continue
                    // A strong, unambiguous hue on the lit ones, so a gem that is lit at
                    // all reads as cleanly unwashed. The point of the test is the count,
                    // not the colour: this makes the hint as confident as it can be.
                    //
                    // The dim ones are left colourless, which is both what noise looks
                    // like and what keeps them dim: chroma is added to luma on the way
                    // back to RGB, so a strong hue on a luma-40 disc reconstructs to
                    // R=141 and the disc counts as lit after all.
                    chroma.data[j * 2] = 90.toByte()
                    chroma.data[j * 2 + 1] = 200.toByte()
                }
            }
        }
        return CanvasView(luma, chroma)
    }

    @Test
    fun `a handful of lit gems is not an opinion about the exposure`() {
        val scanner = GemScanner(GemTargets())
        // Sixteen things found, three of them carrying any light: the shape of a black
        // frame full of noise. The pitch resolves, because twelve blobs is plenty for a
        // median, so nothing upstream declines on its own.
        val result = scanner.scan(discs(count = 16, bright = 3))
        assertTrue("the pitch should still resolve: ${result.pitch}", result.pitch > 0f)
        assertTrue("only ${result.gems.size} gems should be lit", result.gems.size < 6)
        assertTrue(
            "declined to answer is -1; got ${result.washedOut}",
            result.washedOut < 0f,
        )
    }

    @Test
    fun `a wall's worth of lit gems is`() {
        val scanner = GemScanner(GemTargets())
        val result = scanner.scan(discs(count = 16, bright = 16))
        assertTrue("${result.gems.size} gems lit", result.gems.size >= 6)
        assertTrue(
            "with a quorum in frame it must answer; got ${result.washedOut}",
            result.washedOut >= 0f,
        )
    }

    /**
     * The real frame still answers. The quorum must not be so eager that it silences the
     * one measurement the loop exists to act on.
     */
    @Test
    fun `the blown frame still reports its exposure`() {
        val result = GemScanner(GemTargets()).scan(GemFixture.loadBlown())
        assertTrue("got ${result.washedOut}", result.washedOut > 0.35f)
    }
    /**
     * Scattered blobs are not a wall, and get no vote on the camera.
     *
     * This is the state a phone is in far more often than it is in front of the wall:
     * face down on a desk, in a pocket, pointed at the floor. At 1/250 s the frame is
     * black, but sensor noise clears the detector's local-background threshold often
     * enough to yield twenty or thirty blobs, a third of them bright enough to read.
     * Every stage downstream then takes them as gems.
     *
     * Neither of the obvious guards separates this from a real wall. Blob count does
     * not: noise reaches thirty, which is a normal number of gems. Frame brightness
     * does not either: an LED wall two stops under is nearly as dark as an empty room.
     * What does separate them is that gems are on a lattice. Their nearest-neighbour
     * distances agree to within the perspective across the frame; random points'
     * distances do not, and the gap between the two is fourfold.
     */
    @Test
    fun `scattered blobs are not read as a wall`() {
        val rng = kotlin.random.Random(7)
        val w = 1280
        val h = 960
        val luma = GrayImage(w, h)
        val chroma = ChromaImage(w, h)
        chroma.fill(128)
        val radius = 22f
        var placed = 0
        val centres = ArrayList<Pair<Float, Float>>()
        while (placed < 26) {
            val cx = rng.nextInt(60, w - 60).toFloat()
            val cy = rng.nextInt(60, h - 60).toFloat()
            // Not touching, so each stays its own blob and the count is a fair match for
            // a wall's. Their *spacing* is the only thing under test.
            if (centres.any { kotlin.math.hypot(it.first - cx, it.second - cy) < radius * 3 }) continue
            centres.add(cx to cy)
            placed++
            for (y in (cy - radius).toInt()..(cy + radius).toInt()) {
                for (x in (cx - radius).toInt()..(cx + radius).toInt()) {
                    if (x !in 0 until w || y !in 0 until h) continue
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy > radius * radius) continue
                    val j = y * w + x
                    luma.data[j] = 220.toByte()
                    chroma.data[j * 2] = 90.toByte()
                    chroma.data[j * 2 + 1] = 200.toByte()
                }
            }
        }

        val detector = GemBlobDetector()
        val blobs = detector.detect(luma)
        assertTrue("found ${blobs.size} blobs, enough to look like a wall", blobs.size >= 12)
        assertTrue(
            "scattered points should measure irregular: ${detector.latticeSpread(blobs)}",
            detector.latticeSpread(blobs) > GemBlobDetector.MAX_LATTICE_SPREAD,
        )

        val result = GemScanner(GemTargets()).scan(CanvasView(luma, chroma))
        assertFalse("scattered blobs are not a wall", result.looksLikeAWall)
        assertTrue("must publish no exposure hint: ${result.washedOut}", result.washedOut < 0f)
        // Still read, though, and that is deliberate. The measurement is only good to
        // about one blob in ten of glare before it starts calling a real wall scattered,
        // and refusing to read on the strength of it would stop the feature dead while
        // the user stands in front of the thing. Withholding a frame's vote on the
        // camera costs nothing, because the next frame votes again.
        assertTrue("reading must not be blocked by this", result.gems.isNotEmpty())
    }

    /** And the wall itself measures as one, with room to spare either side. */
    @Test
    fun `the real wall measures as a lattice`() {
        val detector = GemBlobDetector()
        for ((name, view) in listOf("labelled" to GemFixture.load(), "blown" to GemFixture.loadBlown())) {
            val spread = detector.latticeSpread(detector.detect(view.luma))
            assertTrue(
                "$name frame measured $spread, over the ${GemBlobDetector.MAX_LATTICE_SPREAD} limit",
                spread in 0f..GemBlobDetector.MAX_LATTICE_SPREAD,
            )
        }
    }
    /**
     * A corner of the wall is still the wall.
     *
     * The cost of getting this backwards is worse than the noise it guards against: a
     * user standing in front of the wall being told there is no wall in view, with
     * nothing they can do about it. Six gems is the fewest the pitch will work from at
     * all, so it is the fewest this has to hold for.
     *
     * Worst case over every six-gem neighbourhood of the labelled frame comes out under
     * a third of the limit, and the same sweep over a real 56-gem frame off the wall
     * gives 0.087. The margin is there so that the threshold can be trusted, not so it
     * can be tightened.
     */
    @Test
    fun `a partial view of the wall is still a lattice`() {
        val detector = GemBlobDetector()
        val all = ArrayList<GemBlobDetector.Blob>()
        for (i in GemFixture.POSITIONS.indices step 2) {
            all.add(GemBlobDetector.Blob(GemFixture.POSITIONS[i], GemFixture.POSITIONS[i + 1], 20f))
        }
        var worst = 0f
        for (centre in all) {
            val nearest = all.sortedBy {
                (it.x - centre.x) * (it.x - centre.x) + (it.y - centre.y) * (it.y - centre.y)
            }.take(6)
            val spread = detector.latticeSpread(nearest)
            if (spread > worst) worst = spread
        }
        assertTrue(
            "the worst six-gem corner measured $worst, against a limit of " +
                "${GemBlobDetector.MAX_LATTICE_SPREAD}",
            worst < GemBlobDetector.MAX_LATTICE_SPREAD / 2f,
        )
    }
}
