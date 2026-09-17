package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.Cell
import com.puzzlesolver.core.puzzle.strategy.Hit
import com.puzzlesolver.core.puzzle.strategy.RedRule
import com.puzzlesolver.core.puzzle.strategy.StrategyPlan
import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import com.puzzlesolver.core.puzzle.strategy.StrategySolver
import com.puzzlesolver.core.puzzle.strategy.StrategyStage
import com.puzzlesolver.core.puzzle.strategy.StrategyStages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every plan is replayed through [StrategyReplay], a simulator written from the rules
 * and not from the solver. A plan that leaves a target standing, or runs into a red it
 * did not budget for, fails here whatever the solver believed.
 *
 * The level 4 expectations are the sequences that were played through to
 * "Congratulations!" on activate-scores.ca before any of this was written, so they pin
 * the solver to the real game rather than to itself.
 */
class StrategySolverTest {

    private val stages by lazy { StrategyStages.bundled() }
    private val gridlock by lazy { StrategyStages.bundled(StrategyRoom.GRIDLOCK) }

    private fun stage(level: Int, index: Int): StrategyStage =
        stages.first { it.level == level && it.index == index }

    private fun solve(level: Int, index: Int): StrategyPlan {
        val plan = StrategySolver(stage(level, index)).solve()
        assertNotNull("no plan for level $level stage $index", plan)
        return plan!!
    }

    private fun sequence(plan: StrategyPlan): String =
        plan.shots.joinToString(" ") { it.label + if (it.hit == Hit.RED) "!" else "" }

    private fun replay(stage: StrategyStage, plan: StrategyPlan): Int = StrategyReplay.replay(stage, plan.shots)

    @Test
    fun `every bundled stage has a plan that replays clean`() {
        assertEquals(48, stages.size)
        assertEquals((1..10).toList(), stages.map { it.level }.distinct())
        assertEquals(20, gridlock.size)
        assertEquals((6..10).toList(), gridlock.map { it.level }.distinct())
        assertEquals(setOf(6, 7, 8, 9, 10), StrategyStages.levels(StrategyRoom.GRIDLOCK).keys)
        var slowest = 0L
        for (stage in stages + gridlock) {
            val t0 = System.nanoTime()
            val plan = StrategySolver(stage).solve()
            val ms = (System.nanoTime() - t0) / 1_000_000
            slowest = maxOf(slowest, ms)
            assertNotNull("no plan for $stage", plan)
            val lives = replay(stage, plan!!)
            assertEquals("$stage: plan and replay disagree on lives", plan.redHits, lives)
            // The screen shows the board from the plan's own replay; it has to end empty too.
            val end = plan.states.last()
            assertEquals(plan.shots.size + 1, plan.states.size)
            assertTrue("$stage: board state replay leaves ${end.targets} ${end.movingTargets} ${end.mirrors}",
                end.targets.isEmpty() && end.movingTargets.isEmpty() && end.mirrors.isEmpty())
            assertEquals(stage.guns.size - plan.shots.size - plan.shots.count { it.hit == Hit.GUN },
                end.guns.size + plan.shots.count { it.hit == Hit.MIRROR && stage.guns.any { g -> g.cell == Cell(stage.width - 1 - it.cell!!.x, it.cell.y) } })
            assertTrue("$stage spends $lives lives", lives < 5)
            assertTrue("$stage: a stage only fails a wave on a red, so the plan must avoid them", stage.redRule == RedRule.LIFE || lives == 0)
        }
        assertTrue("slowest stage took $slowest ms; the app solves on the UI's thread pool", slowest < 2000)
    }

    @Test
    fun `only level 4 stage 4 costs a life`() {
        for (stage in stages + gridlock) {
            val plan = StrategySolver(stage).solve()!!
            val expected = if (stage.level == 4 && stage.index == 4) 1 else 0
            assertEquals("$stage", expected, plan.redHits)
        }
        // The red at (5,5) walls off the last target from the only gun that can reach it;
        // tile 2 clears it and is the sacrifice.
        assertEquals("3 4 5 2! 1", sequence(solve(4, 4)))
    }

    @Test
    fun `level 4 comes out as the sequences played through on the site`() {
        assertEquals("3 2 4 5 6 7 8 9 1", sequence(solve(4, 1)))
        // Every shot of the last stage in the one second the board is not red.
        val flashing = solve(4, 5)
        assertEquals(1, flashing.groups.size)
        assertEquals(listOf(1), flashing.groups.single().frames)
        assertEquals("1 3 2 4 6 5 7 8", sequence(flashing))
    }

    @Test
    fun `numbers run clockwise from the top-left corner`() {
        val s = stage(4, 1)
        fun label(x: Int, y: Int) = s.labels[s.guns.indexOfFirst { it.x == x && it.y == y }]
        assertEquals("1", label(2, 0))
        assertEquals("2", label(4, 0))
        assertEquals("3", label(11, 1))
        assertEquals("5", label(11, 9))
        assertEquals("6", label(3, 11))
        assertEquals("7", label(2, 11))
        assertEquals("8", label(0, 7))
        assertEquals("9", label(0, 4))
    }

    @Test
    fun `two panels are numbered separately as L and R`() {
        val s = stage(7, 1)
        assertEquals(2, s.panels.size)
        assertEquals(listOf(0..6, 9..15), s.panels)
        assertTrue(s.isWall(7) && s.isWall(8))
        assertEquals((1..10).map { "L$it" } + (1..10).map { "R$it" }, s.gunsByLabel.map { s.labels[it] })
        // The inner edges face each other across the wall, and both count as border:
        // the left panel's right-hand column runs L2..L9 top to bottom after the top
        // gun, and the right panel's left-hand column runs bottom to top after its
        // bottom gun.
        assertEquals("L2", s.labels[s.guns.indexOfFirst { it.x == 6 && it.y == 2 }])
        assertEquals("L9", s.labels[s.guns.indexOfFirst { it.x == 6 && it.y == 9 }])
        assertEquals("R3", s.labels[s.guns.indexOfFirst { it.x == 9 && it.y == 9 }])
        assertEquals("R10", s.labels[s.guns.indexOfFirst { it.x == 9 && it.y == 2 }])
    }

    @Test
    fun `gridlock keeps its boards apart or joins them as the site does`() {
        // Levels 7 and 8: two 10-wide boards with a wall between; each is its own panel.
        val apart = gridlock.first { it.level == 7 && it.index == 1 }
        assertEquals(listOf(0..9, 12..21), apart.panels)
        assertTrue(apart.isWall(10) && apart.isWall(11))
        assertEquals((1..8).map { "L$it" } + (1..8).map { "R$it" }, apart.gunsByLabel.map { apart.labels[it] })
        assertEquals("L3", apart.labels[apart.guns.indexOfFirst { it.x == 9 && it.y == 4 }])
        // Levels 6, 9, 10: the boards touch, shots cross, and it is one 22-wide panel.
        val joined = gridlock.first { it.level == 9 && it.index == 1 }
        assertEquals(listOf(0..21), joined.panels)
        assertEquals(null, joined.gap)
        assertTrue(joined.labels.none { it.startsWith("L") || it.startsWith("R") })
        // A purple on the right board lights its target on the left one, mirrored.
        val plan = StrategySolver(apart).solve()!!
        val mirror = plan.shots.first { it.hit == Hit.MIRROR }
        val spawned = Cell(apart.width - 1 - mirror.cell!!.x, mirror.cell.y)
        assertTrue(plan.shots.any { it.hit == Hit.TARGET && it.cell == spawned })
    }

    @Test
    fun `level 9 alternates two layouts of targets`() {
        val s = stage(9, 1)
        val moving = s.movingTargets!!
        assertEquals(2, s.frameCount)
        assertEquals(2000, s.periodMillis)
        assertEquals(16, moving.ids.size)
        // Every target is on the board in exactly one of the two layouts.
        for (id in moving.ids) assertEquals(1, moving.frames.count { id in it })
        val plan = StrategySolver(s).solve()!!
        assertEquals(16, plan.shots.size)
        // Each shot is fired on the layout its target belongs to, never both.
        for (shot in plan.shots) assertEquals(1, shot.frames.size)
    }

    @Test
    fun `the hub guns of level 6 are numbered after the border`() {
        val s = stage(6, 1)
        val border = s.guns.count { it.x == 0 || it.y == 0 || it.x == s.width - 1 || it.y == s.height - 1 }
        val hub = s.guns.indices.filter { s.guns[it].x in 1 until s.width - 1 && s.guns[it].y in 1 until s.height - 1 }
        assertEquals(8, hub.size)
        for (g in hub) assertTrue(s.labels[g].toInt() > border)
    }

    @Test
    fun `moving targets are shot in a few windows`() {
        val plan = solve(4, 2)
        // Eight targets, ten guns: the two spare guns are never pressed.
        assertEquals(8, plan.shots.size)
        assertTrue("${plan.groups.size} waits for a board that repeats every 8 s", plan.groups.size <= 3)
        for (g in plan.groups) assertTrue(g.frames.isNotEmpty())
    }

    private val handWritten = """
        stage level=99 index=1 of=1 width=6 height=4 source=test
        panel 0-1
        panel 4-5
        gap 2-3
        rules gunsblock=1 redrule=fail
        gun 0,1,R
        gun 1,3,U
        gun 5,2,L
        target 1,1
        mirror 4,2
        reds cycle 500
        frame 1,2
        frame
    """.trimIndent()

    @Test
    fun `parses a hand-written stage and plans round its mirror and its red`() {
        val s = StrategyStages.parse(handWritten).single()
        assertEquals(99, s.level)
        assertEquals(2, s.panels.size)
        assertTrue(s.isWall(2) && s.isWall(3) && !s.isWall(4))
        assertTrue(s.gunsBlock)
        assertEquals(RedRule.FAIL, s.redRule)
        assertEquals(2, s.frameCount)
        assertEquals(500, s.periodMillis)
        assertEquals(setOf(Cell(1, 2)), s.redsAt(0))
        assertEquals(emptySet<Cell>(), s.redsAt(1))
        // (1,3) sits on the corner: last of the right side and first of the bottom, which
        // is the same place in the walk, so it is L1 and the left-side gun is L2.
        assertEquals(listOf("L2", "L1", "R1"), s.labels)

        val plan = StrategySolver(s).solve()!!
        // The purple at (4,2) mirrors to (1,2), which only the bottom gun can reach -- so
        // it must not spend itself on (1,1) first, and it must fire while (1,2) is not red.
        assertEquals("L2 R1 L1", sequence(plan))
        assertEquals(listOf(Hit.TARGET, Hit.MIRROR, Hit.TARGET), plan.shots.map { it.hit })
        assertEquals(listOf(1), plan.shots.last().frames)
        assertEquals(1, plan.groups.size)
        assertEquals(0, replay(s, plan))
    }

    @Test
    fun `reports a stage that cannot be cleared`() {
        // Without the bottom gun nothing can reach the target the mirror will spawn.
        val text = handWritten.lines().filterNot { it == "gun 1,3,U" }.joinToString("\n")
        val s = StrategyStages.parse(text).single()
        assertEquals(null, StrategySolver(s).solve())
    }
}
