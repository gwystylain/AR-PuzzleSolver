package com.puzzlesolver.core.puzzle.jigsaw

import kotlin.math.abs

/**
 * Cuts the part a photo was taken for out of a floor read off it: the shapes inside the
 * green box, or the pieces outside it.
 *
 * Green is only ever the box's frame, never a piece or a shape, so it is used to find the
 * box and then dropped. The frame is a rectangle two tiles thick, so whatever part of it a
 * photo takes in, its bounding rectangle is inside the box:
 *
 * - **A corner or more of the frame** -- an L, a U, the whole ring -- bounds the box on two
 *   sides at least, and every cell strictly inside its bounding rectangle is in the box. For
 *   the pieces, only the side of the box the photo was aimed at is kept: a photo of one end
 *   taken from its far wall can take in the other end beyond the box, and those pieces are
 *   the other photo's -- counted twice, one piece could be used twice.
 * - **One straight side only** says where the box's edge is but not which side the box is on.
 *   The side the photo was aimed at is taken as the one wanted -- the box for the box's photo,
 *   the pieces for a piece photo -- and if the photo was aimed at the band itself, the side
 *   with more lit on it for the box, fewer for the pieces.
 * - **No green at all** and everything lit is taken.
 *
 * What is kept is trimmed to its bounding rectangle and written as rows of colour codes, the
 * grid the lobby shares; [Region.dropped] is what was left out, so the phone can show it.
 */
object FloorRegions {

    class Region(
        /** Top to bottom, every row the same length, each cell a colour code or '.'; empty when nothing was kept. */
        val rows: List<String>,
        /** Where [rows]' top-left cell is in the read grid. */
        val originCol: Int,
        val originRow: Int,
        /** Lit cells left out, in the read grid: the frame, and what was on the wrong side of it. */
        val dropped: List<Pair<Int, Int>>,
        /** Said to the player about how the photo was cut, when it is worth saying. */
        val note: String?,
    ) {
        val cells: String get() = rows.joinToString("/")
    }

    /** The shapes inside the box. */
    fun box(read: FloorReader.Read): Region = cut(read, wantBox = true)

    /** The pieces outside the box. */
    fun pieces(read: FloorReader.Read): Region = cut(read, wantBox = false)

    private fun cut(read: FloorReader.Read, wantBox: Boolean): Region {
        val w = read.width
        val h = read.height
        val lit = (0 until w * h).filter { read.colours[it] != null && read.colours[it] != FloorColour.GREEN }
        val green = (0 until w * h).filter { read.colours[it] == FloorColour.GREEN }
        val frame = largestComponent(green.toSet(), w)
        var note: String? = null
        val keep: (Int) -> Boolean
        if (frame.size < MIN_FRAME) {
            keep = { true }
            if (wantBox) note = "No green frame in the photo, so everything lit is taken as the box's shapes."
        } else {
            val x0 = frame.minOf { it % w }
            val x1 = frame.maxOf { it % w }
            val y0 = frame.minOf { it / w }
            val y1 = frame.maxOf { it / w }
            if (x1 - x0 + 1 >= MIN_CORNER && y1 - y0 + 1 >= MIN_CORNER) {
                val inside = { i: Int -> i % w in (x0 + 1) until x1 && i / w in (y0 + 1) until y1 }
                val col = read.aimCol
                val row = read.aimRow
                keep = when {
                    wantBox -> inside
                    row > y1 + 0.5 -> { i -> i / w > y1 }
                    row < y0 - 0.5 -> { i -> i / w < y0 }
                    col > x1 + 0.5 -> { i -> i % w > x1 }
                    col < x0 - 0.5 -> { i -> i % w < x0 }
                    // Aimed at the box itself: everything outside it.
                    else -> { i -> !inside(i) }
                }
            } else {
                // One straight side of the frame: which side of it is wanted?
                val across = x1 - x0 >= y1 - y0
                val position: (Int) -> Int = if (across) { i -> i / w } else { i -> i % w }
                val low = if (across) y0 else x0
                val high = if (across) y1 else x1
                val aim = (if (across) read.aimRow else read.aimCol).toInt()
                val before = lit.count { position(it) < low }
                val after = lit.count { position(it) > high }
                val wantBefore = when {
                    aim < low -> true
                    aim > high -> false
                    wantBox -> before >= after
                    else -> before < after
                }
                keep = if (wantBefore) { i -> position(i) < low } else { i -> position(i) > high }
                note = if (wantBox) {
                    "Only one side of the green frame is in the photo; the shapes are taken from the side it was aimed at."
                } else {
                    "Only one side of the green frame is in the photo; the pieces are taken from the side it was aimed at."
                }
            }
        }
        val kept = lit.filter(keep)
        val dropped = (green + lit.filterNot(keep)).map { it % w to it / w }
        if (kept.isEmpty()) return Region(emptyList(), 0, 0, dropped, note)
        var cx0 = kept.minOf { it % w }
        var cx1 = kept.maxOf { it % w }
        var cy0 = kept.minOf { it / w }
        var cy1 = kept.maxOf { it / w }
        // Too big for the lobby: keep the part round where the photo was aimed.
        if (cx1 - cx0 + 1 > MAX_SIDE) {
            cx0 = (read.aimCol.toInt() - MAX_SIDE / 2).coerceIn(cx0, cx1 - MAX_SIDE + 1)
            cx1 = cx0 + MAX_SIDE - 1
        }
        if (cy1 - cy0 + 1 > MAX_SIDE) {
            cy0 = (read.aimRow.toInt() - MAX_SIDE / 2).coerceIn(cy0, cy1 - MAX_SIDE + 1)
            cy1 = cy0 + MAX_SIDE - 1
        }
        val keptSet = kept.toHashSet()
        val rows = (cy0..cy1).map { y ->
            buildString {
                for (x in cx0..cx1) {
                    val i = y * w + x
                    append(if (i in keptSet) read.colours[i]!!.code else OFF)
                }
            }
        }
        // The size limit can leave empty rows or columns at the edge; take them off too.
        val top = rows.indexOfFirst { r -> r.any { it != OFF } }
        if (top < 0) return Region(emptyList(), 0, 0, dropped, note)
        val bottom = rows.indexOfLast { r -> r.any { it != OFF } }
        val band = rows.subList(top, bottom + 1)
        val left = band.minOf { r -> r.indexOfFirst { it != OFF }.let { if (it < 0) Int.MAX_VALUE else it } }
        val right = band.maxOf { r -> r.indexOfLast { it != OFF } }
        return Region(band.map { it.substring(left, right + 1) }, cx0 + left, cy0 + top, dropped, note)
    }

    private fun largestComponent(cells: Set<Int>, w: Int): List<Int> {
        val seen = HashSet<Int>()
        var best = emptyList<Int>()
        for (start in cells) {
            if (!seen.add(start)) continue
            val component = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue += start
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                component += c
                for (n in intArrayOf(c - 1, c + 1, c - w, c + w)) {
                    if (n !in cells || n in seen) continue
                    // Left and right neighbours must be on the same row.
                    if (abs(n - c) == 1 && n / w != c / w) continue
                    seen += n
                    queue += n
                }
            }
            if (component.size > best.size) best = component
        }
        return best
    }

    private const val OFF = '.'

    /** The lobby's limit on a region's rows and columns (`JigsawBoard.MAX_SIDE`). */
    const val MAX_SIDE = 24

    /** Fewer green cells than this is not the frame. */
    private const val MIN_FRAME = 4

    /** A frame this far both ways is a corner or more; less one way is a single side. */
    private const val MIN_CORNER = 4
}
