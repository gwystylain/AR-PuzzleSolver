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
        // Outside 3..8, so never found for Spellinator: two letters are not held at all, and
        // nine are only for Jumble.
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
        assertEquals(listOf("OOP", "OPT", "POP", "POT", "TOO", "TOP", "TOT"), three.words.sorted())
        assertEquals(7, three.total)

        val four = small.find(LetterMask.of("TOP"), 4)
        assertEquals(listOf("OTTO", "POOP", "TOOT"), four.words.sorted())
    }

    @Test
    fun `a word with any letter nobody has is left out`() {
        val m = small.find(LetterMask.of("TOP"), 3)
        assertFalse("TAP" in m.words)
        assertFalse("PAT" in m.words)
        val withS = small.find(LetterMask.of("TOPS"), 4)
        assertEquals(listOf("OTTO", "POOP", "POTS", "STOP", "TOOT", "TOPS"), withS.words.sorted())
    }

    @Test
    fun `fewest different letters first, then alphabetical`() {
        // OOP, POP, TOO and TOT have two different letters; OPT, POT and TOP have three.
        assertEquals(
            listOf("OOP", "POP", "TOO", "TOT", "OPT", "POT", "TOP"),
            small.find(LetterMask.of("TOP"), 3).words,
        )
        assertEquals(
            listOf("OTTO", "POOP", "TOOT", "POTS", "STOP", "TOPS"),
            small.find(LetterMask.of("TOPS"), 4).words,
        )
    }

    @Test
    fun `the brief's example - one letter each - gives OOP, every two-letter word using two players`() {
        val players = intArrayOf(LetterMask.of("T"), LetterMask.of("O"), LetterMask.of("P"))
        val m = small.find(LetterMask.of("TOP"), 3, players = players)
        assertEquals(listOf("OOP", "POP", "TOO", "TOT", "OPT", "POT", "TOP"), m.words)
    }

    @Test
    fun `among words with as few letters, the one using the most players comes first`() {
        // P1 has O and P, P2 has T. Of the two-letter words, TOO and TOT need both players,
        // OOP and POP only P1, so TOO beats OOP although it comes later alphabetically.
        val players = intArrayOf(LetterMask.of("OP"), LetterMask.of("T"))
        val m = small.find(LetterMask.of("TOP"), 3, players = players)
        assertEquals(listOf("TOO", "TOT", "OOP", "POP", "OPT", "POT", "TOP"), m.words)
    }

    @Test
    fun `a letter two players share counts each of them as used`() {
        // Both have O, so every two-letter word here uses both players, and alphabetical
        // order decides between them.
        val players = intArrayOf(LetterMask.of("OP"), LetterMask.of("OT"))
        val m = small.find(LetterMask.of("TOP"), 3, players = players)
        assertEquals("OOP", m.words.first())
        assertEquals(setOf("OOP", "POP", "TOO", "TOT"), m.words.take(4).toSet())
    }

    @Test
    fun `players with no letters change nothing`() {
        val alone = small.find(LetterMask.of("TOP"), 3, players = intArrayOf(LetterMask.of("TOP")))
        val withEmpty = small.find(LetterMask.of("TOP"), 3, players = intArrayOf(LetterMask.of("TOP"), 0, 0))
        assertEquals(alone, withEmpty)
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
    fun `lengths outside 3 to 15 are not held at all`() {
        assertEquals(0, small.count(2))
        assertEquals(Matches.NONE, small.find(LetterMask.of("TOP"), 2))
        // Nine letters are held, for Jumble, and found if asked for.
        assertEquals(1, small.count(9))
        val sixteen = Lexicon.of("ABCDEFGHIJKLMNO", "ABCDEFGHIJKLMNOP")
        assertEquals(1, sixteen.count(15))
        assertEquals(0, sixteen.count(16))
    }

    @Test
    fun `the list is capped but the total is not`() {
        val m = small.find(LetterMask.of("TOP"), 3, limit = 2)
        assertEquals(listOf("OOP", "POP"), m.words)
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

    // --- Jumble -----------------------------------------------------------------

    private val jumbles = Lexicon.of(
        "LISTEN", "SILENT", "TINSEL", "INLETS", "ENLIST", "LITTEN",
        "LETTER", "TETTER", "RETTLE", "TOPOTYPES",
    )

    @Test
    fun `every word that uses all the letters, alphabetical, in whatever order they were typed`() {
        val m = jumbles.anagrams("NTLSIE")
        assertEquals(listOf("ENLIST", "INLETS", "LISTEN", "SILENT", "TINSEL"), m.words)
        assertEquals(5, m.total)
        assertEquals(m, jumbles.anagrams("SILENT"))
    }

    @Test
    fun `repeats count - every letter typed is used exactly once`() {
        assertEquals(listOf("LETTER", "RETTLE"), jumbles.anagrams("TTEELR").words)
        // One T fewer and one E more: the same set of letters, but not LETTER's counts.
        assertEquals(Matches.NONE, jumbles.anagrams("TEEELR"))
        // LITTEN has the T twice; the letters of LISTEN with a T for the S do not make it.
        assertEquals(listOf("LITTEN"), jumbles.anagrams("NETTIL").words)
        assertFalse("LITTEN" in jumbles.anagrams("NETSIL").words)
    }

    @Test
    fun `a letter short or a letter over is no word at all`() {
        assertEquals(Matches.NONE, jumbles.anagrams("LISTE"))
        assertEquals(Matches.NONE, jumbles.anagrams("LISTENS"))
        assertEquals(Matches.NONE, jumbles.anagrams(""))
        assertEquals(Matches.NONE, jumbles.anagrams("TO"))
    }

    @Test
    fun `jumbles longer than Spellinator's words are answered`() {
        assertEquals(listOf("TOPOTYPES"), jumbles.anagrams("SEPYTOPOT").words)
    }

    @Test
    fun `anything but A to Z answers nothing`() {
        assertEquals(Matches.NONE, jumbles.anagrams("listen"))
        assertEquals(Matches.NONE, jumbles.anagrams("LIST N"))
    }

    @Test
    fun `the anagrams are capped but the total is not`() {
        val m = jumbles.anagrams("LISTEN", limit = 2)
        assertEquals(listOf("ENLIST", "INLETS"), m.words)
        assertEquals(5, m.total)
    }

    // --- Tic-Tac-Toe -----------------------------------------------------------

    @Test
    fun `tic-tac-toe keeps only words with an O or an X, ranked as usual`() {
        val lex = Lexicon.of("TAP", "PAT", "APT", "TOP", "POT", "OPT", "TAX", "PAX", "APP")
        val all = lex.find(LetterMask.of("TAPOX"), 3)
        assertEquals(9, all.total)
        val ox = lex.find(LetterMask.of("TAPOX"), 3, oneOf = TicTacToe.LETTERS)
        // Every one has an O or an X; APP, with two different letters, would have led.
        assertEquals(listOf("OPT", "PAX", "POT", "TAX", "TOP"), ox.words)
        assertEquals("APP", all.words.first())
    }

    @Test
    fun `tic-tac-toe with no O or X typed finds nothing`() {
        val lex = Lexicon.of("TAP", "PAT", "TOP")
        assertEquals(Matches.NONE, lex.find(LetterMask.of("TAP"), 3, oneOf = TicTacToe.LETTERS))
    }

    // --- One and Done 2.0 ------------------------------------------------------

    private val waves = Lexicon.of("CAB", "COT", "CUT", "COP", "DAB", "DIG", "DYE", "TOO", "BOB")

    @Test
    fun `one and done spends the rarest letters first`() {
        // Among the words with no repeated letter, C is in four (CAB COT CUT COP) and D in
        // three (DAB DIG DYE), so DAB costs less than CAB and comes first.
        assertEquals(listOf("DAB", "CAB"), waves.findOneAndDone(LetterMask.of("ABCD"), 3, used = 0).words)
    }

    @Test
    fun `a letter's cost counts only the words still possible after the saved ones`() {
        // With O and U used up, COT CUT and COP are out of reach for the rest of the round,
        // so C is now in one word and D still in three: CAB is the cheaper word.
        assertEquals(listOf("CAB", "DAB"), waves.findOneAndDone(LetterMask.of("ABCD"), 3, used = LetterMask.of("OU")).words)
    }

    @Test
    fun `one and done never offers a used letter or a letter twice`() {
        val m = waves.findOneAndDone(LetterMask.of("ABCDOT"), 3, used = LetterMask.of("D"))
        // TOO and BOB repeat a letter; DAB has the used D.
        assertEquals(setOf("CAB", "COT"), m.words.toSet())
        assertEquals(2, m.total)
        assertEquals(Matches.NONE, waves.findOneAndDone(LetterMask.of("ABCD"), 3, used = LetterMask.of("ABCD")))
    }

    @Test
    fun `at equal cost, the word using the most players comes first, then alphabetical`() {
        val same = Lexicon.of("ABC", "ABD")
        // C and D are in one word each, so ABC and ABD cost the same.
        assertEquals(listOf("ABC", "ABD"), same.findOneAndDone(LetterMask.of("ABCD"), 3, used = 0).words)
        val players = intArrayOf(LetterMask.of("ABC"), LetterMask.of("D"))
        assertEquals(listOf("ABD", "ABC"), same.findOneAndDone(LetterMask.of("ABCD"), 3, used = 0, players = players).words)
    }

    @Test
    fun `contains finds exactly the words held`() {
        assertTrue(waves.contains("DAB"))
        assertFalse(waves.contains("DAD"))
        assertFalse(waves.contains("DA"))
        assertFalse(waves.contains("DABBLING"))
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
        // CSW24 has 280,887 words; these are all but its 127 of two letters.
        assertEquals(280_760, lex.size)
        assertEquals(1_351, lex.count(3))
        assertEquals(42_341, lex.count(8))
        assertEquals(5_956, lex.count(15))
        assertTrue("TOP" in lex.find(LetterMask.of("TOP"), 3).words)
        assertTrue(lex.anagrams("LISTEN").words.containsAll(listOf("ENLIST", "INLETS", "LISTEN", "SILENT", "TINSEL")))
        assertTrue("LETTER" in lex.anagrams("TTEELR").words)

        // Jumble's worst case is the largest bucket, nine letters, against the commonest
        // letters; the mask rules out nearly all of it before any letter is counted.
        repeat(20) { lex.anagrams("AEINRSTLO") }
        val tj = System.nanoTime()
        repeat(200) { lex.anagrams("AEINRSTLO") }
        val jumbleMicros = (System.nanoTime() - tj) / 1000 / 200
        println("CSW24: jumble lookup ${jumbleMicros}us; AEINRSTLO makes ${lex.anagrams("AEINRSTLO").words}")

        // The worst case for a lookup: the longest bucket against a mask that excludes
        // nothing, so every word is ranked, against five players' letters.
        val all = LetterMask.of("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val five = intArrayOf(
            LetterMask.of("ABCDE"), LetterMask.of("FGHIJ"), LetterMask.of("KLMNO"),
            LetterMask.of("PQRST"), LetterMask.of("UVWXYZ"),
        )
        repeat(20) { lex.find(all, 8, players = five) }
        val t0 = System.nanoTime()
        val runs = 200
        repeat(runs) { lex.find(all, 8, players = five) }
        val perLookupMicros = (System.nanoTime() - t0) / 1000 / runs
        println("CSW24: loaded in ${loadMillis}ms; worst-case lookup ${perLookupMicros}us")
        assertEquals(42_341, lex.find(all, 8).total)

        // One and Done's worst case: every letter available, so every eight-letter word with
        // no repeat is a candidate, costed and sorted.
        repeat(5) { lex.findOneAndDone(all, 8, used = 0, players = five) }
        val t1 = System.nanoTime()
        repeat(20) { lex.findOneAndDone(all, 8, used = 0, players = five) }
        val oneAndDoneMicros = (System.nanoTime() - t1) / 1000 / 20
        val best = lex.findOneAndDone(all, 8, used = 0, players = five)
        println("CSW24: one and done worst case ${oneAndDoneMicros}us over ${best.total} words; best ${best.words.take(5)}")
        assertTrue(best.words.all { it.toSet().size == 8 })
    }
}
