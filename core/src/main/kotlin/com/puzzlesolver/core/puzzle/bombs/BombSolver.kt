package com.puzzlesolver.core.puzzle.bombs

import com.puzzlesolver.core.solve.CellObservation
import com.puzzlesolver.core.solve.IncrementalSolver
import com.puzzlesolver.core.solve.ObservationDelta
import com.puzzlesolver.core.solve.PuzzleSolution
import com.puzzlesolver.core.solve.SolutionPolicy
import com.puzzlesolver.core.solve.SolveOutcome
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feeds scanned button colours to [BombPlanner] and turns the plan into a solution.
 *
 * The policy is [SolutionPolicy.REQUIRES_FULL_SCAN], and not as a hedge. Where a mine
 * goes depends on the whole board at once: a white button at the far end of the wall
 * changes what is worth mining at this end, because a mine that reaches it from here
 * is a mine not spent elsewhere. No partial reading pins the answer down, so
 * answering early would mean answering wrong. That is the exact opposite of sudoku,
 * and the reason the two policies exist.
 *
 * **Why planning runs on its own thread.** The pipeline gives each solve step about
 * 12 ms and expects [SolveOutcome.Pending] back if that runs out. That contract
 * assumes a solver whose search can be suspended and resumed, which constraint
 * propagation can and a branch-and-bound over mine placements cannot -- there is no
 * meaningful state to hand back after 12 ms of a hitting-set search. A full-size
 * board needs somewhere around a tenth of a second. So planning is handed to a worker
 * and every call returns `Pending` until the worker has an answer. Simply taking
 * longer than the deadline would be the easy alternative, and it would quietly hold
 * up every other solve step behind it.
 *
 * New observations supersede a running plan rather than merging into it: the board it
 * was working from is stale, and the search is short enough that finishing it to
 * throw the result away is cheaper than trying to patch it.
 */
class BombSolver(
    private val cols: Int,
    private val rows: Int,
    @Volatile var maxRounds: Int = 3,
    private val rules: BombRules = BombRules(),
) : IncrementalSolver {

    override val policy = SolutionPolicy.REQUIRES_FULL_SCAN

    private val kinds = ByteArray(cols * rows) { Button.UNSEEN.toByte() }
    private val confidence = FloatArray(cols * rows)

    private val planner = Executors.newSingleThreadExecutor(DaemonThreads)
    private val generation = AtomicInteger()

    @Volatile
    private var planned: Planned? = null

    @Volatile
    private var planningGeneration = -1

    private class Planned(val generation: Int, val outcome: PlanOutcome)

    override fun reset() {
        kinds.fill(Button.UNSEEN.toByte())
        confidence.fill(0f)
        generation.incrementAndGet()
        planned = null
        planningGeneration = -1
    }

    /** Releases the planning thread. The pipeline calls this when the scan is torn down. */
    fun close() {
        planner.shutdownNow()
    }

    override fun observe(delta: ObservationDelta, deadlineNanos: Long): SolveOutcome {
        var changed = false
        for (c in delta.cells) changed = record(c) || changed
        for (c in delta.revised) changed = record(c) || changed
        if (changed) {
            // Any plan in flight was built from a board we no longer believe.
            generation.incrementAndGet()
            planned = null
        }

        val unseen = kinds.count { it.toInt() == Button.UNSEEN }
        if (unseen > 0 || !delta.fullyScanned) return SolveOutcome.NeedMoreData(unseen)

        val current = generation.get()
        planned?.let { if (it.generation == current) return interpret(it.outcome) }

        if (planningGeneration != current) {
            planningGeneration = current
            val board = BombBoard.of(cols, rows, kinds, rules)
            val budget = mineCap(board)
            val rounds = maxRounds
            planner.execute {
                val outcome = BombPlanner(budget = budget, maxRounds = rounds).plan(board)
                if (generation.get() == current) planned = Planned(current, outcome)
            }
        }
        return SolveOutcome.Pending
    }

    private fun record(c: CellObservation): Boolean {
        val i = c.row * cols + c.col
        if (i !in kinds.indices) return false
        val button = c.value.coerceIn(Button.UNSEEN, Button.OPAQUE).toByte()
        val differs = kinds[i] != button
        kinds[i] = button
        confidence[i] = c.confidence
        return differs
    }

    private fun interpret(outcome: PlanOutcome): SolveOutcome = when (outcome) {
        is PlanOutcome.Found -> SolveOutcome.Solved(solutionOf(outcome.plan))

        // An unreachable button almost always means a misread colour has walled one in
        // -- a dark blue taken for an unknown, say. Naming the suspects lets the
        // pipeline drop their confidence and look again, which beats refusing to solve
        // a board that is probably fine.
        is PlanOutcome.Impossible -> SolveOutcome.Contradiction(outcome.reason, suspects())

        is PlanOutcome.Unfinished -> SolveOutcome.NeedMoreData(-1)
    }

    /**
     * Cells worth a second look when the board comes back impossible: every button
     * still standing, plus what hems it in, least confident first. Returning nothing
     * here would mean nothing gets re-read and the solver contradicts forever, so this
     * always names something.
     */
    private fun suspects(): List<CellObservation> {
        val out = LinkedHashMap<Int, CellObservation>()
        for (i in kinds.indices) {
            val k = kinds[i].toInt()
            if (k != Button.WHITE && k != Button.BLUE && k != Button.OPAQUE) continue
            addSuspect(out, i)
            val col = i % cols
            val row = i / cols
            for (d in 0 until Dir.COUNT) {
                val nc = col + Dir.DX[d]
                val nr = row + Dir.DY[d]
                if (nc in 0 until cols && nr in 0 until rows) addSuspect(out, nr * cols + nc)
            }
        }
        return out.values.sortedBy { it.confidence }.take(MAX_SUSPECTS)
    }

    private fun addSuspect(into: MutableMap<Int, CellObservation>, index: Int) {
        into.getOrPut(index) {
            CellObservation(index % cols, index / cols, kinds[index].toInt(), confidence[index])
        }
    }

    /**
     * Encodes the plan as a bitmask per cell: bit `n` set means press that button in
     * detonation `n + 1`. [CellObservation.EMPTY] marks buttons to leave alone.
     *
     * A mask rather than a round number, because the same button really can be pressed
     * in more than one round. A mine is spent by the detonation it takes part in and
     * the button goes dark again, so re-mining it later is legal, and on a board where
     * only one cell is unlit it is the only way through. Storing the round number
     * alone silently kept the last one and lost the rest.
     *
     * The round is the part the user actually needs either way. Pressing the right
     * cells in the wrong order clears nothing, because each round is planned against
     * the board the round before it leaves behind.
     */
    private fun solutionOf(plan: BombPlan): PuzzleSolution {
        val values = IntArray(cols * rows) { CellObservation.EMPTY }
        val given = BooleanArray(cols * rows)
        for ((n, round) in plan.rounds.withIndex()) {
            for (m in round.mines) {
                values[m] = (if (values[m] == CellObservation.EMPTY) 0 else values[m]) or (1 shl n)
            }
        }
        for (i in kinds.indices) given[i] = kinds[i].toInt() != Button.EMPTY

        val label = buildString {
            append(plan.totalMines).append(" mine")
            if (plan.totalMines != 1) append('s')
            append(" in ").append(plan.roundCount).append(" detonation")
            if (plan.roundCount != 1) append('s')
            append(if (plan.provenMinimal) " (proven fewest)" else " (fewest found)")
        }
        return PuzzleSolution(cols, rows, values, given, label = label)
    }

    private object DaemonThreads : ThreadFactory {
        override fun newThread(r: Runnable): Thread =
            Thread(r, "bomb-planner").apply { isDaemon = true }
    }

    /**
     * Readers for the bitmask encoding above, so the overlay and the tests decode it
     * one way instead of each rolling their own shift.
     */
    /**
     * An upper bound on how many mines the search may spend.
     *
     * There is no budget to ask about: the room always admits a solution, and the only
     * useful answer is the cheapest one, so the planner is asked for a minimum rather
     * than for something that fits a number. It still needs *a* ceiling to bound its
     * search, and the board supplies one.
     *
     * Total remaining hit points works, and cannot exclude a real solution. The greedy
     * pass only ever takes a placement that removes at least one hit point, so it
     * terminates having spent at most that many mines; any cheaper plan the exact and
     * multi-round passes find is then bounded below it. A board needing more mines than
     * it has hit points would need a mine that achieves nothing, which no pass will buy.
     */
    private fun mineCap(board: BombBoard): Int = board.remainingHitPoints.coerceAtLeast(1)

    companion object {
        private const val MAX_SUSPECTS = 24

        /** Cells to press in detonation [round], counting from one. */
        fun minesForRound(solution: PuzzleSolution, round: Int): IntArray {
            val bit = 1 shl (round - 1)
            var n = 0
            for (v in solution.values) if (v > 0 && v and bit != 0) n++
            val out = IntArray(n)
            var w = 0
            for (i in solution.values.indices) {
                val v = solution.values[i]
                if (v > 0 && v and bit != 0) out[w++] = i
            }
            return out
        }

        /** Presses in total, counting a button pressed in two rounds as two mines. */
        fun totalMines(solution: PuzzleSolution): Int =
            solution.values.sumOf { if (it > 0) Integer.bitCount(it) else 0 }

        fun roundCount(solution: PuzzleSolution): Int {
            var mask = 0
            for (v in solution.values) if (v > 0) mask = mask or v
            return if (mask == 0) 0 else 32 - Integer.numberOfLeadingZeros(mask)
        }
    }
}
