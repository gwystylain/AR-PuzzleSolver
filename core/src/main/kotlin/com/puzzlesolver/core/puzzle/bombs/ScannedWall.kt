package com.puzzlesolver.core.puzzle.bombs

/**
 * The mines wall as one pan read it: every button's colour, indexed from the green one.
 *
 * Column 0 is the green button's column and row 0 the top row, so a cell here is the
 * same button on the wall for as long as the room keeps its layout -- which is what lets
 * the answer be drawn in the app rather than on the wall. The user finds "column 23,
 * row 4" by counting from the green, and the app draws the wall in its two-column panels
 * to make that count easy.
 *
 * Immutable: a correction made by tapping a cell is a new wall, so a plan computed for
 * the old one can never be shown against the new one by accident.
 */
class ScannedWall(
    val cols: Int,
    val rows: Int,
    /** [Button] constants, row-major. [Button.UNSEEN] where the scan never looked. */
    private val kinds: ByteArray,
    /** How sure the scan was of each cell, 0..1. 1 for a cell the user set by hand. */
    private val confidence: FloatArray,
    /** Whether the scan saw the far end of the wall, rather than being stopped short. */
    val endSeen: Boolean,
    /** Anything about the scan the user should know before trusting it. */
    val warnings: List<String>,
) {
    init {
        require(kinds.size == cols * rows && confidence.size == cols * rows)
    }

    fun kindAt(col: Int, row: Int): Int = kinds[row * cols + col].toInt()

    fun confidenceAt(col: Int, row: Int): Float = confidence[row * cols + col]

    /** Cells the scan could not name, or never saw. Each one is planned around. */
    val unknownCount: Int get() = kinds.count { isUnknown(it.toInt()) }

    fun countOf(button: Int): Int = kinds.count { it.toInt() == button }

    /** The same wall with one cell set by hand. */
    fun withKind(col: Int, row: Int, button: Int): ScannedWall {
        val k = kinds.copyOf()
        val c = confidence.copyOf()
        k[row * cols + col] = button.toByte()
        c[row * cols + col] = 1f
        return ScannedWall(cols, rows, k, c, endSeen, warnings)
    }

    /**
     * The board the planner sees.
     *
     * A cell nobody could read is planned as a **red hazard**. It might be anything,
     * and red is the one thing a ray must never land on, so planning as though it were
     * red is the only assumption that cannot lose the room. The cost is that a white
     * read as unknown is left standing -- which the board on screen shows, as a "?" for
     * the user to look at and tap.
     */
    fun toBombBoard(rules: BombRules = BombRules()): BombBoard {
        val k = ByteArray(kinds.size) {
            val v = kinds[it].toInt()
            (if (isUnknown(v)) Button.HAZARD else v).toByte()
        }
        return BombBoard.of(cols, rows, k, rules)
    }

    /**
     * Plans the cheapest way to clear this wall.
     *
     * The same planner and the same budget as [BombSolver]: there is no mine count to
     * ask about, the room always admits a solution, and the remaining hit points bound
     * the search without excluding one.
     */
    fun plan(deadlineNanos: Long = Long.MAX_VALUE, maxRounds: Int = 3): PlanOutcome {
        val board = toBombBoard()
        return BombPlanner(budget = board.remainingHitPoints.coerceAtLeast(1), maxRounds = maxRounds)
            .plan(board, deadlineNanos)
    }

    /** The compact text form [BombBoard.parse] reads, for logs and fixtures. */
    fun describe(): String = buildString {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                append(
                    when (kindAt(c, r)) {
                        Button.EMPTY -> '.'
                        Button.GREEN -> 'G'
                        Button.WHITE -> 'W'
                        Button.BLUE -> 'B'
                        Button.MINE -> 'X'
                        Button.HAZARD -> 'R'
                        Button.OPAQUE -> '#'
                        else -> '?'
                    }
                )
            }
            append('\n')
        }
    }

    companion object {
        fun isUnknown(button: Int): Boolean = button == Button.UNSEEN || button == Button.OPAQUE

        /** A wall from [BombBoard.parse]'s text form, for tests. */
        fun parse(text: String): ScannedWall {
            val board = BombBoard.parse(text)
            val kinds = ByteArray(board.cellCount) { board.kindAt(it).toByte() }
            return ScannedWall(board.cols, board.rows, kinds, FloatArray(board.cellCount) { 1f }, true, emptyList())
        }
    }
}
