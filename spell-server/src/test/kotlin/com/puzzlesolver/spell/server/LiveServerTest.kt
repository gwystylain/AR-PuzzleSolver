package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.client.Link
import com.puzzlesolver.spell.client.ServerAddress
import com.puzzlesolver.spell.client.SpellSession
import com.puzzlesolver.spell.client.SpellState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A smoke test of a deployed server, from wherever this runs: two connections, one lobby,
 * the brief's example, the held word, and the real round trip. Skipped unless pointed at a
 * server:
 *
 *     ./gradlew :spell-server:test --tests '*LiveServerTest*' -Dspell.live=spell.example.org
 *
 * It opens a lobby, which anyone browsing will see for the few seconds it is up.
 */
class LiveServerTest {

    @Test
    fun `the deployed server answers, finds words and relays a keypress`() {
        val target = System.getProperty("spell.live")
        assumeTrue("set -Dspell.live to run", !target.isNullOrBlank())
        val base = ServerAddress.parse(target!!, allowCleartext = true) ?: fail("not an address: $target")
        val a = SpellSession(base).apply { start() }
        val b = SpellSession(base).apply { start() }
        try {
            a.await("A connected") { it.link is Link.Online }
            a.host()
            val id = a.await("A hosting") { it.room?.seated == true }.room!!.lobby
            b.await("lobby listed") { st -> st.lobbies.any { it.id == id } }
            b.join(id)
            b.await("B seated") { it.room?.seated == true }

            a.setLength(3)
            a.type('T')
            a.type('O')
            val held = a.await("a word from T and O") { st -> st.room?.let { it.current && it.word != null } == true }.room!!.word
            // P makes better words than any from T and O alone; the first one found stays.
            b.type('P')
            val words = b.await("TOP") { st -> st.room?.let { it.current && "TOP" in it.words } == true }.room!!
            assertEquals(held, words.word, "the first word found is held")
            println("$base: lobby $id, held $held; ${words.total} three-letter words from T, O, P: ${words.words}")

            val samples = mutableListOf<Double>()
            runBlocking {
                for (c in "ABCDEFGHIJKL") {
                    Thread.sleep(150)
                    val seen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        b.state.first { st -> st.room?.players?.firstOrNull { it.id == 1 }?.letters?.endsWith(c) == true }
                        System.nanoTime()
                    }
                    val t0 = System.nanoTime()
                    a.type(c)
                    samples += ((withTimeoutOrNull(5_000) { seen.await() } ?: fail("keypress lost")) - t0) / 1e6
                }
            }
            samples.sort()
            println(
                "keypress on one connection to the other: median %.0f ms, worst %.0f ms; ping %s ms".format(
                    samples[samples.size / 2], samples.last(), (a.state.value.link as? Link.Online)?.rttMillis,
                ),
            )
            assertTrue(words.total > 0)
        } finally {
            a.leave()
            b.leave()
            Thread.sleep(300)
            a.close()
            b.close()
        }
    }

    private fun SpellSession.await(what: String, predicate: (SpellState) -> Boolean): SpellState =
        runBlocking { withTimeoutOrNull(15_000) { state.first(predicate) } }
            ?: fail("timed out waiting for $what; last state: ${state.value}")
}
