package com.puzzlesolver.core.puzzle.strategy

import kotlin.math.abs
import kotlin.random.Random

/**
 * One player's share of a stage: their presses in order, plus which presses by other
 * players each of theirs has to wait for.
 */
class Lane(
    /** 1-based player number. */
    val player: Int,
    /** Indices into [StrategyPlan.shots], in the order this player presses them. */
    val shots: List<Int>,
    /** Runs of consecutive presses that share a look of the board, indexed into [shots]. */
    val groups: List<ShotGroup>,
    /**
     * For a shot in this lane, the shots on *other* lanes that must land first -- only
     * the ones not already implied by an earlier wait, so a chip carries one or two
     * names rather than the whole history.
     */
    val waitsFor: Map<Int, List<Int>>,
)

class TeamPlan(
    val plan: StrategyPlan,
    val players: Int,
    val lanes: List<Lane>,
    /** When the last press lands, in [TeamPlanner] time units. */
    val makespan: Double,
    /** Cells walked, all players together. */
    val travel: Double,
    /** [TeamPlanner.dependencies], kept so the steps below need no second planner. */
    private val dependencies: List<Set<Int>>,
) {
    /** Which player presses a shot. */
    fun playerOf(shot: Int): Int = lanes.first { shot in it.shots }.player

    /**
     * Each player's own count, indexed by shot.
     *
     * A press is numbered one past the later of the player's previous press and every
     * press it waits on, so two players with nothing between them both read 1, 2, 3,
     * while a player whose second press needs the other's second reads 1, 3, 4: the
     * gap is the wait, and the number says when. This is what the board and the chips
     * show, since a player standing at the wall wants "my third" and not "tile 11".
     */
    val steps: IntArray by lazy {
        val out = IntArray(plan.shots.size) { 0 }
        val deps = dependencies
        val next = IntArray(lanes.size)
        var done = 0
        while (done < out.size) {
            var progressed = false
            for ((p, lane) in lanes.withIndex()) {
                while (next[p] < lane.shots.size) {
                    val s = lane.shots[next[p]]
                    if (deps[s].any { out[it] == 0 }) break
                    val previous = if (next[p] == 0) 0 else out[lane.shots[next[p] - 1]]
                    out[s] = maxOf(previous, deps[s].maxOfOrNull { out[it] } ?: 0) + 1
                    next[p]++
                    done++
                    progressed = true
                }
            }
            check(progressed) { "lanes wait on each other in a circle for $plan" }
        }
        out
    }
}

/**
 * Splits a plan between players.
 *
 * A plan is one order that works, but most of it does not have to be in that order:
 * a shot only cares about the tiles on its own line of fire. So the presses form a
 * partial order -- shot B waits for shot A when A changes a tile B fires across, or
 * the other way round -- and any schedule that respects it clears the stage exactly as
 * the plan does. With one player that partial order is irrelevant; with several it is
 * the whole question, because a chain of dependent presses has to be walked in
 * sequence while everything off the chain can happen at the same time somewhere else.
 *
 * The schedule is built in two steps. A greedy pass first: whenever a player is free,
 * they take the shot they could land soonest, counting the walk to reach it and any
 * press it is waiting on, with ties going to the shot that has the longest chain still
 * hanging off it. That respects the dependencies well but is short-sighted about the
 * walking -- a free player will cross the whole wall for a tile that a busier player
 * standing next to it could have taken a moment later. So a local search then moves
 * and swaps presses between lanes, keeping any change that finishes the stage sooner
 * or, at the same finish, walks less. On a stage where nothing depends on anything,
 * that settles into one player per stretch of wall, which is the answer a team would
 * come up with by looking at it.
 *
 * Walking is measured along the wall, mostly sideways, since reaching up costs nothing
 * like crossing the room. Both steps are heuristics; the stages are small enough that
 * they are good ones, and the search is seeded so the same stage always gets the same
 * answer.
 */
class TeamPlanner(private val plan: StrategyPlan) {

    private val stage = plan.stage
    private val n = plan.shots.size

    /** For each shot, the earlier-in-plan shots it must come after. Direct edges only. */
    val dependencies: List<Set<Int>> by lazy { reduce(rawDependencies()) }

    /** Everything a shot waits on, directly or through other shots. */
    fun ancestors(shot: Int): Set<Int> {
        val out = HashSet<Int>()
        val todo = ArrayDeque(dependencies[shot])
        while (todo.isNotEmpty()) {
            val d = todo.removeFirst()
            if (out.add(d)) todo += dependencies[d]
        }
        return out
    }

    private fun touched(shot: PlannedShot): Set<Cell> {
        val g = stage.guns[shot.gun]
        val out = HashSet<Cell>()
        var x = g.x
        var y = g.y
        while (true) {
            x += g.direction.dx
            y += g.direction.dy
            if (x !in 0 until stage.width || y !in 0 until stage.height) break
            val c = Cell(x, y)
            out += c
            if (c == shot.cell) break
        }
        return out
    }

    private fun changed(shot: PlannedShot): Set<Cell> {
        val out = HashSet<Cell>()
        val moving = stage.movingTargets
        when (shot.hit) {
            Hit.TARGET -> if (moving != null && shot.hitIndex < stage.targetCount) {
                // A moving target is everywhere it ever is: a later shot across any of
                // those cells fires on some frame, and this is the safe over-estimate.
                val id = moving.ids[shot.hitIndex]
                for (f in moving.frames) f[id]?.let { out += it }
            } else {
                out += shot.cell!!
            }
            Hit.MIRROR -> {
                out += shot.cell!!
                out += Cell(stage.width - 1 - shot.cell.x, shot.cell.y)
            }
            Hit.GUN, Hit.RED -> out += shot.cell!!
            Hit.WALL, Hit.MISS -> Unit
        }
        // Where guns block, firing one clears its own tile for whoever fires across it.
        if (stage.gunsBlock) out += stage.guns[shot.gun].cell
        return out
    }

    private fun rawDependencies(): List<Set<Int>> {
        val touch = plan.shots.map(::touched)
        val change = plan.shots.map(::changed)
        return List(n) { j ->
            (0 until j).filter { i ->
                change[i].any { it in touch[j] } || change[j].any { it in touch[i] }
            }.toSet()
        }
    }

    /** Drops every edge implied by a longer path, so "waits for" names the nearest cause. */
    private fun reduce(deps: List<Set<Int>>): List<Set<Int>> {
        val ancestors = ArrayList<Set<Int>>()
        for (j in 0 until n) {
            val all = HashSet<Int>()
            for (d in deps[j]) {
                all += d
                all += ancestors[d]
            }
            ancestors += all
        }
        return List(n) { j ->
            deps[j].filter { d -> deps[j].none { e -> e != d && d in ancestors[e] } }.toSet()
        }
    }

    private fun walk(a: Cell, b: Cell): Double = abs(a.x - b.x) + 0.3 * abs(a.y - b.y)

    private fun gunCell(shot: Int): Cell = stage.guns[plan.shots[shot].gun].cell

    /** Longest chain of presses from a shot to the end, the classic critical-path priority. */
    private fun tail(): DoubleArray {
        val out = DoubleArray(n)
        val dependants = List(n) { ArrayList<Int>() }
        for (j in 0 until n) for (d in dependencies[j]) dependants[d] += j
        for (i in n - 1 downTo 0) {
            out[i] = PRESS + (dependants[i].maxOfOrNull { out[it] } ?: 0.0)
        }
        return out
    }

    /**
     * The best schedule for up to [players] people: fastest, then least walking.
     *
     * "Up to", because a greedy schedule is not monotone -- a fifth player can grab a
     * press whose chain then waits on them, and finish later than four would have. So
     * every smaller team is tried as well, under both priority rules, and a player the
     * best schedule does not use gets an empty lane rather than a slower stage.
     */
    fun schedule(players: Int): TeamPlan {
        require(players >= 1)
        var best: Pair<List<List<Int>>, Eval>? = null
        for (k in 1..players) {
            val candidate = bestFor(k)
            if (best == null || candidate.second.betterThan(best.second)) best = candidate
        }
        val (lanes, eval) = best!!
        val padded = lanes + List(players - lanes.size) { emptyList<Int>() }
        return TeamPlan(
            plan = plan,
            players = players,
            lanes = padded.mapIndexed { p, shots -> lane(p + 1, shots) },
            makespan = eval.makespan,
            travel = eval.travel,
            dependencies = dependencies,
        )
    }

    private val bestByTeamSize = HashMap<Int, Pair<List<List<Int>>, Eval>>()

    /**
     * The best schedule for exactly [k] lanes. Memoised and seeded per team size, so
     * the answer for three players is the same whether three or five were asked for.
     */
    private fun bestFor(k: Int): Pair<List<List<Int>>, Eval> = bestByTeamSize.getOrPut(k) {
        var best: Pair<List<List<Int>>, Eval>? = null
        for ((variant, criticalFirst) in listOf(false, true).withIndex()) {
            val lanes = improve(greedy(k, criticalFirst), Random(SEED + k * 2 + variant))
            val eval = evaluate(lanes) ?: continue
            if (best == null || eval.betterThan(best.second)) best = lanes to eval
        }
        val (lanes, eval) = best ?: error("dependency cycle in plan for $stage")
        // Player 1 is whoever presses the lowest number: the search shuffles lanes
        // freely, and a stable order is what makes "player 2" mean something.
        val ordered = lanes.sortedBy { lane -> lane.minOfOrNull { rank(it) } ?: Int.MAX_VALUE }
        ordered to eval
    }

    private fun rank(shot: Int): Int = stage.gunsByLabel.indexOf(plan.shots[shot].gun)

    private class Eval(val makespan: Double, val travel: Double) {
        fun betterThan(o: Eval): Boolean =
            makespan < o.makespan - 1e-9 || (makespan < o.makespan + 1e-9 && travel < o.travel - 1e-9)

        fun noWorseThan(o: Eval): Boolean =
            makespan < o.makespan + 1e-9 && travel < o.travel + 1e-9
    }

    /**
     * Runs the lanes as written and reports when the last press lands and how far
     * everyone walked. Null when the lanes wait on each other in a circle -- one lane's
     * next press needs a press that sits later in another lane that is itself waiting.
     */
    private fun evaluate(lanes: List<List<Int>>): Eval? {
        val finish = DoubleArray(n) { -1.0 }
        val next = IntArray(lanes.size)
        val free = DoubleArray(lanes.size)
        val at = arrayOfNulls<Cell>(lanes.size)
        var travel = 0.0
        var done = 0
        while (done < n) {
            var progressed = false
            for (p in lanes.indices) {
                val lane = lanes[p]
                while (next[p] < lane.size) {
                    val s = lane[next[p]]
                    if (dependencies[s].any { finish[it] < 0 }) break
                    val ready = dependencies[s].maxOfOrNull { finish[it] } ?: 0.0
                    val here = at[p]
                    val distance = if (here == null) 0.0 else walk(here, gunCell(s))
                    finish[s] = maxOf(free[p] + distance, ready) + PRESS
                    free[p] = finish[s]
                    at[p] = gunCell(s)
                    travel += distance
                    next[p]++
                    done++
                    progressed = true
                }
            }
            if (!progressed) return null
        }
        return Eval(finish.max(), travel)
    }

    /**
     * Hill-climbs from a schedule by moving one press to another place in the lanes, or
     * swapping two, and keeping the result whenever it is no worse. Accepting equals
     * lets it drift across plateaus -- most single moves on a stage change nothing --
     * to where an improvement is.
     */
    private fun improve(start: List<List<Int>>, random: Random): List<List<Int>> {
        if (n < 2 || start.size < 2) return start
        var lanes = start.map { it.toMutableList() }
        var current = evaluate(lanes) ?: return start
        repeat(SEARCH_STEPS) {
            val trial = lanes.map { it.toMutableList() }
            if (random.nextBoolean()) {
                // Move: pull a press out of its lane and drop it anywhere in any lane.
                val from = trial.indices.filter { trial[it].isNotEmpty() }.random(random)
                val shot = trial[from].removeAt(random.nextInt(trial[from].size))
                val to = random.nextInt(trial.size)
                trial[to].add(random.nextInt(trial[to].size + 1), shot)
            } else {
                // Swap: two presses change places, across lanes or within one.
                val a = trial.indices.filter { trial[it].isNotEmpty() }.random(random)
                val b = trial.indices.filter { trial[it].isNotEmpty() }.random(random)
                val i = random.nextInt(trial[a].size)
                val j = random.nextInt(trial[b].size)
                val t = trial[a][i]
                trial[a][i] = trial[b][j]
                trial[b][j] = t
            }
            val eval = evaluate(trial) ?: return@repeat
            if (eval.noWorseThan(current)) {
                lanes = trial
                current = eval
            }
        }
        return lanes
    }

    private fun greedy(players: Int, criticalFirst: Boolean): List<List<Int>> {
        val tail = tail()
        val finish = DoubleArray(n) { -1.0 }
        val lanes = List(players) { ArrayList<Int>() }
        val free = DoubleArray(players)
        val at = arrayOfNulls<Cell>(players)
        val pending = (0 until n).toMutableSet()
        while (pending.isNotEmpty()) {
            var best: Choice? = null
            var bestKey: DoubleArray? = null
            for (s in pending) {
                if (dependencies[s].any { finish[it] < 0 }) continue
                val ready = dependencies[s].maxOfOrNull { finish[it] } ?: 0.0
                for (p in 0 until players) {
                    val here = at[p]
                    val distance = if (here == null) 0.0 else walk(here, gunCell(s))
                    val start = maxOf(free[p] + distance, ready)
                    // Soonest landing first, then the longest chain hanging off it -- or
                    // the other way round -- then the shorter walk, then the plan's own
                    // order, which is the clockwise one.
                    val key = if (criticalFirst) {
                        doubleArrayOf(-tail[s], start, distance, s.toDouble())
                    } else {
                        doubleArrayOf(start, -tail[s], distance, s.toDouble())
                    }
                    if (bestKey == null || compare(key, bestKey) < 0) {
                        bestKey = key
                        best = Choice(s, p, start, distance)
                    }
                }
            }
            val c = best ?: error("dependency cycle in plan for $stage")
            finish[c.shot] = c.start + PRESS
            free[c.player] = finish[c.shot]
            at[c.player] = gunCell(c.shot)
            lanes[c.player] += c.shot
            pending -= c.shot
        }
        return lanes
    }

    private fun lane(player: Int, shots: List<Int>): Lane {
        val mine = shots.toSet()
        val waits = HashMap<Int, List<Int>>()
        for (s in shots) {
            val others = dependencies[s].filter { it !in mine }
            if (others.isNotEmpty()) waits[s] = others.sorted()
        }
        return Lane(player, shots, groupRuns(shots.map { plan.shots[it].frames }, stage.frameCount), waits)
    }

    private class Choice(val shot: Int, val player: Int, val start: Double, val distance: Double)

    private fun compare(a: DoubleArray, b: DoubleArray): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        /** A press, in cells walked. Pressing and watching the shot is a few strides' worth. */
        const val PRESS = 4.0

        /** Moves tried per start. Each is a few hundred operations; a stage has at most 27 presses. */
        private const val SEARCH_STEPS = 1500

        /** Fixed, so a stage shows the same lanes every time it is opened. */
        private const val SEED = 7L

        /**
         * Cuts a sequence into the fewest runs that each share a frame. Greedy is optimal
         * here: extending the current run whenever the intersection stays non-empty can
         * never force more runs later than closing it early would.
         */
        fun groupRuns(frames: List<List<Int>>, frameCount: Int): List<ShotGroup> {
            val out = ArrayList<ShotGroup>()
            var first = 0
            var common: Set<Int> = (0 until frameCount).toSet()
            for ((i, f) in frames.withIndex()) {
                val next = common.intersect(f.toSet())
                if (next.isEmpty()) {
                    out += ShotGroup(first, i - 1, common.sorted())
                    first = i
                    common = f.toSet()
                } else {
                    common = next
                }
            }
            if (frames.isNotEmpty()) out += ShotGroup(first, frames.lastIndex, common.sorted())
            return out
        }
    }
}
