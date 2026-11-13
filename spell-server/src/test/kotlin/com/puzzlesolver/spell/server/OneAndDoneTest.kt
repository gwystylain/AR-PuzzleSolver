package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.ServerMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A One and Done 2.0 lobby: three waves, three words, no letter twice. */
class OneAndDoneTest {

    private class FakePeer : Peer {
        val received = mutableListOf<ServerMessage>()
        override fun send(text: String) {
            received += Protocol.decodeServer(text)
        }
        override fun close(code: Short, reason: String) = Unit
        fun state(): ServerMessage.State = received.filterIsInstance<ServerMessage.State>().last()
        fun lastError(): String? = (received.lastOrNull() as? ServerMessage.Error)?.code
    }

    private var now = 1_000_000L
    private val config = ServerConfig(graceMillis = 60_000, offlineAfterMillis = 3_000)

    // C is in four words with no repeated letter, D in three: DAB is cheaper than CAB.
    private val lexicon = Lexicon.of("CAB", "COT", "CUT", "COP", "DAB", "DIG", "DYE", "FIN", "TOO", "BOB")
    private val lobbies = LobbyManager(lexicon, config, clock = { now })

    private fun FakePeer.say(m: ClientMessage) = lobbies.onMessage(this, m)

    /** P1 hosts a One and Done lobby at length 3, P2 joins. */
    private fun round(): Pair<FakePeer, FakePeer> {
        val p1 = FakePeer().apply { say(ClientMessage.Host(GameMode.ONE_AND_DONE)) }
        val id = p1.state().lobby
        val p2 = FakePeer().apply { say(ClientMessage.Join(id)) }
        p1.say(ClientMessage.SetLength(1, 3))
        return p1 to p2
    }

    @Test
    fun `the lobby says what it plays, to browsers and to its players`() {
        val browser = FakePeer().apply { say(ClientMessage.Browse()) }
        val (p1, p2) = round()
        val listed = browser.received.filterIsInstance<ServerMessage.Lobbies>().last().lobbies.single()
        assertEquals(GameMode.ONE_AND_DONE, listed.mode)
        for (p in listOf(p1, p2)) {
            assertEquals(GameMode.ONE_AND_DONE, p.state().mode)
            assertEquals(1, p.state().host)
            assertTrue(p.state().saved.isEmpty())
        }
    }

    // --- Waiting for every letter ---------------------------------------------------

    @Test
    fun `nothing is suggested until the host submits, however many words the letters make`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "CAB"))
        p2.say(ClientMessage.SetLetters(1, "DO"))
        for (p in listOf(p1, p2)) {
            assertNull(p.state().word)
            assertFalse(p.state().solved)
            // Not in the list either: a phone from before Submit shows the list's first.
            assertTrue(p.state().words.isEmpty())
            assertEquals(0, p.state().total)
        }
    }

    @Test
    fun `submitting weighs the whole pool, not the first word that could be made`() {
        val (p1, p2) = round()
        // CAB can be made as soon as P1's letters are in, and spends the dearest letter.
        p1.say(ClientMessage.SetLetters(2, "CAB"))
        assertNull(p1.state().word)
        // With P2's D in, DAB can be made too, and costs less. That is the word to take.
        p2.say(ClientMessage.SetLetters(1, "D"))
        p1.say(ClientMessage.Solve)
        for (p in listOf(p1, p2)) {
            assertEquals("DAB", p.state().word)
            assertTrue(p.state().solved)
        }
    }

    @Test
    fun `the word is held after a submit, and solving again takes in a late letter`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "CAB"))
        p1.say(ClientMessage.Solve)
        assertEquals("CAB", p2.state().word)
        // A letter comes in late. DAB would be better, but the word does not move by itself.
        p2.say(ClientMessage.SetLetters(1, "D"))
        assertEquals("CAB", p2.state().word)
        assertTrue(p2.state().solved)
        p1.say(ClientMessage.Solve)
        assertEquals("DAB", p2.state().word)
    }

    @Test
    fun `clearing goes back to waiting, and does not solve again by itself`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "CAB"))
        p2.say(ClientMessage.SetLetters(1, "D"))
        p1.say(ClientMessage.Solve)
        assertEquals("DAB", p1.state().word)

        p2.say(ClientMessage.SetLetters(2, ""))
        // P1's letters still make CAB, but nobody has submitted them.
        assertNull(p1.state().word)
        assertFalse(p1.state().solved)
        assertTrue(p1.state().words.isEmpty())

        p1.say(ClientMessage.Solve)
        assertEquals("CAB", p1.state().word)
    }

    @Test
    fun `a submit that finds nothing says so, until another letter comes in`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "XZ"))
        p1.say(ClientMessage.Solve)
        assertNull(p2.state().word)
        assertTrue(p2.state().solved, "submitted: there is no word, rather than none yet")
        p2.say(ClientMessage.SetLetters(1, "Q"))
        assertFalse(p2.state().solved, "a new letter means the answer no longer stands")
    }

    @Test
    fun `changing the length goes back to waiting`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "CAB"))
        p1.say(ClientMessage.Solve)
        assertEquals("CAB", p2.state().word)
        p2.say(ClientMessage.SetLength(1, 4))
        assertNull(p2.state().word)
        assertFalse(p2.state().solved)
    }

    @Test
    fun `submitting with no letters in finds nothing and keeps waiting`() {
        val (p1, _) = round()
        p1.say(ClientMessage.Solve)
        assertNull(p1.state().word)
        assertFalse(p1.state().solved)
    }

    // --- A round --------------------------------------------------------------------

    @Test
    fun `a whole round - three waves, three words, no letter twice`() {
        val (p1, p2) = round()
        // Wave 1. C, A, B and D make CAB and DAB; DAB spends the rarer letter.
        p1.say(ClientMessage.SetLetters(2, "CA"))
        p2.say(ClientMessage.SetLetters(1, "BD"))
        p1.say(ClientMessage.Solve)
        assertEquals("DAB", p2.state().word)
        p1.say(ClientMessage.SetSaved(3, listOf("DAB")))
        assertEquals(listOf("DAB"), p2.state().saved)
        // Saved: back to waiting for the next wave's letters.
        assertNull(p2.state().word)
        assertFalse(p2.state().solved)

        // Wave 2: everyone clears and types the next wave's letters.
        p1.say(ClientMessage.SetLetters(4, ""))
        p2.say(ClientMessage.SetLetters(2, ""))
        p1.say(ClientMessage.SetLetters(5, "COTA"))
        assertNull(p1.state().word)
        p1.say(ClientMessage.Solve)
        // CAB would do, but A is spent; COT has nothing used.
        assertEquals("COT", p1.state().word)
        p1.say(ClientMessage.SetSaved(6, listOf("DAB", "COT")))

        // Wave 3.
        p1.say(ClientMessage.SetLetters(7, ""))
        p2.say(ClientMessage.SetLetters(3, "FIN"))
        p1.say(ClientMessage.Solve)
        assertEquals("FIN", p1.state().word)
        p1.say(ClientMessage.SetSaved(8, listOf("DAB", "COT", "FIN")))

        // Done: three words, and nothing more is suggested whatever is typed or submitted.
        assertEquals(listOf("DAB", "COT", "FIN"), p2.state().saved)
        p2.say(ClientMessage.SetLetters(4, "FINDUGE"))
        p1.say(ClientMessage.Solve)
        assertNull(p2.state().word)
        assertFalse(p2.state().solved)
        assertTrue(p2.state().words.isEmpty())

        // A new round starts from nothing saved, and waits like any other wave.
        p1.say(ClientMessage.SetSaved(9, emptyList()))
        assertTrue(p2.state().saved.isEmpty())
        assertNull(p2.state().word)
        p1.say(ClientMessage.Solve)
        assertEquals("FIN", p2.state().word)
    }

    @Test
    fun `a saved word's letters are never suggested again in the round`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "DAB"))
        p1.say(ClientMessage.Solve)
        p1.say(ClientMessage.SetSaved(3, listOf("DAB")))
        p1.say(ClientMessage.SetLetters(4, ""))
        p2.say(ClientMessage.SetLetters(1, "CABOT"))
        p1.say(ClientMessage.Solve)
        // CAB is spelt from these letters, but A and B are spent.
        assertEquals("COT", p2.state().word)
        assertFalse("CAB" in p2.state().words)
    }

    @Test
    fun `undo takes back the last word, its letters are in play again, and it waits for a submit`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetLetters(2, "CA"))
        p2.say(ClientMessage.SetLetters(1, "BD"))
        p1.say(ClientMessage.Solve)
        p1.say(ClientMessage.SetSaved(3, listOf("DAB")))
        p1.say(ClientMessage.SetSaved(4, emptyList()))
        assertNull(p2.state().word)
        assertFalse(p2.state().solved)
        p1.say(ClientMessage.Solve)
        assertEquals("DAB", p2.state().word)
    }

    // --- Who may do what ------------------------------------------------------------

    @Test
    fun `only the host submits`() {
        val (_, p2) = round()
        p2.say(ClientMessage.SetLetters(1, "DAB"))
        p2.say(ClientMessage.Solve)
        assertEquals(ErrorCode.NOT_HOST, p2.lastError())
        assertNull(p2.state().word)
    }

    @Test
    fun `only the host saves`() {
        val (_, p2) = round()
        p2.say(ClientMessage.SetLetters(1, "DAB"))
        p2.say(ClientMessage.SetSaved(2, listOf("DAB")))
        assertEquals(ErrorCode.NOT_HOST, p2.lastError())
        assertTrue(p2.state().saved.isEmpty())
    }

    @Test
    fun `only real words are saved`() {
        val (p1, _) = round()
        p1.say(ClientMessage.SetSaved(2, listOf("BAD")))
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
        assertTrue(p1.state().saved.isEmpty())
    }

    @Test
    fun `other lobbies neither save words nor take submissions, and find words by themselves`() {
        for (mode in listOf(GameMode.CLASSIC, GameMode.TIC_TAC_TOE)) {
            val p1 = FakePeer().apply { say(ClientMessage.Host(mode)) }
            p1.say(ClientMessage.SetSaved(1, listOf("DAB")))
            assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
            p1.say(ClientMessage.Solve)
            assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
            assertEquals(mode, p1.state().mode)
            p1.say(ClientMessage.SetLength(2, 3))
            p1.say(ClientMessage.SetLetters(3, "COT"))
            // Fewest different letters first, as these lobbies rank them.
            assertEquals("TOO", p1.state().word, "found without anyone submitting")
            assertFalse(p1.state().solved)
        }
    }

    @Test
    fun `a late save is dropped like any late change`() {
        val (p1, p2) = round()
        p1.say(ClientMessage.SetSaved(5, listOf("DAB")))
        p1.say(ClientMessage.SetSaved(4, emptyList()))
        assertEquals(listOf("DAB"), p2.state().saved)
    }

    @Test
    fun `the host's job passes to the lowest-numbered player left, but not for a dropped connection`() {
        val (p1, p2) = round()
        val p3 = FakePeer().apply { say(ClientMessage.Join(p1.state().lobby)) }

        // Dropped, seat held: still the host.
        lobbies.onClose(p1)
        now += 10_000
        lobbies.sweep()
        assertEquals(1, p2.state().host)

        // Gone for good: P2 takes over, and can submit and save.
        now += config.graceMillis
        lobbies.sweep()
        assertEquals(2, p2.state().host)
        p2.say(ClientMessage.SetLetters(1, "DAB"))
        p2.say(ClientMessage.Solve)
        assertEquals("DAB", p3.state().word)
        p2.say(ClientMessage.SetSaved(2, listOf("DAB")))
        assertEquals(listOf("DAB"), p3.state().saved)

        // And when the new host leaves, P3.
        p2.say(ClientMessage.Leave)
        assertEquals(3, p3.state().host)
    }
}
