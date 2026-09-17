package com.puzzlesolver.core.puzzle.strategy

/** What a shot ends on. */
enum class Hit {
    TARGET,
    MIRROR,
    /** A fixed red tile, taken on purpose: it costs a life and clears the tile. */
    RED,
    /** Another gun, which is destroyed. Only possible where guns block. */
    GUN,
    /** The wall between two panels. */
    WALL,
    /** Off the far edge. */
    MISS,
}

/** One press. */
class PlannedShot(
    /** Index into [StrategyStage.guns]. */
    val gun: Int,
    /** The clockwise number to press. */
    val label: String,
    val hit: Hit,
    /**
     * Which thing it hits, in the solver's own numbering: a target's index (moving ids
     * in [MovingTargets.ids] order, then one slot per mirror for the target it spawns),
     * a mirror's index, a gun's index, or a fixed red's index. -1 for a miss.
     */
    val hitIndex: Int,
    /** Where the shot lands, when it lands on something. */
    val cell: Cell?,
    /**
     * Frames of the cycle during which this shot is safe -- or, for moving targets,
     * hits the target it is meant to. Every frame, for a static stage.
     */
    val frames: List<Int>,
)

/**
 * A run of consecutive shots that can all be fired in one look of the board.
 *
 * The plan is presented in these rather than shot by shot because the player's real
 * cost on a timed stage is waiting for the reds to move: a run of ten presses that all
 * work in the same window is one wait, not ten.
 */
class ShotGroup(
    val first: Int,
    val last: Int,
    /** Frames common to every shot in the run. */
    val frames: List<Int>,
) {
    val frame: Int get() = frames.first()
    val size: Int get() = last - first + 1
}

/** What is standing on the board at some point in a plan. Cells are positions; ids are moving targets. */
class BoardState(
    val guns: Set<Int>,
    val targets: Set<Cell>,
    val movingTargets: Set<Int>,
    val mirrors: Set<Cell>,
    val fixedReds: Set<Cell>,
)

class StrategyPlan(
    val stage: StrategyStage,
    val shots: List<PlannedShot>,
    val groups: List<ShotGroup>,
) {
    val redHits: Int get() = shots.count { it.hit == Hit.RED }

    /** The group a shot belongs to. */
    fun groupOf(shot: Int): ShotGroup = groups.first { shot in it.first..it.last }

    /**
     * The board before each shot, then after the last -- `states[k]` is what the player
     * sees when pressing shot `k`. Replayed from the shots rather than kept from the
     * search so the screen shows exactly what the plan claims.
     */
    val states: List<BoardState> by lazy { replay { true } }

    /**
     * The board once exactly the shots in [done] have landed, in plan order. For a
     * stage split between players there is no one "step k": what a player sees when
     * they press depends on how far the others have got, and the one thing certain is
     * that the presses theirs waits on have happened.
     */
    fun stateAfter(done: Set<Int>): BoardState = replay { it in done }.last()

    private fun replay(include: (Int) -> Boolean): List<BoardState> {
        val out = ArrayList<BoardState>(shots.size + 1)
        var guns = stage.guns.indices.toSet()
        var targets = stage.targets.toSet()
        var moving = stage.movingTargets?.ids?.toSet() ?: emptySet()
        var mirrors = stage.mirrors.toSet()
        var reds = stage.reds?.takeIf { it.periodMillis == 0 }?.frames?.first() ?: emptySet()
        out += BoardState(guns, targets, moving, mirrors, reds)
        val staticCount = stage.targetCount
        for ((i, s) in shots.withIndex()) {
            if (!include(i)) {
                out += out.last()
                continue
            }
            guns = guns - s.gun
            when (s.hit) {
                Hit.TARGET -> when {
                    stage.movingTargets != null && s.hitIndex < staticCount ->
                        moving = moving - stage.movingTargets.ids[s.hitIndex]
                    else -> targets = targets - s.cell!!
                }
                Hit.MIRROR -> {
                    mirrors = mirrors - s.cell!!
                    val spawned = Cell(stage.width - 1 - s.cell.x, s.cell.y)
                    guns = guns - stage.guns.indices.filter { stage.guns[it].cell == spawned }.toSet()
                    targets = targets + spawned
                }
                Hit.GUN -> guns = guns - s.hitIndex
                Hit.RED -> reds = reds - s.cell!!
                Hit.WALL, Hit.MISS -> Unit
            }
            out += BoardState(guns, targets, moving, mirrors, reds)
        }
        return out
    }
}
