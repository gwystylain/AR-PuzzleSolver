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

/** A Levels 6+ lobby: classic, but no word uses a letter twice. */
class LevelsSixPlusTest {

    private class FakePeer : Peer {
        val received = mutableListOf<ServerMessage>()
        override fun send(text: String) {
            received += Protocol.decodeServer(text)
        }
        override fun close(code: Short, reason: String) = Unit
        fun state(): ServerMessage.State = received.filterIsInstance<ServerMessage.State>().last()
        fun lastError(): String? = (received.lastOrNull() as? ServerMessage.Error)?.code
    }

    private val lexicon = Lexicon.of("TOO", "TOT", "OOP", "POP", "TOP", "POT", "OPT", "TOPS", "STOP", "POTS", "TOOT")
    private val lobbies = LobbyManager(lexicon, ServerConfig(), clock = { 0L })

    private fun FakePeer.say(m: ClientMessage) = lobbies.onMessage(this, m)

    private fun lobby(): Pair<FakePeer, FakePeer> {
        val p1 = FakePeer().apply { say(ClientMessage.Host(GameMode.LEVELS_6_PLUS)) }
        val p2 = FakePeer().apply { say(ClientMessage.Join(p1.state().lobby)) }
        p1.say(ClientMessage.SetLength(1, 3))
        return p1 to p2
    }

    @Test
    fun `the lobby says what it plays`() {
        val browser = FakePeer().apply { say(ClientMessage.Browse()) }
        val (p1, _) = lobby()
        assertEquals(GameMode.LEVELS_6_PLUS, p1.state().mode)
        assertEquals(
            GameMode.LEVELS_6_PLUS,
            browser.received.filterIsInstance<ServerMessage.Lobbies>().last().lobbies.single().mode,
        )
    }

    @Test
    fun `no word with a letter twice is ever suggested`() {
        val (p1, p2) = lobby()
        // T and O alone make TOO and TOT, both with a letter twice: nothing.
        p1.say(ClientMessage.SetLetters(2, "TO"))
        assertNull(p1.state().word)
        assertTrue(p1.state().words.isEmpty())
        // With P, OPT, POT and TOP; classic would have offered OOP or POP first.
        p2.say(ClientMessage.SetLetters(1, "P"))
        assertEquals("OPT", p1.state().word)
        assertEquals(listOf("OPT", "POT", "TOP"), p1.state().words)
    }

    @Test
    fun `the first word found is held, like any other, and found without a submit`() {
        val (p1, p2) = lobby()
        p1.say(ClientMessage.SetLetters(2, "TOP"))
        assertEquals("OPT", p2.state().word)
        assertFalse(p2.state().solved)
        // Four letters now: STOP is possible at length 4 -- but the length is 3, and OPT is held.
        p2.say(ClientMessage.SetLetters(1, "S"))
        assertEquals("OPT", p2.state().word)
        p1.say(ClientMessage.SetLength(3, 4))
        assertEquals("POTS", p2.state().word)
        assertTrue(p2.state().words.none { it == "TOOT" })
    }

    @Test
    fun `levels 6+ neither saves words nor takes submissions`() {
        val (p1, _) = lobby()
        p1.say(ClientMessage.SetSaved(2, listOf("TOP")))
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
        p1.say(ClientMessage.Solve)
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
    }
}
