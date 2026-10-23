package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.PlanOutcome
import com.puzzlesolver.core.puzzle.bombs.WallScanner
import java.io.File
import org.junit.Test

/**
 * Runs a scan saved on the phone back through [WallScanner], with its log, so a scan that
 * came out wrong in the room can be looked at line by line at a desk.
 *
 * Not part of the test run: it does nothing unless pointed at a directory. The app keeps
 * every scan under `files/mines/<date-time>/` -- the frames it read, and `frames.txt`
 * saying when each was taken and whether it was a lattice (`L`) or colour (`C`) frame.
 *
 * ```
 * adb pull /sdcard/Android/data/com.puzzlesolver.app/files/mines
 * ./gradlew :core:test --tests '*MinesReplay*' -Dmines.frames=/path/to/mines/20261023-190000
 * ```
 */
class MinesReplay {

    @Test
    fun `replay a saved scan`() {
        val dir = System.getProperty("mines.frames")
        if (dir == null) {
            println("mines.frames not set; not replaying (this is the normal case)")
            return
        }
        val index = File(dir, "frames.txt").readLines().filter { it.isNotBlank() }
        require(index.isNotEmpty()) { "no frames listed in $dir/frames.txt" }
        val scanner = WallScanner(log = { println("  $it") })
        var progress: WallScanner.Progress? = null
        for (line in index) {
            val parts = line.trim().split(' ')
            val file = File(dir, parts[0])
            if (!file.exists()) continue
            val exposure = if (parts.getOrNull(2) == "C") WallScanner.Exposure.COLOUR else WallScanner.Exposure.LATTICE
            progress = scanner.offer(SpotFixture.read(file), exposure, parts[1].toLong())
        }
        println("ended: ${progress?.phase} -- ${progress?.message}")
        val wall = scanner.result()
        if (wall == null) {
            println("the green was never found")
            return
        }
        print(wall.describe())
        wall.warnings.forEach { println("warning: $it") }
        val outcome = wall.plan(System.nanoTime() + 10_000_000_000L)
        if (outcome is PlanOutcome.Found) {
            for ((n, round) in outcome.plan.rounds.withIndex()) {
                println(
                    "round ${n + 1}: " + round.mines.sorted().joinToString { m ->
                        "${m % wall.cols + 1},${m / wall.cols + 1}"
                    }
                )
            }
        } else {
            println(outcome)
        }
    }
}
