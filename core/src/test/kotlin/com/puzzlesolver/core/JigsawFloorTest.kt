package com.puzzlesolver.core

import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.jigsaw.FloorColour
import com.puzzlesolver.core.puzzle.jigsaw.FloorReader
import com.puzzlesolver.core.puzzle.jigsaw.FloorRegions
import com.puzzlesolver.core.puzzle.jigsaw.JigsawSolver
import com.puzzlesolver.core.puzzle.spot.Homography
import org.junit.Test
import java.io.File
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Jigsaw floor read off photos: drawn ones, in perspective, with a known answer; and the
 * room itself where its photos are on this machine.
 */
class JigsawFloorTest {

    /**
     * A floor like the room's: sixteen wide, the green box two thick round a 12 x 8 inside,
     * shapes in it, and pieces beyond it at the near end. Rows run from the far end down.
     */
    private val floor = listOf(
        "................",
        "GGGGGGGGGGGGGGGG",
        "GGGGGGGGGGGGGGGG",
        "GG............GG",
        "GG...WWW......GG",
        "GG...WYY..BB..GG",
        "GG....YY..B...GG",
        "GG........B...GG",
        "GG............GG",
        "GG............GG",
        "GG............GG",
        "GGGGGGGGGGGGGGGG",
        "GGGGGGGGGGGGGGGG",
        "................",
        ".W........YY....",
        ".W...B..........",
        ".WW..BBB...Y....",
        "...........Y....",
        ".........YY..WW.",
        "..BBB.....Y..W..",
        "................",
    )

    private val tileColour = mapOf(
        'W' to intArrayOf(250, 248, 225),
        'Y' to intArrayOf(255, 225, 70),
        'O' to intArrayOf(255, 168, 112),
        'B' to intArrayOf(20, 120, 255),
        'G' to intArrayOf(10, 250, 170),
    )

    /**
     * The floor drawn as a camera at its near end would see it: [imageFromFloor] takes a floor
     * position (columns, rows, tile corners on whole numbers) to the photo. Lit tiles are their
     * colour, unlit ones a dim grey, the gaps between darker still.
     */
    private fun render(imageFromFloor: Homography, w: Int, h: Int): RgbImage {
        val floorFromImage = Homography.invert(imageFromFloor)!!
        val out = RgbImage(w, h)
        val p = DoubleArray(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                floorFromImage.map(x + 0.5, y + 0.5, p)
                val col = floor(p[0]).toInt()
                val row = floor(p[1]).toInt()
                val fx = p[0] - col
                val fy = p[1] - row
                val onFloor = row in floor.indices && col in 0 until floor[0].length
                val inTile = fx in 0.13..0.87 && fy in 0.13..0.87
                val c = when {
                    !onFloor -> intArrayOf(8, 14, 10)
                    !inTile -> intArrayOf(12, 20, 16)
                    else -> tileColour[floor[row][col]] ?: intArrayOf(55, 70, 60)
                }
                out.set(x, y, c[0], c[1], c[2])
            }
        }
        return out
    }

    /** A view down the floor from its near end: the far end narrow at the top of the photo. */
    private fun lookingDown(w: Int, h: Int): Homography = Homography.fit(
        doubleArrayOf(0.0, 0.0, 16.0, 0.0, 16.0, floor.size.toDouble(), 0.0, floor.size.toDouble()),
        doubleArrayOf(w * 0.28, h * 0.08, w * 0.72, h * 0.08, w * 0.98, h * 0.97, w * 0.02, h * 0.97),
    )!!

    /** The lit cells of a read, as rows of codes, trimmed to what is lit. */
    private fun litRows(read: FloorReader.Read): List<String> {
        val all = (0 until read.height).map { r -> String(CharArray(read.width) { c -> read.colour(c, r)?.code ?: '.' }) }
        return trim(all)
    }

    private fun trim(rows: List<String>): List<String> {
        val kept = rows.dropWhile { r -> r.all { it == '.' } }.dropLastWhile { r -> r.all { it == '.' } }
        val left = kept.minOf { r -> r.indexOfFirst { it != '.' }.let { if (it < 0) Int.MAX_VALUE else it } }
        val right = kept.maxOf { r -> r.indexOfLast { it != '.' } }
        return kept.map { it.substring(left, right + 1) }
    }

    @Test
    fun `a floor in perspective is read cell for cell, the right way up`() {
        val w = 900
        val h = 1200
        val read = FloorReader(log = ::println).read(render(lookingDown(w, h), w, h))
        assertNull(read.failure)
        assertEquals(trim(floor), litRows(read))
    }

    @Test
    fun `a photo taken from the side comes out turned, never mirrored`() {
        // The same floor seen from its left-hand wall: the floor's columns run up the photo.
        val w = 1200
        val h = 900
        val n = floor.size.toDouble()
        val fromSide = Homography.fit(
            doubleArrayOf(16.0, 0.0, 16.0, n, 0.0, n, 0.0, 0.0),
            doubleArrayOf(w * 0.08, h * 0.1, w * 0.92, h * 0.1, w * 0.99, h * 0.95, w * 0.01, h * 0.95),
        )!!
        val read = FloorReader().read(render(fromSide, w, h))
        assertNull(read.failure)
        // Turned a quarter: the photo's rows run from the floor's last column, at the top, to
        // its first; along each, the floor's rows from the far end to the near. A mirror would
        // have the floor's first column at the top instead.
        val turned = (0 until 16).map { k -> String(CharArray(floor.size) { r -> floor[r][15 - k] }) }
        assertEquals(trim(turned), litRows(read))
    }

    @Test
    fun `the box photo gives the shapes inside the frame, and a piece photo the pieces outside it`() {
        val w = 900
        val h = 1200
        val read = FloorReader().read(render(lookingDown(w, h), w, h))
        val box = FloorRegions.box(read)
        assertEquals(
            listOf(
                "WWW....",
                "WYY..BB",
                ".YY..B.",
                ".....B.",
            ),
            box.rows,
        )
        val pieces = FloorRegions.pieces(read)
        assertTrue(pieces.rows.none { 'G' in it })
        assertEquals(trim(floor.drop(13)), pieces.rows)
        assertTrue(box.dropped.isNotEmpty())
    }

    @Test
    fun `a piece photo with only the near side of the frame keeps the side it was aimed at`() {
        // Just the near end and the frame's near side: the shapes beyond are out of the frame
        // of the photo except one blue cell, which must not be taken for a piece.
        val near = listOf(
            "..........B.....",
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "................",
            ".W........YY....",
            ".W...B..........",
            ".WW..BBB...Y....",
        )
        val read = readOf(near, aimCol = 8.0, aimRow = 5.0)
        val pieces = FloorRegions.pieces(read)
        assertEquals(trim(near.drop(3)), pieces.rows)
        assertNotNull(pieces.note)
    }

    @Test
    fun `a piece photo keeps only the end it was aimed at, not the far end beyond the box`() {
        val both = listOf(
            "..BB.....",
            "GGGGGGGGG",
            "GG.....GG",
            "GG.B...GG",
            "GG.....GG",
            "GGGGGGGGG",
            ".W....YY.",
        )
        assertEquals(listOf("W....YY"), FloorRegions.pieces(readOf(both, aimCol = 4.0, aimRow = 6.0)).rows)
        assertEquals(listOf("BB"), FloorRegions.pieces(readOf(both, aimCol = 4.0, aimRow = 0.0)).rows)
        // Aimed at the box: both ends.
        assertEquals(7, FloorRegions.pieces(readOf(both, aimCol = 4.0, aimRow = 3.0)).rows.size)
        assertEquals(listOf("B"), FloorRegions.box(readOf(both, aimCol = 4.0, aimRow = 6.0)).rows)
    }

    @Test
    fun `with no frame in the photo everything lit is kept`() {
        val read = readOf(listOf("W.B", "..B"), aimCol = 1.0, aimRow = 1.0)
        assertEquals(listOf("W.B", "..B"), FloorRegions.pieces(read).rows)
        assertEquals(listOf("W.B", "..B"), FloorRegions.box(read).rows)
    }

    @Test
    fun `the drawn floor solves`() {
        val w = 900
        val h = 1200
        val read = FloorReader().read(render(lookingDown(w, h), w, h))
        val solution = JigsawSolver.solve(FloorRegions.box(read).rows, listOf(FloorRegions.pieces(read).rows))
        val byColour = solution.answers.associateBy { it.colour }
        assertEquals(setOf('W', 'Y', 'B'), byColour.keys)
        // White from its L of four, turned; yellow's square from its two dominoes; blue from
        // its L of four, turned. The white L of three, the yellow L and the blue bar are decoys.
        for (a in solution.answers) {
            assertTrue(a.solved, "${a.colour} unsolved")
            assertEquals(1, a.ways.size, "${a.colour}")
        }
        assertEquals(listOf(2, 2), byColour.getValue('Y').ways.single().map { it.piece.size })
    }

    private fun readOf(rows: List<String>, aimCol: Double, aimRow: Double): FloorReader.Read {
        val w = rows[0].length
        val colours = Array(w * rows.size) { i -> FloorColour.of(rows[i / w][i % w]) }
        return FloorReader.Read(w, rows.size, colours, null, 0, null, aimCol, aimRow)
    }

    /**
     * The room's own photos, where this machine has them: two rounds, each the whole floor from
     * one corner. They are not in the repository, since there are people in them.
     */
    @Test
    fun `the room's photos read into the box and its pieces`() {
        val dir = listOf(File("testVideos/Jigsaw"), File("../testVideos/Jigsaw")).firstOrNull { it.isDirectory }
        if (dir == null) {
            println("no Jigsaw photos on this machine; skipped")
            return
        }
        for (name in listOf("round-a-whole-floor.jpg", "round-b-whole-floor.jpg")) {
            val file = File(dir, name)
            if (!file.exists()) continue
            val read = FloorReader(log = ::println).read(SpotFixture.read(file))
            assertNull(read.failure, name)
            println("$name: ${read.width}x${read.height}, ${read.tiles} tiles")
            for (r in 0 until read.height) {
                println("  " + String(CharArray(read.width) { c -> read.colour(c, r)?.code ?: '.' }))
            }
            val box = FloorRegions.box(read)
            val pieces = FloorRegions.pieces(read)
            println("box:"); box.rows.forEach { println("  $it") }
            println("pieces:"); pieces.rows.forEach { println("  $it") }
            val solution = JigsawSolver.solve(box.rows, listOf(pieces.rows))
            for (a in solution.answers) {
                println("  ${a.colour}: ${a.target.size} cells, pieces ${a.pieces.map { it.size }}, ${a.ways.size} ways")
            }
            // The box's inside is 12 x 8: whatever is in it fits in that.
            assertTrue(box.rows.size <= 8 && box.rows.all { it.length <= 12 }, "box ${box.rows}")
        }
    }
}
