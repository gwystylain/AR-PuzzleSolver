package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.Game
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.SequenceBoard
import com.puzzlesolver.spell.ServerMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Sequence lobby: a board of numbers anyone fills in, counted through by the host. */
class SequenceTest {

    private class FakePeer : Peer {
        val received = mutableListOf<ServerMessage>()
        override fun send(text: String) {
            received += Protocol.decodeServer(text)
        }
        override fun close(code: Short, reason: String) = Unit
        fun state(): ServerMessage.State = received.filterIsInstance<ServerMessage.State>().last()
        fun lobbies(): List<String> = received.filterIsInstance<ServerMessage.Lobbies>().last().lobbies.map { it.id }
        fun lastError(): String? = (received.lastOrNull() as? ServerMessage.Error)?.code
    }

    private var now = 1_000_000L
    private val lobbies = LobbyManager(Lexicon.of("TOP"), ServerConfig(graceMillis = 60_000), clock = { now })

    private fun FakePeer.say(m: ClientMessage) = lobbies.onMessage(this, m)

    /** P1 hosts a Sequence lobby, P2 joins. */
    private fun board(): Pair<FakePeer, FakePeer> {
        val p1 = FakePeer().apply { say(ClientMessage.Host(GameMode.SEQUENCE)) }
        val p2 = FakePeer().apply { say(ClientMessage.Join(p1.state().lobby)) }
        return p1 to p2
    }

    @Test
    fun `sequence lobbies are listed only to phones browsing for them, and word lobbies only to the rest`() {
        val words = FakePeer().apply { say(ClientMessage.Browse()) }
        val numbers = FakePeer().apply { say(ClientMessage.Browse(Game.SEQUENCE)) }
        val (p1, _) = board()
        val spell = FakePeer().apply { say(ClientMessage.Host(GameMode.CLASSIC)) }
        assertEquals(listOf(p1.state().lobby), numbers.lobbies())
        assertEquals(listOf(spell.state().lobby), words.lobbies())
        assertEquals(GameMode.SEQUENCE, p1.state().mode)
    }

    @Test
    fun `a new board is empty, and the count has not started`() {
        val (p1, p2) = board()
        for (p in listOf(p1, p2)) {
            assertEquals(List(SequenceBoard.SLOTS) { null }, p.state().slots)
            assertNull(p.state().counted)
            assertEquals(1, p.state().host)
        }
    }

    @Test
    fun `anyone sets any slot, and everyone sees it`() {
        val (p1, p2) = board()
        p2.say(ClientMessage.SetSlot(1, 0, 12))
        p1.say(ClientMessage.SetSlot(1, 23, 7))
        for (p in listOf(p1, p2)) {
            assertEquals(12, p.state().slots[0])
            assertEquals(7, p.state().slots[23])
        }
        // Overwritten by someone else, then emptied.
        p1.say(ClientMessage.SetSlot(2, 0, 21))
        assertEquals(21, p2.state().slots[0])
        p2.say(ClientMessage.SetSlot(2, 0, null))
        assertNull(p1.state().slots[0])
        // The sender's ack says which of its changes are in.
        assertEquals(2, p2.state().players.first { it.id == 2 }.ack)
    }

    @Test
    fun `a change that arrives late is dropped`() {
        val (p1, _) = board()
        p1.say(ClientMessage.SetSlot(5, 3, 40))
        p1.say(ClientMessage.SetSlot(4, 3, 30))
        assertEquals(40, p1.state().slots[3])
    }

    @Test
    fun `only the host counts and resets`() {
        val (p1, p2) = board()
        p1.say(ClientMessage.SetSlot(1, 0, 12))
        p2.say(ClientMessage.SetCount(1, 12))
        assertEquals(ErrorCode.NOT_HOST, p2.lastError())
        p2.say(ClientMessage.ResetBoard(2))
        assertEquals(ErrorCode.NOT_HOST, p2.lastError())
        assertEquals(12, p1.state().slots[0])
        assertNull(p1.state().counted)

        p1.say(ClientMessage.SetCount(2, 12))
        assertEquals(12, p2.state().counted)
    }

    @Test
    fun `reset wipes every number and the count`() {
        val (p1, p2) = board()
        p2.say(ClientMessage.SetSlot(1, 4, 9))
        p2.say(ClientMessage.SetSlot(2, 9, 33))
        p1.say(ClientMessage.SetCount(1, 9))
        p1.say(ClientMessage.ResetBoard(2))
        for (p in listOf(p1, p2)) {
            assertTrue(p.state().slots.all { it == null })
            assertNull(p.state().counted)
        }
    }

    @Test
    fun `the host passes on, and the new host counts`() {
        val (p1, p2) = board()
        p1.say(ClientMessage.Leave)
        assertEquals(2, p2.state().host)
        p2.say(ClientMessage.SetSlot(1, 0, 5))
        p2.say(ClientMessage.SetCount(2, 5))
        assertEquals(5, p2.state().counted)
    }

    @Test
    fun `a word lobby has no board`() {
        val p1 = FakePeer().apply { say(ClientMessage.Host(GameMode.CLASSIC)) }
        p1.say(ClientMessage.SetSlot(1, 0, 5))
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
        p1.say(ClientMessage.SetCount(2, 5))
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
        assertTrue(p1.state().slots.isEmpty())
    }

    @Test
    fun `a phone that comes back gets the board back`() {
        val (p1, p2) = board()
        val token = p2.received.filterIsInstance<ServerMessage.Joined>().single().token
        p1.say(ClientMessage.SetSlot(1, 2, 17))
        lobbies.onClose(p2)
        p1.say(ClientMessage.SetCount(2, 17))
        val back = FakePeer().apply { say(ClientMessage.Resume(p1.state().lobby, 2, token)) }
        assertEquals(17, back.state().slots[2])
        assertEquals(17, back.state().counted)
    }
}
