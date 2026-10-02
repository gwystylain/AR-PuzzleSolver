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
 * well under a millisecond and needs no index. The buckets are sorted, so the matches come
 * out in alphabetical order.
 */
class Lexicon private constructor(private val buckets: Map<Int, Bucket>) {

    private class Bucket(val words: Array<String>, val masks: IntArray)

    /** Words held, across every length. */
    val size: Int = buckets.values.sumOf { it.words.size }

    fun count(length: Int): Int = buckets[length]?.words?.size ?: 0

    /**
     * Every word of [length] spelt only from the letters in [available] (a [LetterMask]),
     * alphabetically. At most [limit] are listed; [Matches.total] counts them all.
     */
    fun find(available: Int, length: Int, limit: Int = Rules.MAX_WORDS): Matches {
        val bucket = buckets[length] ?: return Matches.NONE
        if (available == 0) return Matches.NONE
        val outside = available.inv()
        val words = ArrayList<String>(minOf(limit, 64))
        var total = 0
        val masks = bucket.masks
        for (i in masks.indices) {
            if (masks[i] and outside == 0) {
                if (total < limit) words.add(bucket.words[i])
                total++
            }
        }
        return Matches(words, total)
    }

    companion object {
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
