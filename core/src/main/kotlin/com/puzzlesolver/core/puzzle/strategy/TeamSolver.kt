package com.puzzlesolver.core.puzzle.strategy

import java.util.stream.Collectors

/**
 * Splits a stage between players, choosing the plan as well as the split.
 *
 * [StrategySolver] finds one plan that clears the stage and [TeamPlanner] splits one
 * plan between players, but a stage can often be cleared with a different gun taking a
 * different tile, and that changes which presses wait on which -- and so how well the
 * stage splits. Where two guns from opposite sides of the room can both reach a tile,
 * one way round has the far player waiting on the near one; the other leaves them both
 * free. Which plan splits best depends on how many players there are.
 *
 * So this asks the solver for its other plans, gives each a quick split for the team,
 * and splits the most promising few in full, keeping the best. The solver's own plan is
 * always one of those, so the result is never worse than splitting that alone.
 */
class TeamSolver internal constructor(
    /** The stage's plan as [StrategySolver.solve] finds it. */
    val plan: StrategyPlan,
    private val tries: Int,
    private val shortlist: Int,
) {
    constructor(plan: StrategyPlan) : this(plan, TRIES, SHORTLIST)

    /** [plan] first, then every other plan the solver found. */
    private val candidates: List<StrategyPlan> by lazy {
        listOf(plan) + StrategySolver(plan.stage).plans(tries, SEED).drop(1)
    }

    private val schedules = HashMap<Int, TeamPlan>()

    /** The split for [players] if it has already been worked out, without working it out. */
    @Synchronized
    fun scheduled(players: Int): TeamPlan? = schedules[players]

    /**
     * The best split for [players] people, of whichever plan gives it; its
     * [TeamPlan.plan] is the one to show. Memoised, and meant for a background thread.
     */
    @Synchronized
    fun schedule(players: Int): TeamPlan = schedules.getOrPut(players) {
        val others = candidates.drop(1)
        val finalists = listOf(plan) + if (others.size <= shortlist) {
            others
        } else {
            val quick = parallel(others) { TeamPlanner(it, quick = true).schedule(players).score }
            others.indices.sortedBy { quick[it] }.take(shortlist).map { others[it] }
        }
        // Ties go to the solver's own plan, which comes first.
        parallel(finalists) { TeamPlanner(it).schedule(players) }.minBy { it.score }
    }

    /**
     * [f] of each plan, in order, on as many cores as there are: every split is its own
     * seeded search, so running them side by side changes how long it takes, not what
     * comes out.
     */
    private fun <T> parallel(plans: List<StrategyPlan>, f: (StrategyPlan) -> T): List<T> =
        plans.parallelStream().map(f).collect(Collectors.toList())

    companion object {
        /** Shuffled searches for other plans; most stages have far fewer than this. */
        private const val TRIES = 24

        /** Other plans split in full after the quick look, besides the solver's own. */
        private const val SHORTLIST = 3

        /** Fixed, so a stage shows the same lanes every time it is opened. */
        private const val SEED = 11L
    }
}
