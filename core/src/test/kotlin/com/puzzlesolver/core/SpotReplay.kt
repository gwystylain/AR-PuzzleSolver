package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.spot.SpotFinder
import java.awt.BasicStroke
import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Runs a directory of photos through Spot and writes each straightened screen out with
 * its boxes drawn on, so a round from the room can be looked at rather than inferred.
 *
 * Not part of the test run: it does nothing unless pointed at a directory. The app keeps
 * every round it takes under `files/spot/` on the phone, as JPEGs in the order taken,
 * which is exactly what this reads.
 *
 * ```
 * ./gradlew :core:test --tests '*SpotReplay*' -Dspot.photos=/path/to/round -Dspot.out=/path/to/out
 * ```
 */
class SpotReplay {

    @Test
    fun `replay a directory of photos`() {
        val dir = System.getProperty("spot.photos")
        if (dir == null) {
            println("spot.photos not set; not replaying (this is the normal case)")
            return
        }
        val files = File(dir).listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            .orEmpty()
        require(files.isNotEmpty()) { "no photos in $dir" }
        val result = SpotFinder(log = { println("    $it") }).find(files.map { SpotFixture.read(it) })
        println("spot: ${result.status} in ${result.millis} ms")
        val out = System.getProperty("spot.out")?.let { File(it).apply { mkdirs() } }
        for ((k, photo) in result.photos.withIndex()) {
            val line = when {
                photo.screen == null -> "screen not found"
                photo.differences.isEmpty() -> "no difference"
                else -> photo.differences.joinToString { d ->
                    "[%.3f,%.3f - %.3f,%.3f] %.0f".format(d.left, d.top, d.right, d.bottom, d.score)
                }
            }
            println("  ${files[k].name}: $line")
            val screen = photo.screen ?: continue
            out ?: continue
            val image = SpotFixture.toBufferedImage(screen)
            val g = image.createGraphics()
            g.stroke = BasicStroke(4f)
            for ((i, d) in photo.differences.withIndex()) {
                g.color = if (i == 0) Color.MAGENTA else Color.YELLOW
                g.drawRect(
                    (d.left * screen.width).toInt() - 6,
                    (d.top * screen.height).toInt() - 6,
                    ((d.right - d.left) * screen.width).toInt() + 12,
                    ((d.bottom - d.top) * screen.height).toInt() + 12,
                )
            }
            g.dispose()
            ImageIO.write(image, "png", File(out, "${files[k].nameWithoutExtension}-spot.png"))
        }
    }
}
