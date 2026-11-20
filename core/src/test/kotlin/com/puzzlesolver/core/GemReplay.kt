package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPalette
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.gems.GemScanner
import com.puzzlesolver.core.puzzle.gems.GemTargets
import java.io.File
import org.junit.Test

/**
 * Runs a directory of Gems captures (`gem-NNNN.ppm.gz`, as `GemRecorder` writes them)
 * through the scanner and prints what it reads, so a round from the room can be read
 * again at a desk after the reader changes.
 *
 * Not part of the test run: it does nothing unless pointed at a directory.
 *
 * ```
 * ./gradlew :core:test --tests '*GemReplay*' -Dgems.frames=/path/to/captures \
 *     -Dgems.targets=red/green/green,red/red/green -Dgems.verbose=true
 * ```
 *
 * Per frame it prints how many rings of each colour were read at each depth and which
 * gems matched; with `gems.verbose` every gem's reading as well, in the sidecar's layout.
 * `gems.hues` (four degrees, red/yellow/green/blue) tries other centroids for the run.
 */
class GemReplay {

    @Test
    fun `replay a directory of gem captures`() {
        val dir = System.getProperty("gems.frames")
        if (dir == null) {
            println("gems.frames not set; not replaying (this is the normal case)")
            return
        }
        val files = File(dir).listFiles { f -> f.name.matches(Regex("gem-\\d+\\.ppm(\\.gz)?")) }
            ?.sortedBy { it.name }
            .orEmpty()
        require(files.isNotEmpty()) { "no gem-NNNN.ppm.gz captures in $dir" }

        val targets = GemTargets()
        System.getProperty("gems.targets")?.split(',')?.forEachIndexed { i, t ->
            val p = t.split('/').map { colourOf(it.trim()) }
            targets.set(i, GemPattern(p.getOrElse(0) { GemColour.UNKNOWN }, p.getOrElse(1) { GemColour.UNKNOWN }, p.getOrElse(2) { GemColour.UNKNOWN }))
        }
        val verbose = System.getProperty("gems.verbose") == "true"
        // Trying other hue centroids against the same frames: -Dgems.hues=352,66,146,220.
        val saved = GemPalette.HUES.copyOf()
        System.getProperty("gems.hues")?.split(',')?.forEachIndexed { i, h -> GemPalette.HUES[i] = h.trim().toFloat() }
        try {
            replay(files, GemScanner(targets), verbose)
        } finally {
            saved.copyInto(GemPalette.HUES)
        }
    }

    private fun replay(files: List<File>, scanner: GemScanner, verbose: Boolean) {

        for (file in files) {
            val result = scanner.scan(load(file))
            if (result.gems.isEmpty()) {
                println("${file.name}  ${result.status}")
                continue
            }
            val tally = Array(3) { IntArray(GemColour.ALL.size + 1) }
            for (g in result.gems) {
                for ((depth, c) in listOf(g.pattern.outer, g.pattern.middle, g.pattern.centre).withIndex()) {
                    val k = GemColour.ALL.indexOf(c)
                    tally[depth][if (k < 0) GemColour.ALL.size else k]++
                }
            }
            val names = GemColour.ALL.map { GemColour.name(it).take(1).uppercase() } + "?"
            val counts = listOf("outer", "middle", "centre").mapIndexed { d, label ->
                label + " " + names.indices.filter { tally[d][it] > 0 }.joinToString(" ") { "${names[it]}${tally[d][it]}" }
            }
            println("${file.name}  pitch=${"%.1f".format(result.pitch)} gems=${result.gems.size} matched=${result.matches.size}  ${counts.joinToString("  ")}")
            for (g in result.gems) {
                if (verbose || g.matchedSlot > 0) {
                    println("    %7.1f  %7.1f  %-7s %-7s %-7s %5.2f  %d%s".format(
                        g.x, g.y, GemColour.name(g.pattern.outer), GemColour.name(g.pattern.middle),
                        GemColour.name(g.pattern.centre), g.confidence, g.matchedSlot, if (g.whole) "" else "  cut off",
                    ))
                }
            }
        }
    }

    private fun colourOf(name: String): Int = when (name) {
        "red" -> GemColour.RED
        "yellow" -> GemColour.YELLOW
        "green" -> GemColour.GREEN
        "blue" -> GemColour.BLUE
        else -> GemColour.UNKNOWN
    }

    /** Loads a capture through luma and BT.601 chroma, as [GemFixture] does and the phone does. */
    private fun load(file: File): CanvasView {
        val raw0 = file.inputStream().buffered().let {
            if (file.name.endsWith(".gz")) java.util.zip.GZIPInputStream(it).buffered() else it
        }
        raw0.use { input ->
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
            require(token() == "P6") { "${file.name} is not a P6 PPM" }
            val w = token().toInt()
            val h = token().toInt()
            token()
            val raw = input.readNBytes(w * h * 3)
            require(raw.size == w * h * 3) { "short capture: ${file.name}" }
            val luma = GrayImage(w, h)
            val chroma = ChromaImage(w, h)
            for (i in 0 until w * h) {
                val r = (raw[i * 3].toInt() and 0xFF).toFloat()
                val g = (raw[i * 3 + 1].toInt() and 0xFF).toFloat()
                val b = (raw[i * 3 + 2].toInt() and 0xFF).toFloat()
                luma.data[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2] = (-0.168736f * r - 0.331264f * g + 0.5f * b + 128f).toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2 + 1] = (0.5f * r - 0.418688f * g - 0.081312f * b + 128f).toInt().coerceIn(0, 255).toByte()
            }
            return CanvasView(luma, chroma)
        }
    }
}
