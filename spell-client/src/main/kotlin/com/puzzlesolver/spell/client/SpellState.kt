package com.puzzlesolver.spell.client

import com.puzzlesolver.spell.GameMode
import com.puzzlesolver.spell.LetterMask
import com.puzzlesolver.spell.OneAndDone
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.LobbySummary
import com.puzzlesolver.spell.PlayerState

/** Everything the Spellinator screen draws, published by [SpellSession]. */
data class SpellState(
    val link: Link = Link.Idle,
    /** Open lobbies, newest first, while browsing. */
    val lobbies: List<LobbySummary> = emptyList(),
    /** A host or join is waiting on the server. */
    val joining: Boolean = false,
    /** The lobby this phone is in, or is getting back into after a dropped connection. */
    val room: Room? = null,
    val notice: Notice? = null,
)

sealed interface Link {
    /** Not started, or stopped. */
    data object Idle : Link

    data object Connecting : Link

    /** [rttMillis] is smoothed, and null until the first reply to a ping. */
    data class Online(val rttMillis: Int?) : Link

    /** Lost, and [attempt] tries into getting it back. */
    data class Offline(val reason: String, val attempt: Int) : Link
}

data class Room(
    val lobby: String,
    /** This phone's player number; null for a moment while rejoining after losing the seat. */
    val me: Int?,
    /** This phone's letters as typed, ahead of the server's copy. */
    val letters: String,
    /** The word length as last chosen here, or as the server has it. */
    val length: Int,
    val players: List<PlayerState>,
    /**
     * The word on everyone's screen, held by the server until a player clears their letters
     * or the length changes; null when there is none.
     */
    val word: String?,
    val words: List<String>,
    val total: Int,
    /** Whether [words] already take in every change made on this phone. */
    val current: Boolean,
    /** Whether the server has this phone seated right now, as opposed to reconnecting. */
    val seated: Boolean,
    val mode: GameMode = GameMode.CLASSIC,
    /** The host's player number. */
    val host: Int = 0,
    /** One and Done 2.0: the round's saved words so far. */
    val saved: List<String> = emptyList(),
) {
    /** Every letter anyone in the lobby has, as a [LetterMask]. */
    val everyone: Int get() = players.fold(LetterMask.of(letters)) { m, p -> m or LetterMask.of(p.letters) }

    val isHost: Boolean get() = me != null && me == host

    /** One and Done 2.0: the letters the saved words have used up, as a [LetterMask]. */
    val used: Int get() = OneAndDone.used(saved)

    /** One and Done 2.0: all three words saved. */
    val done: Boolean get() = mode == GameMode.ONE_AND_DONE && saved.size >= Rules.ONE_AND_DONE_WORDS
}

/** Something to tell the player once, e.g. that their lobby has closed. */
data class Notice(val id: Long, val text: String)

/**
 * Where the seat is kept between runs of the app, so a phone that the system killed in the
 * background -- or that was restarted -- takes its seat back instead of losing it.
 */
interface SessionStore {
    data class Saved(
        val server: String,
        val lobby: String,
        val player: Int,
        val token: String,
        val letters: String,
        val lettersSeq: Long,
        val seq: Long,
    )

    fun load(): Saved?
    fun save(saved: Saved)
    fun clear()

    companion object {
        val NONE: SessionStore = object : SessionStore {
            override fun load(): Saved? = null
            override fun save(saved: Saved) = Unit
            override fun clear() = Unit
        }
    }
}
