package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.client.Link
import com.puzzlesolver.spell.client.SessionStore
import com.puzzlesolver.spell.client.SpellSession
import com.puzzlesolver.spell.client.SpellState
import com.puzzlesolver.spell.client.spellHttpClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone's connection code against the real server, over real sockets, through a
 * [FlakyProxy] that drops and stalls connections the way a phone on cellular does.
 *
 * Timings are shrunk -- pings every 150 ms, a link silent for 700 ms is dead -- so a test
 * that waits out a dead link takes a second rather than ten. The logic is the same.
 */
class EndToEndTest {

    private val lexicon = Lexicon.of("TOP", "POT", "OPT", "OOP", "TOO", "TOT", "POP", "TOOT", "XYST", "STOP")
    private val config = ServerConfig(
        port = 0,
        graceMillis = 10_000,
        offlineAfterMillis = 1_000,
        idleTimeoutMillis = 5_000,
    )
    private val timing = SpellSession.Timing(
        pingEveryMillis = 150,
        deadAfterMillis = 700,
        connectTimeoutMillis = 2_000,
        backoffMillis = listOf(0, 100, 200, 400),
        stableAfterMillis = 1_000,
    )
    private val http = spellHttpClient()
    private val cleanup = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        for (c in cleanup.reversed()) runCatching { c() }
    }

    private fun server(config: ServerConfig = this.config): SpellServer =
        SpellServer(config, lexicon).start().also { cleanup += { it.stop() } }

    private fun proxy(to: SpellServer) = FlakyProxy(to.port).also { cleanup += { it.close() } }

    private fun session(port: Int, store: SessionStore = SessionStore.NONE): SpellSession =
        SpellSession("ws://127.0.0.1:$port", http, store, timing).also {
            cleanup += { it.close() }
            it.start()
        }

    private fun SpellSession.await(what: String, timeoutMillis: Long = 5_000, predicate: (SpellState) -> Boolean): SpellState =
        runBlocking { withTimeoutOrNull(timeoutMillis) { state.first(predicate) } }
            ?: fail("timed out waiting for $what; last state: ${state.value}")

    private fun SpellSession.hostLobby(): String {
        await("connected") { it.link is Link.Online }
        host()
        return await("seated") { it.room?.seated == true }.room!!.lobby
    }

    private fun SpellSession.joinLobby(id: String): Int {
        await("lobby $id listed") { s -> s.lobbies.any { it.id == id } }
        join(id)
        return await("seated in $id") { it.room?.seated == true }.room!!.me!!
    }

    private fun SpellSession.typeAll(letters: String) = letters.forEach { type(it) }

    @Test
    fun `three phones, one letter each, length 3 - every phone sees TOP`() {
        val s = server()
        val a = session(s.port)
        val b = session(s.port)
        val c = session(s.port)
        val id = a.hostLobby()
        assertEquals(2, b.joinLobby(id))
        assertEquals(3, c.joinLobby(id))

        a.type('T')
        b.type('O')
        c.type('P')
        b.setLength(3)

        for (p in listOf(a, b, c)) {
            val room = p.await("TOP") { st -> st.room?.let { it.current && it.length == 3 && "TOP" in it.words } == true }.room!!
            assertEquals(listOf("T", "O", "P"), room.players.map { it.letters })
        }
    }

    @Test
    fun `a keypress reaches the other phones in milliseconds`() {
        val s = server()
        val a = session(s.port)
        val b = session(s.port)
        val id = a.hostLobby()
        b.joinLobby(id)

        val samples = mutableListOf<Double>()
        var expected = ""
        runBlocking {
            repeat(60) { i ->
                // Ten a second: fast typing, and inside the server's rate limit.
                Thread.sleep(100)
                val next = if (expected.length == Rules.MAX_LETTERS) "" else expected + ('A' + i % 26)
                val seen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    b.state.first { st -> st.room?.players?.firstOrNull { it.id == 1 }?.letters == next }
                    System.nanoTime()
                }
                val t0 = System.nanoTime()
                if (next.isEmpty()) a.clear() else a.type(next.last())
                val t1 = withTimeoutOrNull(2_000) { seen.await() } ?: fail("keypress $i never arrived")
                samples += (t1 - t0) / 1e6
                expected = next
            }
        }
        samples.sort()
        val median = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]
        println("keypress to other phone over loopback: median %.2f ms, p95 %.2f ms, max %.2f ms".format(median, p95, samples.last()))
        // Over loopback this is the code's own cost: queueing, encoding, the word lookup,
        // broadcasting. A real link adds its round trip on top and nothing else.
        assertTrue(p95 < 50, "p95 was $p95 ms")
    }

    @Test
    fun `cut every connection - both phones are back in their own seats, and what was typed meanwhile arrives`() {
        val s = server()
        val proxy = proxy(s)
        val a = session(proxy.port)
        val b = session(proxy.port)
        val id = a.hostLobby()
        assertEquals(2, b.joinLobby(id))
        a.typeAll("TO")
        b.await("A's letters") { st -> st.room?.players?.firstOrNull { it.id == 1 }?.letters == "TO" }

        // Pointed at nothing first, so the phones stay down until it is put back: they
        // reconnect so fast otherwise that "while the link was down" is a race.
        proxy.target = 1
        proxy.sever()
        a.await("A offline") { it.link is Link.Offline && it.room?.seated == false }
        // Typed while the link was down: shown at once on A, and sent when it is back.
        a.type('P')
        assertEquals("TOP", a.await("local echo") { it.room?.letters == "TOP" }.room!!.letters)
        proxy.target = s.port

        val back = b.await("A's offline letter at B") { st ->
            st.room?.seated == true && st.room!!.players.firstOrNull { it.id == 1 }?.letters == "TOP"
        }.room!!
        assertEquals(2, back.me)
        val aRoom = a.await("A reseated") { it.room?.seated == true && it.room!!.current }.room!!
        assertEquals(1, aRoom.me)
        assertEquals(id, aRoom.lobby)
    }

    @Test
    fun `a connection that goes silent without closing is noticed and replaced`() {
        val s = server()
        val proxy = proxy(s)
        val a = session(proxy.port)
        val b = session(s.port)
        val id = a.hostLobby()
        b.joinLobby(id)

        val t0 = System.nanoTime()
        // New connections go nowhere until the silence has been noticed; otherwise the
        // reconnect is so quick that the moment A is offline can come and go unseen.
        proxy.target = 1
        proxy.freeze()
        a.await("A gives up on the silent link", timeoutMillis = 3_000) { it.link is Link.Offline }
        val noticed = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        proxy.target = s.port
        a.await("A reseated", timeoutMillis = 3_000) { it.room?.seated == true }
        val recovered = (System.nanoTime() - t1) / 1e6
        println("silent link: noticed after %.0f ms, back in the seat %.0f ms after the way was open".format(noticed, recovered))

        // The server never saw the old connection close; the resume took the seat from it.
        a.type('Z')
        b.await("A's letter after recovery") { st -> st.room?.players?.firstOrNull { it.id == 1 }?.letters == "Z" }
        assertEquals(1, a.state.value.room!!.me)
    }

    @Test
    fun `a dropped player shows offline to the others only after a moment, and online again on return`() {
        val s = server()
        val proxy = proxy(s)
        val a = session(proxy.port)
        val b = session(s.port)
        val id = a.hostLobby()
        b.joinLobby(id)

        // Brief: reconnects well inside offlineAfterMillis, so B never sees A drop.
        val flickers = AtomicInteger()
        val watcher = Thread {
            runBlocking {
                withTimeoutOrNull(1_500) {
                    b.state.collect { st ->
                        if (st.room?.players?.firstOrNull { it.id == 1 }?.online == false) flickers.incrementAndGet()
                    }
                }
            }
        }.apply { start() }
        proxy.sever()
        a.await("A back") { it.room?.seated == true }
        watcher.join()
        assertEquals(0, flickers.get(), "a reconnect within a second should not show")

        // Long: the proxy refuses to reconnect A by pointing at nothing.
        proxy.target = 1
        proxy.sever()
        b.await("A shown offline") { st -> st.room?.players?.firstOrNull { it.id == 1 }?.online == false }
        proxy.target = s.port
        a.reconnectNow()
        b.await("A shown online again") { st -> st.room?.players?.firstOrNull { it.id == 1 }?.online == true }
    }

    @Test
    fun `a seat held too long is given up, and the phone rejoins the same lobby with its letters`() {
        val s = server(config.copy(graceMillis = 600))
        val a = session(s.port)
        val b = session(s.port)
        val id = a.hostLobby()
        b.joinLobby(id)
        a.typeAll("STOP")

        a.stop()
        b.await("A's seat given up") { st -> st.room?.players?.none { it.id == 1 } == true }
        a.start()
        val room = a.await("A rejoined") { it.room?.seated == true && it.room!!.current }.room!!
        assertEquals(id, room.lobby)
        assertEquals("STOP", room.letters)
        assertNotNull(a.state.value.notice, "the player is told their seat timed out")
        b.await("A's letters back") { st -> st.room?.players?.any { it.letters == "STOP" } == true }
    }

    @Test
    fun `a restarted server has no lobbies, and the phones say so and go back to the list`() {
        val first = server()
        val proxy = proxy(first)
        val a = session(proxy.port)
        val id = a.hostLobby()

        val second = server()
        proxy.target = second.port
        first.stop()
        proxy.sever()

        val after = a.await("back to the list") { it.room == null && it.link is Link.Online }
        assertTrue(after.notice?.text?.contains(id) == true, "notice: ${after.notice}")
        a.host()
        assertNotEquals(id, a.await("hosting again") { it.room?.seated == true }.room!!.lobby)
    }

    @Test
    fun `a phone killed in the background takes its seat back when it starts again`() {
        val s = server()
        val store = MemoryStore()
        val a = session(s.port, store)
        val b = session(s.port)
        val id = a.hostLobby()
        b.joinLobby(id)
        a.typeAll("OPT")
        a.await("sent") { it.room?.current == true }
        a.close()

        val again = session(s.port, store)
        val room = again.await("restored") { it.room?.seated == true }.room!!
        assertEquals(1, room.me)
        assertEquals("OPT", room.letters)
        again.type('S')
        b.await("new letter") { st -> st.room?.players?.firstOrNull { it.id == 1 }?.letters == "OPTS" }
    }

    @Test
    fun `leaving clears the seat and the letters, and the lobby closes behind the last one out`() {
        val s = server()
        val a = session(s.port)
        val watcher = session(s.port)
        val id = a.hostLobby()
        watcher.await("listed") { st -> st.lobbies.any { it.id == id } }
        a.typeAll("TOP")
        a.leave()
        val after = a.await("left") { it.room == null }
        assertFalse(after.joining)
        watcher.await("lobby gone from the list") { st -> st.lobbies.none { it.id == id } }
        a.host()
        assertEquals("", a.await("new lobby") { it.room?.seated == true }.room!!.letters)
    }

    @Test
    fun `hosting while the link is down happens when it comes back`() {
        val s = server()
        val proxy = proxy(s)
        proxy.target = 1
        val a = session(proxy.port)
        a.await("failing to connect") { it.link is Link.Offline }
        a.host()
        assertTrue(a.await("joining") { it.joining }.joining)
        proxy.target = s.port
        a.reconnectNow()
        a.await("hosted") { it.room?.seated == true }
    }

    // --- The server's defences, from a raw socket -------------------------------

    private class Raw : WebSocketListener() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val code = AtomicInteger(-1)
        val messages = java.util.concurrent.LinkedBlockingQueue<String>()
        val failure = AtomicReference<Throwable?>()
        override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
        override fun onMessage(webSocket: WebSocket, text: String) { messages += text }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            this.code.set(code)
            webSocket.close(1000, null)
            closed.countDown()
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure.set(t)
            closed.countDown()
        }
    }

    private fun raw(port: Int, origin: String? = null): Pair<WebSocket, Raw> {
        val listener = Raw()
        val request = Request.Builder().url("ws://127.0.0.1:$port${Protocol.PATH}")
            .apply { if (origin != null) header("Origin", origin) }
            .build()
        val ws = http.newWebSocket(request, listener)
        cleanup += { ws.cancel() }
        return ws to listener
    }

    @Test
    fun `a browser page cannot connect`() {
        val s = server()
        val (_, l) = raw(s.port, origin = "https://evil.example")
        assertTrue(l.closed.await(3, TimeUnit.SECONDS))
        assertEquals(1008, l.code.get())
    }

    @Test
    fun `an oversized message closes the connection`() {
        val s = server()
        val (ws, l) = raw(s.port)
        assertTrue(l.opened.await(3, TimeUnit.SECONDS))
        ws.send("""{"t":"letters","seq":1,"letters":"${"A".repeat(Rules.MAX_FRAME_BYTES)}"}""")
        assertTrue(l.closed.await(3, TimeUnit.SECONDS))
        assertEquals(1009, l.code.get())
    }

    @Test
    fun `junk is answered with an error, and enough of it closes the connection`() {
        val s = server()
        val (ws, l) = raw(s.port)
        assertTrue(l.opened.await(3, TimeUnit.SECONDS))
        ws.send("not json")
        val reply = l.messages.poll(3, TimeUnit.SECONDS) ?: fail("no reply")
        assertTrue("bad_request" in reply, reply)
        ws.send("""{"t":"letters","seq":1,"letters":"lowercase"}""")
        assertTrue("bad_request" in (l.messages.poll(3, TimeUnit.SECONDS) ?: fail("no reply")))
        repeat(30) { ws.send("""{"t":"what"}""") }
        assertTrue(l.closed.await(3, TimeUnit.SECONDS))
        assertEquals(1008, l.code.get())
    }

    @Test
    fun `one address cannot hold every connection`() {
        val s = server(config.copy(maxConnectionsPerAddress = 2))
        val (_, one) = raw(s.port)
        val (_, two) = raw(s.port)
        assertTrue(one.opened.await(3, TimeUnit.SECONDS) && two.opened.await(3, TimeUnit.SECONDS))
        val (_, three) = raw(s.port)
        assertTrue(three.closed.await(3, TimeUnit.SECONDS))
        assertEquals(1013, three.code.get())
    }

    @Test
    fun `a change the rate limit refused is sent again, so the server does not stay behind`() {
        val s = server(config.copy(messagesPerSecond = 2.0))
        val a = session(s.port)
        val b = session(s.port)
        val id = a.hostLobby()
        b.joinLobby(id)
        // Far faster than two a second with a burst of four: most of these are refused.
        a.typeAll("TOPSTOPS")
        val room = b.await("A's letters, in the end", timeoutMillis = 10_000) { st ->
            st.room?.players?.firstOrNull { it.id == 1 }?.letters == "TOPSTOPS"
        }.room!!
        assertEquals("TOPSTOPS", room.letters.ifEmpty { room.players.first { it.id == 1 }.letters })
        a.await("A's words current") { it.room?.current == true }
    }

    @Test
    fun `a full word list - far bigger than anything a phone may send - reaches the phones`() {
        // Every three-letter string over A-H: 512 "words", more than one update carries.
        val letters = "ABCDEFGH"
        val many = Lexicon.of(*letters.flatMap { x -> letters.flatMap { y -> letters.map { z -> "$x$y$z" } } }.toTypedArray())
        val s = SpellServer(config, many).start().also { cleanup += { it.stop() } }
        val a = session(s.port)
        a.hostLobby()
        a.setLength(3)
        a.typeAll(letters)
        val room = a.await("the full list") { it.room?.current == true && it.room!!.length == 3 && it.room!!.total == 512 }.room!!
        assertEquals(Rules.MAX_WORDS, room.words.size)
        assertEquals("AAA", room.words.first())
    }

    @Test
    fun `a flood is rate limited`() {
        val s = server()
        val (ws, l) = raw(s.port)
        assertTrue(l.opened.await(3, TimeUnit.SECONDS))
        repeat(100) { ws.send("""{"t":"ping","at":$it}""") }
        // A flood that keeps going is cut off, and told why before it is.
        assertTrue(l.closed.await(3, TimeUnit.SECONDS))
        assertEquals(1008, l.code.get())
        val replies = generateSequence { l.messages.poll() }.toList()
        assertTrue(replies.any { "rate_limited" in it }, "no rate limiting in ${replies.size} replies")
    }

    @Test
    fun `the health check answers`() {
        val s = server()
        val response = http.newCall(Request.Builder().url("http://127.0.0.1:${s.port}/healthz").build()).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("ok", it.body!!.string())
        }
    }

    private class MemoryStore : SessionStore {
        @Volatile private var saved: SessionStore.Saved? = null
        override fun load() = saved
        override fun save(saved: SessionStore.Saved) { this.saved = saved }
        override fun clear() { saved = null }
    }
}
