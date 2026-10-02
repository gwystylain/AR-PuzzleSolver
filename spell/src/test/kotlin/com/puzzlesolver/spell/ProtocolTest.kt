package com.puzzlesolver.spell

import kotlinx.serialization.SerializationException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `every client message survives the wire`() {
        val messages = listOf(
            ClientMessage.Browse,
            ClientMessage.Host,
            ClientMessage.Join("KQZT"),
            ClientMessage.Resume("KQZT", 3, "A".repeat(Token.LENGTH)),
            ClientMessage.Leave,
            ClientMessage.SetLetters(7, "TOP"),
            ClientMessage.SetLength(8, 6),
            ClientMessage.Ping(123456789L),
        )
        for (m in messages) assertEquals(m, Protocol.decodeClient(Protocol.encode(m)))
    }

    @Test
    fun `every server message survives the wire`() {
        val messages = listOf(
            ServerMessage.Lobbies(listOf(LobbySummary("KQZT", 2, 5))),
            ServerMessage.Joined("KQZT", 2, "b".repeat(Token.LENGTH), 41),
            ServerMessage.State(
                "KQZT", 9, 3,
                listOf(PlayerState(1, "T", true, 3), PlayerState(2, "OP", false, 0)),
                listOf("TOP", "POT"), 7,
            ),
            ServerMessage.Left,
            ServerMessage.Error(ErrorCode.LOBBY_FULL, "full"),
            ServerMessage.Pong(5),
        )
        for (m in messages) assertEquals(m, Protocol.decodeServer(Protocol.encode(m)))
    }

    @Test
    fun `the tag is short and the payload is plain`() {
        assertEquals("""{"t":"letters","seq":7,"letters":"TOP"}""", Protocol.encode(ClientMessage.SetLetters(7, "TOP")))
        assertEquals("""{"t":"browse"}""", Protocol.encode(ClientMessage.Browse))
    }

    @Test
    fun `unknown fields are ignored so either side can grow`() {
        assertEquals(
            ClientMessage.Join("KQZT"),
            Protocol.decodeClient("""{"t":"join","lobby":"KQZT","extra":1}"""),
        )
    }

    @Test
    fun `junk is refused at decode`() {
        for (text in listOf("", "{", "[]", """{"t":"nope"}""", """{"lobby":"KQZT"}""", """{"t":"join"}""")) {
            assertFailsWith<SerializationException>(text) { Protocol.decodeClient(text) }
        }
    }

    @Test
    fun `well-formed but out of range is caught by problem()`() {
        val bad = listOf(
            ClientMessage.Join("ABCD"), // vowels are never in a code
            ClientMessage.Join("KQZ"),
            ClientMessage.Join("kqzt"),
            ClientMessage.Resume("KQZT", 0, "A".repeat(Token.LENGTH)),
            ClientMessage.Resume("KQZT", 6, "A".repeat(Token.LENGTH)),
            ClientMessage.Resume("KQZT", 1, "short"),
            ClientMessage.Resume("KQZT", 1, "A".repeat(Token.LENGTH - 1) + "="),
            ClientMessage.SetLetters(1, "top"),
            ClientMessage.SetLetters(1, "T O"),
            ClientMessage.SetLetters(1, "É"),
            ClientMessage.SetLetters(1, "A".repeat(Rules.MAX_LETTERS + 1)),
            ClientMessage.SetLetters(-1, "TOP"),
            ClientMessage.SetLength(1, 2),
            ClientMessage.SetLength(1, 9),
        )
        for (m in bad) assertNotNull(m.problem(), m.toString())

        val good = listOf(
            ClientMessage.Join("KQZT"),
            ClientMessage.SetLetters(0, ""),
            ClientMessage.SetLetters(1, "A".repeat(Rules.MAX_LETTERS)),
            ClientMessage.SetLength(1, 3),
            ClientMessage.SetLength(1, 8),
        )
        for (m in good) assertNull(m.problem(), m.toString())
    }

    @Test
    fun `an update carrying the most words still fits comfortably in a few packets`() {
        val state = ServerMessage.State(
            "KQZT", 1, 8,
            (1..5).map { PlayerState(it, "A".repeat(Rules.MAX_LETTERS), true, Long.MAX_VALUE) },
            List(Rules.MAX_WORDS) { "ABCDEFGH" }, 40_000,
        )
        val bytes = Protocol.encode(state).length
        assertTrue(bytes < 5_000, "a full update is $bytes bytes")
    }

    @Test
    fun `tokens compare equal only when equal`() {
        val t = "abcdefghijklmnopqrstuv"
        assertTrue(Token.matches(t, t))
        assertTrue(!Token.matches(t, t.replaceFirst('a', 'b')))
        assertTrue(!Token.matches(t, t.dropLast(1)))
    }
}
