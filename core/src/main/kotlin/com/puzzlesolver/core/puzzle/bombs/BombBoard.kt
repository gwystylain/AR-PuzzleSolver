package com.puzzlesolver.core.puzzle.bombs

/**
 * The button grid, as the solver sees it.
 *
 * The room's board is a lattice of RGB buttons, roughly 7 rows by 100 columns. A
 * button's colour is its entire state -- there are no glyphs to classify here, which
 * is why this puzzle needs the canvas to carry chroma. See [BombAdapter].
 */
object Button {
    /** Not yet scanned. Blocks solving: an unseen button may be a target. */
    const val UNSEEN = 0

    /** Black / unlit. A mine can be placed here, and rays pass straight through. */
    const val EMPTY = 1

    /** The detonator. Rays pass through it; a mine cannot be placed on it. */
    const val GREEN = 2

    /** One hit to clear. */
    const val WHITE = 3

    /** Two hits to clear. */
    const val BLUE = 4

    /**
     * Magenta -- a mine the player has already placed. Distinct from [HAZARD]: the
     * room lights an armed mine red *and* blue, which is the only thing separating
     * "I put that there" from "do not touch that".
     */
    const val MINE = 5

    /**
     * A colour we scanned but could not name. Treated as an opaque, unclearable
     * blocker, and the solver refuses to answer while any remain: guessing here
     * would produce a confident wrong plan, which is the failure mode that matters.
     */
    const val OPAQUE = 6

    /**
     * Red -- a live hazard. A ray landing on one loses the game outright, so this is
     * not a button to be cleared or a wall to be worked around: it is a constraint no
     * plan may ever violate. It blocks rays like any other lit button, which is what
     * makes it dangerous -- anything with a clear line to it will hit it.
     */
    const val HAZARD = 7

    fun name(v: Int): String = when (v) {
        UNSEEN -> "unseen"
        EMPTY -> "black"
        GREEN -> "green"
        WHITE -> "white"
        BLUE -> "blue"
        MINE -> "magenta/mine"
        HAZARD -> "red/hazard"
        else -> "unknown"
    }
}

/**
 * The eight firing directions, ordered so that the opposite of `d` is `(d + 4) and 7`.
 *
 * That identity is not cosmetic. The annihilation rule is stated in terms of opposing
 * rays, and deriving the opposite by index arithmetic keeps it out of the simulator's
 * inner loop.
 */
object Dir {
    const val COUNT = 8

    /** E, NE, N, NW, W, SW, S, SE. */
    @JvmField
    val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)

    @JvmField
    val DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

    fun opposite(d: Int): Int = (d + 4) and 7

    fun name(d: Int): String = NAMES[d]

    private val NAMES = arrayOf("E", "NE", "N", "NW", "W", "SW", "S", "SE")
}

/**
 * The rules of the room, made explicit rather than hard-coded.
 *
 * Some were confirmed by watching the game; the rest are the safe reading of a detail
 * nobody has checked yet, and say so. They are configuration because a wrong
 * assumption baked into the simulator is invisible, whereas a wrong flag is one line
 * to flip and one test to re-run.
 */
data class BombRules(
    val whiteHitPoints: Int = 1,
    val blueHitPoints: Int = 2,

    /**
     * CONFIRMED. Two mines on a common line send rays toward each other; the rays
     * meet and vaporise, so neither reaches the far side. Mines therefore shadow one
     * another without ever acting as walls.
     */
    val opposingRaysAnnihilate: Boolean = true,

    /**
     * UNVERIFIED. Rays that cross at an angle rather than head-on -- an eastward ray
     * and a northward ray arriving at one cell on the same tick. Modelled as
     * independent, because "advance towards each other then collide" describes
     * head-on motion, and because assuming extra annihilation would make the solver
     * reject plans that in fact work.
     */
    val crossingRaysAnnihilate: Boolean = false,

    /**
     * UNVERIFIED. Whether a button destroyed partway through a detonation still
     * blocks rays arriving later in that same detonation. Defaults to true, the
     * conservative reading: assuming rays punch through a dying button would credit
     * a plan with damage it may never deal.
     */
    val destroyedButtonsBlockUntilRoundEnd: Boolean = true,

    /**
     * UNVERIFIED but near-certain. A blue button hit once stays damaged into the next
     * round. Were this false, blue could only ever be cleared by two hits inside one
     * detonation.
     */
    val damagePersistsBetweenRounds: Boolean = true,
) {
    /** Hit points a freshly-scanned button of this colour starts with. 0 = not a target. */
    fun hitPointsOf(button: Int): Int = when (button) {
        Button.WHITE -> whiteHitPoints
        Button.BLUE -> blueHitPoints
        else -> 0
    }
}

/**
 * An immutable snapshot of the board plus the damage taken so far.
 *
 * Immutable because the planner explores rounds by branching: a mutable board would
 * need an undo log threaded through every backtrack, and a 7 x 100 board is small
 * enough that copying one costs less than getting that undo log wrong.
 */
class BombBoard private constructor(
    @JvmField val cols: Int,
    @JvmField val rows: Int,
    /** Colour per cell, row-major, from [Button]. */
    @JvmField val kind: ByteArray,
    /** Remaining hit points per cell. 0 for anything that is not a live target. */
    @JvmField val hp: IntArray,
    val rules: BombRules,
) {
    val cellCount: Int get() = cols * rows

    fun index(col: Int, row: Int): Int = row * cols + col

    fun colOf(index: Int): Int = index % cols

    fun rowOf(index: Int): Int = index / cols

    fun kindAt(index: Int): Int = kind[index].toInt()

    fun inBounds(col: Int, row: Int): Boolean =
        col >= 0 && col < cols && row >= 0 && row < rows

    /** A mine goes on an unlit button. Green is a button too, but it is the trigger. */
    fun isPlaceable(index: Int): Boolean = kind[index].toInt() == Button.EMPTY

    /**
     * Rays pass through black and green and stop at everything else.
     *
     * A mine is *not* a wall: every mine on the board takes part in the detonation and
     * is consumed by it, and two mines on a line annihilate before either arrives.
     */
    fun blocksRays(index: Int): Boolean = when (kind[index].toInt()) {
        Button.EMPTY, Button.GREEN, Button.MINE, Button.UNSEEN -> false
        // A hazard never clears, so it blocks for good.
        Button.OPAQUE, Button.HAZARD -> true
        else -> rules.destroyedButtonsBlockUntilRoundEnd || hp[index] > 0
    }

    fun isHazard(index: Int): Boolean = kind[index].toInt() == Button.HAZARD

    fun hazards(): IntArray {
        var n = 0
        for (k in kind) if (k.toInt() == Button.HAZARD) n++
        val out = IntArray(n)
        var w = 0
        for (i in kind.indices) if (kind[i].toInt() == Button.HAZARD) out[w++] = i
        return out
    }

    /** True where a ray arriving would deal damage. */
    fun isLiveTarget(index: Int): Boolean = hp[index] > 0

    fun liveTargets(): IntArray {
        var n = 0
        for (h in hp) if (h > 0) n++
        val out = IntArray(n)
        var w = 0
        for (i in hp.indices) if (hp[i] > 0) out[w++] = i
        return out
    }

    val remainingHitPoints: Int get() = hp.sum()

    val isCleared: Boolean get() = hp.all { it == 0 }

    fun hasUnseen(): Boolean = kind.any { it.toInt() == Button.UNSEEN }

    fun hasOpaque(): Boolean = kind.any { it.toInt() == Button.OPAQUE }

    fun countOf(button: Int): Int = kind.count { it.toInt() == button }

    /** Cells already showing red: mines the player has placed but not yet fired. */
    fun placedMines(): IntArray {
        var n = 0
        for (k in kind) if (k.toInt() == Button.MINE) n++
        val out = IntArray(n)
        var w = 0
        for (i in kind.indices) if (kind[i].toInt() == Button.MINE) out[w++] = i
        return out
    }

    /**
     * The board as it stands after [damage] lands and the spent mines go dark.
     *
     * Buttons whose hit points reach zero become black, and that is the whole reason
     * several rounds can beat one detonation: a cleared button stops blocking and
     * opens a line that did not exist before.
     */
    fun afterDetonation(damage: IntArray, firedMines: IntArray): BombBoard {
        val newKind = kind.copyOf()
        val newHp = hp.copyOf()
        for (i in newHp.indices) {
            if (newHp[i] <= 0) continue
            val left = newHp[i] - damage[i]
            if (left <= 0) {
                newHp[i] = 0
                newKind[i] = Button.EMPTY.toByte()
            } else if (rules.damagePersistsBetweenRounds) {
                newHp[i] = left
                // A blue that has taken one hit shows as white: one hit left.
                if (left == 1) newKind[i] = Button.WHITE.toByte()
            }
        }
        for (m in firedMines) {
            if (newKind[m].toInt() == Button.MINE) newKind[m] = Button.EMPTY.toByte()
        }
        return BombBoard(cols, rows, newKind, newHp, rules)
    }

    /** The same board with [mines] armed, ready to hand to [Detonation]. */
    fun withMines(mines: IntArray): BombBoard {
        val newKind = kind.copyOf()
        for (m in mines) newKind[m] = Button.MINE.toByte()
        return BombBoard(cols, rows, newKind, hp.copyOf(), rules)
    }

    fun copy(): BombBoard = BombBoard(cols, rows, kind.copyOf(), hp.copyOf(), rules)

    override fun toString(): String = buildString {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                append(
                    when (kind[index(c, r)].toInt()) {
                        Button.UNSEEN -> '?'
                        Button.EMPTY -> '.'
                        Button.GREEN -> 'G'
                        Button.WHITE -> 'W'
                        Button.BLUE -> 'B'
                        Button.MINE -> 'X'
                        Button.HAZARD -> 'R'
                        else -> '#'
                    }
                )
            }
            append('\n')
        }
    }

    companion object {
        fun of(cols: Int, rows: Int, kinds: ByteArray, rules: BombRules = BombRules()): BombBoard {
            require(kinds.size == cols * rows) { "expected ${cols * rows} cells, got ${kinds.size}" }
            val hp = IntArray(cols * rows) { rules.hitPointsOf(kinds[it].toInt()) }
            return BombBoard(cols, rows, kinds.copyOf(), hp, rules)
        }

        /**
         * Parses the compact text form used by tests and replay fixtures:
         * `.` black, `G` green, `W` white, `B` blue, `X` a mine already placed,
         * `R` a red hazard, `#` an unrecognised colour, `?` unseen.
         */
        fun parse(text: String, rules: BombRules = BombRules()): BombBoard {
            val lines = text.trimIndent().lines().filter { it.isNotBlank() }
            val rows = lines.size
            val cols = lines[0].length
            require(lines.all { it.length == cols }) { "ragged board" }
            val kinds = ByteArray(cols * rows)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    kinds[r * cols + c] = when (lines[r][c]) {
                        '.' -> Button.EMPTY
                        'G' -> Button.GREEN
                        'W' -> Button.WHITE
                        'B' -> Button.BLUE
                        'X' -> Button.MINE
                        'R' -> Button.HAZARD
                        '#' -> Button.OPAQUE
                        '?' -> Button.UNSEEN
                        else -> throw IllegalArgumentException("bad board char '${lines[r][c]}'")
                    }.toByte()
                }
            }
            return of(cols, rows, kinds, rules)
        }
    }
}
