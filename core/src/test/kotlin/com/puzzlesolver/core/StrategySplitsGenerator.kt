package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import com.puzzlesolver.core.puzzle.strategy.StrategySplits
import com.puzzlesolver.core.puzzle.strategy.StrategyStages
import java.io.File
import kotlin.system.exitProcess

/**
 * Works out the bundled team splits: `write <dir>` rewrites the files there, `check
 * <dir>` only says whether they match what the current stages and planner give. Run
 * through Gradle, `:core:generateStrategySplits` and `:core:checkStrategySplits`, rather
 * than directly. Lives with the tests so none of it ships in the app.
 */
object StrategySplitsGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val (mode, dir) = args
        require(mode == "write" || mode == "check") { "usage: write|check <dir>" }
        var stale = false
        for (room in StrategyRoom.entries) {
            val t0 = System.nanoTime()
            val text = StrategySplits.write(room, StrategySplits.generate(StrategyStages.bundled(room)))
            val seconds = (System.nanoTime() - t0) / 1e9
            val file = File(dir, StrategySplits.fileName(room))
            if (mode == "write") {
                file.writeText(text)
                println("${room.displayName}: wrote $file in ${"%.0f".format(seconds)} s")
            } else {
                // Git may have given the file Windows line endings on checkout.
                val current = if (file.exists()) file.readText().replace("\r\n", "\n") else null
                if (current == text) {
                    println("${room.displayName}: $file is up to date")
                } else {
                    println("${room.displayName}: $file is out of date; run ./gradlew :core:generateStrategySplits")
                    stale = true
                }
            }
        }
        if (stale) exitProcess(1)
    }
}
