package com.puzzlesolver.core.puzzle.bombs

/** One press-the-black-buttons-then-press-green cycle. */
class PlannedRound(
    /** Cells to press, in no particular order -- the room does not care. */
    @JvmField val mines: IntArray,
    /** Hits landed per cell by this round. */
    @JvmField val damage: IntArray,
    /** Targets this round finishes off. */
    @JvmField val cleared: IntArray,
)

class BombPlan(
    val rounds: List<PlannedRound>,
    /**
     * True only when the mine count equals a bound no plan can beat, so this is the
     * cheapest solution that exists. False means "the best we found", which is the
     * usual answer on a big board and is worth saying out loud rather than implying
     * a minimum we have not earned.
     */
    val provenMinimal: Boolean,
) {
    val totalMines: Int get() = rounds.sumOf { it.mines.size }

    val roundCount: Int get() = rounds.size
}

sealed interface PlanOutcome {
    data class Found(val plan: BombPlan) : PlanOutcome

    /** No plan exists within the budget, and the search proved it. */
    data class Impossible(val reason: String) : PlanOutcome

    /**
     * The search ran out of time or nodes without any plan at all. Distinct from
     * [Impossible] on purpose: one says the room cannot be beaten this way, the other
     * says we did not finish looking, and showing the second as the first would be a
     * claim the user cannot check.
     */
    data class Unfinished(val reason: String, val best: BombPlan?) : PlanOutcome
}

/**
 * Works out where to put the mines.
 *
 * Three passes, run in that order for a reason: **get an answer, then get a better
 * one, then try to prove it.**
 *
 * 1. **Greedy, always.** Repeatedly take the placement that removes the most hit
 *    points, detonate when nothing more helps, repeat. Cheap, and it finishes on a
 *    seven-by-hundred board in milliseconds. Its job is to guarantee there is
 *    something to show, and to hand the later passes an upper bound to beat.
 *
 * 2. **One detonation, exact.** Iterative deepening on the mine count with a
 *    hitting-set branch and bound. Damage comes from [RoundModel], which is exact
 *    rather than optimistic, so the first plan found at size k really works and no
 *    plan of size k-1 exists. Only attempted while k is small: exhaustive search at
 *    thirty mines does not finish, and there is no point pretending otherwise.
 *
 * 3. **Several detonations, bounded.** Clearing a button turns it black and opens a
 *    line that did not exist, so two small rounds can beat one large one. The space
 *    of useful first rounds is far too large to enumerate, so this is a beam search.
 *
 * The candidate sets come from [LineOfSight], which is optimistic and therefore a
 * superset of what can really hit a button. That is what keeps pass 2 complete: a
 * mine the real mechanics allow to hit a target is always in that target's candidate
 * list, so no valid plan is branched away.
 */
class BombPlanner(
    /** Mines available. The room gives you a fixed number, shared across rounds. */
    val budget: Int,
    val maxRounds: Int = 3,
    val beamWidth: Int = 12,
    val nodeCap: Long = 8_000_000,
) {
    private class OutOfBudget : RuntimeException(null, null, false, false)

    private var nodes = 0L
    private var deadline = Long.MAX_VALUE
    private var ranOut = false

    fun plan(board: BombBoard, deadlineNanos: Long = Long.MAX_VALUE): PlanOutcome {
        nodes = 0
        deadline = deadlineNanos
        ranOut = false

        if (board.hasUnseen()) {
            return PlanOutcome.Unfinished("board not fully scanned", null)
        }
        if (board.isCleared) {
            return PlanOutcome.Found(BombPlan(emptyList(), provenMinimal = true))
        }

        val unreachable = unreachableTargets(board)
        if (unreachable.isNotEmpty()) {
            val c = board.colOf(unreachable[0])
            val r = board.rowOf(unreachable[0])
            return PlanOutcome.Impossible(
                "${unreachable.size} button(s) can never be hit, e.g. column $c row $r"
            )
        }
        if (board.countOf(Button.EMPTY) == 0) {
            return PlanOutcome.Impossible("no unlit button is free to take a mine")
        }

        val floor = lowerBound(board)
        if (floor > budget) {
            return PlanOutcome.Impossible(
                "$budget mine(s) cannot be enough: the board needs at least $floor"
            )
        }

        var bestRounds: List<PlannedRound>? = null
        var bestMines = budget + 1

        // Pass 1 -- something to show, whatever else happens.
        try {
            greedyPlan(board, budget)?.let {
                bestRounds = it
                bestMines = it.sumOf { r -> r.mines.size }
            }
        } catch (_: OutOfBudget) {
            ranOut = true
        }

        // Pass 2 -- the cheapest single detonation, searched to exhaustion.
        val top = minOf(bestMines - 1, budget, EXACT_MAX_MINES)
        var refutedEverySingleRound = top >= budget
        try {
            for (k in floor..top) {
                val mines = searchOneRound(board, k) ?: continue
                bestRounds = listOf(roundOf(board, mines))
                bestMines = k
                break
            }
        } catch (_: OutOfBudget) {
            ranOut = true
            refutedEverySingleRound = false
        }

        // Pass 3 -- can several detonations do it cheaper? Pointless once we are
        // already sitting on the theoretical floor.
        if (maxRounds > 1 && bestMines > floor) {
            try {
                val multi = searchMultiRound(board, minOf(bestMines - 1, budget))
                if (multi != null) {
                    bestRounds = multi
                    bestMines = multi.sumOf { r -> r.mines.size }
                }
            } catch (_: OutOfBudget) {
                ranOut = true
            }
        }

        val rounds = bestRounds
        return when {
            rounds != null -> PlanOutcome.Found(BombPlan(rounds, provenMinimal = bestMines == floor))

            // The only honest impossibility left. Passes 1 and 3 are heuristics -- a
            // greedy run that gets stuck and a beam that comes back empty have proved
            // nothing at all -- so failing to find a plan must never be reported as
            // there being none. Pass 2 is exhaustive, but only over one detonation and
            // only when the budget stayed inside the range it actually searched.
            refutedEverySingleRound && maxRounds == 1 -> PlanOutcome.Impossible(
                "no arrangement of $budget mine(s) clears the board in one detonation"
            )

            ranOut -> PlanOutcome.Unfinished("search hit its time or node limit", null)
            else -> PlanOutcome.Unfinished("no plan found within the search limits", null)
        }
    }

    /**
     * Buttons that can never be hit, however many rounds are spent.
     *
     * Note what this deliberately does *not* ask. "No mine can reach it right now" is
     * the wrong question, because a button standing in the way is itself clearable,
     * and clearing it turns it black and opens the line. A row like `.WWW` has two
     * whites nothing can currently see, and it is solvable in three rounds.
     *
     * The real question is whether some cell on each line could ever hold a mine once
     * everything between has been cleared. So walk out from the button in all eight
     * directions and look for a cell that is unlit now, or will be once cleared. Only
     * an unrecognised colour or the edge of the board stops that walk for good.
     *
     * Naming the button matters more than the refusal: in practice an unreachable
     * target means a misread colour has walled something in, and the column and row
     * send the user straight to it.
     */
    private fun unreachableTargets(board: BombBoard): IntArray {
        val out = ArrayList<Int>()
        for (t in board.liveTargets()) {
            if (!everReachable(board, t)) out.add(t)
        }
        return out.toIntArray()
    }

    private fun everReachable(board: BombBoard, target: Int): Boolean {
        for (d in 0 until Dir.COUNT) {
            var col = board.colOf(target) + Dir.DX[d]
            var row = board.rowOf(target) + Dir.DY[d]
            while (board.inBounds(col, row)) {
                when (board.kindAt(board.index(col, row))) {
                    // Unlit now, or cleared to unlit later -- either way a mine can
                    // stand here eventually, with a clear line back to the target.
                    Button.EMPTY, Button.WHITE, Button.BLUE, Button.MINE -> return true
                    // Transparent forever, but never takes a mine. Keep walking.
                    Button.GREEN -> Unit
                    // An unrecognised colour never clears, so this line is dead.
                    else -> break
                }
                col += Dir.DX[d]
                row += Dir.DY[d]
            }
        }
        return false
    }

    /**
     * A count of mines no plan can beat, over any number of rounds.
     *
     * Two independent arguments, whichever is stronger:
     *  - a mine fires one ray per line and only the mine itself lies on more than one
     *    of them, so it can never hit the same button twice. A button with two hit
     *    points therefore costs two placements, in one round or across several;
     *  - a mine lands at most eight hits in total.
     *
     * Both count *placements*, which is what the room charges for, so both survive
     * re-mining the same cell in a later round.
     */
    private fun lowerBound(board: BombBoard): Int {
        var total = 0
        var worst = 0
        for (t in board.liveTargets()) {
            total += board.hp[t]
            if (board.hp[t] > worst) worst = board.hp[t]
        }
        return maxOf(worst, (total + Dir.COUNT - 1) / Dir.COUNT, 1)
    }

    // ---------------------------------------------------------------- greedy

    /**
     * Take the best placement, over and over, detonating whenever nothing else in the
     * current round helps.
     *
     * Nothing clever, and that is the point -- it is the pass that guarantees an
     * answer on a board far too big to search.
     */
    private fun greedyPlan(board: BombBoard, budget: Int): List<PlannedRound>? {
        var current = board
        val rounds = ArrayList<PlannedRound>()
        var used = 0

        while (!current.isCleared && rounds.size < maxRounds) {
            val los = LineOfSight(current)
            val model = RoundModel(current, los)
            while (used + model.chosenCount < budget) {
                val pick = bestPlacement(current, los, model) ?: break
                model.choose(pick)
                if (model.isCleared) break
            }
            if (model.chosenCount == 0) return null

            val mines = model.mines()
            if (model.losesGame) return null
            val round = roundOf(current, mines)
            val next = current.afterDetonation(round.damage, mines)
            if (next.remainingHitPoints >= current.remainingHitPoints) return null

            rounds.add(round)
            used += mines.size
            current = next
        }
        return if (current.isCleared) rounds else null
    }

    /**
     * The free cell that removes the most outstanding hit points, or null if none does.
     *
     * Placements that leave a ray sitting on a hazard are rejected outright rather than
     * scored down. Losing the room is not a cost to be traded against progress, and the
     * greedy pass has no way to come back and fix it later.
     */
    private fun bestPlacement(board: BombBoard, los: LineOfSight, model: RoundModel): Int? {
        if (++nodes > nodeCap || System.nanoTime() > deadline) throw OutOfBudget()
        val before = model.shortfall
        val hazardBefore = model.hazardHits
        var bestCell = -1
        var bestGain = 0
        for (p in 0 until board.cellCount) {
            if (!model.isFree(p) || los.reachFrom(p).isEmpty()) continue
            val token = model.choose(p)
            val gain = before - model.shortfall
            val safe = model.hazardHits <= hazardBefore
            model.unchoose(p, token)
            if (safe && gain > bestGain) {
                bestGain = gain
                bestCell = p
            }
        }
        return if (bestCell < 0) null else bestCell
    }

    // ---------------------------------------------------------------- one round

    /**
     * Finds at most [limit] mines that clear [board] in one go, or null if none exist.
     * Complete: a null return is a proof, not a shrug.
     */
    private fun searchOneRound(board: BombBoard, limit: Int): IntArray? {
        val model = RoundModel(board, LineOfSight(board))
        return if (dfs(model, limit)) model.mines() else null
    }

    private fun dfs(model: RoundModel, limit: Int): Boolean {
        if (++nodes > nodeCap || (nodes and 0x3FF) == 0L && System.nanoTime() > deadline) {
            throw OutOfBudget()
        }
        if (model.isCleared) return true

        val remaining = limit - model.chosenCount
        if (remaining <= 0) return false

        // Pick the button that is hardest to satisfy. Branching on a target with two
        // candidates rather than thirty is the difference between a search that
        // finishes and one that does not.
        var pivot = -1
        var pivotSlack = Int.MAX_VALUE
        var pivotNeed = 0
        var totalNeed = 0
        for (t in model.allTargets()) {
            val need = model.needOf(t)
            if (need == 0) continue
            totalNeed += need
            var free = 0
            for (c in model.los.candidatesFor(t)) if (model.isFree(c)) free++
            if (free < need) return false
            val slack = free - need
            if (slack < pivotSlack) {
                pivotSlack = slack
                pivot = t
                pivotNeed = need
            }
        }
        // Every button is down, but that is not automatically a win: a ray may be
        // sitting on a hazard. There is nothing left to branch on at that point --
        // branching is driven by unsatisfied buttons -- so this line fails the branch
        // rather than claiming it.
        if (pivot < 0) return model.isCleared
        if ((totalNeed + Dir.COUNT - 1) / Dir.COUNT > remaining) return false

        val cands = model.los.candidatesFor(pivot)
        val free = IntArray(cands.size)
        var freeCount = 0
        for (c in cands) if (model.isFree(c)) free[freeCount++] = c

        // At least pivotNeed of these must be mined, so branch on which comes first.
        val banned = IntArray(freeCount)
        var bannedCount = 0
        var solved = false
        var i = 0
        while (i < freeCount) {
            if (freeCount - i < pivotNeed) break
            val c = free[i]
            val token = model.choose(c)
            solved = dfs(model, limit)
            if (solved) break
            model.unchoose(c, token)
            model.ban(c)
            banned[bannedCount++] = c
            i++
        }
        for (j in 0 until bannedCount) model.unban(banned[j])
        return solved
    }

    // ------------------------------------------------------------- many rounds

    private class Node(
        val board: BombBoard,
        val rounds: List<PlannedRound>,
        val minesUsed: Int,
    )

    /**
     * Beam search over detonations, looking for a plan of at most [ceiling] mines.
     *
     * Each step asks two things of every node: can the remaining budget finish the
     * board now, and if not, which small partial rounds leave it best placed. A
     * partial round earns its place by removing hit points -- not by clearing a
     * button. Knocking two blues down to white clears nothing and opens no line, and
     * is still exactly the move that turns a three-mine board into a two-mine one.
     */
    private fun searchMultiRound(board: BombBoard, ceiling: Int): List<PlannedRound>? {
        var beam = listOf(Node(board, emptyList(), 0))
        var best: List<PlannedRound>? = null
        var bestMines = ceiling + 1

        for (round in 1..maxRounds) {
            val next = ArrayList<Node>()
            for (node in beam) {
                val left = bestMines - 1 - node.minesUsed
                if (left <= 0) continue

                finishFrom(node, left)?.let {
                    val total = node.minesUsed + it.sumOf { r -> r.mines.size }
                    if (total < bestMines) {
                        bestMines = total
                        best = node.rounds + it
                    }
                }
                if (round < maxRounds) next.addAll(partialRounds(node, left))
            }
            if (next.isEmpty()) break
            beam = next
                .sortedWith(compareBy({ it.minesUsed }, { it.board.remainingHitPoints }))
                .take(beamWidth)
        }
        return best
    }

    /** The cheapest way to end it from here: exact while that is affordable, greedy otherwise. */
    private fun finishFrom(node: Node, left: Int): List<PlannedRound>? {
        val floor = lowerBound(node.board)
        if (floor > left) return null
        for (k in floor..minOf(left, EXACT_MAX_MINES)) {
            val mines = searchOneRound(node.board, k) ?: continue
            return listOf(roundOf(node.board, mines))
        }
        return greedyPlan(node.board, left)
    }

    /**
     * Candidate partial rounds for [node]: greedy placements of a few sizes, each
     * seeded from a different opening mine so the beam gets genuinely distinct boards
     * rather than several spellings of one idea.
     */
    private fun partialRounds(node: Node, budgetLeft: Int): List<Node> {
        val board = node.board
        val los = LineOfSight(board)
        val seeds = topOpeningMines(board, los, beamWidth)
        val out = ArrayList<Node>()

        for (seed in seeds) {
            var size = 1
            while (size <= minOf(budgetLeft - 1, MAX_PARTIAL_ROUND)) {
                if (++nodes > nodeCap || System.nanoTime() > deadline) throw OutOfBudget()
                val mines = greedyRound(board, los, seed, size)
                if (mines.size < size) break
                if (firesOnHazard(board, mines)) {
                    size++
                    continue
                }
                val round = roundOf(board, mines)
                val child = board.afterDetonation(round.damage, mines)
                // A round that removes nothing leaves every line exactly as it was, so
                // it cannot help a later round. Spending mines on it is pure loss.
                if (child.remainingHitPoints < board.remainingHitPoints) {
                    out.add(Node(child, node.rounds + round, node.minesUsed + mines.size))
                }
                size++
            }
        }
        return out
    }

    /** Cells whose single-mine damage is highest -- the openings worth exploring. */
    private fun topOpeningMines(board: BombBoard, los: LineOfSight, n: Int): IntArray {
        val scored = ArrayList<IntArray>()
        for (p in 0 until board.cellCount) {
            if (!board.isPlaceable(p)) continue
            var score = 0
            for (t in los.reachFrom(p)) score += if (board.hp[t] == 1) 2 else 1
            if (score > 0) scored.add(intArrayOf(score, p))
        }
        scored.sortWith(compareByDescending { it[0] })
        return IntArray(minOf(n, scored.size)) { scored[it][1] }
    }

    /** Adds mines one at a time from a fixed opening, each the best available. */
    private fun greedyRound(board: BombBoard, los: LineOfSight, seed: Int, size: Int): IntArray {
        val model = RoundModel(board, los)
        model.choose(seed)
        while (model.chosenCount < size) {
            val pick = bestPlacement(board, los, model) ?: break
            model.choose(pick)
        }
        return model.mines()
    }

    /** Cross-check against the tick simulation: would this round lose the room? */
    private fun firesOnHazard(board: BombBoard, mines: IntArray): Boolean =
        Detonation().fire(board, mines).losesGame

    private fun roundOf(board: BombBoard, mines: IntArray): PlannedRound {
        val model = RoundModel(board, LineOfSight(board))
        for (m in mines) model.choose(m)
        val cleared = ArrayList<Int>()
        for (t in model.allTargets()) if (model.hits[t] >= board.hp[t]) cleared.add(t)
        return PlannedRound(mines, model.hits.copyOf(), cleared.toIntArray())
    }

    private companion object {
        /**
         * Above this, exhaustive single-round search does not finish in any budget
         * worth waiting for, and the greedy answer is what the user gets. Chosen so
         * the exact pass stays viable on the boards where it can actually prove
         * something.
         */
        const val EXACT_MAX_MINES = 10

        /**
         * A softening round bigger than this is almost certainly better spent
         * finishing the job, and the beam pays for every size it explores.
         */
        const val MAX_PARTIAL_ROUND = 4
    }
}
