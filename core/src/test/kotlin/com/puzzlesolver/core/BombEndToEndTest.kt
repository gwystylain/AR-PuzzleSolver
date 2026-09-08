package com.puzzlesolver.core

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.canvas.CoverageMap
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.bombs.BombAdapter
import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombSolver
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.ButtonPalette
import com.puzzlesolver.core.puzzle.bombs.Detonation
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.SolveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The whole chain, on a canvas built to look like the wall.
 *
 * Every other test in this suite checks one link: the palette against measured colours,
 * the lattice detector against real frames, the planner against the mechanics. This one
 * runs them in series the way the app does -- render a board, find the grid in it, read
 * the colours back, solve, and then detonate the plan against the board that was
 * originally drawn.
 *
 * The rendering deliberately reproduces the two properties that make this wall awkward,
 * both measured off `testVideos/mines1.mp4`:
 *
 *  - lit buttons **clip to pure white** across their face, so no colour survives in the
 *    middle of a cell and the reading has to come from the glow around it;
 *  - everything sits under a strong ambient wash, so only the *excess* over local
 *    ambient identifies a button.
 *
 * A canvas without those would let a much lazier classifier pass, and would not be the
 * thing the app is handed.
 */
class BombEndToEndTest {

    private val pitch = 40f
    private val cols = 12
    private val rows = 7

    private val spec = CanvasSpec(
        widthTexels = 560,
        heightTexels = 360,
        // 40 texels of pitch reads as 10 cm of wall.
        metresPerTexel = 0.0025f,
    )

    /** Panel between the buttons: dim, and strongly purple, as the room actually is. */
    private val ambient = floatArrayOf(30f, 24f, 52f)

    private val originX = 60f
    private val originY = 55f

    private fun ledExcess(button: Int): FloatArray? = when (button) {
        Button.WHITE -> ButtonPalette.WHITE
        Button.BLUE -> ButtonPalette.BLUE
        Button.HAZARD -> ButtonPalette.RED
        else -> null
    }?.let { floatArrayOf(it[0] * LED_STRENGTH, it[1] * LED_STRENGTH, it[2] * LED_STRENGTH) }

    private fun render(board: BombBoard): CanvasView {
        val rgb = Array(spec.heightTexels) { Array(spec.widthTexels) { ambient.copyOf() } }

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = originX + col * pitch
                val cy = originY + row * pitch
                val kind = board.kindAt(board.index(col, row))
                val excess = ledExcess(kind)

                val faceRadius = pitch * 0.22f
                val haloRadius = pitch * 0.40f
                val x0 = (cx - haloRadius).toInt().coerceAtLeast(0)
                val x1 = (cx + haloRadius).toInt().coerceAtMost(spec.widthTexels - 1)
                val y0 = (cy - haloRadius).toInt().coerceAtLeast(0)
                val y1 = (cy + haloRadius).toInt().coerceAtMost(spec.heightTexels - 1)

                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val d = hypot(x - cx, y - cy)
                        val px = rgb[y][x]
                        if (d <= faceRadius) {
                            if (excess != null) {
                                // Clipped. This is what the sensor really does, and it
                                // is why sampling cell centres cannot work.
                                px[0] = 255f; px[1] = 255f; px[2] = 255f
                            } else {
                                // Unlit: a pale disc catching room light, well above the
                                // panel but nowhere near a lit button.
                                px[0] = 110f; px[1] = 95f; px[2] = 140f
                            }
                        } else if (d <= haloRadius && excess != null) {
                            // Glow falling off outward -- the only place colour lives.
                            val falloff = 1f - (d - faceRadius) / (haloRadius - faceRadius)
                            px[0] = (ambient[0] + excess[0] * falloff).coerceAtMost(254f)
                            px[1] = (ambient[1] + excess[1] * falloff).coerceAtMost(254f)
                            px[2] = (ambient[2] + excess[2] * falloff).coerceAtMost(254f)
                        }
                    }
                }
            }
        }

        // Encode exactly as the accumulator's fragment shader does: BT.601 luma in one
        // channel, Cb and Cr biased into the two that were spare.
        val luma = GrayImage(spec.widthTexels, spec.heightTexels)
        val chroma = ChromaImage(spec.widthTexels, spec.heightTexels)
        for (y in 0 until spec.heightTexels) {
            for (x in 0 until spec.widthTexels) {
                val p = rgb[y][x]
                val yy = 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]
                luma[x, y] = yy.toInt()
                val cb = 128f + (p[2] - yy) / 1.772f
                val cr = 128f + (p[0] - yy) / 1.402f
                val i = (y * spec.widthTexels + x) * 2
                chroma.data[i] = cb.coerceIn(0f, 255f).toInt().toByte()
                chroma.data[i + 1] = cr.coerceIn(0f, 255f).toInt().toByte()
            }
        }
        return CanvasView(luma, chroma)
    }

    private fun fullyCovered(): CoverageMap {
        val coverage = CoverageMap(spec)
        for (i in coverage.confidence.indices) coverage.update(i, 1f, 1)
        return coverage
    }

    /** A board with all four colours that matter, and a hazard that has to be avoided. */
    private fun sampleBoard(): BombBoard = BombBoard.parse(
        """
        ....W.......
        ..W...B.....
        ......R.....
        .W........W.
        ....B.......
        ..........W.
        .....W......
        """
    )

    @Test
    fun `a rendered wall is found, read and solved`() {
        val truth = sampleBoard()
        val view = render(truth)
        val coverage = fullyCovered()
        val adapter = BombAdapter()

        // 1. The grid, from the lattice rather than from lines that do not exist.
        val grid = adapter.detectGrid(view, coverage, spec)
        assertNotNull("no lattice found", grid)
        grid!!
        assertEquals("cols", cols, grid.cols)
        assertEquals("rows", rows, grid.rows)
        assertEquals("pitch", pitch, grid.pitchX, 2f)

        // 2. It recognises the puzzle as its own.
        val score = adapter.identify(view, grid, coverage, spec)
        assertTrue("identify returned $score", score > 0.5f)

        // 3. Every cell reads back the colour that was drawn.
        val observations = adapter.readCells(view, grid, coverage, spec, BooleanArray(grid.cellCount))
        assertEquals("every cell should be readable", cols * rows, observations.size)
        for (o in observations) {
            val expected = truth.kindAt(truth.index(o.col, o.row))
            assertEquals(
                "cell (${o.col},${o.row}) read as ${Button.name(o.value)}, drawn as ${Button.name(expected)}",
                expected,
                o.value,
            )
        }

        // 4. Solved, and the plan works when detonated against the original board.
        val solver = BombSolver(grid.cols, grid.rows)
        try {
            val delta = ObservationDelta(observations, coverageFraction = 1f, fullyScanned = true)
            var outcome: SolveOutcome = SolveOutcome.Pending
            repeat(600) {
                outcome = solver.observe(delta, System.nanoTime() + 12_000_000L)
                if (outcome !is SolveOutcome.Pending) return@repeat
                Thread.sleep(5)
            }
            assertTrue("expected a solution, got $outcome", outcome is SolveOutcome.Solved)
            val solution = (outcome as SolveOutcome.Solved).solution

            var live = truth
            val sim = Detonation()
            for (round in 1..BombSolver.roundCount(solution)) {
                val mines = BombSolver.minesForRound(solution, round)
                for (m in mines) {
                    assertTrue("round $round presses a lit button", live.isPlaceable(m))
                }
                val fired = sim.fire(live, mines)
                assertTrue("round $round fires on the red hazard", !fired.losesGame)
                live = live.afterDetonation(fired.damage, mines)
            }
            assertTrue("board not cleared:\n$live", live.isCleared)
            println("end to end: ${solution.label}, board cleared")
        } finally {
            solver.close()
        }
    }

    @Test
    fun `without chroma the adapter refuses rather than reading colour off brightness`() {
        // The failure this guards against is silent. Luma alone cannot tell a red hazard
        // from a white target -- both clip to the same white -- so a plan built from
        // brightness would look perfectly reasonable and lose the room.
        val view = render(sampleBoard())
        val lumaOnly = CanvasView(view.luma, null)
        val coverage = fullyCovered()
        val adapter = BombAdapter()

        val grid = adapter.detectGrid(lumaOnly, coverage, spec)
        assertNotNull("the lattice is visible in luma alone", grid)
        assertEquals(0f, adapter.identify(lumaOnly, grid!!, coverage, spec), 0.0001f)
        assertTrue(adapter.readCells(lumaOnly, grid, coverage, spec, BooleanArray(grid.cellCount)).isEmpty())
        assertTrue(adapter.lastReadStats.contains("no chroma"))
    }

    private companion object {
        /**
         * Total LED contribution over ambient. Comfortably above the palette's noise
         * floor without saturating, which is where the measured buttons sat.
         */
        const val LED_STRENGTH = 190f
    }
}
