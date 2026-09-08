package com.puzzlesolver.core

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridDetector
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grid detection against a real canvas captured off the device.
 *
 * The fixture is an actual mosaic dump: a sudoku on a monitor at 0.5 mm/texel, with the
 * surrounding desktop, taskbar and a chat panel still in frame. Synthetic grids test the
 * maths; this tests the thing the detector will actually be handed, including the parts
 * nobody designs for -- dark background speckle, moire off the display, and competing
 * straight edges from unrelated UI.
 *
 * It exists because iterating on this through a device install is a 30-second loop with
 * almost no visibility, and the same bug is a millisecond loop here.
 *
 * Ground truth, measured off the image by hand: the grid spans roughly x 285..737 and
 * y 123..592, so the pitch is close to 50 texels in both axes.
 */
class RealCanvasDetectionTest {

    private val spec = CanvasSpec(
        widthTexels = 1184,
        heightTexels = 688,
        metresPerTexel = 0.0005f,
    )

    private fun loadFixture(): GrayImage {
        val stream = javaClass.classLoader!!.getResourceAsStream("canvas-sudoku.pgm")
            ?: error("fixture missing")
        stream.use { input ->
            // PGM: "P5", width height, maxval, then raw bytes.
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
            token()                                     // maxval
            val data = ByteArray(w * h)
            var read = 0
            while (read < data.size) {
                val n = input.read(data, read, data.size - read)
                if (n <= 0) break
                read += n
            }
            require(read == data.size) { "short fixture: $read of ${data.size}" }
            return GrayImage(w, h, data)
        }
    }

    private fun fullCoverage(): CoverageMap = CoverageMap(spec, 16).apply {
        updateFromBytes(ByteArray(cols * rows) { 255.toByte() }, 1)
    }

    @Test
    fun `fixture loads with plausible content`() {
        val img = loadFixture()
        assertTrue("expected the captured canvas size", img.width == 1184 && img.height == 688)
        var ink = 0
        for (b in img.data) if ((b.toInt() and 0xFF) < 100) ink++
        val fraction = ink.toFloat() / img.data.size
        // Mostly dark surround with a bright puzzle: sanity-check we did not load noise.
        assertTrue("suspicious ink fraction $fraction", fraction in 0.05f..0.95f)
    }

    /**
     * Prints the intermediate signals rather than asserting on them, so a failing
     * detection can be understood from one test run instead of a sequence of device
     * installs. Kept as a test so it stays compiling and runnable.
     */
    @Test
    fun `diagnose the detection pipeline on real data`() {
        val img = loadFixture()
        val coverage = fullCoverage()

        val detector = GridDetector(spec)
        val auto = detector.detect(img, coverage)
        println("AUTO  -> $auto  rejection=${detector.lastRejection}")

        detector.expectedCells = 9
        val fixed = detector.detect(img, coverage)
        println("EXP9  -> $fixed  rejection=${detector.lastRejection}")

        // Rotation estimate and profile shape, the two inputs the lattice fit depends on.
        val mag = GrayImage(img.width, img.height)
        val ori = ByteArray(img.width * img.height)
        ImageOps.sobel(img, mag, ori)
        println("rotation=${ImageOps.estimateGridRotation(mag, ori)}")

        val bin = GrayImage(img.width, img.height)
        val s1 = GrayImage(img.width, img.height)
        val s2 = GrayImage(img.width, img.height)
        ImageOps.adaptiveThreshold(img, bin, s1, s2, radius = 12, bias = 8)
        var binInk = 0
        for (b in bin.data) if (b != 0.toByte()) binInk++
        println("mean-only ink fraction=${binInk.toFloat() / bin.data.size}")

        // Mirror what the detector actually does now.
        val gated = GrayImage(img.width, img.height)
        val meanBuf = FloatArray(img.width * img.height)
        val stdBuf = FloatArray(img.width * img.height)
        com.puzzlesolver.core.image.LocalStats().threshold(
            img, gated, radius = 12, bias = 8, minStdDev = 6f,
            meanBuffer = meanBuf, stdBuffer = stdBuf,
        )
        var gatedInk = 0
        for (b in gated.data) if (b != 0.toByte()) gatedInk++
        println("contrast-gated ink fraction=${gatedInk.toFloat() / gated.data.size}")

        val diag = Math.sqrt((img.width * img.width + img.height * img.height).toDouble()).toInt() + 2
        val profile = FloatArray(diag)
        ImageOps.projectionProfile(bin, 0f, vertical = true, out = profile)

        // Where the mass actually is: if the surround dominates, the lattice fit is
        // scoring background rather than the puzzle.
        var peak = 0f
        var peakAt = 0
        var total = 0.0
        for (i in profile.indices) {
            total += profile[i].toDouble()
            if (profile[i] > peak) {
                peak = profile[i]
                peakAt = i
            }
        }
        println("profileX: peak=$peak at=$peakAt mean=${total / diag}")
        val top = profile.withIndex().sortedByDescending { it.value }.take(14).map { it.index }.sorted()
        println("profileX top-14 positions=$top")

        // Now the same for both axes, on the contrast-gated image the detector uses.
        for (vertical in booleanArrayOf(true, false)) {
            val prof = FloatArray(diag)
            ImageOps.projectionProfile(gated, 0f, vertical = vertical, out = prof)
            val label = if (vertical) "X" else "Y"
            var mx = 0f
            var sum = 0.0
            for (v in prof) {
                sum += v.toDouble()
                if (v > mx) mx = v
            }
            val tops = prof.withIndex().sortedByDescending { it.value }.take(20)
                .map { it.index }.sorted()
            println("gated profile$label: peak=$mx mean=${"%.0f".format(sum / diag)} top20=$tops")
            // Peaks separated enough to be distinct rules, which is what the fit needs.
            val distinct = ArrayList<Int>()
            for (i in tops) if (distinct.none { Math.abs(it - i) < 8 }) distinct.add(i)
            println("gated profile$label distinct peaks=$distinct")
        }
    }

    @Test
    fun `detects a nine by nine grid at roughly the measured pitch`() {
        val detector = GridDetector(spec)
        detector.expectedCells = 9
        val grid = detector.detect(loadFixture(), fullCoverage())
        assertNotNull("expected a grid, rejection=${detector.lastRejection}", grid)
        grid!!
        println("pitch=${grid.pitchX}/${grid.pitchY} origin=${grid.originX},${grid.originY} conf=${grid.confidence}")
        // Hand-measured ground truth is ~50 texels; allow generous slack.
        assertTrue("pitchX ${grid.pitchX} not near 50", grid.pitchX in 42f..58f)
        assertTrue("pitchY ${grid.pitchY} not near 50", grid.pitchY in 42f..58f)
        assertTrue("confidence ${grid.confidence} should be non-trivial", grid.confidence > 0.2f)
    }
}
