package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.gems.GemColour.BLUE
import com.puzzlesolver.core.puzzle.gems.GemColour.GREEN
import com.puzzlesolver.core.puzzle.gems.GemColour.RED
import com.puzzlesolver.core.puzzle.gems.GemColour.YELLOW
import com.puzzlesolver.core.puzzle.gems.GemHighlightSteadier
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Rings held steady across scans, so a reading that wobbles does not make them blink.
 *
 * On 2026-10-31 the rings flickered on and off in the third stage, over gems that were
 * not changing: a ring per scan, ten scans a second, drawn from whichever way a
 * borderline gem happened to read that time.
 */
class GemHighlightSteadierTest {

    private val pitch = 100f
    private val target = GemPattern(GREEN, RED, BLUE)
    private val match = GemPattern(GREEN, RED, BLUE)
    private val other = GemPattern(GREEN, YELLOW, BLUE)

    /** A 5x4 patch of wall, panned by [dx], with the gem at (2, 1) read as [centre]. */
    private fun scan(dx: Float, centre: GemPattern?, whole: Boolean = true, dropCentre: Boolean = false): GemScanner.Result {
        val gems = ArrayList<GemScanner.Gem>()
        for (row in 0 until 4) for (col in 0 until 5) {
            val isCentre = col == 2 && row == 1
            if (isCentre && dropCentre) continue
            val pattern = if (isCentre) centre!! else GemPattern(RED, RED, RED)
            gems.add(GemScanner.Gem(200f + col * pitch + dx, 200f + row * pitch, 25f, pattern, 0.5f, 0, !isCentre || whole))
        }
        return GemScanner.Result(gems, 0, gems.size, pitch, 0f, "")
    }

    private fun ringedAtCentre(rings: List<GemScanner.Gem>, dx: Float): Int =
        rings.firstOrNull { hypot(it.x - (400f + dx), it.y - 300f) < 1f }?.matchedSlot ?: 0

    @Test
    fun `one stray reading neither lights a gem nor puts one out`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        // Steadily not a match, then one scan that reads as one.
        val seen = listOf(other, other, match, other, other).map { ringedAtCentre(s.update(scan(0f, it), active), 0f) }
        assertEquals(listOf(0, 0, 0, 0, 0), seen)

        s.reset()
        // Steadily a match, with one scan that misreads it.
        val held = listOf(match, match, other, match, match).map { ringedAtCentre(s.update(scan(0f, it), active), 0f) }
        assertEquals("ringed from the second scan on, through the misread", listOf(0, 1, 1, 1, 1), held)
    }

    @Test
    fun `it follows the wall while the camera pans`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        // Thirty pixels a scan -- three hundred a second, a brisk pan at a 100 px pitch.
        val seen = (0 until 6).map { i ->
            val dx = i * 30f
            ringedAtCentre(s.update(scan(dx, if (i == 3) other else match), active), dx)
        }
        assertEquals(listOf(0, 1, 1, 1, 1, 1), seen)
    }

    @Test
    fun `a gem the detector misses for one scan keeps its ring`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        s.update(scan(0f, match), active)
        s.update(scan(10f, match), active)
        val lost = s.update(scan(20f, match, dropCentre = true), active)
        assertEquals("ringed where the wall moved it to", 1, ringedAtCentre(lost, 20f))
        assertEquals(1, ringedAtCentre(s.update(scan(30f, match), active), 30f))
    }

    @Test
    fun `a gem cut off by the frame edge never votes for a match`() {
        val s = GemHighlightSteadier()
        val active = listOf(target)
        val seen = (0 until 4).map { ringedAtCentre(s.update(scan(0f, match, whole = false), active), 0f) }
        assertEquals(listOf(0, 0, 0, 0), seen)
    }

    /**
     * The room as it was on 2026-11-12: a red and green wall of four decoy patterns
     * repeated everywhere, and each target's pattern on one gem. Scanned while the
     * camera pans across it in buffer y, [step] pitches a scan on average, with hand
     * shake on top. Returns how many of the chances to ring a target were taken -- a
     * chance being a scan where the gem has been whole in view for this scan and the
     * one before -- and how many decoys were ever ringed.
     */
    private fun panAcrossTheRoom(step: Float, blankScan: Int = -1): Pair<Float, Int> {
        val random = java.util.Random(20261112)
        val decoys = listOf(GemPattern(RED, RED, RED), GemPattern(GREEN, GREEN, RED), GemPattern(RED, GREEN, GREEN), GemPattern(RED, GREEN, RED))
        val targets = listOf(GemPattern(GREEN, RED, GREEN), GemPattern(GREEN, RED, RED), GemPattern(GREEN, GREEN, GREEN), GemPattern(RED, RED, GREEN))
        val cols = 8
        val rows = 60
        val wall = Array(rows) { Array(cols) { decoys[random.nextInt(decoys.size)] } }
        // Targets every so often down the wall, in different columns.
        for (r in 5 until rows step 6) wall[r][(r * 3) % cols] = targets[(r / 6) % targets.size]

        val p = 120f
        val viewW = 900f
        val viewH = 1080f
        val reach = p * 0.23f
        val s = GemHighlightSteadier()
        var ox = 0f
        var oy = 0f
        var chances = 0
        var taken = 0
        var decoysRinged = 0
        val wholeBefore = HashSet<Pair<Int, Int>>()
        for (scan in 0 until 70) {
            ox += (random.nextGaussian() * 0.12 * p).toFloat()
            oy += step * p + (random.nextGaussian() * 0.12 * p).toFloat()
            if (scan == blankScan) {
                s.update(GemScanner.Result(emptyList(), 0, 0, -1f, -1f, "no gems in view"), targets)
                wholeBefore.clear()
                continue
            }
            val gems = ArrayList<GemScanner.Gem>()
            val cellOf = ArrayList<Pair<Int, Int>>()
            for (r in 0 until rows) for (c in 0 until cols) {
                val x = 60f + c * p - ox + (random.nextGaussian() * 1.5).toFloat()
                val y = 60f + r * p - oy + (random.nextGaussian() * 1.5).toFloat()
                if (x < 0 || y < 0 || x > viewW || y > viewH) continue
                val whole = x >= reach && y >= reach && x <= viewW - 1 - reach && y <= viewH - 1 - reach
                gems.add(GemScanner.Gem(x, y, 27f, wall[r][c], 0.9f, 0, whole))
                cellOf.add(r to c)
            }
            val rings = s.update(GemScanner.Result(gems, 0, gems.size, p, 0f, ""), targets)
            val wholeNow = HashSet<Pair<Int, Int>>()
            for ((i, gem) in gems.withIndex()) {
                if (!gem.whole) continue
                wholeNow.add(cellOf[i])
                val ringed = rings.any { hypot(it.x - gem.x, it.y - gem.y) < 1f }
                if (gem.pattern in targets) {
                    if (cellOf[i] in wholeBefore) {
                        chances++
                        if (ringed) taken++
                    }
                } else if (ringed) {
                    decoysRinged++
                }
            }
            wholeBefore.clear()
            wholeBefore.addAll(wholeNow)
        }
        assertTrue("the pan never offered a target", chances >= 10)
        return taken.toFloat() / chances to decoysRinged
    }

    /**
     * On 2026-11-12 the user swept the wall at three and a half to five pitches a second
     * -- 0.35 to 0.5 of a pitch between scans -- and the rings almost never appeared: the
     * scanner matched five gems while none was ringed. Gems were followed by nearest
     * position within 0.4 of a pitch, so at that speed every gem started a new history
     * every scan and never reached two matching readings.
     */
    @Test
    fun `targets stay ringed while the camera sweeps the wall`() {
        for (step in listOf(0.1f, 0.45f, 0.6f)) {
            val (rate, decoys) = panAcrossTheRoom(step)
            assertTrue("at $step pitch a scan, ringed ${(rate * 100).toInt()}% of the time", rate >= 0.95f)
            assertEquals("decoys ringed at $step pitch a scan", 0, decoys)
        }
    }

    @Test
    fun `targets stay ringed through a flick of more than a pitch a scan`() {
        val (rate, decoys) = panAcrossTheRoom(1.5f)
        assertTrue("ringed ${(rate * 100).toInt()}% of the time", rate >= 0.9f)
        assertEquals(0, decoys)
    }

    /** One scan with nothing in it -- a blurred frame, the wall dipping out -- forgets nothing. */
    @Test
    fun `an empty scan in the middle of a pan forgets nothing`() {
        val (rate, decoys) = panAcrossTheRoom(0.3f, blankScan = 20)
        assertTrue("ringed ${(rate * 100).toInt()}% of the time", rate >= 0.95f)
        assertEquals(0, decoys)
    }

    @Test
    fun `new targets apply at once to the gems already in view`() {
        val s = GemHighlightSteadier()
        s.update(scan(0f, other), listOf(target))
        s.update(scan(0f, other), listOf(target))
        val rings = s.update(scan(0f, other), listOf(other))
        assertEquals(1, ringedAtCentre(rings, 0f))
        // The red/red/red gems around it match nothing asked for.
        assertTrue(rings.all { hypot(it.x - 400f, it.y - 300f) < 1f })
    }
}
