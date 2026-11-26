package com.puzzlesolver.core

import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.link.LinkColour
import com.puzzlesolver.core.puzzle.link.LinkFrameReader
import com.puzzlesolver.core.puzzle.link.LinkPairer
import com.puzzlesolver.core.puzzle.link.LinkPanel
import com.puzzlesolver.core.puzzle.link.LinkScanner
import com.puzzlesolver.core.puzzle.link.LinkWall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Whole pans of a Link wall through the scanner.
 *
 * Nobody has pointed the phone at the room's Link wall yet, so two kinds of pan stand in:
 *
 *  - **the practice game**, from the screenshot the user sent of it (`link/practice-16.png`,
 *    16 pairs), blown up to the size buttons are in the phone's frames and panned across
 *    as a phone would. Its unlit buttons are drawn grey, which the room's at the LED-wall
 *    preset are not, so it also tests that only lit buttons are taken for panels;
 *  - **a rendered room**: black panel, lit discs with a glow, in colours like the ones the
 *    gem and mines walls measured at the preset -- with the camera rolled, the rows bowed by
 *    the curved wall, the spacing changing across the frame, a white balance that pushes
 *    yellow toward green, and 16, 12 and 8 pairs, one level leaving slots dark.
 */
class LinkPanTest {

    private val practice = LinkWall.parse(
        """
        BRG.RRB.PPP.GYP.GRR.BRG.RRG.BRB.BGG.GRR.YGB.GYP.RBP.RBY.BBB.RBY
        PRP.RGY.YPG.BYP.GBB.PRP.BPB.BRY.GBB.GBB.RPR.BYP.RPR.RBY.GRG.RBY
        RRG.GGY.YPY.GRR.RYB.RRG.PPY.PPP.GBY.RYB.BBB.GRR.RGY.RBG.RGP.RBG
        ...............................................................
        YPB.RBP.BBB.GRR.RRB.RBP.GRR.PPP.BRB.YGB.GBY.RBP.BGG.RRG.YPB.GBY
        BYY.RPR.GRG.YPG.RGY.YYP.YPG.YPG.BRY.RPR.PBG.YYP.GBB.BPB.BYY.PBG
        YBR.RGY.RGP.YPP.GGY.YGR.YPP.YPY.PPP.BBB.BBP.YGR.GBY.PPY.YBR.BBP
        """
    )

    // --- The practice game's screenshot --------------------------------------------------

    private fun screenshot(): RgbImage {
        val stream = javaClass.classLoader.getResourceAsStream("link/practice-16.png") ?: error("fixture missing")
        val img = stream.use { ImageIO.read(it) }
        val out = RgbImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            out.set(x, y, (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }
        return out
    }

    /** [src] scaled by [scale] (bilinear) and cut to a [w]x[h] window at ([x0], [y0]) of the scaled image. */
    private fun window(src: RgbImage, scale: Float, x0: Float, y0: Float, w: Int, h: Int, into: RgbImage? = null): RgbImage {
        val out = into ?: RgbImage(w, h)
        for (y in 0 until h) {
            val sy = (y + y0) / scale
            for (x in 0 until w) {
                val sx = (x + x0) / scale
                if (sx < 0 || sy < 0 || sx >= src.width - 1 || sy >= src.height - 1) {
                    out.set(x, y, 8, 8, 12)
                    continue
                }
                val ix = sx.toInt()
                val iy = sy.toInt()
                val fx = sx - ix
                val fy = sy - iy
                fun ch(c: Int): Int {
                    fun at(xx: Int, yy: Int) = src.data[(yy * src.width + xx) * 3 + c].toInt() and 0xFF
                    val top = at(ix, iy) * (1 - fx) + at(ix + 1, iy) * fx
                    val bot = at(ix, iy + 1) * (1 - fx) + at(ix + 1, iy + 1) * fx
                    return (top * (1 - fy) + bot * fy).roundToInt()
                }
                out.set(x, y, ch(0), ch(1), ch(2))
            }
        }
        return out
    }

    @Test
    fun `one frame of the practice game reads every whole panel in it`() {
        val shot = screenshot()
        // Twice the screenshot is about 50 px a button, as the phone sees the wall sideways.
        val frame = window(shot, 2f, 900f, -5f, 960, 540)
        val read = LinkFrameReader().read(frame)
        println("practice frame: ${read.report}")
        assertTrue("${read.panels.size} panels", read.panels.size >= 6)
        val truth = practice.panels.map { it.pattern }.toSet()
        for (p in read.panels) assertTrue("read ${p.pattern}", p.pattern in truth)
        // Grey unlit buttons are not lit. The window spans screenshot x 450 to 930: grid
        // columns 14 to 33, of which 15 are panel columns, six lit rows each, so 90 lit
        // buttons are in view against some 170 buttons in all.
        assertTrue("${read.lit} lit", read.lit in 72..90)
    }

    @Test
    fun `a pan across the practice game reads the whole board and finishes by itself`() {
        val shot = screenshot()
        val scanner = LinkScanner()
        val scale = 2f
        var t = 0L
        var x = -500f
        val end = shot.width * scale - 460f
        val buffer = RgbImage(960, 540)
        var progress: LinkScanner.Progress? = null
        while (x < end) {
            progress = scanner.offer(window(shot, scale, x, -5f, 960, 540, buffer), t)
            x += 14f
            t += 33_000_000L
        }
        val wall = scanner.result()
        assertNotNull(wall)
        println("practice pan: ${progress?.message}")
        assertEquals(practice.describe(), wall!!.describe())
        assertEquals(LinkScanner.Phase.DONE, progress!!.phase)
        assertTrue(LinkPairer.pair(wall).complete)
    }

    // --- A rendered room ----------------------------------------------------------------

    /** LED faces at the preset, roughly as the gem and mines walls measured them. */
    private val roomColours = arrayOf(
        intArrayOf(240, 28, 60),   // red
        intArrayOf(235, 215, 50),  // yellow
        intArrayOf(40, 232, 110),  // green
        intArrayOf(40, 160, 245),  // blue: cyan-ish, as the camera renders it
        intArrayOf(175, 45, 235),  // purple
    )

    /**
     * The white balance of 2026-11-24's first Gems session, which put yellow on top of
     * green: yellow at hue 160-ish and saturation 0.26 (measured 167-172 at 0.26-0.31),
     * green at 177 (measured 176-178), blue at 213. Yellow is nearer green's starting hue
     * than its own here, and green is on the line between green and blue.
     */
    private val tintedColours = arrayOf(
        intArrayOf(205, 30, 45),
        intArrayOf(176, 235, 212),
        intArrayOf(35, 232, 222),
        intArrayOf(40, 150, 250),
        intArrayOf(165, 60, 235),
    )

    /**
     * Level 5's patterns: eight buttons of one colour round a middle of another (or the
     * same), so two patterns can differ by their middle alone -- exactly the case where
     * yellow read as green makes two pairs into four identical panels.
     */
    private fun centreWall(pairs: Int, seed: Int): LinkWall {
        val random = Random(seed)
        val combos = (0 until 5).flatMap { o -> (0 until 5).map { c -> o to c } }.shuffled(random).take(pairs)
        val places = (0 until pairs).flatMap { s -> listOf(s * 4 to 0, s * 4 to 4) }.shuffled(random)
        val cells = combos.flatMap { listOf(it, it) }
        return LinkWall(places.zip(cells).map { (place, combo) ->
            LinkPanel(place.first, place.second, IntArray(9) { if (it == 4) combo.second else combo.first })
        })
    }

    private class Camera(
        val pitch: Float,
        val roll: Float,
        /** Sag of the rows at the frame's edges, in buttons. */
        val bow: Float,
        /** How much bigger the buttons are at the right edge than the left, as a fraction. */
        val perspective: Float,
    )

    /**
     * Renders the wall at the LED preset with the camera's middle on wall button
     * ([camX], [camY]).
     */
    private fun render(
        wall: LinkWall,
        colours: Array<IntArray>,
        camera: Camera,
        camX: Float,
        camY: Float,
        random: Random,
        into: RgbImage,
        blackout: Boolean = false,
    ): RgbImage {
        val w = into.width
        val h = into.height
        val acc = FloatArray(w * h * 3)
        if (!blackout) {
            val cr = cos(camera.roll)
            val sr = sin(camera.roll)
            for (p in wall.panels) for (k in 0 until LinkPanel.CELLS) {
                val bx = p.col + k % 3
                val by = p.row + k / 3
                var u = (bx - camX) * camera.pitch
                var v = (by - camY) * camera.pitch
                val s = 1f + camera.perspective * u / w
                u *= s
                v *= s
                v += camera.bow * camera.pitch * (u / (w / 2f)) * (u / (w / 2f))
                val x = w / 2f + u * cr - v * sr
                val y = h / 2f + u * sr + v * cr
                val pitchHere = camera.pitch * s
                if (x < -pitchHere || y < -pitchHere || x > w + pitchHere || y > h + pitchHere) continue
                val c = colours[p.cells[k]]
                val face = pitchHere * 0.26f
                val reach = pitchHere * 0.6f
                val x0 = (x - reach).toInt().coerceAtLeast(0)
                val x1 = (x + reach).toInt().coerceAtMost(w - 1)
                val y0 = (y - reach).toInt().coerceAtLeast(0)
                val y1 = (y + reach).toInt().coerceAtMost(h - 1)
                for (yy in y0..y1) for (xx in x0..x1) {
                    val d = hypot(xx - x, yy - y)
                    val a = when {
                        d <= face -> 1f
                        else -> 0.22f * exp(-((d - face) / (pitchHere * 0.12f)).let { it * it })
                    }
                    if (a < 0.01f) continue
                    val i = (yy * w + xx) * 3
                    acc[i] += c[0] * a
                    acc[i + 1] += c[1] * a
                    acc[i + 2] += c[2] * a
                }
            }
        }
        for (i in 0 until w * h) {
            for (ch in 0..2) {
                val v = 4f + acc[i * 3 + ch] + random.nextInt(-4, 5)
                into.data[i * 3 + ch] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return into
    }

    /**
     * Pans [wall] from beyond its left end to beyond its right, a little up and down and at
     * a varying speed, and returns the scanner.
     */
    private fun pan(
        wall: LinkWall,
        colours: Array<IntArray> = roomColours,
        camera: Camera = Camera(pitch = 50f, roll = 0.05f, bow = 0.6f, perspective = 0.12f),
        seed: Int = 1,
        /** Wall columns over which the camera sees nothing at all, as when a hand covers it. */
        blackout: ClosedFloatingPointRange<Float>? = null,
        speed: Float = 0.3f,
    ): Pair<LinkScanner, LinkScanner.Progress> {
        val random = Random(seed)
        val scanner = LinkScanner()
        val frame = RgbImage(960, 540)
        val midY = wall.rows / 2f
        var camX = -3f
        var t = 0L
        var progress: LinkScanner.Progress? = null
        while (camX < wall.cols + 3f) {
            val camY = midY + 0.3f * sin(t / 4e8f)
            val dark = blackout != null && camX in blackout
            progress = scanner.offer(render(wall, colours, camera, camX, camY, random, frame, dark), t)
            camX += speed * (0.6f + 0.8f * random.nextFloat())
            t += 33_000_000L
        }
        return scanner to progress!!
    }

    private fun assertReads(truth: LinkWall, scanner: LinkScanner, progress: LinkScanner.Progress) {
        val wall = scanner.result()
        assertNotNull(wall)
        println(progress.message + "  " + wall!!.warnings.joinToString(" | "))
        assertEquals(truth.describe(), wall.describe())
        assertEquals(LinkScanner.Phase.DONE, progress.phase)
        assertTrue(LinkPairer.pair(wall).complete)
    }

    /** A wall of [pairs] random pairs, as the practice game's harder levels draw them. */
    private fun randomWall(pairs: Int, slots: Int, seed: Int): LinkWall {
        val random = Random(seed)
        val patterns = LinkedHashSet<String>()
        while (patterns.size < pairs) {
            val p = String(CharArray(9) { "RYGBP"[random.nextInt(5)] })
            if (p.toSet().size >= 3) patterns.add(p)
        }
        val places = (0 until slots).flatMap { s -> listOf(s * 4 to 0, s * 4 to 4) }.shuffled(random).take(pairs * 2)
        val cells = patterns.flatMap { listOf(it, it) }
        val panels = places.zip(cells).map { (place, pattern) ->
            LinkPanel(place.first, place.second, IntArray(9) { LinkColour.fromLetter(pattern[it]) })
        }
        return LinkWall(panels)
    }

    @Test
    fun `a rendered room of sixteen pairs reads exactly`() {
        val truth = randomWall(16, 16, 11)
        val (scanner, progress) = pan(truth)
        assertReads(truth, scanner, progress)
    }

    @Test
    fun `twelve pairs read exactly, and so does eight with slots left dark`() {
        val twelve = randomWall(12, 12, 12)
        pan(twelve, seed = 2).let { (s, p) -> assertReads(twelve, s, p) }
        // Eight pairs spread over sixteen slots: whole dark slots, gaps that look like ends.
        val eight = randomWall(8, 16, 13)
        pan(eight, seed = 3).let { (s, p) -> assertReads(eight, s, p) }
    }

    @Test
    fun `a tinted white balance is read by where its own colours fall`() {
        val truth = randomWall(16, 16, 14)
        val (scanner, progress) = pan(truth, colours = tintedColours, seed = 4)
        assertReads(truth, scanner, progress)
        // Level 5's patterns under the same tint: a middle misnamed would pair wrongly.
        val centres = centreWall(14, 24)
        val (s2, p2) = pan(centres, colours = tintedColours, seed = 8)
        assertReads(centres, s2, p2)
    }

    @Test
    fun `a stronger roll and bow, and a faster pan, still read`() {
        val truth = randomWall(16, 16, 15)
        val camera = Camera(pitch = 46f, roll = -0.12f, bow = 1.1f, perspective = 0.2f)
        val (scanner, progress) = pan(truth, camera = camera, seed = 5, speed = 0.5f)
        assertReads(truth, scanner, progress)
    }

    @Test
    fun `losing the picture mid-pan is found again by the patterns`() {
        val truth = randomWall(16, 16, 16)
        val (scanner, progress) = pan(truth, seed = 6, blackout = 24f..30f)
        assertReads(truth, scanner, progress)
    }
}
