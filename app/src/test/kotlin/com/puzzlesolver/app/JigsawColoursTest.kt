package com.puzzlesolver.app

import com.puzzlesolver.core.puzzle.jigsaw.FloorColour
import com.puzzlesolver.spell.JigsawBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The floor reader in `:core` and the lobby protocol in `:spell` know nothing of each other,
 * but the grids one writes the other carries: their colour codes have to be the same.
 */
class JigsawColoursTest {

    @Test
    fun `the reader's piece colours are the lobby's, and green is neither`() {
        assertEquals(JigsawBoard.COLOURS, FloorColour.PIECES.joinToString("") { it.code.toString() })
        assertEquals(JigsawBoard.OFF, '.')
        assertNull(FloorColour.of(JigsawBoard.OFF))
        assert(FloorColour.GREEN.code !in JigsawBoard.COLOURS)
    }
}
