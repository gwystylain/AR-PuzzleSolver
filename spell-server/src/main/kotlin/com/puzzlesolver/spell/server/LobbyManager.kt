package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.ClientMessage
import com.puzzlesolver.spell.ErrorCode
import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.LetterMask
import com.puzzlesolver.spell.Lexicon
import com.puzzlesolver.spell.LobbyCode
import com.puzzlesolver.spell.LobbySummary
import com.puzzlesolver.spell.Matches
import com.puzzlesolver.spell.OneAndDone
import com.puzzlesolver.spell.PlayerState
import com.puzzlesolver.spell.Protocol
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.ServerMessage
import com.puzzlesolver.spell.Token
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import java.util.TreeMap

/** One open connection, as the lobbies see it. Sending never blocks. */
interface Peer {
    fun send(text: String)
    fun close(code: Short, reason: String)
}

/**
 * Every lobby on the server, and who is sitting where.
 *
 * Nothing here knows about sockets: a connection is a [Peer], and the clock is passed in, so
 * the whole of it -- seats, reconnects, the grace period -- is tested without a network.
 *
 * One lock around all of it. At most a few hundred connections each sending a few messages a
 * second, and the most expensive thing done under the lock is a word lookup of well under a
 * millisecond, so finer locking would buy nothing and cost the certainty that two players
 * changing the same lobby at once cannot interleave. Sends inside the lock only queue.
 *
 * State lives only in memory. A restart closes every lobby; the phones notice, say so,
 * and go back to the list.
 */
class LobbyManager(
    private val lexicon: Lexicon,
    private val config: ServerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Player(val slot: Int, val token: String) {
        var letters = ""
        var lastSeq = 0L
        var peer: Peer? = null
        /** When [peer] went away; meaningless while connected. */
        var droppedAt = 0L
        /** What everyone else was last told; lags a drop by [ServerConfig.offlineAfterMillis]. */
        var shownOnline = true
    }

    private class Lobby(val id: String, val mode: GameMode) {
        var length = Rules.DEFAULT_LENGTH
        var rev = 0L
        val players = TreeMap<Int, Player>()

        /**
         * Who opened it, or after they have gone for good the lowest-numbered player left.
         * A host whose connection has only dropped keeps it while their seat is held.
         */
        var host = 1

        /** One and Done 2.0: the round's saved words, whose letters are out of play. */
        var saved: List<String> = emptyList()
        val done: Boolean get() = mode == GameMode.ONE_AND_DONE && saved.size >= Rules.ONE_AND_DONE_WORDS

        /** Called whenever a seat is given up, so the lobby is never left without a host. */
        fun seatGone(slot: Int) {
            if (slot == host && players.isNotEmpty()) host = players.firstKey()
        }

        // The last lookup, kept because many changes -- a repeated letter, a reconnect --
        // do not change what it would return. Keyed by each player's letters as well as
        // everyone's, since who has which letters decides the order.
        var wordsKey: List<Int> = emptyList()
        var words = Matches.NONE

        /**
         * The word on everyone's screen: the first one found, held while more letters come
         * in, so a better word turning up does not snatch away one the team may already be
         * spelling. Let go when a player clears their letters, when the length changes, or
         * when nobody has any letters left; the next word found is then held in its place.
         */
        var held: String? = null

        fun freeSlot(): Int? = (1..Rules.MAX_PLAYERS).firstOrNull { it !in players }
    }

    private class Seat(val lobby: Lobby, val player: Player)

    private val lock = Any()
    /** Insertion-ordered, so the list can be shown newest first. */
    private val lobbies = LinkedHashMap<String, Lobby>()
    private val seats = HashMap<Peer, Seat>()
    private val browsers = LinkedHashSet<Peer>()
    private var lastLobbyList: String? = null

    val lobbyCount: Int get() = synchronized(lock) { lobbies.size }

    fun onMessage(peer: Peer, message: ClientMessage) {
        if (message is ClientMessage.Ping) {
            peer.send(Protocol.encode(ServerMessage.Pong(message.at)))
            return
        }
        synchronized(lock) {
            when (message) {
                ClientMessage.Browse -> browse(peer)
                is ClientMessage.Host -> host(peer, message.mode)
                is ClientMessage.Join -> join(peer, message.lobby)
                is ClientMessage.Resume -> resume(peer, message)
                ClientMessage.Leave -> leave(peer)
                is ClientMessage.SetLetters -> change(peer, message.seq) { lobby, player ->
                    // Clearing -- Clear all, or the last letter backspaced away -- lets the
                    // held word go.
                    if (player.letters.isNotEmpty() && message.letters.isEmpty()) lobby.held = null
                    player.letters = message.letters
                }
                is ClientMessage.SetLength -> change(peer, message.seq) { lobby, _ ->
                    if (message.length != lobby.length) lobby.held = null
                    lobby.length = message.length
                }
                is ClientMessage.SetSaved -> save(peer, message)
                is ClientMessage.Ping -> Unit
            }
        }
    }

    /** The connection has gone, however it went. The seat is held for the grace period. */
    fun onClose(peer: Peer) = synchronized(lock) {
        browsers.remove(peer)
        val seat = seats.remove(peer) ?: return
        if (seat.player.peer === peer) {
            seat.player.peer = null
            seat.player.droppedAt = clock()
        }
    }

    /**
     * Shows dropped players as offline once they have been gone a moment, and gives up their
     * seats once they have been gone the grace period. Called a few times a second.
     */
    fun sweep() = synchronized(lock) {
        val now = clock()
        var listChanged = false
        val iterator = lobbies.values.iterator()
        while (iterator.hasNext()) {
            val lobby = iterator.next()
            var changed = false
            val players = lobby.players.values.iterator()
            while (players.hasNext()) {
                val player = players.next()
                if (player.peer != null) continue
                val gone = now - player.droppedAt
                if (gone >= config.graceMillis) {
                    players.remove()
                    lobby.seatGone(player.slot)
                    changed = true
                    listChanged = true
                    log.info("lobby {}: player {} timed out", lobby.id, player.slot)
                } else if (player.shownOnline && gone >= config.offlineAfterMillis) {
                    player.shownOnline = false
                    changed = true
                }
            }
            if (lobby.players.isEmpty()) {
                iterator.remove()
                log.info("lobby {} closed, {} open", lobby.id, lobbies.size)
            } else if (changed) {
                broadcast(lobby)
            }
        }
        if (listChanged) publishLobbies()
    }

    // --- Messages ----------------------------------------------------------

    private fun browse(peer: Peer) {
        if (peer in seats) return error(peer, ErrorCode.ALREADY_IN_LOBBY, "leave the lobby first")
        browsers.add(peer)
        peer.send(lobbyList())
    }

    private fun host(peer: Peer, mode: GameMode) {
        if (peer in seats) return error(peer, ErrorCode.ALREADY_IN_LOBBY, "leave the lobby first")
        if (lobbies.size >= config.maxLobbies) return error(peer, ErrorCode.SERVER_FULL, "too many lobbies open")
        var id: String
        do {
            id = String(CharArray(LobbyCode.LENGTH) { LobbyCode.ALPHABET[random.nextInt(LobbyCode.ALPHABET.length)] })
        } while (id in lobbies)
        val lobby = Lobby(id, mode)
        lobbies[id] = lobby
        log.info("lobby {} opened ({}), {} open", id, mode, lobbies.size)
        seat(peer, lobby, 1)
    }

    private fun join(peer: Peer, id: String) {
        seats[peer]?.let { seat ->
            // Asked again on the same connection, most likely because the answer was slow:
            // say it again rather than fail.
            if (seat.lobby.id == id) return welcome(peer, seat)
            return error(peer, ErrorCode.ALREADY_IN_LOBBY, "leave the lobby first")
        }
        val lobby = lobbies[id] ?: return error(peer, ErrorCode.LOBBY_NOT_FOUND, "lobby $id has closed")
        val slot = lobby.freeSlot() ?: return error(peer, ErrorCode.LOBBY_FULL, "lobby $id is full")
        seat(peer, lobby, slot)
    }

    private fun resume(peer: Peer, message: ClientMessage.Resume) {
        if (peer in seats) return error(peer, ErrorCode.ALREADY_IN_LOBBY, "leave the lobby first")
        val lobby = lobbies[message.lobby]
            ?: return error(peer, ErrorCode.LOBBY_NOT_FOUND, "lobby ${message.lobby} has closed")
        val player = lobby.players[message.player]
        if (player == null || !Token.matches(player.token, message.token)) {
            return error(peer, ErrorCode.RESUME_FAILED, "that seat has gone")
        }
        // The old connection may not be dead yet as far as the server knows -- a phone that
        // walked out of Wi-Fi range leaves a socket that will take a while to time out.
        // The one presenting the token now is the one that counts.
        player.peer?.let { old ->
            seats.remove(old)
            old.close(CLOSE_REPLACED, "replaced by a newer connection")
        }
        browsers.remove(peer)
        player.peer = peer
        player.shownOnline = true
        val seat = Seat(lobby, player)
        seats[peer] = seat
        welcome(peer, seat)
        broadcast(lobby)
    }

    private fun leave(peer: Peer) {
        val seat = seats.remove(peer) ?: return peer.send(Protocol.encode(ServerMessage.Left))
        val lobby = seat.lobby
        lobby.players.remove(seat.player.slot)
        peer.send(Protocol.encode(ServerMessage.Left))
        lobby.seatGone(seat.player.slot)
        if (lobby.players.isEmpty()) {
            lobbies.remove(lobby.id)
            log.info("lobby {} closed, {} open", lobby.id, lobbies.size)
        } else {
            broadcast(lobby)
        }
        publishLobbies()
    }

    /**
     * One and Done 2.0: the host sets the round's saved words. Each must be a real word, and
     * no letter may appear twice across them ([ClientMessage.problem] has checked that much).
     * The held word is let go, so the next one found avoids the saved words' letters.
     */
    private fun save(peer: Peer, message: ClientMessage.SetSaved) {
        val seat = seats[peer] ?: return error(peer, ErrorCode.NOT_IN_LOBBY, "join a lobby first")
        if (seat.lobby.mode != GameMode.ONE_AND_DONE) {
            return error(peer, ErrorCode.BAD_REQUEST, "this lobby does not save words")
        }
        if (seat.player.slot != seat.lobby.host) return error(peer, ErrorCode.NOT_HOST, "only the host saves words")
        if (!message.words.all { lexicon.contains(it) }) return error(peer, ErrorCode.BAD_REQUEST, "not a word")
        change(peer, message.seq) { lobby, _ ->
            if (message.words != lobby.saved) {
                lobby.saved = message.words
                lobby.held = null
                log.info("lobby {}: {} saved", lobby.id, message.words.size)
            }
        }
    }

    /**
     * Applies a numbered change from a seated player, unless a later one of theirs is already
     * in -- a change that arrives late, or arrives again after a reconnect, is dropped.
     */
    private inline fun change(peer: Peer, seq: Long, apply: (Lobby, Player) -> Unit) {
        val seat = seats[peer] ?: return error(peer, ErrorCode.NOT_IN_LOBBY, "join a lobby first")
        if (seq <= seat.player.lastSeq) return
        val lengthBefore = seat.lobby.length
        seat.player.lastSeq = seq
        apply(seat.lobby, seat.player)
        broadcast(seat.lobby)
        if (seat.lobby.length != lengthBefore) publishLobbies()
    }

    // --- Seating -----------------------------------------------------------

    private fun seat(peer: Peer, lobby: Lobby, slot: Int) {
        val player = Player(slot, newToken())
        player.peer = peer
        lobby.players[slot] = player
        browsers.remove(peer)
        val seat = Seat(lobby, player)
        seats[peer] = seat
        log.info("lobby {}: player {} joined, {} seated", lobby.id, slot, lobby.players.size)
        welcome(peer, seat)
        broadcast(lobby)
        publishLobbies()
    }

    private fun welcome(peer: Peer, seat: Seat) {
        peer.send(
            Protocol.encode(
                ServerMessage.Joined(seat.lobby.id, seat.player.slot, seat.player.token, seat.player.lastSeq),
            ),
        )
        peer.send(stateOf(seat.lobby))
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // --- Telling people ----------------------------------------------------

    /** Sends the lobby's state to everyone connected to it, encoded once. */
    private fun broadcast(lobby: Lobby) {
        lobby.rev++
        val text = stateOf(lobby)
        for (player in lobby.players.values) player.peer?.send(text)
    }

    private fun stateOf(lobby: Lobby): String {
        val each = lobby.players.values.map { LetterMask.of(it.letters) }.toIntArray()
        val everyone = each.fold(0) { m, p -> m or p }
        val used = OneAndDone.used(lobby.saved)
        val key = each.toList() + lobby.length + used + (if (lobby.done) 1 else 0)
        if (key != lobby.wordsKey) {
            lobby.words = when {
                lobby.done -> Matches.NONE
                lobby.mode == GameMode.ONE_AND_DONE -> lexicon.findOneAndDone(everyone, lobby.length, used, players = each)
                else -> lexicon.find(everyone, lobby.length, players = each)
            }
            lobby.wordsKey = key
        }
        if (everyone == 0 || lobby.done) lobby.held = null
        if (lobby.held == null) lobby.held = lobby.words.words.firstOrNull()
        val held = lobby.held
        return Protocol.encode(
            ServerMessage.State(
                lobby = lobby.id,
                rev = lobby.rev,
                length = lobby.length,
                players = lobby.players.values.map { PlayerState(it.slot, it.letters, it.shownOnline, it.lastSeq) },
                // The held word first even in the list, so a phone that predates [word] and
                // shows the list's first shows the same word as everyone else.
                words = if (held == null) {
                    lobby.words.words
                } else {
                    (listOf(held) + (lobby.words.words - held)).take(Rules.MAX_WORDS)
                },
                total = lobby.words.total,
                word = held,
                mode = lobby.mode,
                host = lobby.host,
                saved = lobby.saved,
            ),
        )
    }

    private fun lobbyList(): String = Protocol.encode(
        ServerMessage.Lobbies(
            lobbies.values.reversed().take(MAX_LISTED).map { LobbySummary(it.id, it.players.size, it.length, it.mode) },
        ),
    )

    /** Tells everyone browsing, if what they would see has changed. */
    private fun publishLobbies() {
        val text = lobbyList()
        if (text == lastLobbyList) return
        lastLobbyList = text
        for (peer in browsers) peer.send(text)
    }

    private fun error(peer: Peer, code: String, message: String) {
        peer.send(Protocol.encode(ServerMessage.Error(code, message)))
    }

    companion object {
        /** Private-use WebSocket close code: this seat was taken back by a newer connection. */
        const val CLOSE_REPLACED: Short = 4000

        private const val MAX_LISTED = 50
        private val log = LoggerFactory.getLogger(LobbyManager::class.java)
    }
}
