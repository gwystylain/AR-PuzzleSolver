package com.puzzlesolver.spell

import java.security.MessageDigest

/**
 * The limits of a Spellinator game, enforced by the server and respected by the phone.
 *
 * The phone holds to them so the player is never left typing into something that will be
 * refused; the server holds to them because the phone is not the only thing that can open
 * a socket to it.
 */
object Rules {
    const val MAX_PLAYERS = 5

    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 8
    const val DEFAULT_LENGTH = 5
    val LENGTHS: IntRange = MIN_LENGTH..MAX_LENGTH

    /** Letters one player may enter. Far more than a wall shows one person. */
    const val MAX_LETTERS = 16

    /**
     * Words sent with each update; the total is always sent. A handful of common letters
     * can match thousands of eight-letter words, and nobody reads past the first few
     * hundred, but every update carries the list -- so it is what keeps an update small.
     */
    const val MAX_WORDS = 400

    /**
     * The largest message the server reads. Every message a phone sends is well under
     * 200 bytes; anything near this is not from the app.
     */
    const val MAX_FRAME_BYTES = 1024

    fun isValidLetters(letters: String): Boolean =
        letters.length <= MAX_LETTERS && letters.all { it in 'A'..'Z' }

    fun isValidLength(length: Int): Boolean = length in LENGTHS

    fun isValidPlayer(player: Int): Boolean = player in 1..MAX_PLAYERS
}

/**
 * A lobby's code: four consonants, e.g. `KQZT`.
 *
 * No vowels, so a code never spells anything, and none of the letters that read as digits.
 * It is a name to find a lobby by, not a secret: every lobby is listed to anyone browsing.
 * The secret is the [Token].
 */
object LobbyCode {
    const val ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"
    const val LENGTH = 4

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

/**
 * The proof that a seat in a lobby is yours, handed out when you take it and shown again to
 * take it back after a dropped connection.
 *
 * 128 random bits, base64url without padding. Player numbers are public -- everyone in the
 * lobby sees them -- so without this, anyone could reconnect as anyone.
 */
object Token {
    const val LENGTH = 22
    private val pattern = Regex("^[A-Za-z0-9_-]{$LENGTH}$")

    fun isValid(token: String): Boolean = pattern.matches(token)

    /** Constant-time, so how much of a guess was right cannot be timed. */
    fun matches(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}

/** A set of letters as 26 bits, A in the lowest. Letters may be reused, so a set is all a word needs. */
object LetterMask {
    fun of(letters: CharSequence): Int {
        var mask = 0
        for (c in letters) if (c in 'A'..'Z') mask = mask or (1 shl (c - 'A'))
        return mask
    }

    fun contains(mask: Int, c: Char): Boolean = c in 'A'..'Z' && mask and (1 shl (c - 'A')) != 0
}
