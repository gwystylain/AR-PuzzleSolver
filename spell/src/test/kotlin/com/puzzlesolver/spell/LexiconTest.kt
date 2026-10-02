package com.puzzlesolver.spell

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LexiconTest {

    private val small = Lexicon.of(
        "TOP", "POT", "OPT", "OOP", "TOO", "TOT", "POP", "TOOT", "POOP", "OTTO",
        "TAP", "PAT", "STOP", "POTS", "TOPS",
        // Outside 3..8, so never held.
        "TO", "OP", "TOPOTYPES",
    )

    @Test
    fun `the example from the brief - T, O and P give TOP`() {
        val m = small.find(LetterMask.of("TOP"), 3)
        assertTrue("TOP" in m.words)
    }

    @Test
    fun `letters may be reused, so every word spelt only from them matches`() {
        val three = small.find(LetterMask.of("TOP"), 3)
        assertEquals(listOf("OOP", "OPT", "POP", "POT", "TOO", "TOP", "TOT"), three.words)
        assertEquals(7, three.total)

        val four = small.find(LetterMask.of("TOP"), 4)
        assertEquals(listOf("OTTO", "POOP", "TOOT"), four.words)
    }

    @Test
    fun `a word with any letter nobody has is left out`() {
        val m = small.find(LetterMask.of("TOP"), 3)
        assertFalse("TAP" in m.words)
        assertFalse("PAT" in m.words)
        val withS = small.find(LetterMask.of("TOPS"), 4)
        assertEquals(listOf("OTTO", "POOP", "POTS", "STOP", "TOOT", "TOPS"), withS.words)
    }

    @Test
    fun `order and repeats in the input do not matter, only which letters are there`() {
        assertEquals(
            small.find(LetterMask.of("TOP"), 3),
            small.find(LetterMask.of("PPOOTT"), 3),
        )
    }

    @Test
    fun `no letters, no words`() {
        assertEquals(Matches.NONE, small.find(0, 3))
    }

    @Test
    fun `lengths outside 3 to 8 are not held at all`() {
        assertEquals(0, small.count(2))
        assertEquals(0, small.count(9))
        assertEquals(Matches.NONE, small.find(LetterMask.of("TOP"), 2))
    }

    @Test
    fun `the list is capped but the total is not`() {
        val m = small.find(LetterMask.of("TOP"), 3, limit = 2)
        assertEquals(listOf("OOP", "OPT"), m.words)
        assertEquals(7, m.total)
    }

    @Test
    fun `a list with a definition after each word reads as bare words`() {
        val lex = Lexicon.read(
            """
            AAH an exclamation of delight [interj AAHED, AAHING, AAHS]
            aahs	lower case and a tab
            CAN'T not a plain word
            ZZZ the sound of sleep [n ZZZS]

            AAH a duplicate
            """.trimIndent().reader(),
        )
        assertEquals(3, lex.size)
        assertEquals(listOf("AAH"), lex.find(LetterMask.of("AH"), 3).words)
        assertEquals(listOf("AAHS"), lex.find(LetterMask.of("AHS"), 4).words)
    }

    /**
     * The real CSW24 list, when it is on this machine. Skipped in CI, where it is not --
     * see docs/SPELLINATOR.md for why it is not in the repo.
     */
    @Test
    fun `the full Collins list loads and answers the example`() {
        val path = System.getProperty("spell.dictionary")?.let { Paths.get(it) }
        assumeTrue("no word list at $path", path != null && Files.exists(path))

        val started = System.nanoTime()
        val lex = Lexicon.load(path!!)
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        // CSW24 has 280,887 words; these are its 3- to 8-letter ones.
        assertEquals(120_018, lex.size)
        assertEquals(1_351, lex.count(3))
        assertEquals(42_341, lex.count(8))
        assertTrue("TOP" in lex.find(LetterMask.of("TOP"), 3).words)

        // The worst case for a lookup: the longest bucket against a mask that excludes
        // almost nothing, so nearly every word is copied out.
        val all = LetterMask.of("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        repeat(20) { lex.find(all, 8) }
        val t0 = System.nanoTime()
        val runs = 200
        repeat(runs) { lex.find(all, 8) }
        val perLookupMicros = (System.nanoTime() - t0) / 1000 / runs
        println("CSW24: loaded in ${loadMillis}ms; worst-case lookup ${perLookupMicros}us")
        assertEquals(42_341, lex.find(all, 8).total)
    }
}
