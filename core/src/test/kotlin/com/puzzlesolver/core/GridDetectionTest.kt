package com.puzzlesolver.core

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.grid.GridDetector
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.ImageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grid detection against synthetic canvases.
 *
 * Synthetic rather than captured, because these tests are about the periodic-fit
 * maths and not about image quality; the capture path is exercised by replaying a
 * recording, which is what the ARCore dataset path exists for.
 */
class GridDetectionTest {

    private val spec = CanvasSpec(
        widthTexels = 1024,
        heightTexels = 1024,
        metresPerTexel = 0.002f,
    )

    /** Draws a grid of [cells] x [cells] with the given pitch, dark lines on light paper. */
    private fun drawGrid(
        image: GrayImage,
        originX: Int,
        originY: Int,
        pitch: Int,
        cells: Int,
        lineWidth: Int = 2,
        ink: Int = 30,
        paper: Int = 220,
    ) {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image[x, y] = paper
        }
        val span = pitch * cells
        for (i in 0..cells) {
            val gx = originX + i * pitch
            for (w in 0 until lineWidth) {
                val x = gx + w
                if (x < 0 || x >= image.width) continue
                for (y in originY until (originY + span).coerceAtMost(image.height)) {
                    if (y >= 0) image[x, y] = ink
                }
            }
            val gy = originY + i * pitch
            for (w in 0 until lineWidth) {
                val y = gy + w
                if (y < 0 || y >= image.height) continue
                for (x in originX until (originX + span).coerceAtMost(image.width)) {
                    if (x >= 0) image[x, y] = ink
                }
            }
        }
    }

    private fun fullCoverage(): CoverageMap = CoverageMap(spec, 16).apply {
        confidence.fill(1f)
        updateFromBytes(ByteArray(cols * rows) { 255.toByte() }, 1)
    }

    @Test
    fun `recovers pitch and cell count from a clean nine by nine grid`() {
        val canvas = GrayImage(spec.widthTexels, spec.heightTexels)
        val pitch = 48
        drawGrid(canvas, originX = 200, originY = 150, pitch = pitch, cells = 9)

        val grid = GridDetector(spec).detect(canvas, fullCoverage())
        assertNotNull("detection should succeed on a clean grid", grid)
        grid!!

        assertEquals("horizontal pitch", pitch.toFloat(), grid.pitchX, 1.5f)
        assertEquals("vertical pitch", pitch.toFloat(), grid.pitchY, 1.5f)
        assertEquals("columns", 9, grid.cols)
        assertEquals("rows", 9, grid.rows)
        assertTrue("rotation should be near zero, was ${grid.rotationDeg}", Math.abs(grid.rotationDeg) < 2f)
        assertTrue("confidence should be high, was ${grid.confidence}", grid.confidence > 0.7f)
    }

    @Test
    fun `reports cell size in metres consistent with the canvas scale`() {
        val canvas = GrayImage(spec.widthTexels, spec.heightTexels)
        val pitch = 60
        drawGrid(canvas, originX = 120, originY = 120, pitch = pitch, cells = 10)

        val grid = GridDetector(spec).detect(canvas, fullCoverage())
        assertNotNull(grid)
        val size = grid!!.cellSizeMetres(spec)
        // 60 texels at 2 mm each is 12 cm.
        assertEquals(0.12f, size[0], 0.006f)
        assertEquals(0.12f, size[1], 0.006f)
    }

    @Test
    fun `returns null when nothing has been observed`() {
        val canvas = GrayImage(spec.widthTexels, spec.heightTexels)
        val empty = CoverageMap(spec, 16)
        assertEquals(null, GridDetector(spec).detect(canvas, empty))
    }

    @Test
    fun `refuses featureless canvases instead of inventing a grid`() {
        // A blank wall must not produce a grid. Reporting a bogus grid here would send
        // the whole pipeline down a path it can never recover from.
        val canvas = GrayImage(spec.widthTexels, spec.heightTexels)
        for (i in canvas.data.indices) canvas.data[i] = 200.toByte()
        val grid = GridDetector(spec).detect(canvas, fullCoverage())
        assertTrue("expected no grid on a blank canvas, got $grid", grid == null)
    }

    @Test
    fun `cell bounds stay inside the drawn cell`() {
        val canvas = GrayImage(spec.widthTexels, spec.heightTexels)
        val pitch = 48
        drawGrid(canvas, originX = 200, originY = 150, pitch = pitch, cells = 9)
        val grid = GridDetector(spec).detect(canvas, fullCoverage())!!

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val b = grid.cellBounds(col, row)
                assertTrue("bounds must be ordered", b[2] > b[0] && b[3] > b[1])
                val width = b[2] - b[0]
                // Inset by 12% a side, so a cell box is about 3/4 of the pitch.
                assertTrue(
                    "cell box $width should be smaller than the pitch $pitch",
                    width < pitch,
                )
            }
        }
    }

    // --- The periodicity primitives underneath ---------------------------

    @Test
    fun `dominant period finds the spacing of a comb`() {
        val signal = FloatArray(512)
        val period = 37
        var i = 0
        while (i < signal.size) {
            signal[i] = 100f
            i += period
        }
        val found = ImageOps.dominantPeriod(signal, minPeriod = 8, maxPeriod = 120)
        assertEquals(period.toFloat(), found, 0.6f)
    }

    @Test
    fun `dominant period rejects noise`() {
        val rng = java.util.Random(5)
        val signal = FloatArray(512) { rng.nextFloat() * 100f }
        val found = ImageOps.dominantPeriod(signal, minPeriod = 8, maxPeriod = 120)
        assertTrue("noise must not report a period, got $found", found < 0f)
    }

    @Test
    fun `grid rotation is recovered from gradient orientations`() {
        // Anti-aliased on purpose. A hard-thresholded line at 7 degrees rasterises as a
        // staircase: long axis-aligned runs joined by single-pixel jogs. Those runs put
        // far more gradient energy at 0 degrees than the line's true angle carries, so a
        // binary synthetic image reports 0 no matter how good the estimator is. Camera
        // imagery is never like that -- the lens, the sensor and the canvas resampling
        // all band-limit it -- so coverage-based anti-aliasing is the faithful input
        // here, not a concession to make the test pass.
        val size = 256
        val image = GrayImage(size, size)
        val angle = Math.toRadians(7.0)
        val pitch = 24.0
        val halfWidth = 1.2
        val paper = 220.0
        val ink = 30.0

        /** Distance from [t] to the nearest multiple of [pitch]. */
        fun distanceToLine(t: Double): Double = Math.abs(t - pitch * Math.round(t / pitch))

        for (y in 0 until size) {
            for (x in 0 until size) {
                val cx = x - size / 2.0
                val cy = y - size / 2.0
                val rx = cx * Math.cos(angle) + cy * Math.sin(angle)
                val ry = -cx * Math.sin(angle) + cy * Math.cos(angle)
                val d = Math.min(distanceToLine(rx), distanceToLine(ry))
                // Linear ramp over one pixel at the edge: the coverage a real sensor sees.
                val coverage = (halfWidth + 0.5 - d).coerceIn(0.0, 1.0)
                image[x, y] = (paper - (paper - ink) * coverage).toInt()
            }
        }

        val mag = GrayImage(size, size)
        val ori = ByteArray(size * size)
        ImageOps.sobel(image, mag, ori)
        val estimated = ImageOps.estimateGridRotation(mag, ori)
        // Folded mod 90, since 7 and -83 describe the same grid.
        val folded = ((estimated + 45f) % 90f + 90f) % 90f - 45f
        assertEquals(7f, folded, 2.5f)
    }

    @Test
    fun `decimates a large observed region and still reports geometry in canvas texels`() {
        // Past a bound on sampled texels the detector works on a decimated copy of the
        // region, because the working set is otherwise sized by how much wall has been
        // scanned and a full canvas does not fit in the heap this app gets.
        //
        // What has to survive that is the geometry. Pitch, origin and cell counts are the
        // contract with everything downstream and are all in *canvas* texels; computing
        // them on the decimated image and forgetting to scale them back is the obvious
        // way to break this, and it fails silently -- the grid still looks plausible, it
        // is just half the size it should be and lands in the wrong place.
        val big = CanvasSpec(widthTexels = 2048, heightTexels = 1536, metresPerTexel = 0.002f)
        val canvas = GrayImage(big.widthTexels, big.heightTexels)
        val pitch = 64
        val drawnX = 200
        val drawnY = 200
        drawGrid(canvas, originX = drawnX, originY = drawnY, pitch = pitch, cells = 12)

        val coverage = CoverageMap(big, 16).apply {
            confidence.fill(1f)
            updateFromBytes(ByteArray(cols * rows) { 255.toByte() }, 1)
        }

        val grid = GridDetector(big).detect(canvas, coverage)
        assertNotNull("detection should survive decimation", grid)
        grid!!

        assertEquals("columns", 12, grid.cols)
        assertEquals("rows", 12, grid.rows)
        // Reported in sampled texels this would come back as 32, which is the specific
        // regression being guarded.
        assertEquals("horizontal pitch in canvas texels", pitch.toFloat(), grid.pitchX, 2f)
        assertEquals("vertical pitch in canvas texels", pitch.toFloat(), grid.pitchY, 2f)

        // Modulo the pitch, because locking onto any drawn rule is a correct answer --
        // only being off the lattice entirely is wrong.
        val offX = ((grid.originX - drawnX) % pitch + pitch) % pitch
        val offY = ((grid.originY - drawnY) % pitch + pitch) % pitch
        assertTrue(
            "origin x should sit on a drawn rule, was ${grid.originX} (off by $offX)",
            offX < 6f || pitch - offX < 6f,
        )
        assertTrue(
            "origin y should sit on a drawn rule, was ${grid.originY} (off by $offY)",
            offY < 6f || pitch - offY < 6f,
        )
    }
}
