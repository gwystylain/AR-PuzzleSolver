package com.puzzlesolver.app.spell

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.puzzlesolver.app.BuildConfig
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.client.JumbleSession
import com.puzzlesolver.spell.client.JumbleState
import com.puzzlesolver.spell.client.ServerAddress
import com.puzzlesolver.spell.client.SessionStore
import com.puzzlesolver.spell.client.SpellSession
import com.puzzlesolver.spell.client.SpellState
import com.puzzlesolver.spell.client.spellHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spellinator's and Jumble's hold on the network, for the activity: which server, the
 * [SpellSession] and [JumbleSession] talking to it, and the two things only Android can
 * tell them. The two rooms share the server, so setting it in either sets it for both.
 *
 * - **When the network changes.** Walking from the venue's Wi-Fi onto cellular kills the
 *   socket without closing it. Rather than wait for the session to notice the silence, the
 *   default-network callback tells it straight away and it reconnects on the new network.
 * - **Where to keep the seat.** The lobby, seat, token and letters go into private
 *   preferences on every change, so a phone that the system killed in the background
 *   takes its seat back when it is reopened. The token is a seat in a word game, not a
 *   credential worth more than app-private storage; `allowBackup` is off, so it never
 *   leaves the phone.
 *
 * Opened when the Spellinator screen is picked and closed when another mode is: closing
 * keeps the seat on the server for its grace period, so a quick look at another room and
 * back loses nothing. Jumble is opened and closed the same way, on its own
 * ([openJumble]); it has no seat to keep, only its letters, which stay on the phone.
 */
class SpellController(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("spellinator", Context.MODE_PRIVATE)
    private val http = spellHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(SpellState())
    val state: StateFlow<SpellState> = _state.asStateFlow()

    private val _jumble = MutableStateFlow(JumbleState())
    val jumble: StateFlow<JumbleState> = _jumble.asStateFlow()

    /** The server in use: the one set in the app, else the one built in, else none. */
    private val _server = MutableStateFlow(prefs.getString(KEY_SERVER, null) ?: builtIn())
    val server: StateFlow<String?> = _server.asStateFlow()

    /**
     * What a lobby hosted from this phone plays: classic, or one of the variants switched on at
     * the foot of the lobby list. Remembered, since a team that plays one tends to keep playing
     * it. A phone that had One and Done switched on before there was more than one variant
     * keeps it.
     */
    private val _variant = MutableStateFlow(
        prefs.getString(KEY_VARIANT, null)?.let { name -> GameMode.entries.firstOrNull { it.name == name } }
            ?: if (prefs.getBoolean(KEY_ONE_AND_DONE, false)) GameMode.ONE_AND_DONE else GameMode.CLASSIC,
    )
    val variant: StateFlow<GameMode> = _variant.asStateFlow()

    fun setVariant(mode: GameMode) {
        _variant.value = mode
        prefs.edit().putString(KEY_VARIANT, mode.name).remove(KEY_ONE_AND_DONE).apply()
    }

    private var session: SpellSession? = null
    private var mirror: Job? = null
    private var open = false

    private var jumbleSession: JumbleSession? = null
    private var jumbleMirror: Job? = null
    private var jumbleOpen = false

    fun open() {
        if (open) return
        open = true
        sessionFor(_server.value)?.start()
        watchNetwork(true)
    }

    fun close() {
        if (!open) return
        open = false
        session?.stop()
        watchNetwork(jumbleOpen)
    }

    fun openJumble() {
        if (jumbleOpen) return
        jumbleOpen = true
        jumbleFor(_server.value)?.start()
        watchNetwork(true)
    }

    fun closeJumble() {
        if (!jumbleOpen) return
        jumbleOpen = false
        jumbleSession?.stop()
        watchNetwork(open)
    }

    fun release() {
        close()
        closeJumble()
        session?.close()
        jumbleSession?.close()
        scope.cancel()
    }

    /**
     * Sets the server from what was typed; returns what is wrong with it, or null. Nothing
     * typed goes back to the server built into the app, so a phone that was pointed
     * somewhere else for testing picks up the real one again.
     */
    fun setServer(input: String): String? {
        val base = if (input.isBlank()) {
            prefs.edit().remove(KEY_SERVER).apply()
            builtIn()
        } else {
            val parsed = ServerAddress.parse(input, allowCleartext = BuildConfig.DEBUG)
                ?: return if (input.trim().startsWith("ws://") || input.trim().startsWith("http://")) {
                    "Use a secure (wss://) address"
                } else {
                    "That is not a server address"
                }
            prefs.edit().putString(KEY_SERVER, parsed).apply()
            parsed
        }
        if (base != _server.value) {
            _server.value = base
            session?.close()
            session = null
            mirror?.cancel()
            _state.value = SpellState()
            val next = sessionFor(base)
            if (open) next?.start()
            jumbleSession?.close()
            jumbleSession = null
            jumbleMirror?.cancel()
            _jumble.value = JumbleState()
            val nextJumble = jumbleFor(base)
            if (jumbleOpen) nextJumble?.start()
        }
        return null
    }

    fun host() = session?.host(_variant.value)
    fun join(lobby: String) = session?.join(lobby)
    fun leave() = session?.leave()
    fun type(letter: Char) = session?.type(letter)
    fun backspace() = session?.backspace()
    fun clear() = session?.clear()
    fun setLength(length: Int) = session?.setLength(length)
    fun dismissNotice(id: Long) = session?.dismissNotice(id)
    fun saveWord() = session?.saveWord()
    fun undoSaved() = session?.undoSaved()
    fun newRound() = session?.newRound()

    fun typeJumble(letter: Char) = jumbleSession?.type(letter)
    fun backspaceJumble() = jumbleSession?.backspace()
    fun clearJumble() = jumbleSession?.clear()

    private fun builtIn(): String? = ServerAddress.parse(BuildConfig.SPELL_SERVER, BuildConfig.DEBUG)

    private fun sessionFor(base: String?): SpellSession? {
        if (base == null) return null
        session?.let { if (it.server == base) return it }
        val created = SpellSession(base, http, PrefsStore(prefs), log = { Log.i(TAG, it) })
        session = created
        mirror?.cancel()
        mirror = scope.launch { created.state.collect { _state.value = it } }
        return created
    }

    private fun jumbleFor(base: String?): JumbleSession? {
        if (base == null) return null
        jumbleSession?.let { if (it.server == base) return it }
        val created = JumbleSession(base, http, log = { Log.i(TAG, it) })
        jumbleSession = created
        jumbleMirror?.cancel()
        jumbleMirror = scope.launch { created.state.collect { _jumble.value = it } }
        return created
    }

    // --- The network ------------------------------------------------------

    /** Touched only from the callback, which Android calls on one thread. */
    private var current: Network? = null
    private var hadNone = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            // The first call just reports the network already in use; only a change, or
            // coming back from none, is news.
            if ((previous != null && previous != network) || hadNone) {
                hadNone = false
                session?.reconnectNow()
                jumbleSession?.reconnectNow()
            }
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            hadNone = true
            session?.networkLost()
            jumbleSession?.networkLost()
        }
    }

    private var watching = false

    private fun watchNetwork(on: Boolean) {
        if (on == watching || connectivity == null) return
        try {
            if (on) {
                current = null
                hadNone = false
                connectivity.registerDefaultNetworkCallback(callback)
            } else {
                connectivity.unregisterNetworkCallback(callback)
            }
            watching = on
        } catch (e: RuntimeException) {
            // Only a nicety: without it a dead link is still found by its silence.
            Log.w(TAG, "network callback unavailable: ${e.message}")
        }
    }

    private class PrefsStore(private val prefs: SharedPreferences) : SessionStore {
        override fun load(): SessionStore.Saved? {
            val server = prefs.getString(KEY_SEAT_SERVER, null) ?: return null
            return SessionStore.Saved(
                server = server,
                lobby = prefs.getString(KEY_LOBBY, null) ?: return null,
                player = prefs.getInt(KEY_PLAYER, 0).takeIf { it > 0 } ?: return null,
                token = prefs.getString(KEY_TOKEN, null) ?: return null,
                letters = prefs.getString(KEY_LETTERS, "") ?: "",
                lettersSeq = prefs.getLong(KEY_LETTERS_SEQ, 0),
                seq = prefs.getLong(KEY_SEQ, 0),
            )
        }

        override fun save(saved: SessionStore.Saved) {
            prefs.edit()
                .putString(KEY_SEAT_SERVER, saved.server)
                .putString(KEY_LOBBY, saved.lobby)
                .putInt(KEY_PLAYER, saved.player)
                .putString(KEY_TOKEN, saved.token)
                .putString(KEY_LETTERS, saved.letters)
                .putLong(KEY_LETTERS_SEQ, saved.lettersSeq)
                .putLong(KEY_SEQ, saved.seq)
                .apply()
        }

        override fun clear() {
            prefs.edit()
                .remove(KEY_SEAT_SERVER).remove(KEY_LOBBY).remove(KEY_PLAYER).remove(KEY_TOKEN)
                .remove(KEY_LETTERS).remove(KEY_LETTERS_SEQ).remove(KEY_SEQ)
                .apply()
        }
    }

    private companion object {
        const val TAG = "Spellinator"
        const val KEY_SERVER = "server"
        const val KEY_VARIANT = "mode.variant"
        /** Before Tic-Tac-Toe, when One and Done was the only variant; read once and dropped. */
        const val KEY_ONE_AND_DONE = "mode.oneAndDone"
        const val KEY_SEAT_SERVER = "seat.server"
        const val KEY_LOBBY = "seat.lobby"
        const val KEY_PLAYER = "seat.player"
        const val KEY_TOKEN = "seat.token"
        const val KEY_LETTERS = "seat.letters"
        const val KEY_LETTERS_SEQ = "seat.lettersSeq"
        const val KEY_SEQ = "seat.seq"
    }
}
