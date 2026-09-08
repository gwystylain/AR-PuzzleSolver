package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemBlobDetector
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The whole of Gems, run over an actual frame of the actual wall.
 *
 * This is the test the AR version could never have. Reading the wall through the canvas
 * needed a camera pose, so nothing short of standing in the room exercised it end to
 * end; the pieces were tested separately and the join was taken on trust. With the pose
 * gone, a frame *is* the input, so detection, scale, ring reading and matching all run
 * here on real pixels — bloom, sensor noise, perspective and all.
 */
class GemScannerTest {

    private fun colourOf(name: String): Int = when (name) {
        "red" -> GemColour.RED
        "yellow" -> GemColour.YELLOW
        "green" -> GemColour.GREEN
        "blue" -> GemColour.BLUE
        "purple" -> GemColour.PURPLE
        else -> GemColour.UNKNOWN
    }

    @Test
    fun `it finds the gems and recovers their spacing`() {
        val view = GemFixture.load()
        val detector = GemBlobDetector()
        val blobs = detector.detect(view.luma)

        // The crop holds fourteen gems, two of them close enough to the right edge to be
        // clipped. Bounds rather than an exact count: what matters is that it finds
        // essentially all of them and invents none, not that it agrees to the blob.
        assertTrue("found ${blobs.size} gems (${detector.lastReport})", blobs.size in 12..15)

        val pitch = detector.estimatePitch(blobs)
        // Measured off the full frame at 165 px; the gems inside this crop sit a little
        // further apart. Anywhere in this range puts every ring band on the right part
        // of the gem, which is the only thing the pitch is used for.
        assertTrue("pitch came out at $pitch px", pitch in 150f..195f)

        // Every gem found must be a real one. Checked against the complete position
        // list rather than the colour-labelled subset -- otherwise a detector finding a
        // gem nobody could label would be marked down for being right.
        for (blob in blobs) {
            var nearest = Float.MAX_VALUE
            for (i in GemFixture.POSITIONS.indices step 2) {
                val d = hypot(GemFixture.POSITIONS[i] - blob.x, GemFixture.POSITIONS[i + 1] - blob.y)
                if (d < nearest) nearest = d
            }
            assertTrue("blob at (${blob.x}, ${blob.y}) is not near any known gem", nearest < 40f)
        }
    }

    @Test
    fun `it reads the rings of the gems it finds`() {
        val view = GemFixture.load()
        val scanner = GemScanner(GemTargets())
        val result = scanner.scan(view)

        assertTrue("scanned ${result.gems.size} gems", result.gems.size >= 12)
        assertTrue("washed out ${result.washedOut}", result.washedOut < 0.15f)

        var asserted = 0
        var correct = 0
        val report = StringBuilder()
        for (gem in result.gems) {
            val label = GemFixture.LABELLED.minByOrNull { hypot(it.x - gem.x, it.y - gem.y) } ?: continue
            if (hypot(label.x - gem.x, label.y - gem.y) > 40f) continue
            val expected = listOf(label.outer, label.middle, label.centre)
            val got = listOf(gem.pattern.outer, gem.pattern.middle, gem.pattern.centre)
            for (zone in 0..2) {
                val want = expected[zone] ?: continue
                asserted++
                if (colourOf(want) == got[zone]) {
                    correct++
                } else {
                    report.append("\n  (${gem.x.toInt()}, ${gem.y.toInt()}) ")
                        .append(GemPattern.zoneName(zone))
                        .append(": expected $want, read ${GemColour.name(got[zone])}")
                }
            }
        }
        assertTrue("nothing was compared", asserted >= 25)
        val accuracy = correct.toFloat() / asserted
        // The same bar the canvas version was held to, now reached without any pose,
        // rectification or lattice fit -- the scale comes from gem spacing alone.
        assertTrue(
            "only $correct of $asserted rings correct (${(accuracy * 100).toInt()}%):$report",
            accuracy >= 0.8f,
        )
    }

    @Test
    fun `a target highlights the gems that match it and no others`() {
        val view = GemFixture.load()
        val targets = GemTargets()
        val scanner = GemScanner(targets)

        // Nothing asked for: gems are read, nothing is claimed, and the status says what
        // to do rather than reporting a failed search.
        val idle = scanner.scan(view)
        assertEquals(0, idle.matchCount)
        assertTrue(idle.status, idle.status.contains("tap a target"))

        // Two gems in this crop are blue outer, blue middle: one has a purple centre,
        // one was not labelled at the centre. Asking for the outer ring alone must find
        // every blue-outer gem.
        targets.set(0, GemPattern(outer = GemColour.BLUE))
        val broad = scanner.scan(view)
        assertTrue("expected several blue-outer gems, got ${broad.matchCount}", broad.matchCount >= 3)
        for (gem in broad.matches) {
            assertEquals(GemColour.BLUE, gem.pattern.outer)
            assertEquals(1, gem.matchedSlot)
        }

        // Narrowing to all three rings must be a strict subset, and every match must
        // agree on all three -- this is the property a wrong highlight would break.
        val exact = GemPattern(GemColour.BLUE, GemColour.BLUE, GemColour.PURPLE)
        targets.set(0, exact)
        val narrow = scanner.scan(view)
        assertTrue(
            "narrowing must not widen: ${narrow.matchCount} vs ${broad.matchCount}",
            narrow.matchCount in 1..broad.matchCount,
        )
        for (gem in narrow.matches) assertTrue(exact.matches(gem.pattern))
    }

    @Test
    fun `a gem it could not fully read is never claimed as a match`() {
        val view = GemFixture.load()
        val targets = GemTargets()
        val scanner = GemScanner(targets)
        targets.set(0, GemPattern(GemColour.BLUE, GemColour.BLUE, GemColour.PURPLE))
        val result = scanner.scan(view)
        for (gem in result.matches) {
            assertTrue("matched a gem with an unread ring: ${gem.pattern}", gem.pattern.isReadable)
        }
    }

    /**
     * Too few gems in frame means the pitch is one or two measurements, and a pitch that
     * is a third out puts every band on the wrong part of the gem. Refusing and saying
     * so beats reading it wrong.
     */
    @Test
    fun `it refuses to guess the scale from a handful of gems`() {
        val detector = GemBlobDetector()
        val two = listOf(
            GemBlobDetector.Blob(100f, 100f, 20f),
            GemBlobDetector.Blob(280f, 100f, 20f),
        )
        assertEquals(-1f, detector.estimatePitch(two), 0f)
    }
}
