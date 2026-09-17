package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.strategy.Cell
import com.puzzlesolver.core.puzzle.strategy.Hit
import com.puzzlesolver.core.puzzle.strategy.PlannedShot
import com.puzzlesolver.core.puzzle.strategy.RedRule
import com.puzzlesolver.core.puzzle.strategy.StrategyStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The room's rules, written from the rules and not from the solver: tiles are looked up
 * by walking the board, not by bitmask, and each shot is fired on the first frame it
 * claims. Shared by the solver and team-planner tests so that both a plan and any
 * reordering of it are held to the same standard: every shot lands where it says, and
 * nothing is left standing.
 */
object StrategyReplay {

    /** Replays [shots] in the order given. Returns lives lost, or fails the test. */
    fun replay(stage: StrategyStage, shots: List<PlannedShot>): Int {
        val guns = stage.guns.map { it.cell }.toMutableSet()
        val staticTargets = stage.targets.toMutableSet()
        val mirrors = stage.mirrors.toMutableSet()
        val fixedReds = stage.reds?.takeIf { it.periodMillis == 0 }?.frames?.first()?.toMutableSet() ?: mutableSetOf()
        val movingLeft = stage.movingTargets?.ids?.toMutableSet()
        var lives = 0
        for ((n, shot) in shots.withIndex()) {
            val gun = stage.guns[shot.gun]
            assertTrue("shot $n fires a spent gun ${shot.label}", guns.remove(gun.cell))
            assertTrue("shot $n has no frame", shot.frames.isNotEmpty())
            val frame = shot.frames.first()
            val movingAt = stage.movingTargets?.frames?.get(frame)?.filterKeys { it in movingLeft!! }
                ?.entries?.associate { (id, c) -> c to id } ?: emptyMap()
            val cycling = stage.reds?.takeIf { it.periodMillis > 0 }?.frames?.get(frame) ?: emptySet()
            var x = gun.x
            var y = gun.y
            var landed: Hit = Hit.MISS
            while (true) {
                x += gun.direction.dx
                y += gun.direction.dy
                if (x !in 0 until stage.width || y !in 0 until stage.height) break
                if (stage.isWall(x)) { landed = Hit.WALL; break }
                val c = Cell(x, y)
                if (c in fixedReds) {
                    assertEquals("shot $n (${shot.label}) hits a red the plan did not declare", Hit.RED, shot.hit)
                    assertEquals(RedRule.LIFE, stage.redRule)
                    fixedReds -= c
                    lives++
                    landed = Hit.RED
                    break
                }
                assertTrue("shot $n (${shot.label}) fired into a moving red on frame $frame", c !in cycling)
                if (c in staticTargets) { staticTargets -= c; landed = Hit.TARGET; break }
                movingAt[c]?.let { id -> movingLeft!!.remove(id); landed = Hit.TARGET }
                if (landed == Hit.TARGET) break
                if (c in mirrors) {
                    mirrors -= c
                    val spawned = Cell(stage.width - 1 - x, y)
                    guns -= spawned
                    staticTargets += spawned
                    landed = Hit.MIRROR
                    break
                }
                if (stage.gunsBlock && c in guns) { guns -= c; landed = Hit.GUN; break }
            }
            assertEquals("shot $n (${shot.label}) lands differently in replay", shot.hit, landed)
            if (landed != Hit.MISS) {
                assertEquals("shot $n (${shot.label}) lands on a different tile in replay", shot.cell, Cell(x, y))
            }
        }
        assertTrue("targets left standing: $staticTargets ${movingLeft ?: ""}", staticTargets.isEmpty() && movingLeft.isNullOrEmpty())
        assertTrue("purple tiles left standing: $mirrors", mirrors.isEmpty())
        return lives
    }
}
