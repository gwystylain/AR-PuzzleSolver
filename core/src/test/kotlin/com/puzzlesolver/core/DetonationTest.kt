package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombRules
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mechanics of the room down one rule at a time.
 *
 * Every case here is a board whose *geometry* forces the answer -- a ray either
 * reaches a button or it provably cannot -- rather than a number someone tuned.
 *
 * Three of them carry most of the weight. `two mines either side of a blue` fixes the
 * order of damage and annihilation inside a tick. The even- and odd-distance pair fix
 * annihilation itself, and each is built so that the no-annihilation model would give
 * a visibly different number. Get any of the three backwards and the solver emits
 * plans that look reasonable on screen and fail on the wall.
 */
class DetonationTest {

    private val sim = Detonation()

    private fun fire(board: String, rules: BombRules = BombRules()): Pair<BombBoard, Detonation.Result> {
        val b = BombBoard.parse(board, rules)
        return b to sim.fire(b, tracePaths = true)
    }

    @Test
    fun `a lone mine on an empty board hits nothing`() {
        val (_, r) = fire(
            """
            .....
            ..X..
            .....
            """
        )
        assertEquals(0, r.totalDamage)
        assertEquals("every ray should leave the board", 8, r.flewOffBoard)
    }

    @Test
    fun `a mine hits the first button in each of the eight directions`() {
        val (b, r) = fire(
            """
            WWW
            WXW
            WWW
            """
        )
        assertEquals(8, r.totalDamage)
        for (i in 0 until b.cellCount) {
            if (i == b.index(1, 1)) continue
            assertEquals("cell $i", 1, r.damageAt(i))
        }
    }

    @Test
    fun `a ray stops at the first button and spares the one behind it`() {
        val (b, r) = fire("X.WW")
        assertEquals(1, r.damageAt(b.index(2, 0)))
        assertEquals("the second white is shadowed", 0, r.damageAt(b.index(3, 0)))
    }

    @Test
    fun `the green detonator does not stop a ray`() {
        val (b, r) = fire("X.G.W")
        assertEquals(1, r.damageAt(b.index(4, 0)))
    }

    @Test
    fun `an unrecognised colour blocks like any other button but takes no damage`() {
        val (b, r) = fire("X.#.W")
        assertEquals("nothing reaches past the unknown button", 0, r.damageAt(b.index(4, 0)))
        assertEquals(0, r.totalDamage)
    }

    @Test
    fun `two mines either side of a blue clear it in one detonation`() {
        // The ordering test. Both rays arrive at column 2 on the same tick. Were
        // annihilation resolved before damage they would cancel over the button and
        // deal nothing, making a flanked blue unclearable in a single round.
        val (b, r) = fire("X.B.X")
        assertEquals("both rays land", 2, r.damageAt(b.index(2, 0)))
        assertEquals(0, r.annihilated)
    }

    @Test
    fun `mines an even distance apart meet on a cell and vaporise`() {
        // Mines at columns 0 and 6, white at column 8. The right mine reaches the
        // white. The left mine must not: its eastward ray dies at column 3. Without
        // annihilation it would sail on through the spent mine and the white would
        // take two hits, so the two models give different numbers here.
        val (b, r) = fire("X.....X.W")
        assertEquals(1, r.damageAt(b.index(8, 0)))
        assertTrue("the facing pair should have died", r.annihilated >= 2)
    }

    @Test
    fun `mines an odd distance apart trade cells and still vaporise`() {
        // Distance 5, so the rays never share a cell -- they swap. Handling only the
        // same-cell case would let them tunnel through each other and the white at
        // column 7 would take two hits instead of one.
        val (b, r) = fire("X....X.W")
        assertEquals(1, r.damageAt(b.index(7, 0)))
        assertTrue("the facing pair should have died", r.annihilated >= 2)
    }

    @Test
    fun `annihilation applies on diagonals too`() {
        // Mines at (0,0) and (3,3) face each other down the main diagonal. Only the
        // near mine should reach the white in the corner; the far one is cancelled.
        val (b, r) = fire(
            """
            X....
            .....
            .....
            ...X.
            ....W
            """
        )
        assertEquals(1, r.damageAt(b.index(4, 4)))
        assertTrue(r.annihilated >= 2)
    }

    @Test
    fun `a button between two facing mines is hit from both sides`() {
        // The white stops both rays, so nothing survives into open space to collide.
        val (b, r) = fire("X..W..X")
        assertEquals(2, r.damageAt(b.index(3, 0)))
        assertEquals(0, r.annihilated)
    }

    @Test
    fun `a white button flanked at unequal range takes two hits`() {
        val (b, r) = fire("X.W...X")
        assertEquals(2, r.damageAt(b.index(2, 0)))
    }

    @Test
    fun `a mine already showing red on the board is fired by the green button`() {
        val b = BombBoard.parse(".X.W")
        val r = sim.fire(b, intArrayOf())
        assertEquals("we placed nothing, but the red button still goes off", 1, r.totalDamage)
    }

    @Test
    fun `three mines in a line eat both of the middle one's horizontal rays`() {
        // The strategic consequence of the rule: a row of mines shadows itself, so
        // spreading them out is worth more than lining them up.
        val (b, r) = fire("X..X..X.W")
        assertEquals("only the rightmost mine reaches the white", 1, r.damageAt(b.index(8, 0)))
        assertTrue("two facing pairs died", r.annihilated >= 4)
    }

    @Test
    fun `three adjacent mines pair off with their neighbours, not across them`() {
        // Regression. Mines at (3,2), (2,3) and (1,4) sit shoulder to shoulder down a
        // diagonal, with a blue in the corner at (0,5).
        //
        // On the tick the rays move, the outer two mines both *enter* the middle cell
        // and look like a head-on pair. They are not: each of them crossed a ray
        // coming out of the middle mine half a tick earlier, midway between adjacent
        // cells. Pair the outer two and the middle mine is left firing freely down the
        // diagonal, so the blue appears to take two hits and the plan looks a mine
        // cheaper than it is.
        val (b, r) = fire(
            """
            ...W
            W..W
            ...X
            W.X.
            .X..
            B...
            """
        )
        assertEquals("only the nearest mine gets through", 1, r.damageAt(b.index(0, 5)))
    }

    @Test
    fun `clearing a button opens the line behind it for the next round`() {
        val b = BombBoard.parse("X.WW")
        val first = sim.fire(b)
        val after = b.afterDetonation(first.damage, b.placedMines())
        assertEquals("the near white is gone", 0, after.hp[after.index(2, 0)])
        assertEquals("the mine has gone dark", Button.EMPTY, after.kindAt(after.index(0, 0)))

        // The same placement now reaches the button that was shadowed before.
        val second = sim.fire(after, intArrayOf(after.index(0, 0)))
        assertEquals(1, second.damageAt(after.index(3, 0)))
    }

    @Test
    fun `a blue hit once shows as white and needs one more`() {
        val b = BombBoard.parse("X.B")
        val r = sim.fire(b)
        val after = b.afterDetonation(r.damage, b.placedMines())
        assertEquals(1, after.hp[after.index(2, 0)])
        assertEquals(Button.WHITE, after.kindAt(after.index(2, 0)))
    }
}
