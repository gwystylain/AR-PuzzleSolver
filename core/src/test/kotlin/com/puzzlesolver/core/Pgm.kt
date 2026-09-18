package com.puzzlesolver.core

import com.puzzlesolver.core.image.GrayImage
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reads binary PGMs, plain or gzipped, into a [GrayImage].
 *
 * One loader for the three terminal tools that each used to carry their own copy. The
 * format is what `TerminalRecorder` writes on the phone and what the fixtures under
 * `resources/` are, so a frame captured in the room, a fixture in the test tree and a
 * directory being replayed all come through the same twenty lines -- and a capture pulled
 * off the phone needs no unpacking step before any of them will take it.
 */
object Pgm {

    fun resource(name: String): GrayImage {
        val stream = Pgm::class.java.classLoader!!.getResourceAsStream(name)
            ?: error("fixture $name is missing from the test resources")
        return read(stream)
    }

    fun read(file: File): GrayImage = read(file.inputStream())

    /** Sniffs the gzip magic rather than trusting the name, which costs two bytes. */
    fun read(raw: InputStream): GrayImage {
        val buffered = BufferedInputStream(raw, 1 shl 16)
        buffered.mark(2)
        val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val input = if (gzipped) GZIPInputStream(buffered) else buffered
        input.use { return parse(it) }
    }

    private fun parse(input: InputStream): GrayImage {
        // PGM: "P5", width height, maxval, then raw bytes. Comments start with '#'.
        fun token(): String {
            val sb = StringBuilder()
            var c = input.read()
            while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = input.read()
            if (c == '#'.code) {
                while (c != '\n'.code && c > 0) c = input.read()
                return token()
            }
            while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                sb.append(c.toChar())
                c = input.read()
            }
            return sb.toString()
        }
        require(token() == "P5") { "not a binary PGM" }
        val w = token().toInt()
        val h = token().toInt()
        token()                                     // maxval
        val data = ByteArray(w * h)
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n <= 0) break
            read += n
        }
        require(read == data.size) { "short PGM: $read of ${data.size} bytes" }
        return GrayImage(w, h, data)
    }
}
