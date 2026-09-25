package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.StrategyPlan
import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import com.puzzlesolver.core.puzzle.strategy.StrategySolver
import com.puzzlesolver.core.puzzle.strategy.StrategyStages
import com.puzzlesolver.core.puzzle.strategy.TeamPlanner
import com.puzzlesolver.core.puzzle.strategy.TeamSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the plan as well as the split: it has to pay where a stage leaves a choice,
 * keep the solver's plan where it does not, and come out the same every time. That it
 * is never worse than the solver's own plan is [StrategySplitsTest]'s, on every bundled
 * split; that the other plans are safe to split at all is [TeamPlannerTest]'s.
 */
class TeamSolverTest {

    private fun plan(room: StrategyRoom, level: Int, index: Int): StrategyPlan =
        StrategySolver(StrategyStages.bundled(room).first { it.level == level && it.index == index }).solve()!!

    private fun sequence(plan: StrategyPlan) = plan.shots.joinToString(" ") { it.label }

    @Test
    fun `another plan splits better where the stage allows one`() {
        // Gridlock 7-4: two boards with a wall between, and purple tiles on each that
        // light targets on the other. The solver's first plan has players crossing
        // between the boards for tiles and waiting on each other across the wall; with
        // other guns taking other tiles, three players each keep mostly to one board.
        val plan = plan(StrategyRoom.GRIDLOCK, 7, 4)
        val own = TeamPlanner(plan).schedule(3)
        val chosen = TeamSolver(plan).schedule(3)
        assertNotEquals(sequence(plan), sequence(chosen.plan))
        assertTrue("${chosen.makespan} against ${own.makespan}", chosen.makespan <= 0.85 * own.makespan)
        assertTrue("${chosen.travel} against ${own.travel}", chosen.travel <= 0.85 * own.travel)
        StrategyReplay.replay(chosen.plan.stage, chosen.plan.shots)
    }

    @Test
    fun `a stage with one way to clear it keeps the solver's plan`() {
        // Strategy 1-1 is twelve guns each with one tile in its line: nothing to choose.
        val plan = plan(StrategyRoom.STRATEGY, 1, 1)
        assertEquals(1, StrategySolver(plan.stage).plans(64, 11L).size)
        assertSame(plan, TeamSolver(plan).schedule(2).plan)
    }

    @Test
    fun `the same stage always splits the same way`() {
        for ((room, level, index) in listOf(Triple(StrategyRoom.GRIDLOCK, 7, 4), Triple(StrategyRoom.STRATEGY, 9, 4))) {
            val a = TeamSolver(plan(room, level, index)).schedule(4)
            val b = TeamSolver(plan(room, level, index)).schedule(4)
            assertEquals(sequence(a.plan), sequence(b.plan))
            assertEquals(a.lanes.map { it.shots }, b.lanes.map { it.shots })
        }
    }
}
