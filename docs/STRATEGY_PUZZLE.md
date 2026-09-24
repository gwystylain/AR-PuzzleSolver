# The Strategy room (and Gridlock)

A floor of lit tiles. Orange tiles around the edge are guns: step on one and it fires a
shot straight across the board, the first blue tile in its path goes out, and the gun is
spent. Red tiles are hazards. A level is several stages, each a fresh layout, and each
has to be cleared with exactly the guns it gives you — so the whole puzzle is the order
to press them in, and on the later stages, *when*.

**This mode has no camera.** The stages are fixed, so there is nothing to detect: the
app carries a transcription of every level and, beside it, the pressing order and team
split for every stage and team size, worked out ahead of time (see *Bundled splits*
below). Pick
*Strategy* from the game-mode menu and the scan stops, the camera is released, and the
HUD — logging, exposure, recording, all of it — is replaced by the guide. Pick any
scanning mode to get it all back.

**Gridlock** is a second room that plays by the same rules and gets the same guide:
two boards side by side, guns that block, purple tiles that light a target on the other
board, reds that fail the wave. It is its own entry in the menu with its own stage file
(`gridlock.txt`, levels 3, 4 and 6–10 so far, from activate.ryflix.ca/gridlock.html); everything
below applies to it too. In core the two are `StrategyRoom.STRATEGY` and
`StrategyRoom.GRIDLOCK`, and `StrategyStages.bundled(room)` picks the file.

## Reading the guide

The screen carries no instructions: it is for teams who already know the room, and
anything that is not the answer is clutter over the board. What it shows:

- **Players** is asked first, 2 to 5, and can be changed from the button next to the
  title. Every stage is then split into a lane per player (see below).
- **Level** is the row of buttons across the top, 1 to 10.
- **Stages** are pages: swipe up for the next stage, down for the previous. The board
  stays put; the lanes scroll underneath it, and stay scrolled to the same player from
  stage to stage, so a player who has scrolled down to their own lane finds it there on
  the next stage too. A swipe that starts on the lanes and turns the page does not
  count as scrolling them.
- **The board** is drawn as the floor, always landscape: two boards side by side. In
  Gridlock the gap between separate boards (levels 7 and 8) is left out and the boards
  are drawn edge to edge; Strategy's green strip on its levels 7 and 8 is drawn, since
  it is tiles on the floor. Each gun tile is filled in the colour of the
  player who presses it and carries **that player's own count**: their first press is
  1, their second 2, and so on. A tile nobody presses stays plain orange with no number.
- **The presses** are one lane per player, in the same colour and numbers as the board.
  A number that skips ahead is a wait: a player who reads 1, 3, 4 has to let another
  player get to their 2 before pressing their own 3, and the chip says so underneath
  ("after P1 2", in that player's colour). Two players with nothing between them both
  read 1, 2, 3. A red chip is a tile that has to be fired into a red on purpose — there
  is exactly one in the game, level 4 stage 4. Each player's header gives their number
  of presses and how many tiles they walk.
- Internally the solver numbers tiles clockwise from the top-left corner (`labels` on a
  stage; `L`/`R` for two panels, the level 6 hub after the edge) and that is the order
  its plans are found in; it never reaches the screen.
- On a stage where the reds (or, on level 4 stage 2, the targets) move, the presses come
  in **runs**, each beside a small picture of the board. Wait until the floor looks like
  the picture, then press that run. The runs are cut so that each is as long as the moving
  reds allow.
- **Tap any chip** to see the board at that press: everything it waits on already gone,
  the reds where they are for that shot, and where it lands. That is how to find your
  place again after looking away.

## Playing as a team

A plan is one order that works, but most of it does not have to be in that order: a
shot only cares about the tiles on its own line of fire. `TeamPlanner` turns the plan
into a partial order -- shot B waits for shot A when A changes a tile B fires across, or
the other way round -- and any schedule that respects it clears the stage exactly as the
plan does. `TeamPlannerTest` checks that by replaying every stage in dozens of random
orders that respect only the graph.

Both rooms are floors: a press is a step onto a tile, and walking is measured straight
across the room, rows the same as columns. That puts most of a stage's time into
walking -- crossing the room for one tile costs more than two presses -- and within the
partial order the split follows the team's rules:

1. **Finish early, walk little.** The stage is done when the last press lands, so a
   split is scored on when that is (walking, pressing, and standing waiting for other
   players) and on how far everyone walks in total. A tile one player would cross the
   room for goes to whoever is standing next to it, even if that gives them more presses
   than the rest. On Gridlock 4-4 with five players, the player on the top row takes all
   four top tiles rather than leaving one for a player who would walk up from the bottom
   row for it.
2. **Hand-offs first, waits last.** A press another player waits on goes at the front
   of its player's stack; a press that waits on another player goes at the back of its
   own. "Front" means as early as the press's own prerequisites allow -- a hand-off that
   needs two of the same player's presses first comes third. A wait that would be only
   one step behind the press it needs is penalised on top, since a quick player could
   overtake it. A wait with no margin at all, where the player gets there first and
   stands (their numbers skip, see below), costs as much as a press: on a chain, players
   standing at their own tiles and waiting their turn beat one player walking it.
3. **The level's quirks.** Walls, mirrors, reds, moving and swapping targets are all
   settled by the plan already; the split adds how often a player has to stop and wait
   for the board to come round, and that counts against it too.
4. **Even, where nothing else decides.** Presses are shared out evenly only as far as
   the rules above allow. On a stage where another pair of feet saves nothing, a player
   can have nothing to press, and their lane says so.

These become one score, every term counted in tiles walked. It used to put an even split
first, as a hard rule; across every stage for two to five players, dropping it made the
splits an eighth faster and cut walking by a quarter, and a stage no longer finishes
later with one more player -- under the even rule, fourteen did. The search runs in two
levels: simulated annealing over who presses what, with each candidate split put in order
by a rule that already follows the list above (hand-offs as soon as possible, waits held
back until they have a margin, the rest by shortest walk and same board look); then an
exhaustive polish of the best splits, moving single presses within the order and between
players. It starts from a stretch of edge each, the clockwise numbering dealt out, and on
a timed stage a board look each, and it is seeded, so a stage shows the same lanes every
time. Eight times the search effort changes the result by under one percent.

On Gridlock 7-1 with two players this comes out as a board each: each player's first two
presses are the purple tiles the other player needs, and each takes the other's
hand-offs two steps later. Each player's header on screen says how far they walk,
straight across the floor, as the split measures it.

**The plan is chosen for the team too.** The partial order comes from the plan, and the
plan is one way to clear the stage of several: often a different gun could take a
different tile, and then different presses wait on each other. `StrategySolver.plans`
finds others with the same search -- up to 64 tries, the guns in shuffled orders --
sharing what the first search learned, so most tries cost a fraction of it; a try that
runs long is cut off, counted in positions searched rather than time, so every run finds
the same plans. `TeamSolver` splits every one of them for the team size and keeps the
best, so it is never worse than the solver's plan alone. Across every stage and team
size that takes another 3.4% off the time and 6.1% off the walking, concentrated where
the choice is: on Gridlock 7-4 for three players the first plan has players crossing
between the two boards and waiting on each other across the wall, and the one chosen
keeps each mostly to one board, 92 down to 69 in time and 125 down to 84 tiles walked.
About half the stages have only one way to be cleared, and there nothing changes --
Gridlock 4-4 included: each of its four rings of tiles has one tile that is second in
line for both guns that reach it, so one of those guns has to wait for the other
whichever plan is played.

The screen draws whichever plan the split is of, so the board, the chips and the
tap-to-see pictures all match the lanes.

Each player's numbers come last: a press is numbered one past the later of that
player's previous press and every press it waits on. Independent lanes count 1, 2, 3
in step; a lane that waits skips, and the skipped number is the other player's step it
waits for.

### Bundled splits

None of the search above runs on the phone. The stages are fixed, so every stage's split
for one to five players is worked out on a computer and shipped next to the stage files,
in `strategy-splits.txt` and `gridlock-splits.txt`; the guide reads them, and works out
only the step numbers and waits from the stored lanes, a millisecond's work. That makes
the guide instant, and it means the search can afford to split every plan in full. The
files are line-based like the stage files and name every press by its clockwise number,
so a change to the planner shows up in the diff as which players' presses moved, with
each split's time and walk beside it.

After changing a stage file or anything in the solver or planner, regenerate them:

    ./gradlew :core:generateStrategySplits

It takes about half a minute on a laptop, splitting side by side on every core, and
the same code always writes the same files. `./gradlew :core:checkStrategySplits` only
says whether they are out of date. Neither is part of the build, but the tests catch
both ways of forgetting: every stage's entry carries a fingerprint of the stage, so a
stage corrected without regenerating fails `StrategySplitsTest`, and so does a planner
change that leaves any shipped split scoring worse than the solver's own plan split with
the new rules. The same test replays every shipped split in random orders its
dependencies allow.

## The two transcriptions

There is no official map of the room. Two fan pages have transcribed it:

| | activate-scores.ca | activate.ryflix.ca/strategy.html |
| --- | --- | --- |
| Levels | 1–6 | 7–10 (5 waves each), transcribed by MetaNut |
| Board | one 12×12 grid | 12×16 with a two-column wall for 7 and 8; 12×12 for 9 and 10 |
| Guns in the line of fire | shot passes through | shot stops there and destroys the gun |
| Purple tiles | none | shooting one lights a target in the mirror-image column |
| A shot into red | costs one of five lives, and clears that red | fails the wave |
| Moving pieces | per-tile animation frames; level 4's targets bounce | level 9's targets swap between two layouts every 2 s; level 10's reds are alternating stripes |

The app uses activate-scores.ca for levels 1–6 — its level 4 was played through to
*Congratulations!* with the sequences the solver now produces — and the ryflix page for
7–10, as the page's own note suggests; its 7-1, 9-1 and 10-1 plans were replayed on the
page to *WAVE CLEARED*. activate-scores.ca also has a level 10 (four waves, other
layouts); it is still in `tools/strategy/sources`, and `ROOMS` in
`tools/strategy/convert.py` picks which one ships. Re-running the script rebuilds both
rooms' bundled stage files; regenerate the splits after it (see *Bundled splits*).

Gridlock's transcription (activate.ryflix.ca/gridlock.html, levels 3, 4 and 6–10) follows
the ryflix rules, with two boards of 10 rows by 10 or 11 columns. On levels 3, 4, 6, 9 and 10 the
boards touch and a horizontal shot crosses from one to the other, so the stage is one
22-wide panel; on 7 and 8 a wall stands between them and each board is its own panel.
Its 7-1 and 9-1 plans were replayed on that page to *WAVE CLEARED*.

Rather than two solvers, both transcriptions become one shape of stage: a single grid,
with a strip of wall columns no shot crosses, plus two rule switches (`gunsBlock`,
`redRule`). A level 9 target that is only on the board in one of the two layouts is a
moving target with no position on the other frame.

## Finding the order

`StrategySolver` is a depth-first search over the guns still standing, trying them in
clockwise-number order so the first plan found is the one that reads most like "go
round the board". Two things keep it from being exponential on a 27-gun stage:

- **A matching check before every expansion.** Every remaining target — and every
  purple tile, and the target it will spawn — needs its own gun with it in the line of
  fire. If no such assignment exists the position is dead in every order, and nearly
  every wrong first shot is caught this way at depth one. It ignores what sits between
  gun and target, since that may be shot away: a necessary condition, never a
  sufficient one.
- **A memo of positions proven dead.** Shots that do not interact commute, so the same
  position is reached by many orders and only searched once.

Shots into a red are tried only as a last resort: the search runs with a budget of zero
red hits first and raises it one at a time, so a plan that costs a life is returned only
when none that does not exists. All 44 stages solve in well under a second on the JVM.

On a timed stage a shot is considered only in the frames where nothing red lies in its
way — or, for moving targets, in the frames where it hits the target it is meant to —
and the search prefers to stay in the frames of the shot before. The plan is then cut
into runs greedily, each run kept as long as the shots' common frames allow, which is
optimal for a fixed order. The preference for staying put is a heuristic, so the number
of runs is small rather than proven minimal.

Every plan is checked in `StrategySolverTest` by a replay written from the rules rather
than from the solver, on the frame each shot claims. The level 4 sequences are pinned to
the ones that cleared the real game.

## Two things worth knowing before trusting it in the room

- The fast stages are fast. Level 3's last stage and all of level 6 cycle their reds
  every tenth of a second on activate-scores.ca. The guide still says which look of the
  board each run needs, but timing a press to a 100 ms window is a matter of watching
  the floor, not the phone.
- Levels 6 and 10 exist in both transcriptions as different puzzles, and there is no
  way to tell from here which matches the room. If the floor does not look like the
  board, that is the first thing to suspect — switch the source in `convert.py`.
