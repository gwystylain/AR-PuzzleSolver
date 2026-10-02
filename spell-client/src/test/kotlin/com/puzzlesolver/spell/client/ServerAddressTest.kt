package com.puzzlesolver.spell.client

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAddressTest {

    @Test
    fun `however it is typed, it comes out as one secure base address`() {
        for (typed in listOf(
            "spell.example.org",
            " spell.example.org ",
            "wss://spell.example.org",
            "wss://spell.example.org/",
            "WSS://Spell.Example.org",
            "https://spell.example.org",
            "wss://spell.example.org/v1/ws",
            "wss://spell.example.org:443",
        )) {
            assertEquals("wss://spell.example.org", ServerAddress.parse(typed), typed)
        }
    }

    @Test
    fun `a port and a path prefix are kept`() {
        assertEquals("wss://nas.lan:8443", ServerAddress.parse("nas.lan:8443"))
        assertEquals("wss://example.org/spell", ServerAddress.parse("wss://example.org/spell/v1/ws"))
        assertEquals("wss://example.org/v1/ws", ServerAddress.endpoint("wss://example.org"))
        assertEquals("wss://example.org/spell/v1/ws", ServerAddress.endpoint("wss://example.org/spell"))
    }

    @Test
    fun `cleartext only when allowed`() {
        assertNull(ServerAddress.parse("ws://192.168.1.10:8080"))
        assertNull(ServerAddress.parse("http://192.168.1.10:8080"))
        assertEquals("ws://192.168.1.10:8080", ServerAddress.parse("ws://192.168.1.10:8080", allowCleartext = true))
    }

    @Test
    fun `cleartext to the phone itself is always allowed, since it never leaves the phone`() {
        assertEquals("ws://localhost:8080", ServerAddress.parse("ws://localhost:8080"))
        assertEquals("ws://127.0.0.1:8080", ServerAddress.parse("ws://127.0.0.1:8080"))
        assertNull(ServerAddress.parse("ws://localhost.evil.example:8080"))
        assertNull(ServerAddress.parse("ws://127.0.0.2:8080"))
    }

    @Test
    fun `nonsense is refused`() {
        for (typed in listOf("", "   ", "ftp://example.org", "wss://", "wss://exa mple.org", "wss://user:pw@example.org", "wss://example.org/?a=1")) {
            assertNull(ServerAddress.parse(typed), typed)
        }
    }
}
