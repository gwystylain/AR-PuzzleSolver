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
            ClientMessage.Browse(),
            ClientMessage.Solve,
            ClientMessage.Host(),
            ClientMessage.Host(GameMode.ONE_AND_DONE),
            ClientMessage.Host(GameMode.TIC_TAC_TOE),
            ClientMessage.Host(GameMode.LEVELS_6_PLUS),
            ClientMessage.SetSaved(4, listOf("DAB", "COT")),
            ClientMessage.Join("KQZT"),
            ClientMessage.Resume("KQZT", 3, "A".repeat(Token.LENGTH)),
            ClientMessage.Leave,
            ClientMessage.SetLetters(7, "TOP"),
            ClientMessage.SetLength(8, 6),
            ClientMessage.Ping(123456789L),
            ClientMessage.Jumble("TTEELR"),
            ClientMessage.Browse(Game.SEQUENCE),
            ClientMessage.Host(GameMode.SEQUENCE),
            ClientMessage.SetSlot(9, 23, 42),
            ClientMessage.SetSlot(10, 0, null),
            ClientMessage.SetCount(11, 7),
            ClientMessage.SetCount(12, null),
            ClientMessage.ResetBoard(13),
        )
        for (m in messages) assertEquals(m, Protocol.decodeClient(Protocol.encode(m)))
    }

    @Test
    fun `every server message survives the wire`() {
        val messages = listOf(
            ServerMessage.Lobbies(listOf(LobbySummary("KQZT", 2, 5), LobbySummary("BCDF", 1, 3, GameMode.ONE_AND_DONE), LobbySummary("CDFG", 2, 4, GameMode.TIC_TAC_TOE), LobbySummary("DFGH", 3, 6, GameMode.LEVELS_6_PLUS))),
            ServerMessage.State(
                "BCDF", 2, 3, listOf(PlayerState(2, "FIN", true, 1)), listOf("FIN"), 1,
                word = "FIN", mode = GameMode.ONE_AND_DONE, host = 2, saved = listOf("DAB", "COT"), solved = true,
            ),
            ServerMessage.Joined("KQZT", 2, "b".repeat(Token.LENGTH), 41),
            ServerMessage.State(
                "KQZT", 9, 3,
                listOf(PlayerState(1, "T", true, 3), PlayerState(2, "OP", false, 0)),
                listOf("TOP", "POT"), 7, word = "TOP",
            ),
            ServerMessage.State(
                "GHJK", 4, 5, listOf(PlayerState(1, "", true, 6)), emptyList(), 0,
                mode = GameMode.SEQUENCE, host = 1,
                slots = List(SequenceBoard.SLOTS) { if (it % 5 == 0) it * 4 else null }, counted = 20,
            ),
            ServerMessage.Left,
            ServerMessage.Error(ErrorCode.LOBBY_FULL, "full"),
            ServerMessage.Pong(5),
            ServerMessage.Jumbled("TTEELR", listOf("LETTER"), 1),
            ServerMessage.Jumbled("XQ", emptyList(), 0),
        )
        for (m in messages) assertEquals(m, Protocol.decodeServer(Protocol.encode(m)))
    }

    @Test
    fun `the tag is short and the payload is plain`() {
        assertEquals("""{"t":"letters","seq":7,"letters":"TOP"}""", Protocol.encode(ClientMessage.SetLetters(7, "TOP")))
        assertEquals("""{"t":"browse","game":"spellinator"}""", Protocol.encode(ClientMessage.Browse()))
        assertEquals("""{"t":"slot","seq":3,"slot":5,"value":12}""", Protocol.encode(ClientMessage.SetSlot(3, 5, 12)))
    }

    @Test
    fun `a browse from a phone that predates Sequence asks for Spellinator's lobbies`() {
        assertEquals(ClientMessage.Browse(Game.SPELLINATOR), Protocol.decodeClient("""{"t":"browse"}"""))
        assertEquals(ClientMessage.Browse(Game.SEQUENCE), Protocol.decodeClient("""{"t":"browse","game":"sequence"}"""))
        assertEquals(Game.SEQUENCE, GameMode.SEQUENCE.game)
        assertTrue(GameMode.entries.filter { it != GameMode.SEQUENCE }.all { it.game == Game.SPELLINATOR })
    }

    @Test
    fun `sequence messages must name a real slot, a two-digit number and a count in range`() {
        // Four walls of seven.
        assertEquals(4, SequenceBoard.ROWS)
        assertEquals(7, SequenceBoard.COLUMNS)
        assertNull(ClientMessage.SetSlot(1, 27, 5).problem())
        assertNotNull(ClientMessage.SetSlot(1, 28, 5).problem())
        val good = listOf(
            ClientMessage.SetSlot(1, 0, 0),
            ClientMessage.SetSlot(1, SequenceBoard.SLOTS - 1, 99),
            ClientMessage.SetSlot(1, 7, null),
            ClientMessage.SetCount(1, null),
            ClientMessage.SetCount(1, 0),
            ClientMessage.SetCount(1, 100),
            ClientMessage.ResetBoard(1),
        )
        for (m in good) assertNull(m.problem(), m.toString())
        val bad = listOf(
            ClientMessage.SetSlot(1, -1, 5),
            ClientMessage.SetSlot(1, SequenceBoard.SLOTS, 5),
            ClientMessage.SetSlot(1, 3, 100),
            ClientMessage.SetSlot(1, 3, -1),
            ClientMessage.SetSlot(-1, 3, 5),
            ClientMessage.SetCount(1, -1),
            ClientMessage.SetCount(1, 101),
            ClientMessage.ResetBoard(-1),
        )
        for (m in bad) assertNotNull(m.problem(), m.toString())
    }

    @Test
    fun `the count steps up through the numbers, lowest first, ties together, and past the last`() {
        val slots = MutableList<Int?>(SequenceBoard.SLOTS) { null }
        assertNull(SequenceBoard.next(slots, null), "nothing to count on an empty board")
        slots[0] = 12
        slots[7] = 3
        slots[13] = 40
        slots[20] = 12
        val steps = generateSequence(SequenceBoard.next(slots, null)) { at ->
            SequenceBoard.next(slots, at).takeIf { it != at }
        }.toList()
        assertEquals(listOf(3, 12, 40, 41), steps)
        assertTrue(SequenceBoard.finished(slots, 41))
        assertTrue(!SequenceBoard.finished(slots, 40))
        // And back down again, to not started.
        assertEquals(40, SequenceBoard.previous(slots, 41))
        assertEquals(12, SequenceBoard.previous(slots, 40))
        assertEquals(3, SequenceBoard.previous(slots, 12))
        assertNull(SequenceBoard.previous(slots, 3))
        // A number typed below the count mid-way is shaded; one above it is still to come.
        slots[1] = 5
        slots[2] = 20
        assertEquals(20, SequenceBoard.next(slots, 12))
        // A number past the end of a finished count brings it back to life.
        slots[3] = 99
        assertEquals(99, SequenceBoard.next(slots, 41))
        assertEquals(100, SequenceBoard.next(slots, 99))
    }

    @Test
    fun `a state from a server that predates the held word reads as having none`() {
        val old = """{"t":"state","lobby":"KQZT","rev":1,"length":3,"players":[],"words":["OOP"],"total":1}"""
        assertNull((Protocol.decodeServer(old) as ServerMessage.State).word)
        assertEquals(false, (Protocol.decodeServer(old) as ServerMessage.State).solved)
    }

    @Test
    fun `hosting from an app that predates modes, or naming a mode this side does not know, means classic`() {
        assertEquals(ClientMessage.Host(GameMode.CLASSIC), Protocol.decodeClient("""{"t":"host"}"""))
        assertEquals(ClientMessage.Host(GameMode.CLASSIC), Protocol.decodeClient("""{"t":"host","mode":"chess"}"""))
        assertEquals(
            ClientMessage.Host(GameMode.ONE_AND_DONE),
            Protocol.decodeClient("""{"t":"host","mode":"one_and_done"}"""),
        )
    }

    @Test
    fun `saved words must be words that use no letter twice, three at most`() {
        assertNull(ClientMessage.SetSaved(1, emptyList()).problem())
        assertNull(ClientMessage.SetSaved(1, listOf("DAB", "COT", "FIN")).problem())
        for (bad in listOf(
            listOf("DAB", "COT", "FIN", "JUG"), // four
            listOf("BOOK"), // O twice in one word
            listOf("DAB", "BOT"), // B in both
            listOf("dab"),
            listOf("DA"),
            listOf("DAB1"),
        )) {
            assertNotNull(ClientMessage.SetSaved(1, bad).problem(), bad.toString())
        }
        assertNotNull(ClientMessage.SetSaved(-1, emptyList()).problem())
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
            ClientMessage.Jumble("listen"),
            ClientMessage.Jumble("LIST N"),
            ClientMessage.Jumble("A".repeat(Rules.MAX_LETTERS + 1)),
        )
        for (m in bad) assertNotNull(m.problem(), m.toString())

        val good = listOf(
            ClientMessage.Join("KQZT"),
            ClientMessage.SetLetters(0, ""),
            ClientMessage.SetLetters(1, "A".repeat(Rules.MAX_LETTERS)),
            ClientMessage.SetLength(1, 3),
            ClientMessage.SetLength(1, 8),
            ClientMessage.Jumble(""),
            ClientMessage.Jumble("A".repeat(Rules.MAX_LETTERS)),
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
