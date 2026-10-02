package com.puzzlesolver.spell.server

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP relay between the client and the server that can be made to fail the ways a phone's
 * connection does:
 *
 * - [sever] closes every connection through it, as when the phone switches networks and
 *   the old one is torn down;
 * - [freeze] silently stops passing data on every connection through it while leaving the
 *   sockets open, as when the phone walks out of coverage -- nothing closes, nothing arrives,
 *   and only a timeout can tell;
 * - [target] can be changed, to stand in for the server being restarted.
 *
 * New connections after a [sever] or [freeze] pass normally.
 */
class FlakyProxy(@Volatile var target: Int) : Closeable {
    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val links = CopyOnWriteArrayList<Link>()
    /** Kept only so [close] can shut them; nothing passes through them any more. */
    private val frozen = CopyOnWriteArrayList<Link>()
    val port: Int get() = listener.localPort

    private class Link(val a: Socket, val b: Socket) {
        @Volatile var frozen = false
        fun close() {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    init {
        thread(isDaemon = true, name = "proxy-accept") {
            while (!listener.isClosed) {
                val client = try {
                    listener.accept()
                } catch (e: IOException) {
                    break
                }
                val upstream = try {
                    Socket(InetAddress.getLoopbackAddress(), target)
                } catch (e: IOException) {
                    client.close()
                    continue
                }
                client.tcpNoDelay = true
                upstream.tcpNoDelay = true
                val link = Link(client, upstream)
                links += link
                pump(link, client.getInputStream(), upstream.getOutputStream())
                pump(link, upstream.getInputStream(), client.getOutputStream())
            }
        }
    }

    private fun pump(link: Link, from: InputStream, to: OutputStream) = thread(isDaemon = true, name = "proxy-pump") {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                // Read and dropped, so the sender's TCP is satisfied and suspects nothing.
                if (link.frozen) continue
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } finally {
            if (!link.frozen) link.close()
            links.remove(link)
        }
    }

    fun sever() {
        for (link in links) link.close()
        links.clear()
    }

    fun freeze() {
        for (link in links) link.frozen = true
        frozen += links
        links.clear()
    }

    override fun close() {
        listener.close()
        sever()
        for (link in frozen) link.close()
    }
}
