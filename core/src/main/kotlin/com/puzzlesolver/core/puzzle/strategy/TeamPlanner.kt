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
    /** What the planner scored this split at; lower is better. */
    val score: Double,
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
 * the plan does. Splitting the stage between players is choosing, within that partial
 * order, who presses what and in what order, and the rules for a good split are the
 * team's, in this order of importance:
 *
 * 1. **Even.** Every player gets the same number of presses, or one fewer. This is a
 *    hard constraint: the search only ever swaps presses between players, or moves one
 *    from a longer lane to a shorter one, so it can never unbalance the split.
 * 2. **Hand-offs early, waits late.** Where a press waits on another player's, the
 *    press it waits on belongs at the front of that player's stack and the waiting
 *    press at the back of its own. A hand-off that happens on step 1 cannot be
 *    overtaken by a player who is on step 6; one on step 5 can, if the other player is
 *    a little quick. So every ordinary press in front of a hand-off, or behind a waiting
 *    press, is penalised -- unless it has to be there because the hand-off depends on
 *    it, or it on the waiting press: "the front" is as early as a press's own
 *    dependencies allow. A wait whose margin is a single step is penalised again. And a
 *    wait with no margin at all, where equal pace would have the player arrive before
 *    the press it needs, costs more than anything else -- though on a stage that is one
 *    long chain some of that is unavoidable, since an even split means someone waits.
 * 3. **Short walks.** Each player's walk from press to press along the wall, measured
 *    mostly sideways since reaching up costs nothing like crossing the room: the total,
 *    and half again for whoever walks furthest, so no one player carries the team.
 * 4. **The level's quirks.** Which presses and on which look of the board is already
 *    settled by the plan -- reds, moving and swapping targets, walls, mirrors. What the
 *    split adds is how often a player has to stop and wait for the board to come round:
 *    every extra run of presses in a lane costs a wait, so it is scored too.
 *
 * Where all else is equal, fewer presses that wait on another player at all is
 * preferred, since a dependency inside one player's stack carries no risk.
 *
 * The search is in two levels. Simulated annealing decides who presses what, only ever
 * trading presses between players or moving one from a longer stack to a shorter one,
 * so the split stays even; each candidate split is put in order by [arrange], a rule
 * that already follows the team's rules, and scored. The best splits then get an
 * exhaustive polish that moves presses within the order and between players. It runs
 * from several starts -- a stretch of wall each, the clockwise numbering dealt out, a
 * board look each on a timed stage -- and is seeded, so a stage shows the same lanes
 * every time it is opened. Sixteen times the search effort changes the result by a few
 * percent of walking at most, so it is not stopping short.
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

    /** Every direct edge, prerequisite first: `edgeFrom[i]` must land before `edgeTo[i]`. */
    private val edgeFrom: IntArray by lazy { dependencies.flatMap { it }.toIntArray() }
    private val edgeTo: IntArray by lazy { dependencies.withIndex().flatMap { (b, ds) -> List(ds.size) { b } }.toIntArray() }

    private val prerequisites: Array<IntArray> by lazy { Array(n) { dependencies[it].toIntArray() } }
    private val dependants: Array<IntArray> by lazy {
        val out = List(n) { ArrayList<Int>() }
        for (i in edgeFrom.indices) out[edgeFrom[i]] += edgeTo[i]
        Array(n) { out[it].toIntArray() }
    }

    /** Frames each shot works on, as a bitmask; timed stages have at most a few dozen. */
    private val frameMask: LongArray by lazy {
        LongArray(n) { s -> plan.shots[s].frames.fold(0L) { m, f -> m or (1L shl f) } }
    }

    /**
     * Everything each press waits on, directly or not, and everything that waits on it,
     * as bitmasks -- a stage has at most 64 presses, which the stage itself enforces.
     */
    private val ancestorMask: LongArray by lazy {
        val out = LongArray(n)
        for (s in 0 until n) for (d in prerequisites[s]) out[s] = out[s] or out[d] or (1L shl d)
        out
    }
    private val descendantMask: LongArray by lazy {
        val out = LongArray(n)
        for (s in n - 1 downTo 0) for (d in dependants[s]) out[s] = out[s] or out[d] or (1L shl d)
        out
    }

    private val walkTable: Array<DoubleArray> by lazy {
        Array(n) { a -> DoubleArray(n) { b -> walk(gunCell(a), gunCell(b)) } }
    }

    private val schedules = HashMap<Int, TeamPlan>()

    /** The split for [players] if it has already been worked out, without working it out. */
    @Synchronized
    fun scheduled(players: Int): TeamPlan? = schedules[players]

    /**
     * The split for [players] people. Memoised, and safe to call from a background
     * thread: on a big timed stage the search takes a noticeable fraction of a second.
     */
    @Synchronized
    fun schedule(players: Int): TeamPlan = schedules.getOrPut(players) {
        require(players >= 1)
        val scorer = Scorer(players)
        var best: State? = null
        var bestCost = Double.MAX_VALUE
        for ((i, start) in starts(players).withIndex()) {
            val split = anneal(start.player, players, scorer, Random(SEED + players * 31 + i))
            // The start itself is polished too: a natural split -- a board each, a
            // stretch of wall each -- can be the best there is and still arrange badly
            // enough at first for the annealing to walk away from it.
            for (candidate in listOf(split, start.player)) {
                val st = polish(arrange(candidate, players), scorer)
                val c = scorer.score(st)
                if (c < bestCost - 1e-9) {
                    best = st
                    bestCost = c
                }
            }
        }
        val lanes = lanesOf(best ?: error("no split for $stage")).map { it.toList() }
        // Player 1 is whoever presses the lowest clockwise number: the search shuffles
        // lanes freely, and a stable order is what makes "player 2" mean something.
        val ordered = lanes.sortedBy { lane -> lane.minOfOrNull { rank(it) } ?: Int.MAX_VALUE }
        val timing = evaluate(ordered) ?: error("schedule deadlocks for $stage")
        TeamPlan(
            plan = plan,
            players = players,
            lanes = ordered.mapIndexed { p, shots -> lane(p + 1, shots) },
            makespan = timing.makespan,
            travel = timing.travel,
            dependencies = dependencies,
            score = bestCost,
        )
    }

    private fun rank(shot: Int): Int = stage.gunsByLabel.indexOf(plan.shots[shot].gun)

    /**
     * A split as the search holds it: one order for the whole stage, which always
     * respects every dependency, and a player for every press. Each player's stack is
     * their presses in that order. Holding it this way means every state the search can
     * reach is playable -- no two stacks can end up waiting on each other in a circle,
     * because both follow the one order -- and any playable split can be written this
     * way, so nothing is lost.
     */
    private class State(val order: IntArray, val player: IntArray, val players: Int) {
        fun copy() = State(order.copyOf(), player.copyOf(), players)
    }

    private fun lanesOf(st: State): List<IntArray> {
        val counts = IntArray(st.players)
        for (s in st.order) counts[st.player[s]]++
        val out = List(st.players) { IntArray(counts[it]) }
        val fill = IntArray(st.players)
        for (s in st.order) {
            val p = st.player[s]
            out[p][fill[p]++] = s
        }
        return out
    }

    /**
     * Scores a split: lower is better; see the class comment for what each term stands
     * for and why the weights are ordered as they are. One sweep along the order does
     * nearly all of it, because the order already puts every press after the presses it
     * waits for and after the one before it in its own stack. Holds its working arrays
     * so the search's hundreds of thousands of calls allocate nothing.
     */
    private inner class Scorer(private val players: Int) {
        private val pos = IntArray(n)
        private val step = IntArray(n)
        private val handsOff = BooleanArray(n)
        private val waits = BooleanArray(n)
        private val laneLen = IntArray(players)
        private val lastStep = IntArray(players)
        private val lastShot = IntArray(players)
        private val walked = DoubleArray(players)
        private val common = LongArray(players)
        private val ordinaryAll = LongArray(players)
        private val ordinarySoFar = LongArray(players)

        fun score(st: State): Double {
            laneLen.fill(0)
            lastStep.fill(0)
            lastShot.fill(-1)
            walked.fill(0.0)
            common.fill(-1L)
            var skipped = 0
            var stops = 0
            for (s in st.order) {
                val p = st.player[s]
                pos[s] = laneLen[p]++
                var ready = lastStep[p]
                for (d in prerequisites[s]) if (step[d] > ready) ready = step[d]
                step[s] = ready + 1
                skipped += step[s] - pos[s] - 1
                lastStep[p] = step[s]
                if (lastShot[p] >= 0) walked[p] += walkTable[lastShot[p]][s]
                lastShot[p] = s
                if (stage.isTimed) {
                    val next = common[p] and frameMask[s]
                    if (next == 0L) {
                        if (common[p] != -1L) stops++
                        common[p] = frameMask[s]
                    } else {
                        common[p] = next
                    }
                }
            }

            handsOff.fill(false)
            waits.fill(false)
            var cross = 0
            var tight = 0.0
            for (i in edgeFrom.indices) {
                val a = edgeFrom[i]
                val b = edgeTo[i]
                if (st.player[a] == st.player[b]) continue
                cross++
                handsOff[a] = true
                waits[b] = true
                // The margin wanted is capped by the waiting player's stack: in a stack of
                // two the best there is is first and second.
                // A margin of zero or less is a wait even at equal pace, and the skipped
                // steps already count it; this is only for orders that are legal but close.
                val wanted = minOf(SAFE_MARGIN, laneLen[st.player[b]] - 1)
                val margin = pos[b] - pos[a]
                if (margin in 1 until wanted) tight += ((wanted - margin) * (wanted - margin)).toDouble()
            }

            // Hand-offs at the front of a stack, waiting presses at the back: count every
            // ordinary press that sits in front of a hand-off, or behind a waiting press --
            // except one that has to be there, because the hand-off depends on it or it
            // depends on the waiting press. "The front" is as early as a press's own
            // dependencies allow. Several hand-offs share the front in whatever order
            // walks best.
            ordinaryAll.fill(0L)
            ordinarySoFar.fill(0L)
            for (s in 0 until n) if (!handsOff[s] && !waits[s]) ordinaryAll[st.player[s]] = ordinaryAll[st.player[s]] or (1L shl s)
            var placement = 0
            for (s in st.order) {
                val p = st.player[s]
                val give = handsOff[s]
                val take = waits[s]
                when {
                    give && !take -> placement += java.lang.Long.bitCount(ordinarySoFar[p] and ancestorMask[s].inv())
                    take && !give -> placement += java.lang.Long.bitCount(
                        ordinaryAll[p] and ordinarySoFar[p].inv() and descendantMask[s].inv(),
                    )
                    !give && !take -> ordinarySoFar[p] = ordinarySoFar[p] or (1L shl s)
                }
            }

            var total = 0.0
            var furthest = 0.0
            for (w in walked) {
                total += w
                if (w > furthest) furthest = w
            }
            return W_SKIPPED * skipped +
                W_TIGHT * tight +
                W_PLACEMENT * placement +
                W_CROSS * cross +
                total + 0.5 * furthest +
                W_WAIT * stops
        }

    }

    /**
     * Balanced starting splits over the plan's own order, which respects every
     * dependency by construction. One deals the wall out left to right, a stretch per
     * player; one deals it round the clockwise numbering; on a timed stage one groups the
     * presses that share a look of the board; one deals the plan out in turn.
     */
    private fun starts(players: Int): List<State> {
        val sizes = IntArray(players) { p -> n / players + if (p < n % players) 1 else 0 }
        fun deal(order: List<Int>): State {
            val player = IntArray(n)
            var at = 0
            for ((p, size) in sizes.withIndex()) {
                for (s in order.subList(at, at + size)) player[s] = p
                at += size
            }
            return State(IntArray(n) { it }, player, players)
        }
        val byWall = (0 until n).sortedWith(compareBy({ gunCell(it).x }, { gunCell(it).y }))
        val byLabel = (0 until n).sortedBy { rank(it) }
        val byFrame = (0 until n).sortedWith(
            compareBy({ java.lang.Long.numberOfTrailingZeros(frameMask[it]) }, { gunCell(it).x }, { gunCell(it).y }),
        )
        val dealt = State(IntArray(n) { it }, IntArray(n) { it % players }, players)
        return listOfNotNull(deal(byWall), deal(byLabel), if (stage.isTimed) deal(byFrame) else null, dealt)
    }

    /**
     * The order each player goes in, given who presses what.
     *
     * The order is dealt out in rounds, one press per player per round -- a round is a
     * step number -- so a press only goes in once everything it waits for went in an
     * earlier round. Each player takes, of the presses they could make now:
     *
     * 1. a hand-off, or a press that leads to one within their own stack, since someone
     *    else is waiting on it;
     * 2. otherwise an ordinary press;
     * 3. and a press that waits on another player only when nothing else is left.
     *
     * A press that waits on another player is held back until the press it waits for is
     * [SAFE_MARGIN] rounds behind it, unless there is nothing else to do. Ties go to a
     * press that shares the look of the board the player is already waiting for, then to
     * the shortest walk, then to the clockwise number.
     */
    private fun arrange(player: IntArray, players: Int): State {
        val handsOff = BooleanArray(n)
        val waits = BooleanArray(n)
        for (i in edgeFrom.indices) {
            if (player[edgeFrom[i]] != player[edgeTo[i]]) {
                handsOff[edgeFrom[i]] = true
                waits[edgeTo[i]] = true
            }
        }
        // A press leads to a hand-off if one is downstream of it in its own stack. The
        // plan order puts every dependant after its prerequisites, so one backwards pass.
        val leads = BooleanArray(n)
        for (s in n - 1 downTo 0) {
            leads[s] = handsOff[s] || dependants[s].any { player[it] == player[s] && leads[it] }
        }
        val round = IntArray(n) { -1 }
        val lastShot = IntArray(players) { -1 }
        val run = LongArray(players) { -1L }
        val order = IntArray(n)
        var placed = 0
        var r = 0
        while (placed < n) {
            for (p in 0 until players) {
                var best = -1
                var bestKey = 0
                var bestWalk = 0.0
                for (s in 0 until n) {
                    if (player[s] != p || round[s] >= 0) continue
                    var close = 0
                    var ready = true
                    for (d in prerequisites[s]) {
                        if (round[d] < 0 || round[d] >= r) {
                            ready = false
                            break
                        }
                        if (player[d] != p && round[d] > r - SAFE_MARGIN) close = 1
                    }
                    if (!ready) continue
                    val cls = when {
                        leads[s] && !waits[s] -> 0
                        leads[s] -> 1
                        !waits[s] -> 2
                        else -> 3
                    }
                    val frame = if (run[p] and frameMask[s] != 0L) 0 else 1
                    // Close to its prerequisite first, then class, then the board's look.
                    val key = close * 100 + cls * 10 + frame
                    val walk = if (lastShot[p] < 0) 0.0 else walkTable[lastShot[p]][s]
                    val better = best < 0 || key < bestKey || key == bestKey && (
                        walk < bestWalk - 1e-9 || walk < bestWalk + 1e-9 && rankOf[s] < rankOf[best]
                        )
                    if (better) {
                        best = s
                        bestKey = key
                        bestWalk = walk
                    }
                }
                if (best < 0) continue
                round[best] = r
                order[placed++] = best
                lastShot[p] = best
                val next = run[p] and frameMask[best]
                run[p] = if (next == 0L) frameMask[best] else next
            }
            r++
        }
        return State(order, player.copyOf(), players)
    }

    private val rankOf: IntArray by lazy { IntArray(n) { rank(it) } }

    /**
     * Simulated annealing over who presses what, from [start]; each candidate split is
     * put in order by [arrange] and scored as played. Every move keeps the split even:
     * two presses trade players, or a press moves from a longer stack to a shorter one.
     */
    private fun anneal(start: IntArray, players: Int, scorer: Scorer, random: Random): IntArray {
        var current = start
        var currentCost = scorer.score(arrange(current, players))
        var best = current
        var bestCost = currentCost
        if (n < 2 || players < 2) return best
        val steps = SEARCH_STEPS_PER_PRESS * n
        var temperature = T_START
        val cooling = Math.pow(T_END / T_START, 1.0 / steps)
        val counts = IntArray(players)
        repeat(steps) {
            temperature *= cooling
            val trial = current.copyOf()
            if (random.nextInt(3) > 0) {
                // Two presses trade players.
                val a = random.nextInt(n)
                val b = random.nextInt(n)
                if (trial[a] == trial[b]) return@repeat
                trial[a] = current[b]
                trial[b] = current[a]
            } else {
                // A press moves from a longer stack to a shorter one.
                counts.fill(0)
                for (p in trial) counts[p]++
                val longest = counts.max()
                val s = random.nextInt(n)
                if (counts[trial[s]] != longest) return@repeat
                val to = random.nextInt(players)
                if (counts[to] != longest - 1) return@repeat
                trial[s] = to
            }
            val c = scorer.score(arrange(trial, players))
            if (c <= currentCost || random.nextDouble() < Math.exp((currentCost - c) / temperature)) {
                current = trial
                currentCost = c
                if (c < bestCost - 1e-9) {
                    best = trial
                    bestCost = c
                }
            }
        }
        return best
    }

    /**
     * Where press [s] could go in [order]: the range of places, counted in the order
     * without it, after the last press it waits for and before the first press that
     * waits for it.
     */
    private fun window(order: IntArray, s: Int): Pair<Int, Int> {
        var lo = 0
        var hi = n - 1
        var k = 0
        for (x in order) {
            if (x == s) continue
            if (x in prerequisites[s]) lo = k + 1
            if (x in dependants[s] && k < hi) hi = k
            k++
        }
        return lo to hi
    }

    /** Takes [s] out of [order] and puts it back at [at], counted without it. */
    private fun insert(order: IntArray, s: Int, at: Int) {
        var from = order.indexOf(s)
        if (from < at) {
            while (from < at) {
                order[from] = order[from + 1]
                from++
            }
        } else {
            while (from > at) {
                order[from] = order[from - 1]
                from--
            }
        }
        order[at] = s
    }

    /**
     * Steepest descent after the annealing: every press to every other allowed place in
     * the order, every pair of presses traded between players, every press moved to a
     * shorter stack -- the best change taken, until none helps. Annealing gets close;
     * this makes sure nothing one move away is better.
     */
    private fun polish(start: State, scorer: Scorer): State {
        var st = start
        var current = scorer.score(st)
        while (true) {
            var bestTrial: State? = null
            var bestCost = current - 1e-9
            fun consider(trial: State) {
                val c = scorer.score(trial)
                if (c < bestCost) {
                    bestCost = c
                    bestTrial = trial
                }
            }
            for (s in 0 until n) {
                val (lo, hi) = window(st.order, s)
                for (j in lo..hi) {
                    val trial = st.copy()
                    insert(trial.order, s, j)
                    if (!trial.order.contentEquals(st.order)) consider(trial)
                }
            }
            val counts = IntArray(st.players)
            for (p in st.player) counts[p]++
            val longest = counts.max()
            for (a in 0 until n) {
                for (b in a + 1 until n) {
                    if (st.player[a] == st.player[b]) continue
                    val trial = st.copy()
                    trial.player[a] = st.player[b]
                    trial.player[b] = st.player[a]
                    consider(trial)
                }
                if (counts[st.player[a]] == longest) {
                    for (p in 0 until st.players) {
                        if (counts[p] != longest - 1) continue
                        consider(st.copy().also { it.player[a] = p })
                    }
                }
            }
            val next = bestTrial ?: return st
            st = next
            current = bestCost
        }
    }

    private class Eval(val makespan: Double, val travel: Double)

    /**
     * Runs the lanes in time -- a press takes [PRESS], walking takes a unit a cell, and a
     * press cannot start before the presses it waits on have landed -- and reports when
     * the last one lands and how far everyone walked. Null on a deadlock.
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
        return Eval(finish.maxOrNull() ?: 0.0, travel)
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

    companion object {
        /** A press, in cells walked. Pressing and watching the shot is a few strides' worth. */
        const val PRESS = 4.0

        /** Annealing moves per press. Each move orders and scores a whole split. */
        private const val SEARCH_STEPS_PER_PRESS = 200
        private const val T_START = 150.0
        private const val T_END = 0.3

        /** Fixed, so a stage shows the same lanes every time it is opened. */
        private const val SEED = 7L

        /**
         * Steps of margin a cross-player wait should have before it stops being penalised:
         * the press it waits for at least this many of the waiting player's own steps
         * earlier. Two players keep roughly the same pace, but not exactly.
         */
        private const val SAFE_MARGIN = 2

        // The weights, in cells walked. Their order is the order of the team's rules.
        /** A step a player would stand waiting even at equal pace. */
        private const val W_SKIPPED = 1000.0
        /** Squared shortfall of a cross-player wait's margin below [SAFE_MARGIN]. */
        private const val W_TIGHT = 40.0
        /** An ordinary press in front of a hand-off, or behind a waiting press. */
        private const val W_PLACEMENT = 25.0
        /** A dependency split across two players at all. */
        private const val W_CROSS = 2.0
        /** An extra stop in a lane to wait for the board to come round. */
        private const val W_WAIT = 50.0

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
