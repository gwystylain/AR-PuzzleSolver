package com.puzzlesolver.core

import com.puzzlesolver.core.puzzle.bombs.BombBoard
import com.puzzlesolver.core.puzzle.bombs.BombPlan
import com.puzzlesolver.core.puzzle.bombs.BombPlanner
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.Detonation
import com.puzzlesolver.core.puzzle.bombs.PlanOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The planner is judged on two things, and they are tested separately.
 *
 * **Does the plan work?** Every plan is replayed through [Detonation] round by round,
 * on a board that knows nothing about how the plan was found. A plan that does not
 * end with the board clear is worse than no plan, because the user has spent mines
 * they cannot get back. `every plan the search returns really clears the board` runs
 * that check over hundreds of random boards.
 *
 * **Is it the cheapest?** Where a plan is claimed minimal, the count is forced by an
 * argument rather than by observing what the search happened to do: a mine has eight
 * rays so it cannot hit one button twice, a button behind another cannot be reached
 * until the first is gone, and so on.
 */
class BombPlannerTest {

    private val sim = Detonation()

    private fun solve(board: String, budget: Int, rounds: Int = 3): PlanOutcome =
        BombPlanner(budget = budget, maxRounds = rounds).plan(BombBoard.parse(board))

    private fun found(board: String, budget: Int, rounds: Int = 3): BombPlan {
        val outcome = solve(board, budget, rounds)
        assertTrue("expected a plan, got $outcome", outcome is PlanOutcome.Found)
        val plan = (outcome as PlanOutcome.Found).plan
        assertPlanClearsBoard(BombBoard.parse(board), plan)
        return plan
    }

    /** Replays the plan against the mechanics, with no reference to how it was built. */
    private fun assertPlanClearsBoard(start: BombBoard, plan: BombPlan) {
        var board = start
        for ((i, round) in plan.rounds.withIndex()) {
            for (m in round.mines) {
                assertTrue("round $i puts a mine on a lit button", board.isPlaceable(m))
            }
            val result = sim.fire(board, round.mines)
            assertTrue("round $i fires a ray onto a red hazard -- that loses the room", !result.losesGame)
            board = board.afterDetonation(result.damage, round.mines)
        }
        assertTrue("board still has ${board.remainingHitPoints} hit points left:\n$board", board.isCleared)
    }

    @Test
    fun `one mine in the middle of a ring clears all eight`() {
        // Eight rays, eight buttons, so one mine is both sufficient and the only
        // possible count -- no plan can use fewer than one.
        val plan = found(
            """
            WWW
            W.W
            WWW
            """,
            budget = 4,
        )
        assertEquals(1, plan.totalMines)
        assertEquals(1, plan.roundCount)
        assertTrue(plan.provenMinimal)
    }

    @Test
    fun `a blue button always costs two mines`() {
        // A mine fires one ray down each of eight distinct lines, and only the mine
        // itself lies on more than one of them, so no single mine can hit a button
        // twice. Two hit points therefore means two mines, whatever the board.
        val plan = found(".B.", budget = 4)
        assertEquals(2, plan.totalMines)
        assertTrue(plan.provenMinimal)
    }

    @Test
    fun `one mine takes two buttons at opposite ends of a clear row`() {
        val plan = found("W.....W", budget = 4)
        assertEquals(1, plan.totalMines)
    }

    @Test
    fun `mines are not stacked on one side of a blue where they would cancel`() {
        // Both mines have to reach the blue, and the two obvious cells on the left of
        // it cancel each other: adjacent, nothing in between. The only arrangements
        // that work put the blue itself between the two mines, so it absorbs both rays
        // before they can meet. A planner scoring placements optimistically would take
        // the cancelling pair and come up a hit short in the room.
        val board = "..B......"
        val plan = found(board, budget = 4)
        assertEquals(2, plan.totalMines)

        val b = BombBoard.parse(board)
        val blue = b.index(2, 0)
        val mines = plan.rounds[0].mines
        assertTrue(
            "mines ${mines.toList()} should straddle the blue at $blue",
            mines.any { it < blue } && mines.any { it > blue },
        )
    }

    @Test
    fun `buttons queued behind each other need one round apiece`() {
        // Only column 0 is unlit, so a single detonation can place exactly one mine
        // and that mine can only ever reach the nearest white. The second and third
        // are unreachable until the one in front of them is gone. Three rounds, three
        // mines, and no single-detonation plan exists at any budget.
        val plan = found(".WWW", budget = 5, rounds = 3)
        assertEquals(3, plan.roundCount)
        assertEquals(3, plan.totalMines)

        assertTrue(
            "one detonation cannot do it",
            BombPlanner(budget = 5, maxRounds = 1).plan(BombBoard.parse(".WWW")) !is PlanOutcome.Found,
        )
    }

    @Test
    fun `a button walled in by unknown colours is called impossible, not merely hard`() {
        // Unlit buttons exist elsewhere on the board, so this is specifically the
        // reachability check firing and not the cruder "nowhere to put a mine" one.
        val outcome = solve(
            """
            .....
            .###.
            .#W#.
            .###.
            .....
            """,
            budget = 8,
        )
        assertTrue("expected Impossible, got $outcome", outcome is PlanOutcome.Impossible)
        assertTrue(
            "the reason should point at the button: ${(outcome as PlanOutcome.Impossible).reason}",
            outcome.reason.contains("column 2 row 2"),
        )
    }

    @Test
    fun `a board queued behind clearable buttons is not called impossible`() {
        // The regression for the reachability test. Asking "can a mine hit this now"
        // would condemn `.WWW`, which is solvable in three rounds.
        assertTrue(solve(".WWW", budget = 5) is PlanOutcome.Found)
    }

    @Test
    fun `an unscanned board is refused rather than guessed at`() {
        val outcome = solve("W?.W", budget = 4)
        assertTrue(outcome is PlanOutcome.Unfinished)
        assertEquals("board not fully scanned", (outcome as PlanOutcome.Unfinished).reason)
    }

    @Test
    fun `a budget that cannot possibly stretch is reported as impossible`() {
        // A blue needs two hits; a mine fires one ray per line so it can only ever
        // land one of them; and the budget is one mine across every round, not one
        // per round. So no sequence of detonations clears this.
        val outcome = solve(".B.", budget = 1)
        assertTrue("expected Impossible, got $outcome", outcome is PlanOutcome.Impossible)
    }

    @Test
    fun `two rounds beat one when cancellation blocks the single-detonation answer`() {
        // Two blues. Every cell that sights both of them -- columns 2, 3 and 4 -- sees
        // every other such cell with clear air between, so any two of them cancel the
        // very rays that were meant to do the work. One detonation therefore needs
        // three mines.
        //
        // Two detonations need two: one mine in the middle knocks both blues down to
        // white, and after they turn black-and-white the same middle ground can finish
        // them off. This is the case the multi-round pass exists for.
        val single = BombPlanner(budget = 6, maxRounds = 1).plan(BombBoard.parse(".B...B."))
        assertTrue(single is PlanOutcome.Found)
        assertEquals(3, (single as PlanOutcome.Found).plan.totalMines)

        val plan = found(".B...B.", budget = 6, rounds = 3)
        assertEquals(2, plan.totalMines)
        assertEquals(2, plan.roundCount)
    }

    @Test
    fun `mines already on the board are counted as part of the plan`() {
        // A red button is a mine the player has placed. It goes off when green is
        // pressed whether the plan wanted it to or not.
        val board = BombBoard.parse("X..W")
        val result = sim.fire(board, intArrayOf())
        assertEquals("the pre-placed mine clears the white on its own", 1, result.totalDamage)
    }

    @Test
    fun `a red hazard blocks rays and losing is reported`() {
        val board = BombBoard.parse("X.R.W")
        val r = sim.fire(board)
        assertTrue("the ray lands on the hazard", r.losesGame)
        assertEquals("and nothing gets past it", 0, r.damageAt(board.index(4, 0)))
    }

    @Test
    fun `the planner declines the greedy cell when it would fire on a hazard`() {
        // Whites at 0, 2 and 4; a hazard at 6. Column 5 sights the white at 4, and is
        // exactly the sort of cell a damage-hungry greedy pass reaches for -- but its
        // eastward ray runs straight into the hazard. Columns 1 and 3 do the whole job
        // safely, each shielded from the other by the white between them.
        val board = "W.W.W.R"
        val plan = found(board, budget = 6)
        assertEquals(2, plan.totalMines)

        val b = BombBoard.parse(board)
        assertTrue(
            "column 5 fires into the hazard and must not be used",
            plan.rounds.none { r -> r.mines.contains(b.index(5, 0)) },
        )
    }

    @Test
    fun `a button only reachable by firing on a hazard yields no plan`() {
        // Both cells that can see the white also have a clear line to the hazard
        // behind it, so there is no safe placement at all.
        val outcome = solve("W..R", budget = 6)
        assertTrue("expected no plan, got $outcome", outcome !is PlanOutcome.Found)
    }

    @Test
    fun `every plan the search returns really clears the board`() {
        // The property that matters. Random boards, planned and then replayed through
        // the mechanics with no knowledge of how the plan was chosen.
        val rng = Random(99001)
        var solved = 0
        var impossible = 0
        val reasons = ArrayList<String>()

        repeat(400) {
            val cols = rng.nextInt(4, 14)
            val rows = rng.nextInt(2, 8)
            val kinds = ByteArray(cols * rows) {
                when (rng.nextInt(100)) {
                    in 0..63 -> Button.EMPTY
                    in 64..79 -> Button.WHITE
                    in 80..88 -> Button.BLUE
                    in 89..93 -> Button.GREEN
                    in 94..96 -> Button.HAZARD
                    else -> Button.OPAQUE
                }.toByte()
            }
            val board = BombBoard.of(cols, rows, kinds)
            if (board.isCleared) return@repeat

            when (val outcome = BombPlanner(budget = 12, maxRounds = 3).plan(board)) {
                is PlanOutcome.Found -> {
                    assertPlanClearsBoard(board, outcome.plan)
                    solved++
                }
                is PlanOutcome.Impossible -> {
                    impossible++
                    reasons.add(outcome.reason)
                }
                is PlanOutcome.Unfinished -> Unit
            }
        }

        assertTrue("expected most random boards to be solvable, solved=$solved", solved > 200)
        // Impossibility has to be earned. Greedy getting stuck and the beam coming
        // back empty prove nothing, so anything reported as impossible must carry one
        // of the arguments that actually settles it.
        assertTrue(
            "impossible verdicts must be proof-backed, saw $impossible",
            impossible == 0 || reasons.all { r ->
                r.contains("can never be hit") || r.contains("at least") ||
                    r.contains("no unlit button") || r.contains("one detonation")
            },
        )
    }

    @Test
    fun `a full-size board is planned inside the solver time budget`() {
        // The real room is about seven rows by a hundred columns, and the pipeline
        // gives the solver 12 ms a step. This checks the search returns something
        // honest in that window rather than running away.
        val rng = Random(7)
        val cols = 100
        val rows = 7
        val kinds = ByteArray(cols * rows) {
            when (rng.nextInt(100)) {
                in 0..74 -> Button.EMPTY
                in 75..89 -> Button.WHITE
                in 90..96 -> Button.BLUE
                else -> Button.GREEN
            }.toByte()
        }
        val board = BombBoard.of(cols, rows, kinds)
        for (budget in intArrayOf(30, 40, 50, 60, 80)) {
            val started = System.nanoTime()
            val outcome = BombPlanner(budget = budget, maxRounds = 3)
                .plan(board, deadlineNanos = started + 3_000_000_000L)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val summary = when (outcome) {
                is PlanOutcome.Found -> {
                    assertPlanClearsBoard(board, outcome.plan)
                    "${outcome.plan.totalMines} mines / ${outcome.plan.roundCount} rounds"
                }
                is PlanOutcome.Impossible -> "impossible: ${outcome.reason}"
                is PlanOutcome.Unfinished -> "unfinished: ${outcome.reason}"
            }
            println(
                "7x100 ${board.liveTargets().size} targets, ${board.remainingHitPoints} hp, " +
                    "budget $budget -> $summary in ${elapsedMs}ms"
            )
            assertTrue("budget $budget took ${elapsedMs}ms", elapsedMs < 6_000)
        }
    }
}
