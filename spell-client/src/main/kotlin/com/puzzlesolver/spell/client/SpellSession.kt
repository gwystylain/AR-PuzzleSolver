package com.puzzlesolver.spell.client

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.LobbyCode
import com.puzzlesolver.spell.LobbySummary
import com.puzzlesolver.spell.PlayerState
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
 * One phone's connection to a Spellinator lobby, kept up for as long as the screen is open.
 *
 * **Typing never waits for the network.** A letter appears the instant it is tapped and is
 * sent at once; if the link is down it is kept and sent when it is back. The phone owns its
 * own letters, so there is nothing to reconcile: the server's copy only ever catches up.
 * The word list is the server's, and [Room.current] says whether it has caught up yet.
 *
 * **A dropped link is the normal case, not the exceptional one.** Phones on cellular lose
 * their connection walking between rooms, switching masts, or switching to Wi-Fi. The seat
 * is held on the server for two minutes ([com.puzzlesolver.spell.Token] proves it is
 * yours), and this class gets it back:
 *
 * - it notices a dead link within [Timing.deadAfterMillis] -- a TCP connection on a phone
 *   that walked out of coverage does not close, it just goes quiet, so silence is the only
 *   signal there is;
 * - it retries at once and then backs off ([Timing.backoffMillis]), but never by more than
 *   five seconds, because the person on the other end is standing in a room waiting;
 * - it retries at once again whenever the phone's network changes ([reconnectNow]), rather
 *   than waiting out the backoff while a perfectly good connection is available.
 *
 * Every change of state is made on one coroutine, in order: OkHttp's callbacks and the
 * screen's taps both only post to it. That is what makes it safe without a lock.
 */
class SpellSession(
    /** A base address from [ServerAddress.parse]. */
    val server: String,
    private val http: OkHttpClient = spellHttpClient(),
    private val store: SessionStore = SessionStore.NONE,
    private val timing: Timing = Timing(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
) {
    data class Timing(
        val pingEveryMillis: Long = 2_000,
        /** Silence after which the link is taken to be dead. Pongs arrive every two seconds when it is not. */
        val deadAfterMillis: Long = 6_000,
        /** A connect, TLS included, that takes longer than this is abandoned and retried. */
        val connectTimeoutMillis: Long = 8_000,
        val backoffMillis: List<Long> = listOf(0, 250, 500, 1_000, 2_000, 3_000, 5_000),
        /** How long a connection has to last before the backoff starts again from zero. */
        val stableAfterMillis: Long = 5_000,
        /**
         * How long a change can go unacknowledged on a live link before it is sent again.
         * Nothing is lost on a working TCP connection, so this only ever fires when the
         * server refused the message -- rate limiting -- and it is what stops that leaving
         * the server's copy of this phone's letters behind for good.
         */
        val resendAfterMillis: Long = 1_000,
    )

    private val endpoint = ServerAddress.endpoint(server)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = Channel<() -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(SpellState())
    val state: StateFlow<SpellState> = _state.asStateFlow()

    init {
        scope.launch {
            for (task in inbox) {
                try {
                    task()
                } catch (e: Exception) {
                    log("spell: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // --- What the screen calls. Each only posts; nothing here touches state directly. ---

    fun start() = post {
        if (running) return@post
        running = true
        restore()
        heartbeat = scope.launch {
            while (isActive) {
                delay(timing.pingEveryMillis)
                post { beat() }
            }
        }
        connect()
    }

    /** Closes the connection but keeps the seat, which the server holds for a while. */
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
        log("spell: network changed, reconnecting")
        attempt = 0
        connect()
    }

    /** The phone has no network at all; stop pretending the socket might still be fine. */
    fun networkLost() = post {
        if (running && socket != null) lost("no network")
    }

    fun host(mode: GameMode = GameMode.CLASSIC) = post {
        if (lobby != null || wanted != null) return@post
        wanted = Wanted.Host(mode)
        if (online) send(ClientMessage.Host(mode))
        publish()
    }

    fun join(id: String) = post {
        if (lobby != null || wanted != null || !LobbyCode.isValid(id)) return@post
        wanted = Wanted.Join(id)
        if (online) send(ClientMessage.Join(id))
        publish()
    }

    fun leave() = post {
        if (lobby == null && wanted == null) return@post
        if (seated) send(ClientMessage.Leave)
        dropSeat()
        if (online) send(ClientMessage.Browse)
        publish()
    }

    fun type(letter: Char) = post {
        if (lobby == null || letter !in 'A'..'Z' || letters.length >= Rules.MAX_LETTERS) return@post
        changeLetters(letters + letter)
    }

    fun backspace() = post {
        if (lobby == null || letters.isEmpty()) return@post
        changeLetters(letters.dropLast(1))
    }

    fun clear() = post {
        if (lobby == null || letters.isEmpty()) return@post
        changeLetters("")
    }

    fun setLength(length: Int) = post {
        if (lobby == null || !Rules.isValidLength(length) || length == shownLength()) return@post
        val change = PendingLength(++seq, length)
        pendingLength = change
        if (seated) {
            send(ClientMessage.SetLength(change.seq, change.length))
            changeSentAt = now()
        }
        persist()
        publish()
    }

    /** One and Done 2.0, host only: save the word on screen as the round's next. */
    fun saveWord() = post {
        val room = latest ?: return@post
        val word = room.word ?: return@post
        if (room.saved.size < Rules.ONE_AND_DONE_WORDS) setSaved(room, room.saved + word)
    }

    /** One and Done 2.0, host only: take back the last saved word. */
    fun undoSaved() = post {
        val room = latest ?: return@post
        if (room.saved.isNotEmpty()) setSaved(room, room.saved.dropLast(1))
    }

    /** One and Done 2.0, host only: start a fresh round of three. */
    fun newRound() = post {
        val room = latest ?: return@post
        if (room.saved.isNotEmpty()) setSaved(room, emptyList())
    }

    fun dismissNotice(id: Long) = post {
        if (notice?.id == id) {
            notice = null
            publish()
        }
    }

    // --- Everything below runs on the inbox coroutine only. ---

    private sealed interface Wanted {
        data class Host(val mode: GameMode) : Wanted
        data class Join(val lobby: String) : Wanted
    }

    private data class PendingLength(val seq: Long, val length: Int)

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

    private var lobbies: List<LobbySummary> = emptyList()
    private var notice: Notice? = null
    private var noticeCount = 0L

    private var lobby: String? = null
    private var me: Int? = null
    private var token: String? = null
    private var seated = false
    private var wanted: Wanted? = null
    /** A resume was refused and a fresh seat in the same lobby has been asked for. */
    private var rejoining = false
    /** The server has a later change of ours than this phone remembers (it was restarted mid-change). */
    private var adoptServerLetters = false

    private var seq = 0L
    private var letters = ""
    private var lettersSeq = 0L
    private var pendingLength: PendingLength? = null
    private var latest: ServerMessage.State? = null
    /** When a change was last sent, for [Timing.resendAfterMillis]. */
    private var changeSentAt = 0L

    private val online: Boolean get() = link is Link.Online

    private fun post(task: () -> Unit) {
        inbox.trySend(task)
    }

    private fun now(): Long = System.nanoTime()

    private fun connect() {
        retry?.cancel()
        retry = null
        socket?.cancel()
        seated = false
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
        seated = false
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
        log("spell: connected to $endpoint")
        val id = lobby
        val player = me
        val proof = token
        val wanting = wanted
        when {
            id != null && player != null && proof != null -> send(ClientMessage.Resume(id, player, proof))
            id != null -> send(ClientMessage.Join(id))
            wanting is Wanted.Host -> send(ClientMessage.Host(wanting.mode))
            wanting is Wanted.Join -> send(ClientMessage.Join(wanting.lobby))
            else -> send(ClientMessage.Browse)
        }
        send(ClientMessage.Ping(t / 1000))
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
            is ServerMessage.Lobbies -> lobbies = message.lobbies
            is ServerMessage.Joined -> joined(message)
            is ServerMessage.State -> if (message.lobby == lobby) state(message)
            ServerMessage.Left -> Unit
            is ServerMessage.Error -> failed(message)
            is ServerMessage.Pong -> {
                val sample = (lastHeard / 1000 - message.at) / 1000.0
                // Quick to fall, slow to rise: the first ping meets a server that has not
                // warmed up yet, and one slow sample should not read as a slow link for
                // the next ten seconds, while a link that has really slowed still shows it.
                if (sample >= 0) {
                    rtt = rtt?.let { if (sample < it) it * 0.3 + sample * 0.7 else it * 0.7 + sample * 0.3 } ?: sample
                }
                link = Link.Online(rtt?.roundToInt())
            }
        }
        publish()
    }

    private fun joined(message: ServerMessage.Joined) {
        val freshLobby = message.lobby != lobby
        if (rejoining) notify("Your seat timed out -- you are back in as P${message.player}")
        wanted = null
        rejoining = false
        lobby = message.lobby
        me = message.player
        token = message.token
        seated = true
        if (freshLobby) latest = null
        if (seq < message.ack) seq = message.ack
        adoptServerLetters = message.ack > lettersSeq
        // Typed while the link was down, or sent on a connection that died before it arrived.
        val resent = sendUnacknowledged(message.ack)
        log("spell: seated as P${message.player} in ${message.lobby}, resent $resent")
        persist()
    }

    /** Sends every change the server has not acknowledged, oldest first, and says how many. */
    private fun sendUnacknowledged(ack: Long): Int {
        val unsent = buildList {
            if (lettersSeq > ack) add(lettersSeq to ClientMessage.SetLetters(lettersSeq, letters))
            pendingLength?.takeIf { it.seq > ack }?.let { add(it.seq to ClientMessage.SetLength(it.seq, it.length)) }
        }
        for ((_, m) in unsent.sortedBy { it.first }) send(m)
        if (unsent.isNotEmpty()) changeSentAt = now()
        return unsent.size
    }

    private fun state(message: ServerMessage.State) {
        latest = message
        val mine = message.players.firstOrNull { it.id == me } ?: return
        if (adoptServerLetters) {
            letters = mine.letters
            lettersSeq = mine.ack
            adoptServerLetters = false
            persist()
        }
        pendingLength?.let { if (mine.ack >= it.seq) pendingLength = null }
    }

    private fun failed(error: ServerMessage.Error) {
        log("spell: server error ${error.code}: ${error.message}")
        when (error.code) {
            ErrorCode.RESUME_FAILED -> {
                val id = lobby
                token = null
                me = null
                seated = false
                if (id != null && !rejoining) {
                    rejoining = true
                    send(ClientMessage.Join(id))
                } else {
                    dropSeat()
                    notify("Lost your seat")
                    send(ClientMessage.Browse)
                }
            }
            ErrorCode.LOBBY_NOT_FOUND -> {
                val id = lobby ?: (wanted as? Wanted.Join)?.lobby
                dropSeat()
                notify(if (id != null) "Lobby $id has closed" else "That lobby has closed")
                send(ClientMessage.Browse)
            }
            ErrorCode.LOBBY_FULL -> {
                val id = lobby ?: (wanted as? Wanted.Join)?.lobby
                dropSeat()
                notify(if (id != null) "Lobby $id is full" else "That lobby is full")
                send(ClientMessage.Browse)
            }
            ErrorCode.SERVER_FULL -> {
                wanted = null
                notify("The server has too many lobbies open -- try again in a minute")
            }
            // The rest mean this phone and the server disagree about something minor; the
            // next snapshot settles it.
            else -> Unit
        }
    }

    private fun lost(reason: String) {
        generation++
        socket?.cancel()
        socket = null
        seated = false
        if (!running) return
        val wait = timing.backoffMillis[minOf(attempt, timing.backoffMillis.lastIndex)]
            .let { if (it == 0L) 0L else (it * Random.nextDouble(0.8, 1.2)).toLong() }
        attempt++
        link = Link.Offline(reason, attempt)
        log("spell: link lost ($reason), retry $attempt in ${wait}ms")
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
                val ack = latest?.players?.firstOrNull { it.id == me }?.ack
                if (seated && ack != null && (t - changeSentAt) / 1_000_000 > timing.resendAfterMillis) {
                    sendUnacknowledged(ack)
                }
            }
            else -> Unit
        }
    }

    private fun send(message: ClientMessage) {
        socket?.send(Protocol.encode(message))
    }

    /**
     * Sends the round's saved words. Not kept for resending like letters are: a save lost to
     * a dropped connection simply has not happened yet, the word is still on the host's
     * screen, and the host taps again -- the server keeping its own copy is what matters.
     */
    private fun setSaved(room: ServerMessage.State, words: List<String>) {
        if (!seated || room.mode != GameMode.ONE_AND_DONE || me != room.host) return
        send(ClientMessage.SetSaved(++seq, words))
        persist()
    }

    private fun changeLetters(new: String) {
        letters = new
        lettersSeq = ++seq
        adoptServerLetters = false
        if (seated) {
            send(ClientMessage.SetLetters(lettersSeq, letters))
            changeSentAt = now()
        }
        persist()
        publish()
    }

    private fun dropSeat() {
        lobby = null
        me = null
        token = null
        seated = false
        wanted = null
        rejoining = false
        adoptServerLetters = false
        latest = null
        letters = ""
        lettersSeq = 0
        pendingLength = null
        store.clear()
    }

    private fun notify(text: String) {
        notice = Notice(++noticeCount, text)
    }

    private fun restore() {
        val saved = store.load() ?: return
        if (saved.server != server || !LobbyCode.isValid(saved.lobby)) {
            store.clear()
            return
        }
        lobby = saved.lobby
        me = saved.player
        token = saved.token
        letters = saved.letters
        lettersSeq = saved.lettersSeq
        seq = maxOf(saved.seq, saved.lettersSeq)
        log("spell: restored seat P${saved.player} in ${saved.lobby}")
    }

    private fun persist() {
        val id = lobby ?: return
        val player = me ?: return
        val t = token ?: return
        store.save(SessionStore.Saved(server, id, player, t, letters, lettersSeq, seq))
    }

    private fun shownLength(): Int = pendingLength?.length ?: latest?.length ?: Rules.DEFAULT_LENGTH

    private fun publish() {
        val room = lobby?.let { id ->
            val server = latest?.takeIf { it.lobby == id }
            val mine = server?.players?.firstOrNull { it.id == me }
            val newest = maxOf(lettersSeq, pendingLength?.seq ?: 0)
            val players = server?.players.orEmpty().map {
                if (it.id == me) it.copy(letters = letters, online = seated) else it
            }.let { list ->
                val self = me
                if (self != null && list.none { it.id == self }) {
                    (list + PlayerState(self, letters, seated, lettersSeq)).sortedBy { it.id }
                } else {
                    list
                }
            }
            Room(
                lobby = id,
                me = me,
                letters = letters,
                length = shownLength(),
                players = players,
                // A server from before held words sends none; its best word is the first.
                word = server?.let { it.word ?: it.words.firstOrNull() },
                words = server?.words.orEmpty(),
                total = server?.total ?: 0,
                current = server != null && mine != null && mine.ack >= newest && !adoptServerLetters,
                seated = seated,
                mode = server?.mode ?: GameMode.CLASSIC,
                host = server?.host ?: 0,
                saved = server?.saved.orEmpty(),
            )
        }
        _state.value = SpellState(
            link = link,
            lobbies = lobbies,
            joining = wanted != null || (lobby != null && me == null),
            room = room,
            notice = notice,
        )
    }
}
