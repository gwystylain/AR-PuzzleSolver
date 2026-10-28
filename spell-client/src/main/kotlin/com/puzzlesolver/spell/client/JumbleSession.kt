package com.puzzlesolver.spell.client

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.ServerMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The Jumble screen's line to the server, kept up for as long as the screen is open.
 *
 * Jumble is played alone, so there is far less to it than [SpellSession]: no lobby, no seat,
 * nothing the server keeps. The letters are this phone's; each change is sent whole, and the
 * server answers with every word that uses exactly those letters. The word list stays on
 * the server, for the reason in docs/SPELLINATOR.md.
 *
 * **An answer depends on nothing but which letters were asked**, so answers are kept by the
 * letters sorted. A backspace, or the same letters typed in another order, is answered from
 * here at once without asking again, and an answer that arrives after the letters have moved
 * on is kept for when they come back.
 *
 * **A dropped link costs only the wait.** Nothing is held on the server to get back: once
 * reconnected, the letters on screen are asked about again if they have no answer yet. The
 * link is kept the way [SpellSession] keeps it, and for the same reasons -- silence is the
 * only sign of a dead link on cellular, retries start at once and back off to no more than
 * five seconds, and a change of network retries at once ([reconnectNow]).
 *
 * Every change of state is made on one coroutine, in order, as in [SpellSession].
 */
class JumbleSession(
    /** A base address from [ServerAddress.parse]. */
    val server: String,
    private val http: OkHttpClient = spellHttpClient(),
    private val timing: SpellSession.Timing = SpellSession.Timing(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
) {
    private val endpoint = ServerAddress.endpoint(server)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = Channel<() -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(JumbleState())
    val state: StateFlow<JumbleState> = _state.asStateFlow()

    init {
        scope.launch {
            for (task in inbox) {
                try {
                    task()
                } catch (e: Exception) {
                    log("jumble: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // --- What the screen calls. Each only posts; nothing here touches state directly. ---

    fun start() = post {
        if (running) return@post
        running = true
        heartbeat = scope.launch {
            while (isActive) {
                delay(timing.pingEveryMillis)
                post { beat() }
            }
        }
        connect()
    }

    /** Closes the connection; the letters stay, for when the screen is opened again. */
    fun stop() = post { halt() }

    /** [stop], for good. */
    fun close() {
        post {
            halt()
            scope.cancel()
        }
        inbox.close()
    }

    /** The phone's network has changed: try now rather than waiting out a backoff or a dead socket. */
    fun reconnectNow() = post {
        if (!running) return@post
        log("jumble: network changed, reconnecting")
        attempt = 0
        connect()
    }

    /** The phone has no network at all; stop pretending the socket might still be fine. */
    fun networkLost() = post {
        if (running && socket != null) lost("no network")
    }

    fun type(letter: Char) = post {
        if (letter !in 'A'..'Z' || letters.length >= Rules.MAX_JUMBLE_LETTERS) return@post
        changeLetters(letters + letter)
    }

    fun backspace() = post {
        if (letters.isNotEmpty()) changeLetters(letters.dropLast(1))
    }

    fun clear() = post {
        if (letters.isNotEmpty()) changeLetters("")
    }

    // --- Everything below runs on the inbox coroutine only. ---

    private var running = false
    private var socket: WebSocket? = null
    /** Bumped for every new socket, so callbacks from an old one are ignored. */
    private var generation = 0
    private var attempt = 0
    private var retry: Job? = null
    private var heartbeat: Job? = null
    private var link: Link = Link.Idle
    private var connectingSince = 0L
    private var openedAt = 0L
    private var lastHeard = 0L
    private var rtt: Double? = null

    private var letters = ""

    /** Answers by their letters sorted, least recently shown first, so the oldest go once there are [KEPT]. */
    private val answers = object : LinkedHashMap<String, ServerMessage.Jumbled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerMessage.Jumbled>?) =
            size > KEPT
    }

    /** When the server was last asked, for [SpellSession.Timing.resendAfterMillis]. */
    private var askedAt = 0L

    /** The server on this connection did not know the question: it predates Jumble. */
    private var unsupported = false

    private val online: Boolean get() = link is Link.Online

    private fun post(task: () -> Unit) {
        inbox.trySend(task)
    }

    private fun now(): Long = System.nanoTime()

    private fun connect() {
        retry?.cancel()
        retry = null
        socket?.cancel()
        val g = ++generation
        link = Link.Connecting
        connectingSince = now()
        publish()
        socket = http.newWebSocket(Request.Builder().url(endpoint).build(), Listener(g))
    }

    private fun halt() {
        running = false
        retry?.cancel()
        heartbeat?.cancel()
        generation++
        socket?.close(1000, "bye")
        socket = null
        link = Link.Idle
        publish()
    }

    private inner class Listener(private val g: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = post { if (g == generation) opened() }

        override fun onMessage(webSocket: WebSocket, text: String) = post { if (g == generation) received(text) }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            post { if (g == generation) lost(reason.ifEmpty { "closed ($code)" }) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            post { if (g == generation) lost(reason.ifEmpty { "closed ($code)" }) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
            if (g != generation) return@post
            lost(
                when {
                    response != null -> "server said ${response.code}"
                    t is IOException -> t.message ?: "connection failed"
                    else -> t.javaClass.simpleName
                },
            )
        }
    }

    private fun opened() {
        val t = now()
        openedAt = t
        lastHeard = t
        link = Link.Online(rtt?.roundToInt())
        // A new connection may be to a newer server; give it the chance to answer.
        unsupported = false
        log("jumble: connected to $endpoint")
        send(ClientMessage.Ping(t / 1000))
        ask()
        publish()
    }

    private fun received(text: String) {
        lastHeard = now()
        val message = try {
            Protocol.decodeServer(text)
        } catch (e: Exception) {
            // A newer server may say things this phone does not know; that is not an error.
            return
        }
        when (message) {
            is ServerMessage.Jumbled -> answers[keyOf(message.letters)] = message
            is ServerMessage.Pong -> {
                val sample = (lastHeard / 1000 - message.at) / 1000.0
                // Smoothed as SpellSession does: quick to fall, slow to rise.
                if (sample >= 0) {
                    rtt = rtt?.let { if (sample < it) it * 0.3 + sample * 0.7 else it * 0.7 + sample * 0.3 } ?: sample
                }
                link = Link.Online(rtt?.roundToInt())
            }
            is ServerMessage.Error -> {
                log("jumble: server error ${message.code}: ${message.message}")
                // Only questions and pings are sent, and a ping is never refused, so this is
                // a server that does not know the question. Asking again on this connection
                // would only add to the strikes that close it. A refusal for going too fast
                // is not one of these: the heartbeat asks again.
                if (message.code == ErrorCode.BAD_REQUEST) unsupported = true
            }
            // Lobby messages are Spellinator's, and never sent here unasked.
            else -> Unit
        }
        publish()
    }

    private fun lost(reason: String) {
        generation++
        socket?.cancel()
        socket = null
        if (!running) return
        val wait = timing.backoffMillis[minOf(attempt, timing.backoffMillis.lastIndex)]
            .let { if (it == 0L) 0L else (it * Random.nextDouble(0.8, 1.2)).toLong() }
        attempt++
        link = Link.Offline(reason, attempt)
        log("jumble: link lost ($reason), retry $attempt in ${wait}ms")
        publish()
        retry = scope.launch {
            delay(wait)
            post { if (running && socket == null) connect() }
        }
    }

    private fun beat() {
        val ws = socket ?: return
        val t = now()
        when (link) {
            is Link.Connecting -> if ((t - connectingSince) / 1_000_000 > timing.connectTimeoutMillis) {
                lost("timed out connecting")
            }
            is Link.Online -> {
                if ((t - lastHeard) / 1_000_000 > timing.deadAfterMillis) {
                    lost("no reply from the server")
                    return
                }
                if (attempt != 0 && (t - openedAt) / 1_000_000 > timing.stableAfterMillis) attempt = 0
                ws.send(Protocol.encode(ClientMessage.Ping(t / 1000)))
                // Nothing is lost on a working connection, so an answer this late means the
                // rate limit refused the question.
                if ((t - askedAt) / 1_000_000 > timing.resendAfterMillis) ask()
            }
            else -> Unit
        }
    }

    private fun send(message: ClientMessage) {
        socket?.send(Protocol.encode(message))
    }

    private fun changeLetters(new: String) {
        letters = new
        ask()
        publish()
    }

    /** Asks about the letters on screen, unless they are answered already or there is no one to ask. */
    private fun ask() {
        if (letters.length < Rules.MIN_LENGTH || keyOf(letters) in answers || !online || unsupported) return
        send(ClientMessage.Jumble(letters))
        askedAt = now()
    }

    private fun publish() {
        val answer = answers[keyOf(letters)]
        _state.value = JumbleState(
            link = link,
            letters = letters,
            words = answer?.words.orEmpty(),
            total = answer?.total ?: 0,
            current = answer != null || letters.length < Rules.MIN_LENGTH,
            unsupported = unsupported,
        )
    }

    private companion object {
        /** Answers kept. A few hundred bytes each, and more than any one jumble's backspacing needs. */
        const val KEPT = 64

        fun keyOf(letters: String): String = String(letters.toCharArray().also { it.sort() })
    }
}
