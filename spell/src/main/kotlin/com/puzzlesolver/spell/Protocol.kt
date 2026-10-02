package com.puzzlesolver.spell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The messages between a phone and the lobby server: JSON text frames over one WebSocket,
 * tagged by `t`.
 *
 * The design is about two things, latency and dropped connections:
 *
 * - **The server sends whole state, never diffs.** A lobby is five short strings and a
 *   number, so a full snapshot costs nothing, and it means a phone that missed messages
 *   while its connection was down is correct again after the first one it receives.
 * - **A phone sends whole values, numbered.** "My letters are now TOP" rather than "add P":
 *   resending one after a reconnect is harmless, and the number ([ClientMessage.SetLetters.seq])
 *   lets the server drop one that arrives late and lets the phone see, from the `ack` echoed
 *   back in [PlayerState], which of its changes the words on screen already include.
 */
object Protocol {
    /**
     * The WebSocket path. The version is in it so a future protocol can be served
     * alongside this one rather than breaking every phone not yet updated.
     */
    const val PATH = "/v1/ws"

    val json: Json = Json {
        classDiscriminator = "t"
        // New fields can be added to either side without breaking the other.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    fun encode(message: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), message)

    /** Throws on malformed JSON or an unknown `t`; check the result with [ClientMessage.problem]. */
    fun decodeClient(text: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), text)

    fun decodeServer(text: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), text)
}

@Serializable
sealed interface ClientMessage {

    /** Send me the open lobbies, now and whenever they change, until I join one. */
    @Serializable
    @SerialName("browse")
    data object Browse : ClientMessage

    /** Open a lobby and seat me in it. */
    @Serializable
    @SerialName("host")
    data object Host : ClientMessage

    @Serializable
    @SerialName("join")
    data class Join(val lobby: String) : ClientMessage

    /** Give me back the seat I had before my connection dropped. */
    @Serializable
    @SerialName("resume")
    data class Resume(val lobby: String, val player: Int, val token: String) : ClientMessage

    @Serializable
    @SerialName("leave")
    data object Leave : ClientMessage

    /**
     * My letters are now [letters]. [seq] numbers every change a phone makes, letters and
     * length alike, and only ever goes up.
     */
    @Serializable
    @SerialName("letters")
    data class SetLetters(val seq: Long, val letters: String) : ClientMessage

    /** Everyone's word length is now [length]. */
    @Serializable
    @SerialName("length")
    data class SetLength(val seq: Long, val length: Int) : ClientMessage

    /** Echoed straight back as [ServerMessage.Pong], to time the round trip and prove the link is alive. */
    @Serializable
    @SerialName("ping")
    data class Ping(val at: Long) : ClientMessage

    /** Why the server should refuse this message, or null if it is well formed. */
    fun problem(): String? = when (this) {
        Browse, Host, Leave, is Ping -> null
        is Join -> if (LobbyCode.isValid(lobby)) null else "bad lobby code"
        is Resume -> when {
            !LobbyCode.isValid(lobby) -> "bad lobby code"
            !Rules.isValidPlayer(player) -> "bad player"
            !Token.isValid(token) -> "bad token"
            else -> null
        }
        is SetLetters -> when {
            seq < 0 -> "bad seq"
            !Rules.isValidLetters(letters) -> "letters must be at most ${Rules.MAX_LETTERS} of A-Z"
            else -> null
        }
        is SetLength -> when {
            seq < 0 -> "bad seq"
            !Rules.isValidLength(length) -> "length must be ${Rules.MIN_LENGTH}-${Rules.MAX_LENGTH}"
            else -> null
        }
    }
}

@Serializable
sealed interface ServerMessage {

    @Serializable
    @SerialName("lobbies")
    data class Lobbies(val lobbies: List<LobbySummary>) : ServerMessage

    /**
     * You are [player] in [lobby]. [token] takes the seat back after a dropped connection;
     * [ack] is the last change of yours the server has, so a phone that lost count can
     * carry on numbering above it.
     */
    @Serializable
    @SerialName("joined")
    data class Joined(val lobby: String, val player: Int, val token: String, val ack: Long) : ServerMessage

    /**
     * The whole lobby, sent to everyone in it on every change. [words] are the best of
     * [total] words of [length] letters spelt from everyone's letters together, best first
     * as [Lexicon.find] ranks them: fewest different letters, then the most players.
     */
    @Serializable
    @SerialName("state")
    data class State(
        val lobby: String,
        val rev: Long,
        val length: Int,
        val players: List<PlayerState>,
        val words: List<String>,
        val total: Int,
    ) : ServerMessage

    /** You have left; you are browsing again. */
    @Serializable
    @SerialName("left")
    data object Left : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val at: Long) : ServerMessage
}

@Serializable
data class LobbySummary(val id: String, val players: Int, val length: Int)

/**
 * One seat. [online] goes false a moment after the player's connection drops, not at once,
 * so a phone switching from Wi-Fi to cellular does not flicker on everyone else's screen.
 * [ack] is the last of their changes applied.
 */
@Serializable
data class PlayerState(val id: Int, val letters: String, val online: Boolean, val ack: Long)

/** The `code` of a [ServerMessage.Error]. */
object ErrorCode {
    const val BAD_REQUEST = "bad_request"
    const val RATE_LIMITED = "rate_limited"
    const val SERVER_FULL = "server_full"
    const val LOBBY_NOT_FOUND = "lobby_not_found"
    const val LOBBY_FULL = "lobby_full"
    /** The seat has gone -- held too long without a connection -- or the token is wrong. */
    const val RESUME_FAILED = "resume_failed"
    const val NOT_IN_LOBBY = "not_in_lobby"
    const val ALREADY_IN_LOBBY = "already_in_lobby"
}
