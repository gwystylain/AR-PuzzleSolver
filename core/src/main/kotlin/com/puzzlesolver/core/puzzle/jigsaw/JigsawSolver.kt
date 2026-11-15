package com.puzzlesolver.core.puzzle.jigsaw

/**
 * Which pieces outside the box make the shapes inside it.
 *
 * Each colour is its own puzzle. Its **shape** is every cell of that colour in the box --
 * more than one patch is fine, they are filled together. Its **pieces** are the patches of
 * that colour outside, each patch one piece: 4-connected cells of one colour in one photo.
 * The answer is a set of pieces that covers the shape exactly, every cell once and nothing
 * over, each piece used at most once and **turned but never mirrored**: in this room a
 * piece's mirror image is a decoy. Pieces left over are decoys too.
 *
 * The search is the usual one for tiling: take the first cell of the shape not yet covered,
 * reading row by row, and try every unused piece in every turn that puts its own first cell
 * there. A branch is dropped as soon as no set of the unused pieces adds up to the cells
 * left. The shapes are a few dozen cells and the pieces a handful, so it is over in well
 * under a millisecond; [budget] only guards against a board read wildly wrong.
 *
 * Every distinct set of pieces that works is collected, up to [MAX_WAYS], because the phone
 * cannot tell which the room means if there is more than one -- two identical pieces, say --
 * and should say so rather than pick.
 *
 * Green is the box's frame and never a shape or a piece; it is ignored wherever it appears.
 */
object JigsawSolver {

    data class Cell(val col: Int, val row: Int)

    class Piece(
        /** Its index in [Solution.pieces]. */
        val id: Int,
        /** Which of the piece grids it is in, as passed to [solve]. */
        val side: Int,
        val colour: Char,
        /** Where it is in its grid, row by row. */
        val cells: List<Cell>,
    ) {
        val size: Int get() = cells.size
    }

    /** A piece where it goes in the box: turned [turns] quarter turns clockwise, onto [cells]. */
    class Placement(val piece: Piece, val turns: Int, val cells: List<Cell>)

    class Answer(
        val colour: Char,
        /** The shape in the box, row by row. */
        val target: List<Cell>,
        /** The pieces of this colour, used or not. */
        val pieces: List<Piece>,
        /** Every distinct set of pieces that makes the shape, at most [MAX_WAYS]; none when nothing does. */
        val ways: List<List<Placement>>,
        /** The search stopped at its budget: there may be ways it did not find. */
        val gaveUp: Boolean,
    ) {
        val solved: Boolean get() = ways.isNotEmpty()
    }

    class Solution(
        /** One per colour that has a shape in the box, in [FloorColour.PIECES] order. */
        val answers: List<Answer>,
        val pieces: List<Piece>,
    )

    const val MAX_WAYS = 6
    private const val BUDGET = 300_000
    private const val OFF = '.'
    private const val GREEN = 'G'

    /**
     * [box] is the box's grid and [sides] the piece grids, each as rows of colour codes and
     * '.', as the lobby has them.
     */
    fun solve(box: List<String>, sides: List<List<String>>, budget: Int = BUDGET): Solution {
        val pieces = ArrayList<Piece>()
        for ((side, rows) in sides.withIndex()) {
            for (patch in patches(rows)) pieces += Piece(pieces.size, side, patch.first, patch.second)
        }
        val width = box.maxOfOrNull { it.length } ?: 0
        val order = FloorColour.PIECES.map { it.code }
        val colours = box.flatMap { it.toList() }.filter { it != OFF && it != GREEN }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
        val answers = colours.map { colour ->
            val target = ArrayList<Cell>()
            for ((r, row) in box.withIndex()) for ((c, ch) in row.withIndex()) if (ch == colour) target += Cell(c, r)
            val mine = pieces.filter { it.colour == colour }
            Search(target, width, box.size, mine, budget).run(colour)
        }
        return Solution(answers, pieces)
    }

    /** Each 4-connected patch of one colour, green aside, with its cells row by row. */
    fun patches(rows: List<String>): List<Pair<Char, List<Cell>>> {
        val seen = HashSet<Cell>()
        val out = ArrayList<Pair<Char, List<Cell>>>()
        for ((r, row) in rows.withIndex()) {
            for ((c, ch) in row.withIndex()) {
                if (ch == OFF || ch == GREEN || !seen.add(Cell(c, r))) continue
                val patch = ArrayList<Cell>()
                val queue = ArrayDeque(listOf(Cell(c, r)))
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    patch += cell
                    for (n in listOf(Cell(cell.col + 1, cell.row), Cell(cell.col - 1, cell.row), Cell(cell.col, cell.row + 1), Cell(cell.col, cell.row - 1))) {
                        if (rows.getOrNull(n.row)?.getOrNull(n.col) == ch && seen.add(n)) queue += n
                    }
                }
                out += ch to patch.sortedWith(compareBy({ it.row }, { it.col }))
            }
        }
        return out
    }

    /**
     * A piece's distinct turns: each as its cells shifted to start at (0, 0) and sorted row by
     * row, with how many quarter turns clockwise it took. A square has one, a bar two.
     */
    fun turns(cells: List<Cell>): List<Pair<Int, List<Cell>>> {
        val out = ArrayList<Pair<Int, List<Cell>>>()
        var current = cells
        for (t in 0 until 4) {
            val shape = normalised(current)
            if (out.none { it.second == shape }) out += t to shape
            // A quarter turn clockwise on screen, rows running down: (c, r) to (-r, c).
            current = current.map { Cell(-it.row, it.col) }
        }
        return out
    }

    private fun normalised(cells: List<Cell>): List<Cell> {
        val c0 = cells.minOf { it.col }
        val r0 = cells.minOf { it.row }
        return cells.map { Cell(it.col - c0, it.row - r0) }.sortedWith(compareBy({ it.row }, { it.col }))
    }

    private class Search(
        val target: List<Cell>,
        val width: Int,
        val height: Int,
        pieces: List<Piece>,
        val budget: Int,
    ) {
        // Biggest first: they have the fewest places to go.
        val pieces = pieces.filter { it.size <= target.size }.sortedByDescending { it.size }
        val shapes = this.pieces.map { turns(it.cells) }
        val wanted = BooleanArray(width * height).also { a -> for (c in target) a[c.row * width + c.col] = true }
        val covered = BooleanArray(width * height)
        val used = BooleanArray(this.pieces.size)
        val placed = ArrayList<Placement>()
        val ways = LinkedHashMap<Set<Int>, List<Placement>>()
        var nodes = 0
        var gaveUp = false

        fun run(colour: Char): Answer {
            if (target.isNotEmpty() && pieces.isNotEmpty()) step(0, target.size)
            return Answer(colour, target, pieces.sortedBy { it.id }, ways.values.toList(), gaveUp)
        }

        private fun step(from: Int, left: Int) {
            if (ways.size >= MAX_WAYS || gaveUp) return
            if (++nodes > budget) {
                gaveUp = true
                return
            }
            if (left == 0) {
                val set = placed.map { it.piece.id }.toSet()
                if (set !in ways) ways[set] = placed.toList()
                return
            }
            if (!sumReachable(left)) return
            var first = from
            while (!wanted[first] || covered[first]) first++
            val fc = first % width
            val fr = first / width
            for ((k, piece) in pieces.withIndex()) {
                if (used[k] || piece.size > left) continue
                for ((turn, shape) in shapes[k]) {
                    // Its first cell, row by row, goes on the first cell left.
                    val dc = fc - shape[0].col
                    val dr = fr - shape[0].row
                    var fits = true
                    for (cell in shape) {
                        val c = cell.col + dc
                        val r = cell.row + dr
                        if (c !in 0 until width || r !in 0 until height) { fits = false; break }
                        val i = r * width + c
                        if (!wanted[i] || covered[i]) { fits = false; break }
                    }
                    if (!fits) continue
                    val cells = shape.map { Cell(it.col + dc, it.row + dr) }
                    for (cell in cells) covered[cell.row * width + cell.col] = true
                    used[k] = true
                    placed += Placement(piece, turn, cells)
                    step(first + 1, left - piece.size)
                    placed.removeAt(placed.size - 1)
                    used[k] = false
                    for (cell in cells) covered[cell.row * width + cell.col] = false
                    if (ways.size >= MAX_WAYS || gaveUp) return
                }
            }
        }

        /** Bits of the sums the unused pieces can make, reused at every step. */
        val sums = LongArray((target.size ushr 6) + 1)

        /** Whether some set of the unused pieces adds up to exactly [left] cells. */
        private fun sumReachable(left: Int): Boolean {
            val words = (left ushr 6) + 1
            sums.fill(0L, 0, words)
            sums[0] = 1L
            for ((k, piece) in pieces.withIndex()) {
                if (used[k]) continue
                // Every sum made so far, plus this piece: a shift and an or, a word at a time.
                val ws = piece.size ushr 6
                val bs = piece.size and 63
                for (i in words - 1 downTo ws) {
                    var v = sums[i - ws] shl bs
                    if (bs != 0 && i - ws - 1 >= 0) v = v or (sums[i - ws - 1] ushr (64 - bs))
                    sums[i] = sums[i] or v
                }
                if (sums[left ushr 6] and (1L shl (left and 63)) != 0L) return true
            }
            return sums[left ushr 6] and (1L shl (left and 63)) != 0L
        }
    }
}
