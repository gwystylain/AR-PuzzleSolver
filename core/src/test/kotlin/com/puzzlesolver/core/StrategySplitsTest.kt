package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import com.puzzlesolver.core.puzzle.strategy.StrategySolver
import com.puzzlesolver.core.puzzle.strategy.StrategySplits
import com.puzzlesolver.core.puzzle.strategy.StrategyStage
import com.puzzlesolver.core.puzzle.strategy.StrategyStages
import com.puzzlesolver.core.puzzle.strategy.TeamPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * The splits the guide serves are worked out ahead of time, so these hold the bundled
 * files to what the phone used to work out for itself: every stage and team size is
 * there, every split clears its stage in any order its dependencies allow, none is worse
 * than splitting the solver's own plan with the planner as it stands, and a stage
 * corrected without regenerating is caught. Whether the files are exactly what the
 * current code would generate is `./gradlew :core:checkStrategySplits`, which takes
 * minutes rather than seconds.
 */
class StrategySplitsTest {

    private val bundled by lazy { StrategyRoom.entries.associateWith { StrategySplits.bundled(it) } }

    @Test
    fun `every stage has a split for every team size`() {
        for ((room, splits) in bundled) {
            assertEquals(StrategyStages.bundled(room).map { it.level to it.index }, splits.map { it.stage.level to it.stage.index })
            for (s in splits) assertEquals("${s.stage}", StrategySplits.TEAM_SIZES.toSet(), s.teamSizes)
        }
    }

    @Test
    fun `the files read back as written`() {
        for ((room, splits) in bundled) {
            val file = StrategySplits::class.java.getResourceAsStream("/strategy/${StrategySplits.fileName(room)}")!!
                .bufferedReader().use { it.readText() }.replace("\r\n", "\n")
            assertEquals(file, StrategySplits.write(room, splits))
        }
    }

    @Test
    fun `every split clears its stage in any order it allows`() {
        val random = Random(20260924)
        for (s in bundled.values.flatten()) {
            val lives = StrategySolver(s.stage).solve()!!.redHits
            for (players in s.teamSizes) {
                val team = s.forPlayers(players)
                val plan = team.plan
                assertEquals("${s.stage} p$players", lives, StrategyReplay.replay(s.stage, plan.shots))
                assertEquals(players, team.lanes.size)
                val deps = TeamPlanner(plan).dependencies
                repeat(10) {
                    val order = ArrayList<Int>()
                    val pending = deps.indices.toMutableList()
                    while (pending.isNotEmpty()) {
                        val ready = pending.filter { d -> deps[d].all { it in order } }
                        order += ready[random.nextInt(ready.size)].also { pending -= it }
                    }
                    StrategyReplay.replay(s.stage, order.map { plan.shots[it] })
                }
            }
        }
    }

    @Test
    fun `no split is worse than splitting the solver's own plan`() {
        // True when the files are generated, since the solver's plan is one of those
        // split. It fails when the planner has changed since -- the bundled lanes were
        // chosen by the old rules -- which is the reminder to regenerate.
        for (s in bundled.values.flatten()) {
            val planner = TeamPlanner(StrategySolver(s.stage).solve()!!)
            for (players in s.teamSizes) {
                val own = planner.schedule(players).score
                val shipped = s.forPlayers(players).score
                assertTrue("${s.stage} p$players: $shipped against $own", shipped <= own + 1e-6)
            }
        }
    }

    @Test
    fun `Gridlock 4-4 for five clears the top row in order`() {
        // The split that prompted the rules: one player pressed two tiles on the bottom
        // row and crossed the room for (10,0), beside another player's three on the top
        // row, who pressed theirs out of order -- starting in the middle of the row. Now
        // one player takes the whole top row from one end to the other, nobody crosses
        // the board, and nobody has to find a tile in the middle of a row.
        val s = bundled.getValue(StrategyRoom.GRIDLOCK).first { it.stage.level == 4 && it.stage.index == 4 }
        val team = s.forPlayers(5)
        fun gun(shot: Int) = s.stage.guns[team.plan.shots[shot].gun]
        val top = team.lanes.first { lane -> lane.shots.any { gun(it).y == 0 } }
        val xs = top.shots.map { gun(it) }.filter { it.y == 0 }.map { it.x }
        assertTrue("top row pressed as $xs", xs == listOf(7, 8, 9, 10) || xs == listOf(10, 9, 8, 7))
        for (lane in team.lanes) {
            val rows = lane.shots.map { gun(it).y }
            assertTrue("P${lane.player} crosses the board: $rows", !(0 in rows && s.stage.height - 1 in rows))
            assertEquals("P${lane.player} hunts mid-row", 0, lane.middles)
        }
    }

    @Test
    fun `a stage corrected without regenerating its splits is caught`() {
        val stages = StrategyStages.bundled(StrategyRoom.GRIDLOCK)
        val text = StrategySplits::class.java.getResourceAsStream("/strategy/${StrategySplits.fileName(StrategyRoom.GRIDLOCK)}")!!
            .bufferedReader().use { it.readText() }
        val old = stages.first()
        // The same stage with its last target moved one tile over.
        val moved = StrategyStage(
            old.level, old.index, old.stageCount, old.width, old.height, old.panels, old.gap, old.guns,
            old.targets.dropLast(1) + old.targets.last().let { it.copy(x = it.x + 1) },
            old.mirrors, old.reds, old.movingTargets, old.gunsBlock, old.redRule, old.source,
        )
        try {
            StrategySplits.parse(listOf(moved) + stages.drop(1), text)
            fail("a changed stage was read without complaint")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, "generateStrategySplits" in e.message!!)
        }
    }
}
