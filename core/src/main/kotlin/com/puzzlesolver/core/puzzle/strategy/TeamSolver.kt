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
 * So this asks the solver for its other plans, splits every one of them for the team,
 * and keeps the best. The solver's own plan is one of them, so the result is never worse
 * than splitting that alone. It does not run on the phone: [StrategySplits.generate]
 * runs it for every stage and team size ahead of time, which is what lets it split every
 * plan in full rather than a shortlist.
 */
class TeamSolver internal constructor(
    /** The stage's plan as [StrategySolver.solve] finds it. */
    val plan: StrategyPlan,
    private val tries: Int,
) {
    constructor(plan: StrategyPlan) : this(plan, TRIES)

    /** [plan] first, then every other plan the solver found. */
    private val candidates: List<StrategyPlan> by lazy {
        listOf(plan) + StrategySolver(plan.stage).plans(tries, SEED).drop(1)
    }

    private val schedules = HashMap<Int, TeamPlan>()

    /**
     * The best split for [players] people, of whichever plan gives it; its
     * [TeamPlan.plan] is the one to show. Memoised.
     */
    @Synchronized
    fun schedule(players: Int): TeamPlan = schedules.getOrPut(players) {
        // Every split is its own seeded search, so running them side by side on all the
        // cores changes how long it takes, not what comes out. Ties go to the solver's
        // own plan, which comes first.
        candidates.parallelStream()
            .map { TeamPlanner(it).schedule(players) }
            .collect(Collectors.toList())
            .minBy { it.score }
    }

    companion object {
        /** Shuffled searches for other plans; most stages have far fewer than this. */
        private const val TRIES = 64

        /** Fixed, so the same stage always splits the same way. */
        private const val SEED = 11L
    }
}
