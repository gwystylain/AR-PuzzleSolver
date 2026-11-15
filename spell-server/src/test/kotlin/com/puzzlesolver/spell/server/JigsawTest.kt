package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.Game
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.JigsawBoard
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.ServerMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Jigsaw lobby: three grids read from photos of the floor, set by anyone, wiped by the host. */
class JigsawTest {

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

    /** P1 hosts a Jigsaw lobby, P2 joins. */
    private fun floor(): Pair<FakePeer, FakePeer> {
        val p1 = FakePeer().apply { say(ClientMessage.Host(GameMode.JIGSAW)) }
        val p2 = FakePeer().apply { say(ClientMessage.Join(p1.state().lobby)) }
        return p1 to p2
    }

    @Test
    fun `jigsaw lobbies are listed only to phones browsing for them`() {
        val words = FakePeer().apply { say(ClientMessage.Browse()) }
        val numbers = FakePeer().apply { say(ClientMessage.Browse(Game.SEQUENCE)) }
        val floors = FakePeer().apply { say(ClientMessage.Browse(Game.JIGSAW)) }
        val (p1, _) = floor()
        val board = FakePeer().apply { say(ClientMessage.Host(GameMode.SEQUENCE)) }
        assertEquals(listOf(p1.state().lobby), floors.lobbies())
        assertEquals(listOf(board.state().lobby), numbers.lobbies())
        assertTrue(words.received.filterIsInstance<ServerMessage.Lobbies>().all { it.lobbies.isEmpty() })
        assertEquals(GameMode.JIGSAW, p1.state().mode)
    }

    @Test
    fun `a new floor has nothing read, and no sequence board`() {
        val (p1, p2) = floor()
        for (p in listOf(p1, p2)) {
            assertEquals(List(JigsawBoard.REGIONS) { null }, p.state().regions)
            assertEquals(List(JigsawBoard.REGIONS) { null }, p.state().regionBy)
            assertTrue(p.state().slots.isEmpty())
            assertEquals(1, p.state().host)
        }
    }

    @Test
    fun `anyone sets any region, everyone sees it, and who set it`() {
        val (p1, p2) = floor()
        p2.say(ClientMessage.SetRegion(1, JigsawBoard.BOX, "BB/B."))
        p1.say(ClientMessage.SetRegion(1, JigsawBoard.RIGHT, "W"))
        for (p in listOf(p1, p2)) {
            assertEquals(listOf("BB/B.", null, "W"), p.state().regions)
            assertEquals(listOf(2, null, 1), p.state().regionBy)
        }
        // Read again by someone else, then taken away.
        p1.say(ClientMessage.SetRegion(2, JigsawBoard.BOX, "B"))
        assertEquals("B", p2.state().regions[JigsawBoard.BOX])
        assertEquals(1, p2.state().regionBy[JigsawBoard.BOX])
        p2.say(ClientMessage.SetRegion(2, JigsawBoard.BOX, null))
        assertNull(p1.state().regions[JigsawBoard.BOX])
        assertNull(p1.state().regionBy[JigsawBoard.BOX])
        assertEquals(2, p2.state().players.first { it.id == 2 }.ack)
    }

    @Test
    fun `a region that arrives late is dropped`() {
        val (p1, _) = floor()
        p1.say(ClientMessage.SetRegion(5, JigsawBoard.LEFT, "YY"))
        p1.say(ClientMessage.SetRegion(4, JigsawBoard.LEFT, "Y"))
        assertEquals("YY", p1.state().regions[JigsawBoard.LEFT])
    }

    @Test
    fun `only the host resets, and a reset wipes every region`() {
        val (p1, p2) = floor()
        p2.say(ClientMessage.SetRegion(1, JigsawBoard.BOX, "O"))
        p2.say(ClientMessage.SetRegion(2, JigsawBoard.LEFT, "OO"))
        p2.say(ClientMessage.ResetBoard(3))
        assertEquals(ErrorCode.NOT_HOST, p2.lastError())
        assertEquals("O", p1.state().regions[JigsawBoard.BOX])
        p1.say(ClientMessage.ResetBoard(1))
        for (p in listOf(p1, p2)) {
            assertEquals(List(JigsawBoard.REGIONS) { null }, p.state().regions)
            assertEquals(List(JigsawBoard.REGIONS) { null }, p.state().regionBy)
        }
    }

    @Test
    fun `other lobbies have no floor, and nobody counts in a jigsaw lobby`() {
        val word = FakePeer().apply { say(ClientMessage.Host(GameMode.CLASSIC)) }
        word.say(ClientMessage.SetRegion(1, JigsawBoard.BOX, "W"))
        assertEquals(ErrorCode.BAD_REQUEST, word.lastError())
        assertTrue(word.state().regions.isEmpty())
        val board = FakePeer().apply { say(ClientMessage.Host(GameMode.SEQUENCE)) }
        board.say(ClientMessage.SetRegion(1, JigsawBoard.BOX, "W"))
        assertEquals(ErrorCode.BAD_REQUEST, board.lastError())
        val (p1, _) = floor()
        p1.say(ClientMessage.SetCount(1, 5))
        assertEquals(ErrorCode.BAD_REQUEST, p1.lastError())
    }

    @Test
    fun `a phone that comes back gets the floor back`() {
        val (p1, p2) = floor()
        val token = p2.received.filterIsInstance<ServerMessage.Joined>().single().token
        p2.say(ClientMessage.SetRegion(1, JigsawBoard.LEFT, "PP/P."))
        lobbies.onClose(p2)
        p1.say(ClientMessage.SetRegion(1, JigsawBoard.BOX, "PPP"))
        val back = FakePeer().apply { say(ClientMessage.Resume(p1.state().lobby, 2, token)) }
        assertEquals(listOf("PPP", "PP/P.", null), back.state().regions)
    }
}
