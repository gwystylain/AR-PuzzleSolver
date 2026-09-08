package com.puzzlesolver.core

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridDetector
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.bombs.ButtonLatticeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Finding the grid on a wall that has no lines drawn on it.
 *
 * The two halves of this file test different things on purpose, because the two
 * available inputs can each only prove one of them.
 *
 * **Real frames** from `testVideos/mines1.mp4` prove the part that was genuinely
 * unknown: whether a lattice of domed buttons can be picked out of a dim, unevenly lit
 * wall at all. They cannot prove the lattice fit, because they are raw camera frames
 * with perspective and wall curvature still in them -- nearest-neighbour spacing drifts
 * from 61 to 89 texels across a single frame, so no rigid square lattice fits one, and
 * demanding that it did would be testing the fixture rather than the code. The real
 * pipeline hands the detector a metrically rectified canvas where that is gone.
 *
 * **A synthetic rectified lattice** proves the fit: known pitch, rotation and origin
 * going in, the same numbers expected back out.
 */
class ButtonGridDetectionTest {

    private val spec = CanvasSpec(
        widthTexels = 540,
        heightTexels = 960,
        // So a ~95 texel pitch reads as ~10 cm of wall, inside every believable range.
        metresPerTexel = 0.00105f,
    )

    private fun load(name: String): GrayImage {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("fixture $name missing")
        stream.use { input ->
            fun token(): String {
                val sb = StringBuilder()
                var c = input.read()
                while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = input.read()
                if (c == '#'.code) {
                    while (c != '\n'.code) c = input.read()
                    return token()
                }
                while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                    sb.append(c.toChar())
                    c = input.read()
                }
                return sb.toString()
            }
            require(token() == "P5")
            val w = token().toInt()
            val h = token().toInt()
            token()
            val data = ByteArray(w * h)
            var read = 0
            while (read < data.size) {
                val n = input.read(data, read, data.size - read)
                if (n <= 0) break
                read += n
            }
            return GrayImage(w, h, data)
        }
    }

    private fun fullyCovered(): CoverageMap {
        val coverage = CoverageMap(spec)
        for (i in coverage.confidence.indices) coverage.update(i, 1f, 1)
        return coverage
    }

    @Test
    fun `the ruled-line detector is confidently wrong about a button lattice`() {
        // Why a second detector exists at all. The true button pitch in these fixtures
        // is 80 to 105 texels, measured off the images. GridDetector reports about 10 --
        // the floor of its allowed range -- and calls it high confidence, because with
        // no ruled lines to lock onto it settles for sensor noise and button-face
        // texture.
        //
        // Returning nothing would be recoverable: the app would keep scanning. A
        // confident wrong pitch is not, because everything downstream trusts it and
        // then reads cells that are not where it thinks they are.
        for (fixture in listOf("canvas-buttons.pgm", "canvas-buttons-unlit.pgm")) {
            val grid = GridDetector(spec).detect(load(fixture), fullyCovered())
            assertNotNull("$fixture: expected the ruled detector to claim a grid", grid)
            assertTrue(
                "$fixture: pitch ${grid!!.pitchX} should be nowhere near the true ~95",
                grid.pitchX < 30f,
            )
            assertTrue("$fixture: and it should be sure of itself", grid.confidence > 0.5f)
        }
    }

    @Test
    fun `buttons are found on a real wall, lit and unlit alike`() {
        // The unlit case is the one that matters. During a clean sweep almost every
        // button is unlit, and an unlit button is not dark -- it is a pale disc at maybe
        // a fifth the brightness of a lit one, on a panel the room lights very unevenly.
        // A global threshold cannot separate those; a local one can.
        val expectations = listOf("canvas-buttons.pgm" to 45, "canvas-buttons-unlit.pgm" to 37)
        for ((fixture, expected) in expectations) {
            val detector = ButtonLatticeDetector(spec)
            detector.detect(load(fixture))
            println(
                "$fixture -> ${detector.lastBlobCount} buttons, " +
                    "pitch ${detector.lastPitchTexels}, area ${detector.lastBlobArea}"
            )
            assertTrue(
                "$fixture: found ${detector.lastBlobCount} buttons, expected near $expected",
                abs(detector.lastBlobCount - expected) <= 8,
            )
            assertTrue(
                "$fixture: pitch ${detector.lastPitchTexels} outside the measured 75..115",
                detector.lastPitchTexels in 75f..115f,
            )
        }
    }

    @Test
    fun `the lattice fit recovers a known pitch, rotation and origin`() {
        for (rotation in listOf(0f, 3.5f, -7f)) {
            for (pitch in listOf(40f, 95f)) {
                val lat = makeLattice(pitch, rotation)
                val detector = ButtonLatticeDetector(spec)
                val grid = detector.detect(lat.image)
                val tag = "pitch $pitch rotation $rotation (${lat.cols}x${lat.rows})"
                assertNotNull("$tag: no lattice -- ${detector.lastReport}", grid)
                grid!!

                assertEquals("pitch, $tag", pitch, grid.pitchX, pitch * 0.04f)
                assertEquals("rotation, $tag", rotation, grid.rotationDeg, 1.0f)
                assertEquals("cols, $tag", lat.cols, grid.cols)
                assertEquals("rows, $tag", lat.rows, grid.rows)

                // The origin is the outer corner of cell (0,0), so the centre of that
                // cell must land back on the first button we drew.
                val centre = grid.cellCentre(0, 0)
                assertEquals("centre x, $tag", lat.originX, centre[0], pitch * 0.15f)
                assertEquals("centre y, $tag", lat.originY, centre[1], pitch * 0.15f)
                assertTrue("confidence ${grid.confidence}, $tag", grid.confidence > 0.8f)
            }
        }
    }

    @Test
    fun `a wall of unlit buttons with a few lit ones still fits one lattice`() {
        // Lit buttons blow out and bloom far wider than unlit ones, so their blobs are
        // much bigger. The area filter is a ratio to the median rather than an absolute
        // window precisely so a handful of giants cannot drag it off the buttons.
        val lat = makeLattice(80f, 2f, litCells = setOf(0, 4, 13, 22, 30))
        val detector = ButtonLatticeDetector(spec)
        val grid = detector.detect(lat.image)
        assertNotNull("no lattice -- ${detector.lastReport}", grid)
        assertEquals(80f, grid!!.pitchX, 4f)
        assertEquals(lat.cols, grid.cols)
        assertEquals(lat.rows, grid.rows)
    }

    @Test
    fun `detection stays fast on a full-size canvas`() {
        // Found on device, not on the bench. Run over the whole 4096-square canvas the
        // blur and connected-components passes took about 1.5 seconds, which showed up
        // as a 1528 ms solver step and would stall detection completely. The search now
        // runs on the observed region, shrunk so its longest side is bounded.
        //
        // The number below is loose on purpose: it is a guard against losing the
        // bounding entirely, not a benchmark.
        val big = CanvasSpec(widthTexels = 4096, heightTexels = 4096, metresPerTexel = 0.0015f)
        val image = GrayImage(big.widthTexels, big.heightTexels)
        val rng = java.util.Random(11)
        for (i in image.data.indices) image.data[i] = (rng.nextInt(40)).toByte()

        val coverage = CoverageMap(big)
        for (i in coverage.confidence.indices) coverage.update(i, 1f, 1)

        val detector = ButtonLatticeDetector(big)
        // Warm the JIT so this measures the algorithm rather than the first-run cost.
        detector.detect(image, coverage)
        val started = System.nanoTime()
        detector.detect(image, coverage)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("4096x4096 lattice detection: ${millis}ms")
        assertTrue("took ${millis}ms over the full canvas", millis < 400)
    }

    @Test
    fun `an unobserved canvas is rejected without searching it`() {
        val big = CanvasSpec(widthTexels = 4096, heightTexels = 4096, metresPerTexel = 0.0015f)
        val detector = ButtonLatticeDetector(big)
        val grid = detector.detect(GrayImage(big.widthTexels, big.heightTexels), CoverageMap(big))
        assertEquals(null, grid)
        assertTrue(detector.lastReport.contains("nothing observed"))
    }

    private class Lattice(
        val image: GrayImage,
        val cols: Int,
        val rows: Int,
        val originX: Float,
        val originY: Float,
    )

    /**
     * Renders discs on a lattice, roughly the way the wall looks once rectified, sized
     * and centred so the whole thing fits however it is rotated. Getting that wrong
     * silently clips columns off the edge and looks like a detector bug.
     */
    private fun makeLattice(
        pitch: Float,
        rotationDeg: Float,
        litCells: Set<Int> = emptySet(),
    ): Lattice {
        val c = cos(Math.toRadians(rotationDeg.toDouble())).toFloat()
        val s = sin(Math.toRadians(rotationDeg.toDouble())).toFloat()
        val margin = pitch * 0.75f
        val availW = spec.widthTexels - 2 * margin
        val availH = spec.heightTexels - 2 * margin

        var cols = (availW / pitch).toInt() + 1
        var rows = (availH / pitch).toInt() + 1
        var box = boundsOf(cols, rows, pitch, c, s)
        while ((box[2] - box[0] > availW && cols > 3) || (box[3] - box[1] > availH && rows > 3)) {
            if (box[2] - box[0] > availW) cols--
            if (box[3] - box[1] > availH) rows--
            box = boundsOf(cols, rows, pitch, c, s)
        }

        val originX = margin - box[0]
        val originY = margin - box[1]

        val image = GrayImage(spec.widthTexels, spec.heightTexels)
        // A dark panel with a gentle lighting gradient, which is what the room gives
        // and what a global threshold would trip over.
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image[x, y] = 14 + (18 * (1f - abs(x - image.width / 2f) / (image.width / 2f))).toInt()
            }
        }

        val buttonRadius = pitch * 0.28f
        for (r in 0 until rows) {
            for (col in 0 until cols) {
                val cx = originX + col * pitch * c - r * pitch * s
                val cy = originY + col * pitch * s + r * pitch * c
                val lit = (r * cols + col) in litCells
                val radius = if (lit) buttonRadius * 1.5f else buttonRadius
                val level = if (lit) 255 else 95
                val x0 = (cx - radius).toInt().coerceAtLeast(0)
                val x1 = (cx + radius).toInt().coerceAtMost(image.width - 1)
                val y0 = (cy - radius).toInt().coerceAtLeast(0)
                val y1 = (cy + radius).toInt().coerceAtMost(image.height - 1)
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        if (hypot(x - cx, y - cy) <= radius) image[x, y] = level
                    }
                }
            }
        }
        return Lattice(image, cols, rows, originX, originY)
    }

    /** Bounding box of the lattice node offsets, relative to node (0,0). */
    private fun boundsOf(cols: Int, rows: Int, pitch: Float, c: Float, s: Float): FloatArray {
        var minX = 0f
        var minY = 0f
        var maxX = 0f
        var maxY = 0f
        for (r in intArrayOf(0, rows - 1)) {
            for (col in intArrayOf(0, cols - 1)) {
                val x = col * pitch * c - r * pitch * s
                val y = col * pitch * s + r * pitch * c
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
