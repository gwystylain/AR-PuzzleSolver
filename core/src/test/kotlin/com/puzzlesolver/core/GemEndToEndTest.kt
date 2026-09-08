package com.puzzlesolver.core

import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.hypot

/**
 * The whole Gems chain, on a canvas built to look like the wall.
 *
 * The rendering reproduces the three properties that make these gems awkward, all of
 * them measured off `testVideos/Gems/VID20260814182431.mp4`:
 *
 *  - the LEDs sit in **three concentric rings** at 0, 0.105 and 0.20 of the lattice
 *    pitch, so the bands a reader can sample are narrow and adjacent;
 *  - each ring **clips**, so no ring's own colour survives at its brightest point and
 *    a reader that samples peaks gets white every time;
 *  - each ring **glows over the others**, so every band contains a wash of its
 *    neighbours' colours and the reading has to be a vote rather than an average.
 *
 * A board of plain flat discs would let a far lazier classifier pass and would not be
 * the thing the app is handed. What this cannot reproduce is real sensor behaviour --
 * that is what `RealGemFrameTest` and `GemScannerTest` are for, against actual frames.
 * What it has that they do not is *exact* ground truth: eighty-four gems whose colours
 * are not a judgement call but the values the renderer was told to draw.
 */
class GemEndToEndTest {

    private val pitch = 60f
    private val cols = 12
    private val rows = 7

    private val spec = CanvasSpec(
        widthTexels = 840,
        heightTexels = 540,
        // 60 texels of pitch reads as 15 cm of wall.
        metresPerTexel = 0.0025f,
    )

    private val originX = 60f
    private val originY = 55f

    /** The dark panel between the gems. */
    private val ambient = floatArrayOf(14f, 12f, 22f)

    private val targets = GemTargets()

    /**
     * A board with every colour in every ring position, plus repeats, so a bug that
     * swaps two rings or two colours cannot hide behind a board where they happen to
     * agree.
     */
    private fun board(): Array<GemPattern> {
        val palette = GemColour.ALL
        return Array(cols * rows) { i ->
            GemPattern(
                outer = palette[i % 5],
                middle = palette[(i / 5 + 1) % 5],
                centre = palette[(i / 7 + 3) % 5],
            )
        }
    }

    /**
     * Linear RGB a ring's LEDs emit, before clipping.
     *
     * These are the mean channel ratios of 27 hand-labelled rings in the reference
     * clip, not textbook colour names. It matters most for red against purple, which
     * on this wall are 26 degrees apart on the hue circle and are where a reader that
     * mishandles clipping goes wrong first. Inventing the two would have quietly set
     * the difficulty of the test to whatever the author guessed.
     */
    private fun emission(colour: Int): FloatArray = when (colour) {
        GemColour.RED -> floatArrayOf(1.000f, 0.089f, 0.201f)
        GemColour.YELLOW -> floatArrayOf(1.000f, 0.889f, 0.401f)
        GemColour.GREEN -> floatArrayOf(0.194f, 1.000f, 0.522f)
        GemColour.BLUE -> floatArrayOf(0.179f, 0.601f, 1.000f)
        else -> floatArrayOf(1.000f, 0.148f, 0.822f)
    }

    /** Radial position of each ring, as a fraction of the pitch. */
    private val ringRadii = floatArrayOf(0f, 0.105f, 0.20f)

    private fun render(board: Array<GemPattern>): CanvasView {
        val rgb = Array(spec.heightTexels) { Array(spec.widthTexels) { ambient.copyOf() } }
        val lensRadius = pitch * 0.25f
        val sigma = pitch * 0.030f

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val gem = board[row * cols + col]
                val cx = originX + col * pitch
                val cy = originY + row * pitch
                val zoneColours = intArrayOf(gem.centre, gem.middle, gem.outer)

                val x0 = (cx - lensRadius).toInt().coerceAtLeast(0)
                val x1 = (cx + lensRadius).toInt().coerceAtMost(spec.widthTexels - 1)
                val y0 = (cy - lensRadius).toInt().coerceAtLeast(0)
                val y1 = (cy + lensRadius).toInt().coerceAtMost(spec.heightTexels - 1)

                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val d = hypot(x - cx, y - cy)
                        if (d > lensRadius) continue
                        val px = rgb[y][x]
                        for (zone in 0..2) {
                            val emit = emission(zoneColours[zone])
                            val dr = d - ringRadii[zone] * pitch
                            // A ring's own light, falling off with distance from it, plus
                            // a floor of scattered light across the whole lens. The floor
                            // is what makes every band a mixture -- exactly the thing a
                            // hue average gets wrong.
                            val near = exp(-(dr * dr) / (2f * sigma * sigma))
                            val gain = RING_PEAK * near + RING_SCATTER
                            px[0] += emit[0] * gain
                            px[1] += emit[1] * gain
                            px[2] += emit[2] * gain
                        }
                        // The sensor clips, and clipping is not a rounding detail here:
                        // it is why the middle of a ring carries no colour at all.
                        px[0] = px[0].coerceAtMost(255f)
                        px[1] = px[1].coerceAtMost(255f)
                        px[2] = px[2].coerceAtMost(255f)
                    }
                }
            }
        }

        // Encoded exactly as the accumulator's fragment shader does.
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

    /** Finds the gem the scanner reports nearest a drawn position, if any. */
    private fun at(result: GemScanner.Result, col: Int, row: Int): GemScanner.Gem? {
        val cx = originX + col * pitch
        val cy = originY + row * pitch
        return result.gems.firstOrNull { hypot(it.x - cx, it.y - cy) < pitch * 0.4f }
    }

    @Test
    fun `a rendered wall is found, read and matched`() {
        val truth = board()
        val view = render(truth)
        val scanner = GemScanner(targets)

        // 1. Every gem found, none invented, and the spacing recovered from the gems
        //    themselves rather than from any lattice fit.
        val idle = scanner.scan(view)
        assertEquals("every gem should be found", cols * rows, idle.gems.size)
        assertEquals("pitch", pitch, idle.pitch, 2f)

        // 2. Every gem reads back the three rings it was drawn with. Exact, because
        //    unlike the real-frame test this board's ground truth is not a judgement
        //    call -- these are the colours the renderer was told to draw.
        val wrong = StringBuilder()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val gem = at(idle, col, row)
                if (gem == null) {
                    wrong.append("\n  ($col,$row) not found")
                    continue
                }
                val expected = truth[row * cols + col]
                if (gem.pattern != expected) {
                    wrong.append("\n  ($col,$row) drawn $expected, read ${gem.pattern}")
                }
            }
        }
        assertEquals("misread gems:$wrong", 0, wrong.count { it == '\n' })

        // 3. With no target set there is nothing to answer, and it says so rather than
        //    reporting a search that found nothing.
        assertEquals(0, idle.matchCount)
        assertTrue(idle.status, idle.status.contains("tap a target"))

        // 4. With a target set, exactly the gems that match are marked.
        val wanted = truth[3 * cols + 4]
        targets.set(0, wanted)
        val result = scanner.scan(view)
        var matched = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val gem = at(result, col, row) ?: continue
                val shouldMatch = truth[row * cols + col] == wanted
                assertEquals("gem ($col,$row) match", shouldMatch, gem.matchedSlot > 0)
                if (shouldMatch) matched++
            }
        }
        assertTrue("the chosen target should match at least itself", matched >= 1)
        assertEquals(matched, result.matchCount)
    }

    /**
     * A partial target is a live filter, not an incomplete one.
     *
     * This is the behaviour that makes the input dialog worth using: the first colour
     * tapped already narrows the wall, so the user finds out whether they read the
     * target right before entering the other two.
     */
    @Test
    fun `a target with only an outer ring matches on that ring alone`() {
        val truth = board()
        val view = render(truth)
        val scanner = GemScanner(targets)

        targets.set(0, GemPattern(outer = GemColour.GREEN))
        val result = scanner.scan(view)

        var matches = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val gem = at(result, col, row) ?: continue
                val expected = truth[row * cols + col].outer == GemColour.GREEN
                assertEquals("gem ($col,$row)", expected, gem.matchedSlot > 0)
                if (expected) matches++
            }
        }
        assertTrue("the board should contain green outer rings", matches > 5)
    }

    /**
     * Overlapping targets resolve to the first that matches, so the digit drawn on a gem
     * is stable rather than depending on the order the frame happened to be scanned in.
     */
    @Test
    fun `a gem matching two targets is attributed to the earlier one`() {
        val truth = board()
        val view = render(truth)
        val scanner = GemScanner(targets)

        val exact = truth[2 * cols + 3]
        targets.set(0, GemPattern(outer = exact.outer))
        targets.set(1, exact)

        val result = scanner.scan(view)
        val gem = at(result, 3, 2)
        assertNotNull("the gem should have been found", gem)
        assertEquals("the broader target is slot 1 and should win", 1, gem!!.matchedSlot)
    }

    private companion object {
        /**
         * Peak brightness a ring adds at its own radius. Well over 255 on purpose: the
         * ring cores have to clip, because that is what the real ones do.
         */
        const val RING_PEAK = 260f

        /** Light every ring scatters across the whole lens, whatever the radius. */
        const val RING_SCATTER = 12f
    }
}
