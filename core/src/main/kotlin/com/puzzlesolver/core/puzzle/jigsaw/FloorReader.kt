package com.puzzlesolver.core.puzzle.jigsaw

import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.spot.Homography
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads a photo of the Jigsaw room's floor into a grid of lit cells, each with its colour.
 *
 * The floor is a flat grid of square tiles, so a photo of it from anywhere is a single
 * homography of that grid -- no pose, no lens model, nothing to know about the phone. The
 * reader finds that homography from the lit tiles themselves:
 *
 * 1. **The lit tiles.** Every pixel whose brightest channel is near the photo's brightest is
 *    lit; each 4-connected patch of them is a candidate tile. A tile is a square seen in
 *    perspective, so each patch is fitted with a quadrilateral -- the pixel farthest from its
 *    middle, the one farthest from that, and the two farthest either side of the line between
 *    them -- and kept if it fills that quadrilateral as a tile would. Ceiling lights, people
 *    and the glow on the walls mostly fail that, and the rest fail the grid.
 * 2. **One tile, then its neighbours.** A tile's four corners alone fix a homography, which
 *    says where its neighbours should be and, from the nearest of them, how far apart tiles
 *    are. From there the grid grows: any tile that lands within a quarter cell of a cell
 *    centre, next to a tile already placed, and the size a tile at that cell should be, is
 *    placed, and the homography is fitted again to every tile placed so far. The reach widens
 *    in steps -- two cells, three, four, six, anywhere -- while the tolerance tightens, so the
 *    far-flung pieces are reached only once the fit is good enough to trust that far out.
 *    Every tile is tried as the first, up to [MAX_SEEDS] of the likeliest, and the one that
 *    places the most wins. A big tile near the camera with its glow is a poor first tile; one
 *    in a run of neighbours, a good one.
 * 3. **Every cell, read.** With the grid known, each cell inside the span of the tiles placed
 *    is read afresh, from the middle half of its tile: lit if most of that is lit, coloured by
 *    the mean of its lit pixels. That picks up far tiles whose glow ran together into one patch,
 *    which step 2 had to leave out.
 * 4. **The right way up.** The grid is turned in quarter turns so that its rows run across the
 *    photo and its first row is the farthest: drawn on the phone, it looks like the photo.
 *    Never mirrored -- a homography of a photo of a floor from above cannot be -- which matters,
 *    because in this room a piece's mirror image is a different piece.
 *
 * On the two photos of the room taken so far, both from one corner with the whole floor in
 * them, this places 143 and 98 tiles and reads every piece within about twenty cells of the
 * camera; past that, a tile is a few pixels high and is lost. The phone is meant to be closer.
 */
class FloorReader(private val log: (String) -> Unit = {}) {

    /** A floor as read off one photo. */
    class Read(
        val width: Int,
        val height: Int,
        /** Row by row; null for a cell that is dark, or could not be read. */
        val colours: Array<FloorColour?>,
        /** Where each cell's centre is in the photo, the cell at column c, row r being (c, r). */
        val imageFromGrid: Homography?,
        /** Tiles the grid was fitted to. */
        val tiles: Int,
        /** Why nothing was read, when nothing was. */
        val failure: String?,
        /** The grid position of the middle of the photo: what it was aimed at. */
        val aimCol: Double,
        val aimRow: Double,
    ) {
        fun colour(col: Int, row: Int): FloorColour? =
            if (col in 0 until width && row in 0 until height) colours[row * width + col] else null

        val litCount: Int get() = colours.count { it != null }

        companion object {
            fun failed(why: String) = Read(0, 0, emptyArray(), null, 0, why, 0.0, 0.0)
        }
    }

    private class Blob(
        val area: Int,
        /** Four corners, clockwise as the photo is seen, x then y. */
        val corners: DoubleArray,
        /** Where the diagonals cross: the tile's centre, exactly, in any perspective. */
        val cx: Double,
        val cy: Double,
    )

    private class Growth(val toCell: Homography, val blobs: IntArray, val cells: IntArray) {
        val size: Int get() = blobs.size
    }

    fun read(image: RgbImage): Read {
        val start = System.nanoTime()
        val w = image.width
        val h = image.height
        val n = w * h
        if (n == 0) return Read.failed("empty image")
        val brightest = ByteArray(n)
        val histogram = IntArray(256)
        val data = image.data
        for (i in 0 until n) {
            val r = data[i * 3].toInt() and 0xFF
            val g = data[i * 3 + 1].toInt() and 0xFF
            val b = data[i * 3 + 2].toInt() and 0xFF
            val m = maxOf(r, g, b)
            brightest[i] = m.toByte()
            histogram[m]++
        }
        val top = percentile(histogram, n, 0.995)
        val threshold = max(MIN_THRESHOLD, (top * THRESHOLD_FRACTION).roundToInt())

        val blobs = findBlobs(brightest, w, h, threshold)
        if (blobs.isEmpty()) return Read.failed("no lit tiles in the photo").also { log("jigsaw: no blobs, threshold $threshold") }

        val growth = bestGrowth(blobs) ?: return Read.failed("couldn't find the floor's grid")
            .also { log("jigsaw: ${blobs.size} blobs, no grid") }
        if (growth.size < MIN_TILES) {
            log("jigsaw: ${blobs.size} blobs, best grid only ${growth.size}")
            return Read.failed("too few tiles lined up into a grid")
        }

        val read = readCells(image, brightest, threshold, growth)
        log(
            String.format(
                Locale.US,
                "jigsaw: threshold %d, %d blobs, %d placed, %dx%d grid, %d lit, %d ms",
                threshold, blobs.size, growth.size, read.width, read.height, read.litCount,
                (System.nanoTime() - start) / 1_000_000,
            ),
        )
        return read
    }

    // --- 1. The lit tiles -------------------------------------------------------

    private fun findBlobs(brightest: ByteArray, w: Int, h: Int, threshold: Int): List<Blob> {
        val n = w * h
        // 0 dark, -1 lit and not yet visited, otherwise visited.
        val label = IntArray(n)
        for (i in 0 until n) if ((brightest[i].toInt() and 0xFF) > threshold) label[i] = -1
        // Each patch's pixels, in the order the fill found them; the array is the fill's queue too.
        val order = IntArray(n)
        val minArea = max(12, n / 120_000)
        val maxArea = n / 20
        val blobs = ArrayList<Blob>()
        var next = 0
        var id = 0
        for (seed in 0 until n) {
            if (label[seed] != -1) continue
            id++
            val first = next
            label[seed] = id
            order[next++] = seed
            var head = first
            while (head < next) {
                val p = order[head++]
                val x = p % w
                val y = p / w
                if (x > 0 && label[p - 1] == -1) { label[p - 1] = id; order[next++] = p - 1 }
                if (x < w - 1 && label[p + 1] == -1) { label[p + 1] = id; order[next++] = p + 1 }
                if (y > 0 && label[p - w] == -1) { label[p - w] = id; order[next++] = p - w }
                if (y < h - 1 && label[p + w] == -1) { label[p + w] = id; order[next++] = p + w }
            }
            val count = next - first
            if (count < minArea || count > maxArea) continue
            fitTile(order, first, next, w)?.let { blobs += it }
        }
        return blobs
    }

    /** The quadrilateral a patch of lit pixels fills, if it fills it as a tile would. */
    private fun fitTile(order: IntArray, from: Int, to: Int, w: Int): Blob? {
        val count = to - from
        var sx = 0.0
        var sy = 0.0
        for (k in from until to) {
            sx += order[k] % w
            sy += order[k] / w
        }
        val mx = sx / count
        val my = sy / count
        val a = farthest(order, from, to, w, mx, my)
        val ax = (a % w).toDouble()
        val ay = (a / w).toDouble()
        val c = farthest(order, from, to, w, ax, ay)
        val cx = (c % w).toDouble()
        val cy = (c / w).toDouble()
        // Farthest from the line A-C on each side.
        var bBest = 0.0
        var dBest = 0.0
        var b = a
        var d = a
        for (k in from until to) {
            val p = order[k]
            val cross = (cx - ax) * (p / w - ay) - (cy - ay) * (p % w - ax)
            if (cross > bBest) { bBest = cross; b = p }
            if (cross < dBest) { dBest = cross; d = p }
        }
        if (b == a || d == a) return null
        val xs = doubleArrayOf(ax, (b % w).toDouble(), cx, (d % w).toDouble())
        val ys = doubleArrayOf(ay, (b / w).toDouble(), cy, (d / w).toDouble())
        val area = 0.5 * abs((xs[2] - xs[0]) * (ys[3] - ys[1]) - (ys[2] - ys[0]) * (xs[3] - xs[1]))
        if (area < 1.0) return null
        val fill = count / area
        if (fill < MIN_FILL || fill > MAX_FILL) return null
        // Clockwise as seen, y being down: by angle round their middle.
        val qx = xs.average()
        val qy = ys.average()
        val sorted = (0 until 4).sortedBy { atan2(ys[it] - qy, xs[it] - qx) }
        val corners = DoubleArray(8)
        for ((i, k) in sorted.withIndex()) {
            corners[i * 2] = xs[k]
            corners[i * 2 + 1] = ys[k]
        }
        val centre = diagonalsCross(corners) ?: return null
        return Blob(count, corners, centre[0], centre[1])
    }

    private fun farthest(order: IntArray, from: Int, to: Int, w: Int, x: Double, y: Double): Int {
        var best = order[from]
        var bestD = -1.0
        for (k in from until to) {
            val p = order[k]
            val dx = p % w - x
            val dy = p / w - y
            val d = dx * dx + dy * dy
            if (d > bestD) { bestD = d; best = p }
        }
        return best
    }

    private fun diagonalsCross(q: DoubleArray): DoubleArray? {
        val d1x = q[4] - q[0]
        val d1y = q[5] - q[1]
        val d2x = q[6] - q[2]
        val d2y = q[7] - q[3]
        val det = d1x * -d2y - d1y * -d2x
        if (abs(det) < 1e-9) return null
        val ex = q[2] - q[0]
        val ey = q[3] - q[1]
        val t = (ex * -d2y - ey * -d2x) / det
        return doubleArrayOf(q[0] + t * d1x, q[1] + t * d1y)
    }

    // --- 2. One tile, then its neighbours ---------------------------------------

    private fun bestGrowth(blobs: List<Blob>): Growth? {
        // Likeliest first tiles: those with the most others close by, the larger first.
        val neighbours = IntArray(blobs.size) { i ->
            val reach = 2.5 * kotlin.math.sqrt(blobs[i].area.toDouble())
            blobs.indices.count { j -> j != i && hypot(blobs[j].cx - blobs[i].cx, blobs[j].cy - blobs[i].cy) < reach }
        }
        val seeds = blobs.indices
            .filter { neighbours[it] > 0 }
            .sortedWith(compareByDescending<Int> { minOf(neighbours[it], 6) }.thenByDescending { blobs[it].area })
            .take(MAX_SEEDS)
        var best: Growth? = null
        for (seed in seeds) {
            val g = grow(blobs, seed) ?: continue
            if (best == null || g.size > best.size) best = g
            if (g.size >= blobs.size * 0.9) break
        }
        return best
    }

    private fun grow(blobs: List<Blob>, seed: Int): Growth? {
        val s = blobs[seed]
        val h0 = Homography.fit(s.corners, UNIT_SQUARE, 4) ?: return null
        // How far apart tiles are, in tile widths, from the seed's nearest straight neighbours.
        val p = DoubleArray(2)
        val gaps = ArrayList<Double>()
        for ((k, b) in blobs.withIndex()) {
            if (k == seed) continue
            h0.map(b.cx, b.cy, p)
            val dx = abs(p[0] - 0.5)
            val dy = abs(p[1] - 0.5)
            val long = max(dx, dy)
            val short = minOf(dx, dy)
            if (long > 0.9 && long < 2.2 && short < 0.3 * long) gaps += long
        }
        if (gaps.isEmpty()) return null
        gaps.sort()
        val pitch = gaps[gaps.size / 2]
        var toCell = Homography(doubleArrayOf(1 / pitch, 0.0, -0.5 / pitch, 0.0, 1 / pitch, -0.5 / pitch, 0.0, 0.0, 1.0)) * h0
        val half = 0.5 / pitch
        // The seed's corners stay in every fit: they hold the grid's shape while few tiles are placed.
        val seedCells = doubleArrayOf(-half, -half, half, -half, half, half, -half, half)

        val placed = BooleanArray(blobs.size)
        val cellOf = IntArray(blobs.size * 2)
        val taken = HashSet<Long>()
        val order = ArrayList<Int>()
        placed[seed] = true
        taken += key(0, 0)
        order += seed

        class Candidate(val r: Double, val blob: Int, val i: Int, val j: Int)

        val corner = DoubleArray(2)
        val q = DoubleArray(8)
        for ((reach, tolerance) in STAGES) {
            for (round in 0 until MAX_ROUNDS) {
                val back = Homography.invert(toCell) ?: return null
                val candidates = ArrayList<Candidate>()
                for ((k, b) in blobs.withIndex()) {
                    if (placed[k]) continue
                    toCell.map(b.cx, b.cy, p)
                    val i = p[0].roundToInt()
                    val j = p[1].roundToInt()
                    val r = hypot(p[0] - i, p[1] - j)
                    if (r > tolerance) continue
                    var near = Int.MAX_VALUE
                    for (o in order) near = minOf(near, max(abs(cellOf[o * 2] - i), abs(cellOf[o * 2 + 1] - j)))
                    if (near > reach) continue
                    // The size a tile at that cell would be, against this one.
                    for (c in 0 until 4) {
                        back.map(i + CORNER_X[c], j + CORNER_Y[c], corner)
                        q[c * 2] = corner[0]
                        q[c * 2 + 1] = corner[1]
                    }
                    val cellArea = 0.5 * abs((q[4] - q[0]) * (q[7] - q[3]) - (q[5] - q[1]) * (q[6] - q[2]))
                    val ratio = b.area / max(cellArea, 1e-6)
                    if (ratio < MIN_AREA_RATIO || ratio > MAX_AREA_RATIO) continue
                    candidates += Candidate(r, k, i, j)
                }
                var changed = false
                for (c in candidates.sortedBy { it.r }) {
                    if (!taken.add(key(c.i, c.j))) continue
                    placed[c.blob] = true
                    cellOf[c.blob * 2] = c.i
                    cellOf[c.blob * 2 + 1] = c.j
                    order += c.blob
                    changed = true
                }
                if (!changed) break
                if (order.size >= 4) {
                    val count = order.size + 4
                    val src = DoubleArray(count * 2)
                    val dst = DoubleArray(count * 2)
                    for ((m, o) in order.withIndex()) {
                        src[m * 2] = blobs[o].cx
                        src[m * 2 + 1] = blobs[o].cy
                        dst[m * 2] = cellOf[o * 2].toDouble()
                        dst[m * 2 + 1] = cellOf[o * 2 + 1].toDouble()
                    }
                    System.arraycopy(s.corners, 0, src, order.size * 2, 8)
                    System.arraycopy(seedCells, 0, dst, order.size * 2, 8)
                    Homography.fit(src, dst, count)?.let { toCell = it }
                }
            }
        }
        val cells = IntArray(order.size * 2)
        for ((m, o) in order.withIndex()) {
            cells[m * 2] = cellOf[o * 2]
            cells[m * 2 + 1] = cellOf[o * 2 + 1]
        }
        return Growth(toCell, order.toIntArray(), cells)
    }

    // --- 3. Every cell, read -----------------------------------------------------

    private fun readCells(image: RgbImage, brightest: ByteArray, threshold: Int, growth: Growth): Read {
        val w = image.width
        val h = image.height
        var minI = Int.MAX_VALUE
        var maxI = Int.MIN_VALUE
        var minJ = Int.MAX_VALUE
        var maxJ = Int.MIN_VALUE
        val placed = HashSet<Long>()
        for (m in 0 until growth.size) {
            val i = growth.cells[m * 2]
            val j = growth.cells[m * 2 + 1]
            minI = minOf(minI, i); maxI = max(maxI, i)
            minJ = minOf(minJ, j); maxJ = max(maxJ, j)
            placed += key(i, j)
        }
        val toCell = growth.toCell
        val toImage = Homography.invert(toCell) ?: return Read.failed("couldn't find the floor's grid")

        // 4. Which way up: the quarter turn that sends "down the photo" down the grid.
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        toCell.map(w / 2.0, h / 2.0, a)
        toCell.map(w / 2.0, h / 2.0 + h * 0.05, b)
        val du = b[0] - a[0]
        val dv = b[1] - a[1]
        val turn = listOf(dv, du, -dv, -du).withIndex().maxBy { it.value }.index
        val rotate = TURNS[turn]
        val spanI = maxI - minI + 1
        val spanJ = maxJ - minJ + 1
        val width = if (turn % 2 == 0) spanI else spanJ
        val height = if (turn % 2 == 0) spanJ else spanI
        // Rotated, the span's corner nearest the origin.
        val rc = DoubleArray(2)
        var originC = Double.MAX_VALUE
        var originR = Double.MAX_VALUE
        for ((ci, cj) in listOf(minI to minJ, maxI to minJ, minI to maxJ, maxI to maxJ)) {
            rotate.map(ci.toDouble(), cj.toDouble(), rc)
            originC = minOf(originC, rc[0])
            originR = minOf(originR, rc[1])
        }
        val gridFromCell = Homography(doubleArrayOf(1.0, 0.0, -originC, 0.0, 1.0, -originR, 0.0, 0.0, 1.0)) * rotate
        val gridFromImage = gridFromCell * toCell

        val colours = arrayOfNulls<FloorColour>(width * height)
        val corner = DoubleArray(2)
        val q = DoubleArray(8)
        val cell = DoubleArray(2)
        val data = image.data
        for (j in minJ..maxJ) {
            for (i in minI..maxI) {
                for (c in 0 until 4) {
                    toImage.map(i + CORNER_X[c] * SAMPLE, j + CORNER_Y[c] * SAMPLE, corner)
                    q[c * 2] = corner[0]
                    q[c * 2 + 1] = corner[1]
                }
                val x0 = minOf(q[0], q[2], q[4], q[6]).toInt()
                val x1 = max(max(q[0], q[2]), max(q[4], q[6])).toInt() + 1
                val y0 = minOf(q[1], q[3], q[5], q[7]).toInt()
                val y1 = max(max(q[1], q[3]), max(q[5], q[7])).toInt() + 1
                if (x0 < 0 || y0 < 0 || x1 >= w || y1 >= h) continue
                val area = 0.5 * abs((q[4] - q[0]) * (q[7] - q[3]) - (q[5] - q[1]) * (q[6] - q[2]))
                if (area < MIN_CELL_PIXELS) continue
                var inside = 0
                var lit = 0
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        toCell.map(x.toDouble(), y.toDouble(), cell)
                        if (abs(cell[0] - i) > SAMPLE || abs(cell[1] - j) > SAMPLE) continue
                        inside++
                        val p = y * w + x
                        if ((brightest[p].toInt() and 0xFF) <= threshold) continue
                        lit++
                        sr += data[p * 3].toInt() and 0xFF
                        sg += data[p * 3 + 1].toInt() and 0xFF
                        sb += data[p * 3 + 2].toInt() and 0xFF
                    }
                }
                if (inside == 0 || lit == 0) continue
                if (lit * 2 <= inside && key(i, j) !in placed) continue
                val colour = FloorColour.classify((sr / lit).toInt(), (sg / lit).toInt(), (sb / lit).toInt())
                rotate.map(i.toDouble(), j.toDouble(), rc)
                val col = (rc[0] - originC).roundToInt()
                val row = (rc[1] - originR).roundToInt()
                colours[row * width + col] = colour
            }
        }
        val aim = DoubleArray(2)
        gridFromImage.map(w / 2.0, h / 2.0, aim)
        return Read(
            width = width,
            height = height,
            colours = colours,
            imageFromGrid = Homography.invert(gridFromImage),
            tiles = growth.size,
            failure = null,
            aimCol = aim[0],
            aimRow = aim[1],
        )
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    private fun percentile(histogram: IntArray, n: Int, fraction: Double): Int {
        val target = (n * fraction).toLong()
        var seen = 0L
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen >= target) return v
        }
        return 255
    }

    companion object {
        /** Lit is above this fraction of the photo's near-brightest, but never below [MIN_THRESHOLD]. */
        const val THRESHOLD_FRACTION = 0.72
        const val MIN_THRESHOLD = 110

        /** A tile's patch fills its fitted quadrilateral about this well; rounded corners push it over one. */
        const val MIN_FILL = 0.75
        const val MAX_FILL = 1.35

        /** A tile is about 0.55 of its cell by area; glow makes it more, a far tile less. */
        const val MIN_AREA_RATIO = 0.25
        const val MAX_AREA_RATIO = 1.1

        const val MAX_SEEDS = 40
        const val MAX_ROUNDS = 60
        const val MIN_TILES = 4

        /** Half the side of the square read in each cell, in cells: the middle half of the cell. */
        const val SAMPLE = 0.25

        /** A cell smaller than this in the photo is too far off to read. */
        const val MIN_CELL_PIXELS = 12.0

        /** How far a tile may be from those placed (in cells), and how close to a cell centre, as the grid grows. */
        private val STAGES = listOf(2 to 0.25, 3 to 0.22, 4 to 0.2, 6 to 0.18, Int.MAX_VALUE to 0.16)

        private val UNIT_SQUARE = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        private val CORNER_X = doubleArrayOf(-0.5, 0.5, 0.5, -0.5)
        private val CORNER_Y = doubleArrayOf(-0.5, -0.5, 0.5, 0.5)

        /** The four quarter turns, (u, v) to (u, v), (-v, u), (-u, -v) and (v, -u): never a mirror. */
        private val TURNS = listOf(
            Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
            Homography(doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)),
            Homography(doubleArrayOf(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0)),
            Homography(doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0)),
        )
    }
}
