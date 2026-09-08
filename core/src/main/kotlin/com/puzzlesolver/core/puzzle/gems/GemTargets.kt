package com.puzzlesolver.core.puzzle.gems

import java.util.concurrent.atomic.AtomicReference

/**
 * The gems the user is hunting for: four slots, mirroring the four target buttons
 * above the wall in the room.
 *
 * Shared between the UI thread, which writes it as the user taps colours, and the
 * solver thread, which reads it. An immutable list behind an [AtomicReference] rather
 * than a lock: writes are rare and reads happen inside a board scan, so the thing
 * worth protecting is the reader never seeing a half-edited target -- a slot with an
 * outer colour from the old pattern and a middle colour from the new one would
 * highlight gems that match neither.
 *
 * [generation] is what lets the pipeline notice a change without polling the contents.
 * The engine only re-solves when new cells arrive, so a target edit has to announce
 * itself; a counter is enough and cannot miss an edit that lands between two reads.
 */
class GemTargets(slots: Int = DEFAULT_SLOTS) {

    private val state = AtomicReference(
        State(List(slots) { GemPattern.BLANK }, 0)
    )

    private class State(val patterns: List<GemPattern>, val generation: Int)

    val size: Int get() = state.get().patterns.size

    fun all(): List<GemPattern> = state.get().patterns

    operator fun get(slot: Int): GemPattern = state.get().patterns.getOrElse(slot) { GemPattern.BLANK }

    /** Bumped on every edit, so a reader can tell "same targets" from "same values". */
    val generation: Int get() = state.get().generation

    /** Targets that actually constrain anything; blank slots are not filters. */
    fun active(): List<GemPattern> = state.get().patterns.filter { !it.isBlank }

    fun set(slot: Int, pattern: GemPattern) {
        while (true) {
            val current = state.get()
            if (slot !in current.patterns.indices) return
            if (current.patterns[slot] == pattern) return
            val next = current.patterns.toMutableList().also { it[slot] = pattern }
            if (state.compareAndSet(current, State(next, current.generation + 1))) return
        }
    }

    fun clear(slot: Int) = set(slot, GemPattern.BLANK)

    fun clearAll() {
        while (true) {
            val current = state.get()
            if (current.patterns.all { it.isBlank }) return
            val next = List(current.patterns.size) { GemPattern.BLANK }
            if (state.compareAndSet(current, State(next, current.generation + 1))) return
        }
    }

    companion object {
        /** Four, because the room shows four targets above the wall. */
        const val DEFAULT_SLOTS = 4
    }
}
