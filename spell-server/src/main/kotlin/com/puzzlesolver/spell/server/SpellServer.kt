package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.ServerMessage
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * The lobby server: one WebSocket endpoint and a health check, over a [LobbyManager].
 *
 * Plain HTTP. TLS is the reverse proxy's job (see docs/SPELLINATOR.md), and the container's
 * port is meant to be reachable only from that proxy.
 */
class SpellServer(
    private val config: ServerConfig,
    lexicon: Lexicon,
    clock: () -> Long = System::currentTimeMillis,
) {
    val lobbies = LobbyManager(lexicon, config, clock)
    private val connections = ConnectionLimiter(config.maxConnections, config.maxConnectionsPerAddress)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** The port actually bound, which differs from the configured one when that was 0. */
    val port: Int
        get() = runBlocking { engine!!.engine.resolvedConnectors().first().port }

    val openConnections: Int get() = connections.open

    fun start(wait: Boolean = false): SpellServer {
        val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module() }
        engine = server
        background.launch {
            while (isActive) {
                delay(SWEEP_MILLIS)
                lobbies.sweep()
            }
        }
        server.start(wait = wait)
        return this
    }

    fun stop() {
        background.cancel()
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 2_000)
        engine = null
    }

    private fun Application.module() {
        install(WebSockets) {
            // Ktor's own protocol pings, as a backstop under the app's: they catch a phone
            // that has stopped sending without closing.
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = Rules.MAX_FRAME_BYTES.toLong()
            masking = false
        }
        routing {
            get("/healthz") { call.respondText("ok") }
            webSocket(Protocol.PATH) { serve() }
        }
    }

    private suspend fun DefaultWebSocketServerSession.serve() {
        // The app is not a browser and sends no Origin. A browser always does, so this
        // turns away any web page trying to use a visitor's browser to reach the server.
        if (call.request.headers[HttpHeaders.Origin] != null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "browsers are not served"))
            return
        }
        val address = clientAddress()
        if (!connections.tryAcquire(address)) {
            log.warn("refused a connection from {}: too many open", address)
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections"))
            return
        }
        // Everything bound for this phone goes through a short queue, drained by its own
        // coroutine. A broadcast to a lobby therefore never waits on its slowest phone; a
        // phone so far behind that its queue fills is dropped, and catches up from one fresh
        // snapshot when it reconnects, which is quicker for it than draining a backlog.
        val outbound = Channel<String>(OUTBOUND_QUEUE)
        val peer = object : Peer {
            override fun send(text: String) {
                val sent = outbound.trySend(text)
                // Full, rather than already closed because this connection is on its way out.
                if (sent.isFailure && !sent.isClosed) {
                    log.warn("dropping a connection from {} that fell behind", address)
                    outbound.close()
                    launch { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "fell behind")) }
                }
            }

            override fun close(code: Short, reason: String) {
                outbound.close()
                launch { close(CloseReason(code, reason)) }
            }
        }
        val writer = launch {
            for (text in outbound) send(Frame.Text(text))
        }
        val limiter = RateLimiter(config.messagesPerSecond)
        var strikes = 0
        // Why the server is ending it, if it is; null when the phone went away by itself.
        var ending: CloseReason? = null
        try {
            while (true) {
                val result = withTimeoutOrNull(config.idleTimeoutMillis) { incoming.receiveCatching() }
                if (result == null) {
                    ending = CloseReason(CloseReason.Codes.GOING_AWAY, "idle")
                    break
                }
                val frame = result.getOrNull() ?: break
                val problem = when {
                    !limiter.tryAcquire() -> {
                        peer.send(Protocol.encode(ServerMessage.Error(ErrorCode.RATE_LIMITED, "slow down")))
                        "too many messages"
                    }
                    frame !is Frame.Text -> "text frames only"
                    else -> handle(peer, frame.readText())?.also {
                        peer.send(Protocol.encode(ServerMessage.Error(ErrorCode.BAD_REQUEST, it)))
                    }
                }
                if (problem != null && ++strikes >= MAX_STRIKES) {
                    log.warn("closing a connection from {}: {}", address, problem)
                    ending = CloseReason(CloseReason.Codes.VIOLATED_POLICY, problem)
                    break
                }
            }
        } finally {
            lobbies.onClose(peer)
            connections.release(address)
            outbound.close()
            withContext(NonCancellable) {
                // What is already queued goes first -- the error that explains the close, say
                // -- but only briefly: a phone that is not reading does not get to hold this.
                withTimeoutOrNull(DRAIN_MILLIS) { writer.join() }
                writer.cancel()
                ending?.let { runCatching { close(it) } }
            }
        }
    }

    /** Hands one message to the lobbies, or says what was wrong with it. */
    private fun handle(peer: Peer, text: String): String? {
        val message = try {
            Protocol.decodeClient(text)
        } catch (e: SerializationException) {
            return "not a message"
        } catch (e: IllegalArgumentException) {
            return "not a message"
        }
        message.problem()?.let { return it }
        lobbies.onMessage(peer, message)
        return null
    }

    /**
     * Who is on the other end. Behind the proxy that is the last `X-Forwarded-For` entry --
     * the one the proxy appended itself; anything to the left of it came from the client and
     * proves nothing.
     */
    private fun DefaultWebSocketServerSession.clientAddress(): String {
        if (config.trustForwarded) {
            call.request.headers[HttpHeaders.XForwardedFor]
                ?.split(',')
                ?.map { it.trim() }
                ?.lastOrNull { it.isNotEmpty() }
                ?.let { return it }
        }
        return call.request.origin.remoteAddress
    }

    companion object {
        private const val SWEEP_MILLIS = 250L
        private const val OUTBOUND_QUEUE = 64
        private const val MAX_STRIKES = 20
        private const val DRAIN_MILLIS = 1_000L
        private val log = LoggerFactory.getLogger(SpellServer::class.java)
    }
}
