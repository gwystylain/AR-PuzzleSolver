package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.link.LinkPairer
import com.puzzlesolver.core.puzzle.link.LinkPlanner
import com.puzzlesolver.core.puzzle.link.LinkScanner
import java.io.File
import org.junit.Test

/**
 * Runs a Link scan saved on the phone back through [LinkScanner], with its log, so a scan
 * that came out wrong in the room can be looked at line by line at a desk.
 *
 * Not part of the test run: it does nothing unless pointed at a directory. The app keeps
 * every scan under `files/link/<date-time>/` -- the frames it read and `frames.txt` saying
 * when each was taken.
 *
 * ```
 * adb pull /sdcard/Android/data/com.puzzlesolver.app/files/link
 * ./gradlew :core:test --tests '*LinkReplay*' -Dlink.frames=/path/to/link/20261127-190000
 * ```
 */
class LinkReplay {

    @Test
    fun `replay a saved scan`() {
        val dir = System.getProperty("link.frames")
        if (dir == null) {
            println("link.frames not set; not replaying (this is the normal case)")
            return
        }
        val index = File(dir, "frames.txt").readLines().filter { it.isNotBlank() }
        require(index.isNotEmpty()) { "no frames listed in $dir/frames.txt" }
        val scanner = LinkScanner(log = { println("  $it") })
        var progress: LinkScanner.Progress? = null
        for (line in index) {
            val parts = line.trim().split(' ')
            val file = File(dir, parts[0])
            if (!file.exists()) continue
            progress = scanner.offer(SpotFixture.read(file), parts[1].toLong())
        }
        println("ended: ${progress?.phase} -- ${progress?.message}")
        val wall = scanner.result()
        if (wall == null) {
            println("no panel was read")
            return
        }
        print(wall.describe())
        wall.warnings.forEach { println("warning: $it") }
        val pairing = LinkPairer.pair(wall)
        for ((a, b) in pairing.pairs) {
            println("pair ${wall.label(a)} ${wall.label(b)} ${wall.panels[a].pattern} differs=${pairing.differences[a]}")
        }
        pairing.unpaired.forEach { println("unpaired ${wall.label(it)} ${wall.panels[it].pattern}") }
        val path = LinkPlanner.plan(wall, pairing)
        println("path: %.0f buttons between pairs".format(path.between))
        path.steps.forEachIndexed { n, s -> println("step ${n + 1}: ${wall.label(s.first)} then ${wall.label(s.second)}") }
    }
}
