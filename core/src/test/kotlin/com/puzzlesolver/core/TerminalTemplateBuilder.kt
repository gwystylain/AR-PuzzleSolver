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
 * Not part of the test run: it does nothing unless pointed at directories of frames.
 * It lives here as a test rather than as a script because it has to use the *same*
 * [GlyphNormaliser] and the same [DigitReader] geometry the reader uses, and the only
 * way to guarantee that is to run the real code. A Python script that reimplemented the
 * normalisation would drift from it silently, and the symptom would be an unexplained
 * few points of correlation lost on every digit.
 *
 * ```
 * ./gradlew :core:test --tests '*TerminalTemplateBuilder*' \
 *     "-Dterminal.frames=clip;room-0917;room-0924" -Dterminal.out=src/main/resources
 * ```
 *
 * Relative paths resolve against `core/`, which is where the test runs -- so the output
 * is `src/main/resources` and not `core/src/main/resources`, which would land in
 * `core/core/`.
 *
 * **One set of ten per directory**, in the order given, separated by the platform's path
 * separator -- `;` on Windows, `:` elsewhere. Each directory holds frames, as PGMs plain
 * or gzipped, which is what `TerminalRecorder` writes, and a `wall.txt` saying what the
 * wall showed, read by eye:
 *
 * ```
 * # top row first, each row left to right; anything not three digits is skipped
 * 066 025 055 004 058 024
 * 056 045 006 018 073 084 031
 * ...
 * ```
 *
 * A frame whose detected layout does not have that shape is skipped, so a frame with
 * the wall partly out of view costs nothing. A display with a blank tile is skipped
 * too -- cleared, or caught mid-flip -- so a wall labelled once at the start of a round
 * serves every frame of it. A display caught mid-flip with every tile still lit is not
 * caught that way, so leave frames like that out of the directory.
 *
 * The shipped file holds three sets, and the frames are chosen so that every test
 * fixture stays out of them:
 *
 *  1. the reference clip, `testVideos/Terminal/VID20260904193707.mp4`, every eighth
 *     frame from 24 on -- frame 0 is `TerminalWallTest`'s fixture;
 *  2. the capture from the room on 17 September, `term-0002` to `term-0012` without
 *     `0006` and `0008`, which each catch a display mid-flip -- `term-0001` is
 *     `TerminalRoomTest`'s fixture;
 *  3. the capture from 24 September, `term-0017` to `term-0024` without `0021`, which
 *     catches `002` mid-flip -- `0025` to `0036` are held out, and `term-0025` is
 *     `TerminalBloomTest`'s fixture.
 *
 * Sets and not one average, because the three were shot at different exposures and the
 * wall looks different at each -- the digits bloom at the brighter ones until their
 * counters are a few pixels across. An average of those is a template that matches none
 * of them well. Separate sets let a glyph find the one shot the way it was.
 */
class TerminalTemplateBuilder {

    @Test
    fun `build digit templates from frame directories`() {
        val framesDirs = System.getProperty("terminal.frames")
        if (framesDirs == null) {
            println("terminal.frames not set; not rebuilding templates (this is the normal case)")
            return
        }
        val outDir = File(System.getProperty("terminal.out") ?: "build")
        val size = GlyphNormaliser.SIZE
        val detector = DisplayDetector()
        // No classification here, only isolation: the whole point is to collect the
        // glyphs a classifier will later be given.
        val reader = DigitReader(object : GlyphClassifier {
            override fun classify(normalized: GrayImage): Pair<Int, Float> = -1 to 0f
        })

        val sets = ArrayList<ByteArray>()
        for (dir in framesDirs.split(File.pathSeparator).map { File(it) }) {
            val wall = readWall(File(dir, "wall.txt"))
            val frames = dir.listFiles { f -> f.name.endsWith(".pgm") || f.name.endsWith(".pgm.gz") }
                ?.sorted()
                ?: error("no frames in $dir")

            val sums = Array(10) { DoubleArray(size * size) }
            val counts = IntArray(10)
            var used = 0
            for (file in frames) {
                val frame = Pgm.read(file)
                val rows = groupIntoRows(detector.detect(frame))
                if (rows.map { it.size } != wall.map { it.size }) continue
                used++
                for ((ri, row) in rows.withIndex()) {
                    for ((ci, box) in row.withIndex()) {
                        val label = wall[ri][ci]
                        if (label.length != DigitReader.TILES || !label.all { it.isDigit() }) continue
                        if (reader.read(frame, box).blankTiles > 0) continue
                        for (k in 0 until DigitReader.TILES) {
                            val patch = reader.isolate(frame, box, k) ?: continue
                            val digit = label[k] - '0'
                            for (i in 0 until size * size) {
                                sums[digit][i] += (patch.data[i].toInt() and 0xFF).toDouble()
                            }
                            counts[digit]++
                        }
                    }
                }
            }

            println("${dir.name}: used $used of ${frames.size} frames; samples per digit ${counts.toList()}")
            for (d in 0..9) assertTrue("${dir.name} has no samples for digit $d", counts[d] > 0)
            val set = ByteArray(size * size * 10)
            for (d in 0..9) {
                for (i in 0 until size * size) {
                    set[d * size * size + i] = (sums[d][i] / counts[d]).toInt().coerceIn(0, 255).toByte()
                }
            }
            sets.add(set)
        }

        // One strip, a set of ten after another, digit 0 at the top of each. A PGM so it
        // can be looked at, which matters: a template set that has gone wrong is obvious
        // in a picture and invisible in a byte array.
        outDir.mkdirs()
        val out = File(outDir, TerminalDigits.RESOURCE)
        out.outputStream().use { s ->
            s.write(
                ("P5\n# terminal wall digit templates, ${size}x$size each, ${sets.size} sets of ten, " +
                    "0 at the top of each\n").toByteArray()
            )
            s.write("$size ${size * 10 * sets.size}\n255\n".toByteArray())
            for (set in sets) s.write(set)
        }
        println("wrote ${out.absolutePath}")
    }

    /** Rows of the wall, each a list of three-character entries. */
    private fun readWall(file: File): List<List<String>> {
        require(file.exists()) { "$file is missing: every frame directory needs its wall labelled" }
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")) }
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
}
