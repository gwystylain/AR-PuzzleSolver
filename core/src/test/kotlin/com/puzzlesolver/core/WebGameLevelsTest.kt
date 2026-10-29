package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombPlanner
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import com.puzzlesolver.core.puzzle.bombs.LineOfSight
import com.puzzlesolver.core.puzzle.bombs.PlanOutcome
import com.puzzlesolver.core.puzzle.bombs.RoundModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner against real levels of the practice game.
 *
 * Nine levels the game generated (`mines/web-levels.txt`), each with the bomb budget
 * the game gives it and the hidden solution it was built around.
 *
 * **The game's own solutions do not all work in the game.** Its level generator
 * checks a solution bomb by bomb with every ray stopping at the first mine it meets;
 * its detonation runs every ray at once and lets a ray on through a mine that has
 * already gone down. Played in the game on 2026-10-28, four of these nine hidden
 * solutions lost -- a ray carried on into a kill square -- and one left a mine
 * standing. Only four won. So the budget is a fair target for the planner on those
 * four, and on the rest the measure is only that our plan wins.
 *
 * Every plan the planner makes here was played in the game on 2026-10-28, bomb by bomb,
 * and won all nine.
 */
class WebGameLevelsTest {

    private val sim = Detonation()

    @Test
    fun `the game's own solutions do in the simulator what they did in the game`() {
        for ((i, level) in MinesWebLevels.load().withIndex()) {
            // Clicked in the generator's order, which is the order the game was played in.
            val r = sim.fire(level.board, level.solution)
            val left = level.board.liveTargets().count { r.damage[it] < level.board.hp[it] }
            val outcome = if (r.losesGame) "LOST" else if (left == 0) "WIN" else "$left left"
            assertEquals(level.name, GAME_SOLUTION_OUTCOMES[i], outcome)

            // And what the search's fast model would have made of it.
            val model = RoundModel(level.board, LineOfSight(level.board))
            for (m in level.solution) model.choose(m)
            println("${level.name}: the game's ${level.solution.size}-bomb solution: $outcome (fast model short by ${model.shortfall})")
        }
    }

    @Test
    fun `every practice-game level is planned to a win`() {
        val levels = MinesWebLevels.load()
        assertTrue(levels.size >= 9)
        for (level in levels) {
            val started = System.nanoTime()
            // As the app asks: no budget, just the fewest, inside the time it allows.
            val outcome = BombPlanner(budget = level.board.remainingHitPoints, maxRounds = 3)
                .plan(level.board, deadlineNanos = started + PLAN_NANOS)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("${level.name}: expected a plan, got $outcome", outcome is PlanOutcome.Found)
            val plan = (outcome as PlanOutcome.Found).plan
            if (GAME_SOLUTION_OUTCOMES[levels.indexOf(level)] == "WIN") {
                // The game's budget is known to be achievable here.
                assertTrue("${level.name}: ${plan.totalMines} mines, budget ${level.budget}", plan.totalMines <= level.budget)
            }

            var board = level.board
            for (round in plan.rounds) {
                val r = sim.settled(board, round.mines)
                assertNotNull("${level.name}: a round loses or depends on press order", r)
                board = board.afterDetonation(r!!.damage, round.mines)
            }
            assertTrue("${level.name}: not cleared:\n$board", board.isCleared)

            // In the game's own coordinates, row,col, for playing it back there.
            val rounds = plan.rounds.joinToString(" | ") { round ->
                round.mines.joinToString(";") { "${it / level.board.cols},${it % level.board.cols}" }
            }
            println(
                "${level.name}: ${plan.totalMines}/${level.budget} bombs in ${plan.roundCount} detonation(s) " +
                    "in ${ms}ms${if (plan.provenMinimal) ", proven fewest" else ""}: $rounds"
            )
        }
    }

    private companion object {
        /** What the app gives the planner: MinesController.PLAN_BUDGET_NANOS. */
        const val PLAN_NANOS = 6_000_000_000L

        /** What each level's hidden solution did when played in the game, 2026-10-28. */
        val GAME_SOLUTION_OUTCOMES = listOf(
            "LOST", "LOST", "1 left", "LOST", "WIN", "WIN", "LOST", "WIN", "WIN",
        )
    }
}

/** The practice-game levels in the test resources. */
object MinesWebLevels {
    class Level(val name: String, val budget: Int, val board: BombBoard, val solution: IntArray)

    fun load(): List<Level> {
        val text = MinesWebLevels::class.java.classLoader!!.getResourceAsStream("mines/web-levels.txt")!!
            .bufferedReader().readLines()
        return text.filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, line ->
            val parts = line.trim().split(' ')
            val kinds = ByteArray(COLS * ROWS) { Button.EMPTY.toByte() }
            val solution = ArrayList<Int>()
            for (part in parts.drop(2)) {
                val cells = part.substringAfter(':')
                if (cells.isEmpty()) continue
                for (cell in cells.split(';')) {
                    val (r, c) = cell.split(',').map { it.toInt() }
                    when (part[0]) {
                        'W' -> kinds[r * COLS + c] = Button.WHITE.toByte()
                        'B' -> kinds[r * COLS + c] = Button.BLUE.toByte()
                        'R' -> kinds[r * COLS + c] = Button.HAZARD.toByte()
                        'S' -> solution.add(r * COLS + c)
                        else -> error("bad part $part")
                    }
                }
            }
            Level("${parts[0]}#${i + 1}", parts[1].toInt(), BombBoard.of(COLS, ROWS, kinds), solution.toIntArray())
        }
    }

    /** The practice game's grid. */
    const val COLS = 59
    const val ROWS = 7
}
