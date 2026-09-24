package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.StrategyPlan
import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import com.puzzlesolver.core.puzzle.strategy.StrategySolver
import com.puzzlesolver.core.puzzle.strategy.StrategyStages
import com.puzzlesolver.core.puzzle.strategy.TeamPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The dependency graph is the load-bearing part: a missing edge lets two players fire
 * in an order that changes what a shot hits, and the room does not give the tile back.
 * So every plan -- the solver's first, and every other it offers [TeamSolver] to choose
 * from -- is replayed in many random orders that respect only the graph, not the plan's
 * own order, and each shot has to land exactly where the plan says.
 *
 * The split itself is a search, and is held to the team's rules where they can be
 * checked outright: nobody crosses the room for a tile someone else is standing by;
 * a chain is pressed by players standing at it; another player never makes a stage
 * slower; a player whose last press waited on another's fifth now waits on a first; two
 * boards and two players come out a board each; and every cross-lane wait is named.
 */
class TeamPlannerTest {

    private val plans: List<StrategyPlan> by lazy {
        StrategyRoom.entries.flatMap { room -> StrategyStages.bundled(room).map { StrategySolver(it).solve()!! } }
    }

    /** Every plan [TeamSolver] may choose, the solver's first included. */
    private val everyPlan: List<StrategyPlan> by lazy {
        StrategyRoom.entries.flatMap { room -> StrategyStages.bundled(room).flatMap { StrategySolver(it).plans(24, 11L) } }
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
        for (plan in everyPlan) {
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
        for (plan in everyPlan) {
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
        // is (0,2) -> (2,0) -> (0,4), straight across the floor each time.
        assertEquals(3 * TeamPlanner.PRESS + hypot(2.0, 2.0) + hypot(2.0, 4.0), team.makespan, 1e-9)
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

    private fun plan(room: StrategyRoom, level: Int, index: Int): StrategyPlan =
        StrategySolver(StrategyStages.bundled(room).first { it.level == level && it.index == index }).solve()!!

    @Test
    fun `a tile goes to whoever is next to it, not whoever is short of presses`() {
        // Gridlock 4-4 for five players is the split that prompted this: with every
        // player held to three or four presses, one pressed two tiles on the bottom row
        // and then crossed the room for (10,0), right beside another player's three on
        // the top row. That player takes it now, and nobody crosses the board.
        val plan = plan(StrategyRoom.GRIDLOCK, 4, 4)
        val team = TeamPlanner(plan).schedule(5)
        fun gun(s: Int) = plan.stage.guns[plan.shots[s].gun]
        val top = team.lanes.first { lane -> lane.shots.any { gun(it).x == 10 && gun(it).y == 0 } }
        assertEquals(listOf(7, 8, 9, 10), top.shots.map { gun(it) }.filter { it.y == 0 }.map { it.x }.sorted())
        for (lane in team.lanes) {
            val rows = lane.shots.map { gun(it).y }
            assertTrue("P${lane.player} crosses the board: $rows", !(0 in rows && plan.stage.height - 1 in rows))
        }
    }

    @Test
    fun `a chain is pressed by players standing at it, not walked by one`() {
        // Gridlock 3-3 is six presses, each waiting on the one before, spread round the
        // board. With five players each stands by their own and waits their turn, so the
        // stage takes six presses back to back and barely a step -- a third of the time
        // it takes one player to walk the chain, which is what the split falls back to if
        // a wait is priced above the walking it saves.
        val team = TeamPlanner(plan(StrategyRoom.GRIDLOCK, 3, 3)).schedule(5)
        assertTrue("makespan ${team.makespan}", team.makespan <= 6 * TeamPlanner.PRESS + 1.0)
        assertTrue("travel ${team.travel}", team.travel <= 2.0)
    }

    @Test
    fun `another player never makes a stage slower`() {
        // With every split held even, a stage finished later with one more player
        // fourteen times across the two rooms: the newcomer's even share was presses the
        // others would have made on the way past, and they had to cross the room for
        // them. A little is allowed, since the split also trades time for walking.
        for (plan in plans) {
            val planner = TeamPlanner(plan)
            for (players in 1..4) {
                val fewer = planner.schedule(players).makespan
                val more = planner.schedule(players + 1).makespan
                assertTrue("${plan.stage}: $players players take $fewer, ${players + 1} take $more", more <= fewer * 1.05)
            }
        }
    }

    @Test
    fun `a hand-off comes first and the press that waits on it last`() {
        // Gridlock 6-1 for three players is the split that prompted the rules: a
        // player's last press waited on another player's fifth, which a quick player
        // could overtake. Every wait now sits at the back of its stack, and every press
        // waited for at the front of its own.
        val team = TeamPlanner(plan(StrategyRoom.GRIDLOCK, 6, 1)).schedule(3)
        val waits = team.lanes.flatMap { lane -> lane.waitsFor.entries.map { lane to it } }
        assertTrue("expected at least one hand-off to check", waits.isNotEmpty())
        for ((lane, entry) in waits) {
            assertEquals("P${lane.player} waits before its last press", lane.shots.last(), entry.key)
            for (w in entry.value) {
                val other = team.lanes.first { w in it.shots }
                assertEquals("P${other.player} hands off late", other.shots.first(), w)
            }
        }
    }

    @Test
    fun `two boards and two players come out a board each`() {
        // Gridlock 7-1: the purple tiles on each board light targets on the other, so
        // there is no split without hand-offs. The best has each player stay on their
        // own board, hand off from the front and take the other's hand-offs at a margin.
        val plan = plan(StrategyRoom.GRIDLOCK, 7, 1)
        val team = TeamPlanner(plan).schedule(2)
        for (lane in team.lanes) {
            val panels = lane.shots.map { s -> plan.stage.panels.indexOfFirst { plan.stage.guns[plan.shots[s].gun].x in it } }
            assertEquals("P${lane.player} crosses the wall", 1, panels.distinct().size)
            for ((s, prereqs) in lane.waitsFor) {
                for (w in prereqs) assertTrue("P${lane.player}: margin too small", team.steps[s] - team.steps[w] >= 2)
            }
        }
        // Waits only ever skip no numbers: both players count 1..8 without a gap.
        for (lane in team.lanes) assertEquals((1..8).toList(), lane.shots.map { team.steps[it] })
    }

    @Test
    fun `the same stage always splits the same way`() {
        for ((room, level, index) in listOf(Triple(StrategyRoom.STRATEGY, 8, 3), Triple(StrategyRoom.GRIDLOCK, 9, 3))) {
            val a = TeamPlanner(plan(room, level, index)).schedule(3).lanes.map { it.shots }
            val b = TeamPlanner(plan(room, level, index)).schedule(3).lanes.map { it.shots }
            assertEquals(a, b)
        }
    }

    @Test
    fun `independent presses split into stretches of the edge`() {
        // Level 1 stage 1: twelve guns, twelve targets, nothing depends on anything. Two
        // players should take a side each and never cross the room: five presses each
        // on the sides, one each on the top and bottom. A side is six rows end to end,
        // and the top and bottom tiles are five or six cells from the nearest end of one.
        val plan = plans.first { it.stage.level == 1 && it.stage.index == 1 }
        val team = TeamPlanner(plan).schedule(2)
        assertTrue("travel ${team.travel}", team.travel <= 24.0)
        assertTrue("makespan ${team.makespan}", team.makespan <= 6 * TeamPlanner.PRESS + 12.0)
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
