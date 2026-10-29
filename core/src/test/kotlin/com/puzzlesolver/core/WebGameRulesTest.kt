package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.Detonation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The practice game's rules, one board at a time, as the game itself played them.
 *
 * The practice game is `activate.ryflix.ca/mines.html`, the one the team trains on.
 * Each board here was set up in it on 2026-10-28 and detonated by its own code; the
 * comment on each says what it did. Coordinates are shifted onto small boards, which
 * changes nothing: rays only care about distance along their line.
 *
 * The rules they settle, and the defaults in [com.puzzlesolver.core.puzzle.bombs.BombRules]:
 *
 *  - rays never cancel, and a bomb's cell is empty once it fires -- so two mines on the
 *    same side of a blue both reach it;
 *  - a button that goes down lets later rays on through it, to whatever is behind --
 *    including a red hazard;
 *  - two rays reaching one button on the same step are taken in the order the bombs
 *    were clicked: the first destroys it, the other carries on.
 */
class WebGameRulesTest {

    private val sim = Detonation()

    @Test
    fun `two mines on the same side of a blue clear it in one detonation`() {
        // Game: bombs (3,10) and (3,11), armored mine (3,13) -- cleared.
        val b = BombBoard.parse("...B")
        val r = sim.fire(b, intArrayOf(0, 1))
        assertEquals(2, r.damageAt(3))
        assertNotNull("and not by luck of ordering", sim.settled(b, intArrayOf(0, 1)))
    }

    @Test
    fun `mines either side of a blue clear it too`() {
        // Game: bombs (3,10) and (3,14), armored mine (3,12) -- cleared.
        val b = BombBoard.parse("..B..")
        assertEquals(2, sim.fire(b, intArrayOf(0, 4)).damageAt(2))
    }

    @Test
    fun `a ray carries on through a button that is already down`() {
        // Game: bombs (3,10) and (3,11), mines (3,13) and (3,15) -- both cleared. The
        // near bomb destroys the first white a step before the far bomb's ray gets
        // there, and that ray goes on to the second.
        val b = BombBoard.parse("...W.W")
        val r = sim.fire(b, intArrayOf(0, 1))
        assertEquals(1, r.damageAt(3))
        assertEquals(1, r.damageAt(5))
        assertEquals("no tie: the rays arrive a step apart", 0, r.contested)
    }

    @Test
    fun `and carries on into a hazard behind it`() {
        // Game: bombs (3,10) and (3,11), mine (3,13), kill square (3,15) -- lost. The
        // rule the old model got dangerously wrong: it had the spent white still
        // blocking, and called this board safe.
        val b = BombBoard.parse("...W.R")
        assertTrue(sim.fire(b, intArrayOf(0, 1)).losesGame)
        assertNull(sim.settled(b, intArrayOf(0, 1)))
    }

    /**
     * Game: a white at (3,12) reached on the same step by a bomb at (3,10) firing east
     * and one at (1,12) firing south, with a kill square at either (6,12) or (3,16):
     *
     * | clicked first | kill south | kill east |
     * | --- | --- | --- |
     * | the east-firing bomb | lost | safe |
     * | the south-firing bomb | safe | lost |
     *
     * The bomb clicked first destroys the white; the other's ray goes on through.
     */
    @Test
    fun `a same-step tie is settled by click order, and no plan may lean on it`() {
        val killSouth = BombBoard.parse(
            """
            .........
            .........
            .........
            .........
            .........
            ..W......
            .........
            .........
            ..R......
            """
        )
        val killEast = BombBoard.parse(
            """
            .........
            .........
            .........
            .........
            .........
            ..W.....R
            .........
            .........
            .........
            """
        )
        // Shifted so the white is at (2,5): the east-firing bomb two to its left, the
        // south-firing one two above it.
        val east = killSouth.index(0, 5)
        val south = killSouth.index(2, 3)

        // Click order is mine order, so the forward run is the game's run.
        assertTrue(sim.fire(killSouth, intArrayOf(east, south)).losesGame)
        assertFalse(sim.fire(killSouth, intArrayOf(south, east)).losesGame)
        assertFalse(sim.fire(killEast, intArrayOf(east, south)).losesGame)
        assertTrue(sim.fire(killEast, intArrayOf(south, east)).losesGame)

        val r = sim.fire(killEast, intArrayOf(east, south))
        assertEquals("one ray got through on order alone", 1, r.contested)
        // Order-dependent either way round, so neither board's pair is offered.
        assertNull(sim.settled(killSouth, intArrayOf(east, south)))
        assertNull(sim.settled(killEast, intArrayOf(east, south)))
    }

    @Test
    fun `a tie that goes nowhere either way is fine`() {
        // The same white and the same two bombs, with nothing behind it in either
        // direction: one ray or the other leaves the board, and the white is down
        // whichever. Refusing that would refuse plans for nothing.
        val b = BombBoard.parse(
            """
            .....
            .....
            .....
            ..W..
            .....
            """
        )
        val r = sim.settled(b, intArrayOf(b.index(0, 3), b.index(2, 1)))
        assertNotNull(r)
        assertEquals(1, r!!.damageAt(b.index(2, 3)))
    }
}
