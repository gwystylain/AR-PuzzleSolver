package com.puzzlesolver.core

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.GlyphClassifier
import com.puzzlesolver.core.puzzle.terminal.DigitReader
import com.puzzlesolver.core.puzzle.terminal.DisplayDetector
import com.puzzlesolver.core.puzzle.terminal.GlyphNormaliser
import com.puzzlesolver.core.puzzle.terminal.TerminalDigits
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rebuilds `terminal-digits.pgm`, the shipped digit templates for the terminal wall.
 *
 * Not part of the test run: it does nothing unless pointed at a directory of frames.
 * It lives here as a test rather than as a script because it has to use the *same*
 * [GlyphNormaliser] and the same [DigitReader] geometry the reader uses, and the only
 * way to guarantee that is to run the real code. A Python script that reimplemented the
 * normalisation would drift from it silently, and the symptom would be an unexplained
 * few points of correlation lost on every digit.
 *
 * To regenerate, after extracting frames as 8-bit PGMs:
 *
 * ```
 * ./gradlew :core:test --tests '*TerminalTemplateBuilder*' \
 *     -Dterminal.frames=/path/to/frames -Dterminal.out=core/src/main/resources
 * ```
 *
 * Frames must be labelled by the wall map below, which is the reference wall as read by eye.
 * Any frame whose detected layout does not match that shape is skipped, so a frame in
 * which the wall is partly out of view costs nothing.
 */
class TerminalTemplateBuilder {

    /**
     * The reference wall, top to bottom and left to right.
     *
     * Display (2, 3) is the one that is hit during the clip and so reads differently
     * from frame to frame; it is excluded rather than labelled.
     */
    private val wall = listOf(
        listOf("048", "094", "024", "086", "090", "072"),
        listOf("087", "012", "091", "083", "030", "085", "065"),
        listOf("057", "099", "032", "008", "089", "043"),
        listOf("059", "074", "---", "082", "069", "021", "073"),
        listOf("027", "039", "093", "071", "018", "052"),
    )

    private val excluded = setOf(2 to 3, 3 to 2)

    @Test
    fun `build digit templates from a frame directory`() {
        val framesDir = System.getProperty("terminal.frames")
        if (framesDir == null) {
            println("terminal.frames not set; not rebuilding templates (this is the normal case)")
            return
        }
        val outDir = File(System.getProperty("terminal.out") ?: "build")
        val frames = File(framesDir).listFiles { f -> f.name.endsWith(".pgm") }?.sorted()
            ?: error("no frames in $framesDir")

        val size = GlyphNormaliser.SIZE
        val sums = Array(10) { DoubleArray(size * size) }
        val counts = IntArray(10)
        val detector = DisplayDetector()
        // No classification here, only isolation: the whole point is to collect the
        // glyphs a classifier will later be given.
        val reader = DigitReader(object : GlyphClassifier {
            override fun classify(normalized: GrayImage): Pair<Int, Float> = -1 to 0f
        })
        var used = 0

        for (file in frames) {
            val frame = readPgm(file)
            val rows = groupIntoRows(detector.detect(frame))
            if (rows.map { it.size } != wall.map { it.size }) continue
            used++
            for ((ri, row) in rows.withIndex()) {
                for ((ci, box) in row.withIndex()) {
                    if (ri to ci in excluded) continue
                    val label = wall[ri][ci]
                    for (k in 0 until DigitReader.TILES) {
                        val digit = label[k] - '0'
                        val patch = reader.isolate(frame, box, k) ?: continue
                        for (i in 0 until size * size) {
                            sums[digit][i] += (patch.data[i].toInt() and 0xFF).toDouble()
                        }
                        counts[digit]++
                    }
                }
            }
        }

        println("used $used of ${frames.size} frames; samples per digit ${counts.toList()}")
        for (d in 0..9) assertTrue("no samples for digit $d", counts[d] > 0)

        // One strip, digit 0 at the top. A PGM so it can be looked at, which matters:
        // a template set that has gone wrong is obvious in a picture and invisible in
        // a byte array.
        val strip = GrayImage(size, size * 10)
        for (d in 0..9) {
            for (i in 0 until size * size) {
                strip.data[d * size * size + i] = (sums[d][i] / counts[d]).toInt().coerceIn(0, 255).toByte()
            }
        }
        outDir.mkdirs()
        val out = File(outDir, TerminalDigits.RESOURCE)
        out.outputStream().use { s ->
            s.write("P5\n# terminal wall digit templates, ${size}x$size each, 0 at the top\n".toByteArray())
            s.write("$size ${size * 10}\n255\n".toByteArray())
            s.write(strip.data, 0, size * size * 10)
        }
        println("wrote ${out.absolutePath}")
    }

    private fun groupIntoRows(boxes: List<DisplayDetector.Box>): List<List<DisplayDetector.Box>> {
        val sorted = boxes.sortedBy { it.y }
        val out = ArrayList<MutableList<DisplayDetector.Box>>()
        for (b in sorted) {
            val row = out.lastOrNull()
            if (row != null && b.y - row[0].y < row[0].height * 0.6f) row.add(b) else out.add(mutableListOf(b))
        }
        return out.map { it.sortedBy { b -> b.x } }
    }

    private fun readPgm(file: File): GrayImage = file.inputStream().use { input ->
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
}
