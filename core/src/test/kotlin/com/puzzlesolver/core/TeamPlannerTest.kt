package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.StrategyPlan
import com.puzzlesolver.core.puzzle.strategy.StrategySolver
import com.puzzlesolver.core.puzzle.strategy.StrategyStages
import com.puzzlesolver.core.puzzle.strategy.TeamPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The dependency graph is the load-bearing part: a missing edge lets two players fire
 * in an order that changes what a shot hits, and the wall does not give the tile back.
 * So every plan is replayed in many random orders that respect only the graph -- not the
 * plan's own order -- and each shot has to land exactly where the plan says.
 *
 * The scheduling itself is a heuristic and is held to the things a player would notice:
 * the example from the brief comes out as two clean lanes, more players never make a
 * stage slower, and every cross-lane wait is named.
 */
class TeamPlannerTest {

    private val plans: List<StrategyPlan> by lazy {
        StrategyStages.bundled().map { StrategySolver(it).solve()!! }
    }

    /** A random order in which every shot comes after everything it depends on. */
    private fun randomOrder(deps: List<Set<Int>>, random: Random): List<Int> {
        val out = ArrayList<Int>()
        val pending = deps.indices.toMutableList()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { s -> deps[s].all { it in out } }
            val pick = ready[random.nextInt(ready.size)]
            out += pick
            pending -= pick
        }
        return out
    }

    @Test
    fun `any order that respects the dependencies replays clean`() {
        val random = Random(20260917)
        for (plan in plans) {
            val deps = TeamPlanner(plan).dependencies
            repeat(25) {
                val order = randomOrder(deps, random)
                val lives = StrategyReplay.replay(plan.stage, order.map { plan.shots[it] })
                assertEquals("${plan.stage}: reordering changed the lives spent", plan.redHits, lives)
            }
        }
    }

    @Test
    fun `dependencies are the plan's own order at most`() {
        for (plan in plans) {
            for ((j, deps) in TeamPlanner(plan).dependencies.withIndex()) {
                for (d in deps) assertTrue("${plan.stage}: shot $j depends on later shot $d", d < j)
            }
        }
    }

    @Test
    fun `a chain and three independent tiles split into two lanes`() {
        // Column 2 is a chain: 6 clears (2,2) so 1 can fire down to (2,4), so 5 can fire
        // across to (4,4). The right side is three tiles that touch nothing else.
        val text = """
            stage level=99 index=1 of=1 width=8 height=6 source=test
            panel 0-7
            rules gunsblock=0 redrule=life
            gun 2,0,D
            gun 0,2,R
            gun 0,4,R
            gun 7,1,L
            gun 7,2,L
            gun 7,3,L
            target 2,2
            target 2,4
            target 4,4
            target 6,1
            target 6,2
            target 6,3
        """.trimIndent()
        val stage = StrategyStages.parse(text).single()
        val plan = StrategySolver(stage).solve()!!
        val team = TeamPlanner(plan).schedule(2)
        val lanes = team.lanes.map { lane -> lane.shots.map { plan.shots[it].label } }
        assertEquals(listOf(listOf("6", "1", "5"), listOf("2", "3", "4")), lanes)
        assertTrue(team.lanes.all { it.waitsFor.isEmpty() })
        // Nothing between the lanes, so both players count 1, 2, 3.
        assertEquals(listOf(listOf(1, 2, 3), listOf(1, 2, 3)), team.lanes.map { l -> l.shots.map { team.steps[it] } })
        // Three presses each, nobody waiting: the chain is the whole stage, and its walk
        // is (0,2) -> (2,0) -> (0,4), two columns and two rows each way.
        assertEquals(3 * TeamPlanner.PRESS + (2 + 0.6) + (2 + 1.2), team.makespan, 1e-9)
        // With one player the chain still has to be walked in order.
        val solo = TeamPlanner(plan).schedule(1)
        val order = solo.lanes.single().shots.map { plan.shots[it].label }
        assertTrue(order.indexOf("6") < order.indexOf("1") && order.indexOf("1") < order.indexOf("5"))
    }

    @Test
    fun `cross-lane waits name exactly the other lanes' prerequisites`() {
        for (plan in plans) {
            val planner = TeamPlanner(plan)
            for (players in 2..5) {
                val team = planner.schedule(players)
                assertEquals(plan.shots.indices.toSet(), team.lanes.flatMap { it.shots }.toSet())
                for (lane in team.lanes) {
                    val mine = lane.shots.toSet()
                    for (s in lane.shots) {
                        val expected = planner.dependencies[s].filter { it !in mine }.sorted()
                        assertEquals("${plan.stage} p$players shot $s", expected, lane.waitsFor[s] ?: emptyList<Int>())
                        // Within a lane, order already covers the dependencies.
                        for (d in planner.dependencies[s]) {
                            if (d in mine) assertTrue(lane.shots.indexOf(d) < lane.shots.indexOf(s))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `step numbers count from one and skip past what they wait for`() {
        for (plan in plans) {
            val planner = TeamPlanner(plan)
            for (players in 1..5) {
                val team = planner.schedule(players)
                for (lane in team.lanes) {
                    var previous = 0
                    for (s in lane.shots) {
                        val step = team.steps[s]
                        assertTrue("${plan.stage} p$players: step $step after $previous", step > previous)
                        val waited = planner.dependencies[s].maxOfOrNull { team.steps[it] } ?: 0
                        // Exactly one past whatever is later: no gap without a reason.
                        assertEquals("${plan.stage} p$players shot $s", maxOf(previous, waited) + 1, step)
                        previous = step
                    }
                    if (lane.shots.isNotEmpty() && lane.waitsFor.isEmpty()) {
                        assertEquals(lane.shots.indices.map { it + 1 }, lane.shots.map { team.steps[it] })
                    }
                }
            }
        }
    }

    @Test
    fun `more players never make a stage slower`() {
        for (plan in plans) {
            val planner = TeamPlanner(plan)
            var previous = planner.schedule(1).makespan
            assertTrue(previous >= plan.shots.size * TeamPlanner.PRESS)
            for (players in 2..5) {
                val now = planner.schedule(players).makespan
                assertTrue("${plan.stage}: $players players take $now, ${players - 1} took $previous", now <= previous + 1e-9)
                previous = now
            }
        }
    }

    @Test
    fun `independent presses split into stretches of wall`() {
        // Level 1 stage 1: twelve guns, twelve targets, nothing depends on anything. Two
        // players should take a side each and never cross the wall: five presses each
        // on the sides, one each on the top and bottom, and the walk along a side is a
        // third of a cell per row. Anything near the crossing-the-wall schedule the
        // greedy pass alone produces is twice this.
        val plan = plans.first { it.stage.level == 1 && it.stage.index == 1 }
        val team = TeamPlanner(plan).schedule(2)
        assertTrue("travel ${team.travel}", team.travel <= 18.0)
        assertTrue("makespan ${team.makespan}", team.makespan <= 6 * TeamPlanner.PRESS + 9.0)
        for (lane in team.lanes) {
            val xs = lane.shots.map { plan.stage.guns[plan.shots[it].gun].x }
            // Each lane lives on one side: all its side tiles share an x.
            val sides = xs.filter { it == 0 || it == plan.stage.width - 1 }.distinct()
            assertEquals("lane ${lane.player} spans both sides: $xs", 1, sides.size)
        }
    }

    @Test
    fun `level 4 stage 1 halves for two players`() {
        val plan = plans.first { it.stage.level == 4 && it.stage.index == 1 }
        val planner = TeamPlanner(plan)
        val solo = planner.schedule(1).makespan
        val team = planner.schedule(2)
        // Nine presses and no chain longer than three, so a second player should take
        // close to half the time off, walking included.
        assertTrue("two players ${team.makespan} vs one ${solo}", team.makespan <= 0.6 * solo)
        assertTrue(team.lanes.all { it.shots.isNotEmpty() })
    }
}
