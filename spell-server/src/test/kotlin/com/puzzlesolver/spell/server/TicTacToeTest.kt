package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.ServerMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Tic-Tac-Toe lobby: classic, but every word has an O or an X. */
class TicTacToeTest {

    private class FakePeer : Peer {
        val received = mutableListOf<ServerMessage>()
        override fun send(text: String) {
            received += Protocol.decodeServer(text)
        }
        override fun close(code: Short, reason: String) = Unit
        fun state(): ServerMessage.State = received.filterIsInstance<ServerMessage.State>().last()
        fun lastError(): String? = (received.lastOrNull() as? ServerMessage.Error)?.code
    }

    private val lexicon = Lexicon.of("TAP", "PAT", "APT", "APP", "TOP", "POT", "OPT", "TAX", "PAX")
    private val lobbies = LobbyManager(lexicon, ServerConfig(), clock = { 0L })

    private fun FakePeer.say(m: ClientMessage) = lobbies.onMessage(this, m)

    private fun lobby(): Pair<FakePeer, FakePeer> {
        val p1 = FakePeer().apply { say(ClientMessage.Host(GameMode.TIC_TAC_TOE)) }
        val p2 = FakePeer().apply { say(ClientMessage.Join(p1.state().lobby)) }
        p1.say(ClientMessage.SetLength(1, 3))
        return p1 to p2
    }

    @Test
    fun `the lobby says what it plays`() {
        val browser = FakePeer().apply { say(ClientMessage.Browse) }
        val (p1, _) = lobby()
        assertEquals(GameMode.TIC_TAC_TOE, p1.state().mode)
        assertEquals(GameMode.TIC_TAC_TOE, browser.received.filterIsInstance<ServerMessage.Lobbies>().last().lobbies.single().mode)
    }

    @Test
    fun `without an O or an X there is no word, however many others there are`() {
        val (p1, p2) = lobby()
        p1.say(ClientMessage.SetLetters(2, "TA"))
        p2.say(ClientMessage.SetLetters(1, "P"))
        // TAP, PAT, APT and APP are all there to be had in a classic lobby.
        assertNull(p1.state().word)
        assertTrue(p1.state().words.isEmpty())
    }

    @Test
    fun `the first word with an O or an X is held, like any other`() {
        val (p1, p2) = lobby()
        p1.say(ClientMessage.SetLetters(2, "TA"))
        p2.say(ClientMessage.SetLetters(1, "PX"))
        // PAX and TAX need both players; PAX comes first.
        assertEquals("PAX", p1.state().word)
        assertTrue(p1.state().words.all { 'O' in it || 'X' in it })
        // An O makes OPT and POT possible too, but PAX is held.
        p1.say(ClientMessage.SetLetters(3, "TAO"))
        assertEquals("PAX", p2.state().word)
        // Until someone clears: then T, A and O alone spell nothing here, so nothing is held.
        p2.say(ClientMessage.SetLetters(2, ""))
        assertNull(p2.state().word)
    }

    @Test
    fun `tic-tac-toe does not save words`() {
        val (p1, _) = lobby()
        p1.say(ClientMessage.SetSaved(2, listOf("TAX")))
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
    }
}
