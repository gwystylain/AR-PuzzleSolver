package com.puzzlesolver.core.puzzle.strategy

import kotlin.random.Random

/**
 * Finds a pressing order that clears a stage.
 *
 * Depth-first over the guns still standing, trying them in clockwise-number order so
 * the first plan found is the one that reads most like "go round the wall". Two things
 * keep that from being exponential:
 *
 * - A matching check before every expansion. Each remaining target -- and each purple
 *   tile, and the target it will spawn -- needs its own gun with it in the line of fire.
 *   If no such assignment exists the position is dead however the shots are ordered,
 *   and almost every wrong first move is caught this way at depth one. It ignores what
 *   sits between gun and target, since that may be shot away; it is a necessary
 *   condition, never a sufficient one.
 * - A memo of positions already proven dead. Shots that do not interact commute, so
 *   the same position is reached by many orders and only searched once.
 *
 * Shots into a red are allowed only under [RedRule.LIFE], and only as a last resort:
 * the search runs with a budget of zero red hits first and raises it one at a time, so
 * a plan that costs a life is returned only when no plan that does not exists. Level 4
 * has one such stage; nothing else needs it.
 *
 * On a timed stage a shot is only considered in the frames where nothing red lies in
 * its way, and the search prefers to stay in the frames of the shot before, so the plan
 * needs as few waits as the ordering allows. That preference is a heuristic, not a
 * guarantee of the fewest waits possible.
 *
 * The first plan found is not always the one a team splits best, so [plans] finds
 * others too, for [TeamSolver] to choose among.
 */
class StrategySolver(private val stage: StrategyStage) {

    /** Set while [plans] looks for other plans: the order to try guns in, instead of clockwise. */
    private var shuffle: Random? = null

    /** Positions searched so far, and how many [plans] lets one more try run to. */
    private var expanded = 0L
    private var expansionLimit = Long.MAX_VALUE

    /** Thrown to abandon a try at [expansionLimit]; without a stack trace, it costs nothing. */
    private object OutOfBudget : RuntimeException(null, null, false, false)

    private val width = stage.width
    private val cells = stage.width * stage.height
    private val gunAt = IntArray(cells) { -1 }
    private val staticTargetAt = IntArray(cells) { -1 }
    private val mirrorAt = IntArray(cells) { -1 }
    private val phantomAt = IntArray(cells) { -1 }
    private val staticRedAt = IntArray(cells) { -1 }
    private val wallColumn = BooleanArray(stage.width) { stage.isWall(it) }
    private val frameCount = stage.frameCount
    private val cyclingReds: Array<BooleanArray>?
    private val movingTargetAt: Array<IntArray>?
    private val targetCount = stage.targetCount
    private val staticReds: List<Cell>
    private val phantomCells: List<Cell> = stage.mirrors.map { Cell(width - 1 - it.x, it.y) }

    /** Position -> the largest red budget it has been proven dead with. */
    private val dead = HashMap<Position, Int>()

    init {
        for ((i, g) in stage.guns.withIndex()) gunAt[stage.cellIndex(g.x, g.y)] = i
        for ((i, c) in stage.targets.withIndex()) staticTargetAt[stage.cellIndex(c.x, c.y)] = i
        for ((i, c) in stage.mirrors.withIndex()) mirrorAt[stage.cellIndex(c.x, c.y)] = i
        for ((i, c) in phantomCells.withIndex()) phantomAt[stage.cellIndex(c.x, c.y)] = i
        val reds = stage.reds
        if (reds != null && reds.periodMillis == 0) {
            staticReds = reds.frames.first().toList()
            for ((i, c) in staticReds.withIndex()) staticRedAt[stage.cellIndex(c.x, c.y)] = i
            cyclingReds = null
        } else {
            staticReds = emptyList()
            cyclingReds = reds?.frames?.map { frame ->
                BooleanArray(cells).also { for (c in frame) it[stage.cellIndex(c.x, c.y)] = true }
            }?.toTypedArray()
        }
        require(staticReds.size <= 64) { "too many fixed reds for a bitmask" }
        movingTargetAt = stage.movingTargets?.let { moving ->
            Array(moving.frames.size) { f ->
                IntArray(cells) { -1 }.also { at ->
                    for ((id, c) in moving.frames[f]) at[stage.cellIndex(c.x, c.y)] = moving.ids.indexOf(id)
                }
            }
        }
    }

    /** Bitmasks of what is still standing. Targets include the phantoms mirrors will spawn. */
    private data class Position(val guns: Long, val targets: Long, val mirrors: Long, val reds: Long)

    private class Shot(val hit: Hit, val index: Int, val cell: Cell?)

    /** Where a shot from [gun] ends on [frame], given what is standing in [p]. Null if it meets a cycling red. */
    private fun shoot(p: Position, gun: Int, frame: Int): Shot? {
        val g = stage.guns[gun]
        var x = g.x
        var y = g.y
        while (true) {
            x += g.direction.dx
            y += g.direction.dy
            if (x < 0 || y < 0 || x >= width || y >= stage.height) return Shot(Hit.MISS, -1, null)
            if (wallColumn[x]) return Shot(Hit.WALL, -1, Cell(x, y))
            val i = y * width + x
            val cell = Cell(x, y)
            // Red is checked before whatever else is on the tile: in both transcriptions
            // a red over a target kills the shot, it does not reveal the target.
            val red = staticRedAt[i]
            if (red >= 0 && p.reds.has(red)) return Shot(Hit.RED, red, cell)
            if (cyclingReds != null && cyclingReds[frame][i]) return null
            val target = movingTargetAt?.get(frame)?.get(i) ?: staticTargetAt[i]
            if (target >= 0 && p.targets.has(target)) return Shot(Hit.TARGET, target, cell)
            val phantom = phantomAt[i]
            if (phantom >= 0 && p.targets.has(targetCount + phantom)) return Shot(Hit.TARGET, targetCount + phantom, cell)
            val mirror = mirrorAt[i]
            if (mirror >= 0 && p.mirrors.has(mirror)) return Shot(Hit.MIRROR, mirror, cell)
            val other = gunAt[i]
            if (other >= 0 && stage.gunsBlock && p.guns.has(other)) return Shot(Hit.GUN, other, cell)
        }
    }

    private fun apply(p: Position, gun: Int, shot: Shot): Position {
        var guns = p.guns.without(gun)
        var targets = p.targets
        var mirrors = p.mirrors
        var reds = p.reds
        when (shot.hit) {
            Hit.TARGET -> targets = targets.without(shot.index)
            Hit.MIRROR -> {
                mirrors = mirrors.without(shot.index)
                targets = targets.with(targetCount + shot.index)
                // The spawned target replaces whatever was on that tile, a gun included.
                val c = phantomCells[shot.index]
                val g = gunAt[stage.cellIndex(c.x, c.y)]
                if (g >= 0) guns = guns.without(g)
            }
            Hit.GUN -> guns = guns.without(shot.index)
            Hit.RED -> reds = reds.without(shot.index)
            Hit.WALL, Hit.MISS -> Unit
        }
        return Position(guns, targets, mirrors, reds)
    }

    /** Guns that are currently the first thing in some other gun's line of fire. */
    private fun blockers(p: Position): Long {
        var out = 0L
        for (g in stage.guns.indices) {
            if (!p.guns.has(g)) continue
            val s = shoot(p, g, 0) ?: continue
            if (s.hit == Hit.GUN) out = out.with(s.index)
        }
        return out
    }

    private class Move(val priority: Int, val gun: Int, val shot: Shot, val frames: List<Int>)

    /**
     * Whether [cell] is ahead of [gun] with no wall between. What is between now may be
     * gone later, so this is the loosest sense of "can hit".
     */
    private fun inLineOfFire(gun: Int, cell: Cell): Boolean {
        val g = stage.guns[gun]
        if (g.direction.dx == 0) {
            return cell.x == g.x && (cell.y - g.y) * g.direction.dy > 0
        }
        if (cell.y != g.y || (cell.x - g.x) * g.direction.dx <= 0) return false
        val lo = minOf(g.x, cell.x)
        val hi = maxOf(g.x, cell.x)
        for (x in lo + 1 until hi) if (wallColumn[x]) return false
        return true
    }

    /** The matching check: a distinct gun for everything that still has to be shot. */
    private fun feasible(p: Position): Boolean {
        val needs = ArrayList<List<Cell>>()
        for (t in 0 until targetCount) {
            if (!p.targets.has(t)) continue
            needs += stage.movingTargets?.let { m -> m.frames.mapNotNull { it[m.ids[t]] } }
                ?: listOf(stage.targets[t])
        }
        for (m in stage.mirrors.indices) {
            if (p.mirrors.has(m)) {
                needs += listOf(stage.mirrors[m])
                needs += listOf(phantomCells[m])
            } else if (p.targets.has(targetCount + m)) {
                needs += listOf(phantomCells[m])
            }
        }
        val guns = stage.guns.indices.filter { p.guns.has(it) }
        if (guns.size < needs.size) return false
        val adjacency = needs.map { cells -> guns.filter { g -> cells.any { inLineOfFire(g, it) } } }
        val matchedTo = HashMap<Int, Int>()
        fun augment(need: Int, seen: HashSet<Int>): Boolean {
            for (g in adjacency[need]) {
                if (!seen.add(g)) continue
                val current = matchedTo[g]
                if (current == null || augment(current, seen)) {
                    matchedTo[g] = need
                    return true
                }
            }
            return false
        }
        return needs.indices.all { augment(it, HashSet()) }
    }

    private fun moves(p: Position, redBudget: Int, currentFrames: Set<Int>?): List<Move> {
        val blockers = if (stage.gunsBlock) blockers(p) else 0L
        val out = ArrayList<Move>()
        for (gun in stage.gunsByLabel) {
            if (!p.guns.has(gun)) continue
            // The same gun can end differently on different frames when targets move,
            // so outcomes are collected per (what it hits), each with its frames.
            val outcomes = LinkedHashMap<Pair<Hit, Int>, Pair<Shot, ArrayList<Int>>>()
            for (f in 0 until frameCount) {
                val s = shoot(p, gun, f) ?: continue
                outcomes.getOrPut(s.hit to s.index) { s to ArrayList() }.second += f
            }
            for ((shot, frames) in outcomes.values) {
                val priority = when {
                    shot.hit == Hit.TARGET || shot.hit == Hit.MIRROR -> 0
                    shot.hit == Hit.RED && stage.redRule == RedRule.LIFE && redBudget > 0 -> 2
                    // A wasted shot is only worth a thought if it unblocks something:
                    // the gun itself was in someone's way, or the gun it destroys was.
                    stage.gunsBlock && (blockers.has(gun) || (shot.hit == Hit.GUN && blockers.has(shot.index))) -> 1
                    else -> continue
                }
                out += Move(priority, gun, shot, frames)
            }
        }
        // While [plans] looks for others, a shuffled order stands in for the clockwise
        // one; the sort is stable, so the rest of it still applies.
        shuffle?.let { out.shuffle(it) }
        // Staying in the current window comes before the clockwise order, because a
        // wait is what the player actually pays for.
        return out.sortedWith(
            compareBy<Move>({ it.priority })
                .thenBy { if (currentFrames == null || it.frames.any { f -> f in currentFrames }) 0 else 1 },
        )
    }

    private class Step(val gun: Int, val shot: Shot, val frames: List<Int>)

    private fun search(p: Position, redBudget: Int, currentFrames: Set<Int>?): List<Step>? {
        if (++expanded > expansionLimit) throw OutOfBudget
        if (p.targets == 0L && p.mirrors == 0L) return emptyList()
        if ((dead[p] ?: -1) >= redBudget) return null
        if (!feasible(p)) {
            dead[p] = Int.MAX_VALUE
            return null
        }
        for (m in moves(p, redBudget, currentFrames)) {
            val budget = if (m.shot.hit == Hit.RED) redBudget - 1 else redBudget
            val frames = m.frames.toSet()
            val next = currentFrames?.intersect(frames)?.takeIf { it.isNotEmpty() } ?: frames
            val rest = search(apply(p, m.gun, m.shot), budget, next) ?: continue
            return listOf(Step(m.gun, m.shot, m.frames)) + rest
        }
        dead[p] = maxOf(dead[p] ?: -1, redBudget)
        return null
    }

    private val start = Position(
        guns = mask(stage.guns.size),
        targets = mask(targetCount),
        mirrors = mask(stage.mirrors.size),
        reds = mask(staticReds.size),
    )

    private fun plan(steps: List<Step>): StrategyPlan {
        val shots = steps.map { s ->
            PlannedShot(s.gun, stage.labels[s.gun], s.shot.hit, s.shot.index, s.shot.cell, s.frames)
        }
        return StrategyPlan(stage, shots, TeamPlanner.groupRuns(shots.map { it.frames }, frameCount))
    }

    /** The plan, or null when the stage cannot be cleared under its rules with the lives available. */
    fun solve(): StrategyPlan? {
        // Five lives, so at most four can be spent and still finish the wave.
        for (budget in 0..4) {
            val steps = search(start, budget, null) ?: continue
            return plan(steps)
        }
        return null
    }

    /**
     * [solve]'s plan first, then others that clear the stage for the same lives: the
     * same search with the guns tried in shuffled orders, up to [tries] of them, each
     * different plan kept once.
     *
     * Plans differ in which gun takes which tile, and so in which presses wait on
     * which: one gun taking the near tile and another the far one can leave two players
     * free, where the other way round has one waiting on the other across the room. The
     * team split chooses among them. The tries share what the first search learned
     * about dead positions, so most cost a fraction of it; the few that would not are
     * cut off. The limits are counted in positions searched, not in time, so a slow
     * phone finds exactly the plans a fast one does.
     */
    fun plans(tries: Int, seed: Long): List<StrategyPlan> {
        val first = solve() ?: return emptyList()
        val out = LinkedHashMap<Set<Triple<Int, Hit, Int>>, StrategyPlan>()
        fun key(p: StrategyPlan) = p.shots.mapTo(HashSet()) { Triple(it.gun, it.hit, it.hitIndex) }
        out[key(first)] = first
        val unit = maxOf(expanded, MIN_EXPANSIONS)
        val stopAt = expanded + unit * ALL_TRIES
        try {
            for (i in 1..tries) {
                if (expanded >= stopAt) break
                shuffle = Random(seed * 1_000_003 + i)
                expansionLimit = minOf(expanded + unit * ONE_TRY, stopAt)
                val steps = try {
                    search(start, first.redHits, null)
                } catch (_: OutOfBudget) {
                    null
                } ?: continue
                val p = plan(steps)
                out.putIfAbsent(key(p), p)
            }
        } finally {
            shuffle = null
            expansionLimit = Long.MAX_VALUE
        }
        return out.values.toList()
    }

    private companion object {
        /**
         * What [plans] allows, in multiples of what the first plan took to find: one try,
         * and all of them together. The least a try is allowed, in positions searched, is
         * [MIN_EXPANSIONS] however quickly the first plan was found.
         */
        const val ONE_TRY = 2L
        const val ALL_TRIES = 10L
        const val MIN_EXPANSIONS = 2_000L

        fun mask(n: Int): Long = if (n >= 64) -1L else (1L shl n) - 1
        fun Long.has(bit: Int): Boolean = (this ushr bit) and 1L == 1L
        fun Long.with(bit: Int): Long = this or (1L shl bit)
        fun Long.without(bit: Int): Long = this and (1L shl bit).inv()
    }
}
