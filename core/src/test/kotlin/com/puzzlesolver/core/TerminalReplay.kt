package com.puzzlesolver.core

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.GlyphClassifier
import com.puzzlesolver.core.puzzle.TemplateGlyphClassifier
import com.puzzlesolver.core.puzzle.terminal.DigitReader
import com.puzzlesolver.core.puzzle.terminal.DisplayDetector
import com.puzzlesolver.core.puzzle.terminal.TerminalDigits
import com.puzzlesolver.core.puzzle.terminal.TerminalScanner
import java.io.File
import org.junit.Test
import kotlin.math.sqrt

/**
 * Replays a directory of frames through the terminal reader and prints what the device's
 * heartbeat would have printed.
 *
 * Not part of the test run: it does nothing unless pointed at a directory of PGMs. It
 * exists because the app cannot replay ordinary footage -- `VideoFrameSource` decodes
 * straight to a `SurfaceTexture` and never shows the CPU a frame -- and yet the only
 * evidence a visit to the room brings back is the heartbeat's aggregate counts. This
 * closes that gap from the other end: extract the frames, run the real scanner over them
 * here, and compare `displays / numbers / unread` line for line with the log.
 *
 * ```
 * ./gradlew :core:test --tests '*TerminalReplay*' -Dterminal.frames=/path/to/frames
 * ```
 *
 * The second half is the part the heartbeat cannot give you: for every digit it prints
 * the correlation the winning template scored, so a run that reports displays as
 * illegible can be asked *how close* they came, and whether the floor is in the right
 * place or the glyph was never going to be read.
 */
class TerminalReplay {

    @Test
    fun `replay a directory of frames`() {
        val dir = System.getProperty("terminal.frames")
        if (dir == null) {
            println("terminal.frames not set; not replaying (this is the normal case)")
            return
        }
        val frames = framesIn(dir)

        val scanner = TerminalScanner()
        var totalDisplays = 0
        var totalNumbers = 0
        var totalUnread = 0
        var totalCleared = 0

        for (file in frames) {
            val frame = readPgm(file)
            scanner.scan(frame)                          // settle, as the device does
            val r = scanner.scan(frame)
            val cleared = r.displays.count { it.cleared }
            totalDisplays += r.displays.size
            totalNumbers += r.remaining
            totalUnread += r.unread
            totalCleared += cleared
            println(
                "%-14s displays=%-3d numbers=%-3d unread=%-3d cleared=%-3d settled=%-5s next=%-4s then=%-4s"
                    .format(
                        file.name, r.displays.size, r.remaining, r.unread, cleared, r.settled,
                        r.lowest?.text ?: "-", r.second?.text ?: "-",
                    )
            )
        }
        val lit = totalNumbers + totalUnread
        println(
            "\nTOTAL displays=%d numbers=%d unread=%d cleared=%d  ·  unread is %.0f%% of lit"
                .format(totalDisplays, totalNumbers, totalUnread, totalCleared, 100.0 * totalUnread / lit)
        )

        scoreDistribution(frames)
    }

    /**
     * Every PGM in the directory, plain or gzipped.
     *
     * Gzipped because that is how `TerminalRecorder` writes them: a capture pulled off
     * the phone is pointed at this as it stands, with no unpacking step to forget.
     */
    private fun framesIn(dir: String): List<File> =
        File(dir).listFiles { f -> f.name.endsWith(".pgm") || f.name.endsWith(".pgm.gz") }
            ?.sorted()
            ?: error("no frames in $dir")

    /**
     * Every digit's winning correlation, bucketed.
     *
     * The one question the device log cannot answer. A display is reported illegible when
     * any one of its three digits fails the floor, and that failure looks identical
     * whether the glyph scored 0.69 or 0.20 -- which are opposite problems. The first is a
     * threshold in the wrong place, the second is a glyph that never arrived.
     */
    private fun scoreDistribution(frames: List<File>) {
        val detector = DisplayDetector()
        val reader = DigitReader(object : GlyphClassifier {
            override fun classify(normalized: GrayImage): Pair<Int, Float> = -1 to 0f
        })
        val templates = TerminalDigits.templates
        val buckets = IntArray(11)
        var total = 0
        var blank = 0
        for (file in frames) {
            val frame = readPgm(file)
            for (box in detector.detect(frame)) {
                for (k in 0 until DigitReader.TILES) {
                    val glyph = reader.isolate(frame, box, k)
                    if (glyph == null) {
                        blank++
                        continue
                    }
                    var best = -1f
                    for (t in templates) {
                        val s = correlate(glyph, t)
                        if (s > best) best = s
                    }
                    total++
                    buckets[(best.coerceIn(0f, 0.999f) * 10).toInt()]++
                }
            }
        }
        println("\n--- winning correlation per digit, %d glyphs (%d tiles read as blank) ---".format(total, blank))
        for (b in buckets.indices.reversed()) {
            if (buckets[b] == 0) continue
            val lo = b / 10.0
            val mark = if (lo < TerminalDigits.MIN_CORRELATION) "  <- rejected" else ""
            println("  %.1f-%.1f  %6d  %5.2f%%%s".format(lo, lo + 0.1, buckets[b], 100.0 * buckets[b] / total, mark))
        }
        val rejected = (0 until (TerminalDigits.MIN_CORRELATION * 10).toInt()).sumOf { buckets[it] }
        println("  rejected by the %.2f floor: %d of %d = %.1f%% of digits"
            .format(TerminalDigits.MIN_CORRELATION, rejected, total, 100.0 * rejected / total))
    }

    private fun correlate(glyph: GrayImage, template: TemplateGlyphClassifier.Template): Float {
        val n = glyph.width * glyph.height
        var mean = 0f
        for (i in 0 until n) mean += (glyph.data[i].toInt() and 0xFF).toFloat()
        mean /= n
        var dot = 0f
        var acc = 0f
        for (i in 0 until n) {
            val v = (glyph.data[i].toInt() and 0xFF) - mean
            dot += v * template.values[i]
            acc += v * v
        }
        return dot / (sqrt(acc) * template.norm)
    }

    private fun readPgm(file: File): GrayImage = openMaybeGzipped(file).use { input ->
        fun token(): String {
            val sb = StringBuilder()
            var c = input.read()
            while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = input.read()
            if (c == '#'.code) {
                while (c != '\n'.code) c = input.read()
                return token()
            }
            while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                sb.append(c.toChar())
                c = input.read()
            }
            return sb.toString()
        }
        require(token() == "P5") { "$file is not a binary PGM" }
        val w = token().toInt()
        val h = token().toInt()
        token()
        val data = ByteArray(w * h)
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n <= 0) break
            read += n
        }
        GrayImage(w, h, data)
    }

    /** Sniffs the gzip magic rather than trusting the name, which costs two bytes. */
    private fun openMaybeGzipped(file: File): java.io.InputStream {
        val raw = java.io.BufferedInputStream(file.inputStream(), 1 shl 16)
        raw.mark(2)
        val gzipped = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        return if (gzipped) java.util.zip.GZIPInputStream(raw) else raw
    }
}
