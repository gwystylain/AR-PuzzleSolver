package com.puzzlesolver.spell.client

import com.puzzlesolver.spell.Protocol
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Turning what someone types as the server into the URL the app connects to. */
object ServerAddress {

    /**
     * `spell.example.org`, `wss://spell.example.org/` and the full `.../v1/ws` all become
     * `wss://spell.example.org`. Null if it is not a usable address.
     *
     * Plain `ws://` is refused unless [allowCleartext]: letters are not secret, but the token
     * that holds a seat is, and on a venue's Wi-Fi anything unencrypted is readable by the
     * table next to you. Only a debug build allows it, for a server on the same desk -- or
     * any build, to the phone itself (`localhost`), which never leaves the phone and is how
     * `adb reverse` reaches a server on a laptop.
     */
    fun parse(input: String, allowCleartext: Boolean = false): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val (secure, rest) = when {
            lower.startsWith("wss://") -> true to text.substring(6)
            lower.startsWith("https://") -> true to text.substring(8)
            lower.startsWith("ws://") -> false to text.substring(5)
            lower.startsWith("http://") -> false to text.substring(7)
            "://" in text -> return null
            else -> true to text
        }
        val url = ((if (secure) "https://" else "http://") + rest).toHttpUrlOrNull() ?: return null
        if (!secure && !allowCleartext && url.host !in LOOPBACK) return null
        if (url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return null
        }
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        val path = url.encodedPath.removeSuffix("/").removeSuffix(Protocol.PATH).removeSuffix("/")
        return "${if (secure) "wss" else "ws"}://$host$port$path"
    }

    private val LOOPBACK = setOf("localhost", "127.0.0.1")

    /** The WebSocket URL for a base address from [parse]. */
    fun endpoint(base: String): String = base.trimEnd('/') + Protocol.PATH

    /** What to show for a base address: just the host for the usual secure one. */
    fun describe(base: String): String = base.removePrefix("wss://")
}

/**
 * The HTTP client Spellinator connects with.
 *
 * - **No Nagle.** Every message here is a few dozen bytes and wants to leave now. With
 *   Nagle's algorithm on, a small write waits for the previous one's ACK, and against a
 *   peer that delays its ACKs that is up to 200 ms on a keypress -- the single largest
 *   latency in the whole path, and it costs nothing to turn off.
 * - **No read timeout, no OkHttp pings.** Liveness is judged by the session itself
 *   ([SpellSession.Timing]), which pings every two seconds and gives up on a link that has
 *   gone quiet far sooner than a read timeout safe for a slow handshake would.
 */
fun spellHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(NoDelaySocketFactory(SocketFactory.getDefault()))
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .pingInterval(0, TimeUnit.MILLISECONDS)
    .build()

/** Sets TCP_NODELAY on every socket made, before it connects; TLS is layered on top. */
class NoDelaySocketFactory(private val delegate: SocketFactory) : SocketFactory() {
    private fun Socket.noDelay(): Socket = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = delegate.createSocket().noDelay()
    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).noDelay()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).noDelay()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).noDelay()
}
