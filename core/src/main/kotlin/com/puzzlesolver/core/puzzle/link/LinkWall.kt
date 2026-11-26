package com.puzzlesolver.core.puzzle.link

import kotlin.math.roundToInt

/**
 * One 3x3 panel on the wall, as read.
 *
 * [col] and [row] are where its top-left button is, counted in buttons from the leftmost
 * panel's left column and the top band's top row. Distances on the wall are in buttons
 * too, which is all the path needs: the room's button pitch is the same everywhere.
 */
class LinkPanel(
    val col: Int,
    val row: Int,
    /** The nine colours, row by row from the top left, as [LinkColour]s. */
    val cells: IntArray,
    /** How sure the read was of each cell, 0..1. 1 for one set by hand. */
    val confidence: FloatArray = FloatArray(CELLS) { 1f },
    /** Frames the scan read it in. Zero for a panel typed in. */
    val looks: Int = 0,
) {
    init {
        require(cells.size == CELLS && confidence.size == CELLS)
    }

    /** The middle button, which is where the panel is on the wall for the path. */
    val centreX: Float get() = col + 1f
    val centreY: Float get() = row + 1f

    val pattern: String get() = String(CharArray(CELLS) { LinkColour.letter(cells[it]) })

    val unknownCount: Int get() = cells.count { it == LinkColour.UNKNOWN }

    companion object {
        const val SIZE = 3
        const val CELLS = SIZE * SIZE
    }
}

/**
 * A Link wall as read: every lit panel, wherever it is.
 *
 * Nothing here assumes how many panels there are or where. The practice game fills two
 * bands of 14 or 16 slots, one button of gutter between neighbours, but the room's easier
 * levels light 8 or 12 pairs, and a level that leaves slots dark is read the same way: a
 * panel is wherever nine lit buttons stand in a square.
 *
 * Panels are kept sorted left to right, then top to bottom, and moved so the leftmost
 * column and the topmost row are 0. Immutable, like the mines board: a correction is a new
 * wall, so a path planned for the old one can never be drawn on the new one.
 */
class LinkWall(panels: List<LinkPanel>, val warnings: List<String> = emptyList()) {

    val panels: List<LinkPanel>

    init {
        val minCol = panels.minOfOrNull { it.col } ?: 0
        val minRow = panels.minOfOrNull { it.row } ?: 0
        this.panels = panels
            .map { if (minCol == 0 && minRow == 0) it else LinkPanel(it.col - minCol, it.row - minRow, it.cells, it.confidence, it.looks) }
            .sortedWith(compareBy({ it.col }, { it.row }))
    }

    val size: Int get() = panels.size

    /** Buttons across and down, from the leftmost panel's left edge to the rightmost's right. */
    val cols: Int get() = (panels.maxOfOrNull { it.col } ?: -LinkPanel.SIZE) + LinkPanel.SIZE
    val rows: Int get() = (panels.maxOfOrNull { it.row } ?: -LinkPanel.SIZE) + LinkPanel.SIZE

    val unknownCount: Int get() = panels.sumOf { it.unknownCount }

    /**
     * Buttons from one slot to the next, measured: the commonest step between neighbouring
     * columns of panels. Four on the practice game -- three buttons and a gutter -- and
     * taken from the wall rather than assumed, so a gap where a slot is dark counts as the
     * slots it spans.
     */
    val slotSpacing: Int by lazy {
        val starts = columnStarts()
        if (starts.size < 2) return@lazy DEFAULT_SPACING
        val steps = starts.zipWithNext { a, b -> b - a }.filter { it >= LinkPanel.SIZE }
        if (steps.isEmpty()) return@lazy DEFAULT_SPACING
        val counts = steps.groupingBy { it }.eachCount()
        val top = counts.values.max()
        counts.filterValues { it == top }.keys.min()
    }

    /** The bands, top to bottom, as the row each starts at. */
    val bandRows: List<Int> by lazy { clusterStarts(panels.map { it.row }) }

    /** 1-based slot of panel [i], counted from the leftmost panel's. */
    fun slotOf(i: Int): Int = ((panels[i].col.toFloat()) / slotSpacing).roundToInt() + 1

    /** 0-based band of panel [i], from the top. */
    fun bandOf(i: Int): Int {
        val r = panels[i].row
        return bandRows.indices.minByOrNull { kotlin.math.abs(bandRows[it] - r) } ?: 0
    }

    /** How many slots the wall spans, gaps included. */
    val slotCount: Int get() = if (panels.isEmpty()) 0 else panels.indices.maxOf { slotOf(it) }

    /**
     * The name the practice game gives a panel with its "E" labels on: T for the top band
     * and B for the bottom, and the slot from the left, so "T3" or "B12". A wall of more
     * than two bands -- which no level has -- names them A, B, C... instead.
     */
    fun label(i: Int): String {
        val band = bandOf(i)
        val prefix = if (bandRows.size <= 2) (if (band == 0) "T" else "B") else ('A' + band).toString()
        return prefix + slotOf(i)
    }

    /** Index of the panel at ([col], [row]) top-left, or -1. */
    fun indexAt(col: Int, row: Int): Int = panels.indexOfFirst { it.col == col && it.row == row }

    /** The same wall with one cell of one panel set by hand. */
    fun withCell(panel: Int, cell: Int, colour: Int): LinkWall {
        val p = panels[panel]
        val cells = p.cells.copyOf().also { it[cell] = colour }
        val conf = p.confidence.copyOf().also { it[cell] = 1f }
        val out = panels.toMutableList()
        out[panel] = LinkPanel(p.col, p.row, cells, conf, p.looks)
        return LinkWall(out, warnings)
    }

    fun withWarnings(warnings: List<String>): LinkWall = LinkWall(panels, warnings)

    /**
     * The wall drawn as text: a letter for every lit button, '.' for every dark one, a row
     * of text for each row of buttons. [parse] reads it back, so it is both what a scan
     * files in `board.txt` and what a board is typed in as.
     */
    fun describe(): String = buildString {
        val grid = Array(rows) { CharArray(cols) { '.' } }
        for (p in panels) {
            for (k in 0 until LinkPanel.CELLS) {
                grid[p.row + k / LinkPanel.SIZE][p.col + k % LinkPanel.SIZE] = LinkColour.letter(p.cells[k])
            }
        }
        for (line in grid) appendLine(String(line))
    }

    override fun toString(): String = describe()

    private fun columnStarts(): List<Int> = clusterStarts(panels.map { it.col })

    companion object {
        const val DEFAULT_SPACING = 4

        /** Distinct values, with any within two of the last kept folded into it. */
        private fun clusterStarts(values: List<Int>): List<Int> {
            val out = ArrayList<Int>()
            for (v in values.distinct().sorted()) {
                if (out.isEmpty() || v - out.last() > 2) out.add(v)
            }
            return out
        }

        /**
         * Reads a wall drawn as [describe] draws it. Rows may be separated by newlines or
         * '/', and blank lines and surrounding spaces are ignored. Every lit button has to
         * belong to a whole 3x3 panel.
         */
        fun parse(text: String): LinkWall {
            val lines = text.replace('/', '\n').lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(lines.isNotEmpty()) { "no rows" }
            val width = lines.maxOf { it.length }
            val grid = lines.map { it.padEnd(width, '.') }
            fun lit(r: Int, c: Int) = r in grid.indices && c in 0 until width && grid[r][c] != '.' && grid[r][c] != ' '
            val used = Array(grid.size) { BooleanArray(width) }
            val panels = ArrayList<LinkPanel>()
            for (r in grid.indices) {
                for (c in 0 until width) {
                    if (!lit(r, c) || used[r][c]) continue
                    val cells = IntArray(LinkPanel.CELLS)
                    for (k in 0 until LinkPanel.CELLS) {
                        val rr = r + k / LinkPanel.SIZE
                        val cc = c + k % LinkPanel.SIZE
                        require(lit(rr, cc) && !used[rr][cc]) { "lit button at row ${r + 1}, column ${c + 1} is not the corner of a whole 3x3 panel" }
                        val ch = grid[rr][cc]
                        require(ch == '?' || LinkColour.fromLetter(ch) != LinkColour.UNKNOWN) { "'$ch' is not a colour (R Y G B P or ?)" }
                        cells[k] = LinkColour.fromLetter(ch)
                        used[rr][cc] = true
                    }
                    panels.add(LinkPanel(c, r, cells))
                }
            }
            return LinkWall(panels)
        }
    }
}
