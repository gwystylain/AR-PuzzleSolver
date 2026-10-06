package com.puzzlesolver.spell

import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * The word list, split by length, with each word's letters kept as a bitmask.
 *
 * Letters may be reused, so a word can be spelt from the players' letters exactly when
 * every letter in it is one of theirs -- one AND against a 26-bit mask per word. Even the
 * longest bucket, eight letters, is about 42,000 words, so a lookup is a straight scan of
 * well under a millisecond and needs no index.
 *
 * The matches come best first ([find] says what best is). The ranking is a handful of
 * small integers, so the best are placed by counting how many words fall at each rank and
 * then dealing them out in a second scan -- no sort -- and since the buckets are sorted,
 * words of equal rank stay alphabetical.
 */
class Lexicon private constructor(private val buckets: Map<Int, Bucket>) {

    private class Bucket(val words: Array<String>, val masks: IntArray)

    /** Words held, across every length. */
    val size: Int = buckets.values.sumOf { it.words.size }

    fun count(length: Int): Int = buckets[length]?.words?.size ?: 0

    /**
     * Every word of [length] spelt only from the letters in [available] (a [LetterMask]),
     * best first:
     *
     * 1. the fewest different letters -- fewer letters for the team to find;
     * 2. then letters from the most [players] (one [LetterMask] each) -- the word spread
     *    over more of the team;
     * 3. then alphabetical.
     *
     * At most [limit] are listed; [Matches.total] counts them all.
     */
    fun find(available: Int, length: Int, limit: Int = Rules.MAX_WORDS, players: IntArray = IntArray(0)): Matches {
        val bucket = buckets[length] ?: return Matches.NONE
        if (available == 0) return Matches.NONE
        val outside = available.inv()
        val masks = bucket.masks

        val perRank = IntArray(RANKS)
        var total = 0
        for (mask in masks) {
            if (mask and outside != 0) continue
            perRank[rank(mask, players)]++
            total++
        }
        if (total == 0) return Matches.NONE

        // Where each rank's words start in the full ranking; only the first [limit] are kept.
        val next = IntArray(RANKS)
        var filled = 0
        for (r in 0 until RANKS) {
            next[r] = filled
            filled += perRank[r]
        }
        val kept = arrayOfNulls<String>(minOf(limit, total))
        for (i in masks.indices) {
            val mask = masks[i]
            if (mask and outside != 0) continue
            val at = next[rank(mask, players)]++
            if (at < kept.size) kept[at] = bucket.words[i]
        }
        return Matches(kept.map { it!! }, total)
    }

    /**
     * For a One and Done 2.0 round: words of [length] spelt from [available], with no letter
     * twice and none of [used] (the letters of the round's saved words), best first:
     *
     * 1. the rarest letters -- the round wants three words with no letter in common, and a
     *    letter spent now is gone for the waves to come, so the word to take is the one
     *    whose letters the rest of the word list needs least. A letter's cost is how many
     *    words of [length] contain it among those still possible later (no repeated letter,
     *    none of [used]); a word's is the sum over its letters. Q, J, X and Z are cheap; E,
     *    S and A dear;
     * 2. then letters from the most [players];
     * 3. then alphabetical.
     *
     * At most [limit] are listed; [Matches.total] counts them all.
     */
    fun findOneAndDone(
        available: Int,
        length: Int,
        used: Int,
        limit: Int = Rules.MAX_WORDS,
        players: IntArray = IntArray(0),
    ): Matches {
        val bucket = buckets[length] ?: return Matches.NONE
        val usable = available and used.inv()
        if (usable == 0) return Matches.NONE
        val masks = bucket.masks

        val cost = IntArray(26)
        for (mask in masks) {
            if (mask and used != 0 || Integer.bitCount(mask) != length) continue
            var m = mask
            while (m != 0) {
                cost[Integer.numberOfTrailingZeros(m)]++
                m = m and (m - 1)
            }
        }

        // One key per candidate, packed so a plain sort orders them: cost, then players
        // left out, then position in the bucket, which is alphabetical. The bucket index
        // fits 16 bits (the largest has 42,341 words) and players left out 3.
        val outside = usable.inv()
        var keys = LongArray(256)
        var n = 0
        for (i in masks.indices) {
            val mask = masks[i]
            if (mask and outside != 0 || Integer.bitCount(mask) != length) continue
            var sum = 0
            var m = mask
            while (m != 0) {
                sum += cost[Integer.numberOfTrailingZeros(m)]
                m = m and (m - 1)
            }
            var missed = 0
            for (p in players) if (p and mask == 0) missed++
            if (n == keys.size) keys = keys.copyOf(n * 2)
            keys[n++] = (sum.toLong() shl 19) or (minOf(missed, Rules.MAX_PLAYERS).toLong() shl 16) or i.toLong()
        }
        if (n == 0) return Matches.NONE
        keys.sort(0, n)
        return Matches(List(minOf(limit, n)) { bucket.words[(keys[it] and 0xFFFF).toInt()] }, n)
    }

    /** Whether [word] is in the list (at a length it holds). */
    fun contains(word: String): Boolean {
        val bucket = buckets[word.length] ?: return false
        return bucket.words.binarySearch(word) >= 0
    }

    /** Lower is better: different letters first, then how many players the word leaves out. */
    private fun rank(mask: Int, players: IntArray): Int {
        var missed = 0
        for (p in players) if (p and mask == 0) missed++
        return Integer.bitCount(mask) * (Rules.MAX_PLAYERS + 1) + minOf(missed, Rules.MAX_PLAYERS)
    }

    companion object {
        /** Every rank [rank] can give: up to 26 different letters, each with 0 to 5 players left out. */
        private const val RANKS = 27 * (Rules.MAX_PLAYERS + 1)

        /**
         * Reads a word list, one word per line.
         *
         * Only the first token of each line is taken, so a list that carries a definition
         * after each word (CSW21 as Zyzzyva exports it) reads the same as a bare one.
         * Lines that are not plain A-Z words of a length in [lengths] are skipped, and
         * duplicates are dropped.
         */
        fun read(reader: Reader, lengths: IntRange = Rules.LENGTHS): Lexicon {
            val byLength = HashMap<Int, MutableList<String>>()
            reader.buffered().useLines { lines ->
                for (line in lines) {
                    val word = line.trim().substringBefore(' ').substringBefore('\t').uppercase(Locale.ROOT)
                    if (word.length !in lengths || !word.all { it in 'A'..'Z' }) continue
                    byLength.getOrPut(word.length) { ArrayList() }.add(word)
                }
            }
            return Lexicon(
                byLength.mapValues { (_, list) ->
                    val words = list.distinct().sorted().toTypedArray()
                    Bucket(words, IntArray(words.size) { LetterMask.of(words[it]) })
                },
            )
        }

        fun load(path: Path, lengths: IntRange = Rules.LENGTHS): Lexicon =
            Files.newBufferedReader(path, Charsets.UTF_8).use { read(it, lengths) }

        fun of(vararg words: String): Lexicon = read(words.joinToString("\n").reader())
    }
}

/** The words that can be spelt, the first [words] of [total] of them. */
data class Matches(val words: List<String>, val total: Int) {
    companion object {
        val NONE = Matches(emptyList(), 0)
    }
}
