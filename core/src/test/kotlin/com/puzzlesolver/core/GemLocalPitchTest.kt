package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemBlobDetector
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pitch, measured where each gem actually is rather than once for the frame.
 *
 * Every ring radius is a fraction of the button pitch, so the pitch is the ruler the
 * whole classifier is calibrated against. One number for the frame assumes the spacing
 * is constant across it, and on this wall it is not: it is curved, the camera is never
 * square to it, and on a real frame the nearest-neighbour distance ran 117 to 161 px
 * while the median came out 132. A gem at the wide end had all three bands a fifth too
 * far in -- on one measured gem the outer ring sat at 0.245 of the global pitch, outside
 * the 0.175-0.230 band altogether, and what the band caught instead was the middle
 * ring's wash.
 */
class GemLocalPitchTest {

    private fun blob(x: Float, y: Float) = GemBlobDetector.Blob(x, y, 20f)

    /**
     * A row of gems whose spacing grows left to right, which is what perspective on a
     * flat wall does and what a curved one does more of.
     */
    private fun spreadingLattice(): List<GemBlobDetector.Blob> {
        val blobs = ArrayList<GemBlobDetector.Blob>()
        for (row in 0 until 4) {
            var x = 0f
            var gap = 100f
            for (col in 0 until 6) {
                blobs.add(blob(x, row * 130f))
                x += gap
                gap += 12f
            }
        }
        return blobs
    }

    @Test
    fun `local pitch tracks a spacing that changes across the frame`() {
        val detector = GemBlobDetector()
        val blobs = spreadingLattice()
        val global = detector.estimatePitch(blobs)
        val local = detector.localPitches(blobs, global)
        assertNotNull(local)
        local!!

        // The left-hand gems sit ~100 px apart and the right-hand ones ~160. A single
        // median cannot be right for both, and being wrong by this much is the whole
        // difference between reading the outer ring and reading the wash inside it.
        val leftmost = local[0]
        val rightmost = local[5]
        assertTrue(
            "left $leftmost should be tighter than right $rightmost",
            rightmost > leftmost * 1.15f,
        )
        assertTrue("left $leftmost tracks the ~100 px end", leftmost < global)
        assertTrue("right $rightmost tracks the ~160 px end", rightmost > global)
    }

    /**
     * The statistic has to match the one the ring constants were calibrated against.
     *
     * They were fitted against a median of *nearest-neighbour* distances, and on a
     * lattice whose rows and columns are spaced differently a nearest-neighbour distance
     * measures the shorter axis. An estimator that measured something between the axes
     * instead -- the median of each gem's three nearest, say -- comes out systematically
     * larger, and on the labelled fixture that bias alone turned three correct rings
     * into misreads. Same ruler, measured locally, is the requirement.
     */
    @Test
    fun `local pitch agrees with the global median on an even lattice`() {
        val detector = GemBlobDetector()
        val blobs = ArrayList<GemBlobDetector.Blob>()
        // Rows and columns deliberately unequal: 120 across, 150 down. Nearest-neighbour
        // means 120, and anything that reports ~135 has changed the ruler.
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                blobs.add(blob(col * 120f, row * 150f))
            }
        }
        val global = detector.estimatePitch(blobs)
        assertEquals("global should be the shorter axis", 120f, global, 0.5f)
        val local = detector.localPitches(blobs, global)!!
        for (p in local) {
            assertEquals("local pitch drifted off the global ruler", 120f, p, 0.5f)
        }
    }

    @Test
    fun `two blobs on top of each other cannot collapse their neighbours' pitch`() {
        val detector = GemBlobDetector()
        val blobs = ArrayList<GemBlobDetector.Blob>()
        for (col in 0 until 8) blobs.add(blob(col * 120f, 0f))
        for (col in 0 until 8) blobs.add(blob(col * 120f, 120f))
        // One gem found twice, three pixels apart -- glare splitting a disc, which the
        // detector's own filters do not always catch. Unclamped its neighbours inherit a
        // pitch near zero, and a band of radius zero reads the panel behind the wall.
        blobs.add(blob(360f, 3f))

        val global = detector.estimatePitch(blobs)
        val local = detector.localPitches(blobs, global)!!
        val floor = global * GemBlobDetector.MIN_LOCAL_PITCH_RATIO
        for (p in local) {
            assertTrue("local pitch $p fell through the floor $floor", p >= floor)
        }
    }

    @Test
    fun `it declines rather than guessing when barely any gems are in frame`() {
        val detector = GemBlobDetector()
        val few = listOf(blob(0f, 0f), blob(120f, 0f), blob(0f, 120f))
        assertNull(detector.localPitches(few, 120f))
        assertNull("no pitch to be local to", detector.localPitches(spreadingLattice(), -1f))
    }

    /**
     * The check that matters: on the one frame with hand-labelled ground truth, reading
     * each gem against its own pitch must not be worse than reading them all against the
     * frame median.
     */
    @Test
    fun `on the labelled wall it reads at least as well as one pitch for the frame`() {
        val view = GemFixture.load()
        val reader = GemReader()
        val detector = GemBlobDetector()

        val blobs = ArrayList<GemBlobDetector.Blob>()
        for (i in GemFixture.POSITIONS.indices step 2) {
            blobs.add(blob(GemFixture.POSITIONS[i], GemFixture.POSITIONS[i + 1]))
        }
        val global = detector.estimatePitch(blobs)
        val local = detector.localPitches(blobs, global)!!

        fun accuracy(pitchAt: (Float, Float) -> Float): Pair<Int, Int> {
            var right = 0
            var asserted = 0
            for (gem in GemFixture.LABELLED) {
                val read = reader.readAt(view, gem.x, gem.y, pitchAt(gem.x, gem.y)).pattern
                val want = listOf(gem.outer, gem.middle, gem.centre)
                val got = listOf(read.outer, read.middle, read.centre)
                for (zone in 0..2) {
                    val expected = want[zone] ?: continue
                    asserted++
                    if (GemColour.name(got[zone]) == expected) right++
                }
            }
            return right to asserted
        }

        val (globalRight, total) = accuracy { _, _ -> GemFixture.PITCH }
        val (localRight, _) = accuracy { x, y ->
            val i = blobs.indexOfFirst { it.x == x && it.y == y }
            if (i >= 0) local[i] else GemFixture.PITCH
        }
        assertTrue(
            "local pitch read $localRight of $total, one pitch for the frame read $globalRight",
            localRight >= globalRight,
        )
        assertTrue("$localRight of $total rings correct", localRight.toFloat() / total >= 0.85f)
    }
}
