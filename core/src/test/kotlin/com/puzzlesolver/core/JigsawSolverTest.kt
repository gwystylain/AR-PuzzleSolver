package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.jigsaw.JigsawSolver
import com.puzzlesolver.core.puzzle.jigsaw.JigsawSolver.Cell
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JigsawSolverTest {

    private fun grid(vararg rows: String) = rows.toList()

    @Test
    fun `pieces are turned to fit, and the ones left over are decoys`() {
        // A bar of three and an L of five make the shape; the square is a decoy.
        val box = grid(
            "BBB.",
            "B...",
            "BBBB",
        )
        val left = grid(
            "BB....",
            "B..BB.",
            "B..BB.",
            "B.....",
        )
        val right = grid("B", "B", "B")
        val answer = JigsawSolver.solve(box, listOf(left, right)).answers.single()
        assertTrue(answer.solved)
        assertEquals(1, answer.ways.size)
        val way = answer.ways.single()
        assertEquals(setOf(3, 5), way.map { it.piece.size }.toSet())
        // Every cell of the shape covered once.
        assertEquals(answer.target.toSet(), way.flatMap { it.cells }.toSet())
        assertEquals(answer.target.size, way.sumOf { it.cells.size })
        // The L lies upright in the photo and goes in on its back: three quarter turns.
        assertEquals(3, way.single { it.piece.size == 5 }.turns)
    }

    @Test
    fun `a piece is never mirrored - the mirror image is a decoy`() {
        // An S in the box. The pieces: a Z (the S mirrored), and an S lying on its side.
        val box = grid(
            ".YY",
            "YY.",
        )
        val z = grid(
            "YY.",
            ".YY",
        )
        val sideways = grid(
            "Y.",
            "YY",
            ".Y",
        )
        val answer = JigsawSolver.solve(box, listOf(z, sideways)).answers.single()
        assertEquals(1, answer.ways.size)
        assertEquals(1, answer.ways.single().single().piece.side, "the S, turned, not the Z")
        // With only the Z there is no answer at all.
        assertFalse(JigsawSolver.solve(box, listOf(z)).answers.single().solved)
    }

    @Test
    fun `two pieces that would each do are both offered`() {
        val box = grid("OO", "O.")
        val left = grid("OO.OO", "O...O")
        val answer = JigsawSolver.solve(box, listOf(left)).answers.single()
        assertEquals(2, answer.ways.size)
    }

    @Test
    fun `each colour is solved from its own pieces, and green is the frame, never a piece`() {
        val box = grid(
            "GGGGGG",
            "GWWBBG",
            "GWWB.G",
            "GGGGGG",
        )
        val left = grid("WW.BB", "WW.B.", "GG...")
        val answers = JigsawSolver.solve(box, listOf(left)).answers
        assertEquals(listOf('W', 'B'), answers.map { it.colour })
        assertTrue(answers.all { it.solved })
        assertTrue(answers.flatMap { a -> a.pieces }.none { it.colour == 'G' })
    }

    @Test
    fun `a shape the pieces cannot make has no answer`() {
        val box = grid("PPP", "P.P")
        val left = grid("PP.P", "....", "PPP.")
        val answer = JigsawSolver.solve(box, listOf(left)).answers.single()
        assertFalse(answer.solved)
        assertEquals(3, answer.pieces.size)
    }

    @Test
    fun `turns are distinct shapes only, clockwise`() {
        assertEquals(1, JigsawSolver.turns(listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1), Cell(1, 1))).size)
        assertEquals(2, JigsawSolver.turns(listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0))).size)
        val l = JigsawSolver.turns(listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1)))
        assertEquals(4, l.size)
        // One quarter turn clockwise: the corner at bottom left goes to top left.
        assertEquals(listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1)), l[1].second)
    }

    @Test
    fun `a big board is still quick`() {
        // The whole 12 x 8 inside of the box in one colour, from every domino and tromino
        // there could be: many ways, and the search has to stop at its cap, not run on.
        val box = List(8) { "W".repeat(12) }
        val left = List(23) { r -> if (r % 2 == 0) "WW.WW.WW.WW.WW.WW.WW.WW" else "......................." }
        val right = List(23) { r -> if (r % 2 == 0) "WWW.WWW.WWW.WWW.WWW.WWW" else "......................." }
        val start = System.nanoTime()
        val answer = JigsawSolver.solve(box, listOf(left, right)).answers.single()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(answer.solved)
        assertEquals(JigsawSolver.MAX_WAYS, answer.ways.size)
        assertTrue(ms < 5_000, "$ms ms")
    }
}
