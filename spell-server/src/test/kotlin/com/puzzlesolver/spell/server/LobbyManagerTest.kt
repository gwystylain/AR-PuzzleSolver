package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.ServerMessage
import com.puzzlesolver.spell.Token
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyManagerTest {

    private class FakePeer : Peer {
        val received = mutableListOf<ServerMessage>()
        var closed: Short? = null

        override fun send(text: String) {
            received += Protocol.decodeServer(text)
        }

        override fun close(code: Short, reason: String) {
            closed = code
        }

        fun state(): ServerMessage.State = received.filterIsInstance<ServerMessage.State>().last()
        fun joined(): ServerMessage.Joined = received.filterIsInstance<ServerMessage.Joined>().last()
        fun lobbies(): ServerMessage.Lobbies = received.filterIsInstance<ServerMessage.Lobbies>().last()
        fun lastError(): String? = (received.lastOrNull() as? ServerMessage.Error)?.code
    }

    private var now = 1_000_000L
    private val config = ServerConfig(graceMillis = 120_000, offlineAfterMillis = 3_000, maxLobbies = 3)
    private val lexicon = Lexicon.of("TOP", "POT", "OPT", "OOP", "TOO", "TOT", "POP", "TOOT", "STOP", "TAPS")
    private val lobbies = LobbyManager(lexicon, config, clock = { now })

    private fun FakePeer.say(m: ClientMessage) = lobbies.onMessage(this, m)

    private fun hosted(): Pair<FakePeer, String> {
        val host = FakePeer()
        host.say(ClientMessage.Host)
        return host to host.joined().lobby
    }

    private fun joining(lobby: String) = FakePeer().also { it.say(ClientMessage.Join(lobby)) }

    @Test
    fun `hosting seats you as player 1 with a token and an empty lobby`() {
        val (host, id) = hosted()
        val joined = host.joined()
        assertEquals(1, joined.player)
        assertTrue(Token.isValid(joined.token))
        assertEquals(0, joined.ack)
        val state = host.state()
        assertEquals(id, state.lobby)
        assertEquals(Rules.DEFAULT_LENGTH, state.length)
        assertEquals(listOf(1), state.players.map { it.id })
        assertTrue(state.words.isEmpty())
    }

    @Test
    fun `the brief's example - T, O and P from three players at length 3 gives TOP to all of them`() {
        val (p1, id) = hosted()
        val p2 = joining(id)
        val p3 = joining(id)
        assertEquals(2, p2.joined().player)
        assertEquals(3, p3.joined().player)

        p1.say(ClientMessage.SetLetters(1, "T"))
        p2.say(ClientMessage.SetLetters(1, "O"))
        p3.say(ClientMessage.SetLetters(1, "P"))
        p2.say(ClientMessage.SetLength(2, 3))

        for (p in listOf(p1, p2, p3)) {
            val s = p.state()
            assertEquals(3, s.length)
            assertTrue("TOP" in s.words, "player sees ${s.words}")
            // Letters are reusable, so TOO and POP count too; TAPS needs an A and an S.
            assertEquals(listOf("OOP", "OPT", "POP", "POT", "TOO", "TOP", "TOT"), s.words)
            assertEquals(listOf("T", "O", "P"), s.players.map { it.letters })
        }
    }

    @Test
    fun `a change that arrives after a later one is dropped`() {
        val (p1, _) = hosted()
        p1.say(ClientMessage.SetLetters(5, "TOP"))
        p1.say(ClientMessage.SetLetters(4, "X"))
        p1.say(ClientMessage.SetLetters(5, "Y"))
        assertEquals("TOP", p1.state().players.single().letters)
        assertEquals(5, p1.state().players.single().ack)
    }

    @Test
    fun `a sixth player is turned away`() {
        val (_, id) = hosted()
        repeat(Rules.MAX_PLAYERS - 1) { joining(id) }
        val sixth = joining(id)
        assertEquals(ErrorCode.LOBBY_FULL, sixth.lastError())
    }

    @Test
    fun `leaving frees the seat, the lowest free seat is taken next, and the last one out closes the lobby`() {
        val browser = FakePeer().also { it.say(ClientMessage.Browse) }
        val (p1, id) = hosted()
        val p2 = joining(id)
        val p3 = joining(id)
        assertEquals(3, browser.lobbies().lobbies.single().players)

        p2.say(ClientMessage.Leave)
        assertTrue(p2.received.last() is ServerMessage.Left)
        assertEquals(listOf(1, 3), p1.state().players.map { it.id })
        assertEquals(2, browser.lobbies().lobbies.single().players)

        val p4 = joining(id)
        assertEquals(2, p4.joined().player)

        for (p in listOf(p1, p3, p4)) p.say(ClientMessage.Leave)
        assertTrue(browser.lobbies().lobbies.isEmpty())
        assertEquals(ErrorCode.LOBBY_NOT_FOUND, joining(id).lastError())
    }

    @Test
    fun `a leaver's letters stop counting`() {
        val (p1, id) = hosted()
        val p2 = joining(id)
        p1.say(ClientMessage.SetLength(1, 3))
        p1.say(ClientMessage.SetLetters(2, "TO"))
        p2.say(ClientMessage.SetLetters(1, "P"))
        assertTrue("TOP" in p1.state().words)
        p2.say(ClientMessage.Leave)
        assertEquals(listOf("TOO", "TOT"), p1.state().words)
    }

    @Test
    fun `a dropped player stays online a moment, then shows offline, then is gone after the grace period`() {
        val (p1, id) = hosted()
        val p2 = joining(id)
        p2.say(ClientMessage.SetLetters(1, "P"))
        p1.say(ClientMessage.SetLetters(1, "TO"))
        p1.say(ClientMessage.SetLength(2, 3))

        lobbies.onClose(p2)
        now += 1_000
        lobbies.sweep()
        assertTrue(p1.state().players.first { it.id == 2 }.online, "a blip does not show")

        now += 2_500
        lobbies.sweep()
        val shown = p1.state().players.first { it.id == 2 }
        assertFalse(shown.online)
        assertTrue("TOP" in p1.state().words, "a dropped player's letters still count while their seat is held")

        now += config.graceMillis
        lobbies.sweep()
        assertEquals(listOf(1), p1.state().players.map { it.id })
        assertFalse("TOP" in p1.state().words)
    }

    @Test
    fun `everyone dropping keeps the lobby for the grace period, then closes it`() {
        val (p1, id) = hosted()
        lobbies.onClose(p1)
        now += config.graceMillis - 1
        lobbies.sweep()
        assertEquals(1, lobbies.lobbyCount)
        now += 1
        lobbies.sweep()
        assertEquals(0, lobbies.lobbyCount)
        assertEquals(ErrorCode.LOBBY_NOT_FOUND, joining(id).lastError())
    }

    @Test
    fun `resuming with the token gives the same seat and letters back, and says what was last applied`() {
        val (p1, id) = hosted()
        p1.say(ClientMessage.SetLetters(7, "TOP"))
        val token = p1.joined().token
        lobbies.onClose(p1)
        now += 10_000
        lobbies.sweep()

        val again = FakePeer()
        again.say(ClientMessage.Resume(id, 1, token))
        val joined = again.joined()
        assertEquals(1, joined.player)
        assertEquals(token, joined.token)
        assertEquals(7, joined.ack)
        val me = again.state().players.single()
        assertEquals("TOP", me.letters)
        assertTrue(me.online)
    }

    @Test
    fun `a wrong token, a wrong seat or a closed lobby does not resume`() {
        val (p1, id) = hosted()
        val token = p1.joined().token
        lobbies.onClose(p1)

        val wrongToken = token.reversed()
        assertNotEquals(token, wrongToken)
        FakePeer().apply { say(ClientMessage.Resume(id, 1, wrongToken)) }.also {
            assertEquals(ErrorCode.RESUME_FAILED, it.lastError())
        }
        FakePeer().apply { say(ClientMessage.Resume(id, 2, token)) }.also {
            assertEquals(ErrorCode.RESUME_FAILED, it.lastError())
        }
        FakePeer().apply { say(ClientMessage.Resume("BCDF", 1, token)) }.also {
            assertEquals(ErrorCode.LOBBY_NOT_FOUND, it.lastError())
        }
    }

    @Test
    fun `resuming while the server still thinks the old connection is up takes the seat from it`() {
        val (old, id) = hosted()
        val token = old.joined().token

        val fresh = FakePeer()
        fresh.say(ClientMessage.Resume(id, 1, token))
        assertEquals(1, fresh.joined().player)
        assertEquals(LobbyManager.CLOSE_REPLACED, old.closed)

        // Anything still in flight from the old connection is not taken as the player's.
        old.say(ClientMessage.SetLetters(99, "ZZZ"))
        assertEquals(ErrorCode.NOT_IN_LOBBY, old.lastError())
        // And when its close finally lands, it does not knock the new one offline.
        lobbies.onClose(old)
        now += 60_000
        lobbies.sweep()
        assertTrue(fresh.state().players.single().online)
    }

    @Test
    fun `changes need a seat`() {
        val p = FakePeer()
        p.say(ClientMessage.SetLetters(1, "A"))
        assertEquals(ErrorCode.NOT_IN_LOBBY, p.lastError())
        p.say(ClientMessage.SetLength(2, 4))
        assertEquals(ErrorCode.NOT_IN_LOBBY, p.lastError())
    }

    @Test
    fun `one seat per connection`() {
        val (p1, id) = hosted()
        p1.say(ClientMessage.Host)
        assertEquals(ErrorCode.ALREADY_IN_LOBBY, p1.lastError())
        val (_, other) = hosted()
        p1.say(ClientMessage.Join(other))
        assertEquals(ErrorCode.ALREADY_IN_LOBBY, p1.lastError())
        // Asking again for the lobby you are in is answered, not refused.
        p1.say(ClientMessage.Join(id))
        assertEquals(1, p1.joined().player)
    }

    @Test
    fun `there is a cap on lobbies`() {
        repeat(config.maxLobbies) { hosted() }
        val late = FakePeer().apply { say(ClientMessage.Host) }
        assertEquals(ErrorCode.SERVER_FULL, late.lastError())
    }

    @Test
    fun `browsers see lobbies open, fill, change length and close, newest first`() {
        val browser = FakePeer().also { it.say(ClientMessage.Browse) }
        assertTrue(browser.lobbies().lobbies.isEmpty())
        val (a, first) = hosted()
        val (_, second) = hosted()
        assertEquals(listOf(second, first), browser.lobbies().lobbies.map { it.id })
        a.say(ClientMessage.SetLength(1, 7))
        assertEquals(7, browser.lobbies().lobbies.first { it.id == first }.length)

        // Once seated, a browser stops getting the list.
        browser.say(ClientMessage.Join(first))
        val count = browser.received.count { it is ServerMessage.Lobbies }
        hosted()
        assertEquals(count, browser.received.count { it is ServerMessage.Lobbies })
    }

    @Test
    fun `a ping is answered with the same number`() {
        val p = FakePeer()
        p.say(ClientMessage.Ping(42))
        assertEquals(ServerMessage.Pong(42), p.received.single())
    }

    @Test
    fun `every state carries a higher revision`() {
        val (p1, _) = hosted()
        val before = p1.state().rev
        p1.say(ClientMessage.SetLetters(1, "A"))
        val after = p1.state().rev
        assertTrue(after > before)
        assertNull(p1.lastError())
        assertNotNull(p1.state())
    }
}
