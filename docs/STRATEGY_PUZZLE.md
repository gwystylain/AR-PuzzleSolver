# The Strategy room

A wall of lit tiles. Orange tiles around the edge are guns: press one and it fires a
shot straight across the wall, the first blue tile in its path goes out, and the gun is
spent. Red tiles are hazards. A level is several stages, each a fresh layout, and each
has to be cleared with exactly the guns it gives you — so the whole puzzle is the order
to press them in, and on the later stages, *when*.

**This mode has no camera.** The stages are fixed, so there is nothing to detect: the
app carries a transcription of every level and works out the pressing order itself. Pick
*Strategy* from the game-mode menu and the scan stops, the camera is released, and the
HUD — logging, exposure, recording, all of it — is replaced by the guide. Pick any
scanning mode to get it all back.

## Reading the guide

- **Players** is asked first, 2 to 5, and can be changed from the button next to the
  title. Every stage is then split into a lane per player (see below).
- **Level** is the row of buttons across the top, 1 to 10.
- **Stages** are pages: swipe up for the next stage, down for the previous. The board
  stays put; the lanes scroll underneath it.
- **The board** is drawn as the wall. Each gun tile is filled in the colour of the
  player who presses it and carries **that player's own count**: their first press is
  1, their second 2, and so on. A tile nobody presses stays plain orange with no number.
- **The presses** are one lane per player, in the same colour and numbers as the board.
  A number that skips ahead is a wait: a player who reads 1, 3, 4 has to let another
  player get to their 2 before pressing their own 3, and the chip says so underneath
  ("after P1 2", in that player's colour). Two players with nothing between them both
  read 1, 2, 3. A red chip is a tile that has to be fired into a red on purpose — there
  is exactly one in the game, level 4 stage 4, and the note says so.
- Internally the solver numbers tiles clockwise from the top-left corner (`labels` on a
  stage; `L`/`R` for two panels, the level 6 hub after the edge) and that is the order
  its plans are found in; it never reaches the screen.
- On a stage where the reds (or, on level 4 stage 2, the targets) move, the presses come
  in **runs**, each with a small picture of the board. Wait until the wall looks like the
  picture, then press that run. The runs are cut so that each is as long as the moving
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

The split between players is then a scheduling problem: presses take a fixed time,
walking along the wall costs a cell per tile sideways (and much less up and down, since
reaching costs nothing like crossing the room), and a press cannot start until the
presses it waits on have landed. A greedy pass assigns each free player the press they
could land soonest; a seeded local search then moves and swaps presses between lanes,
keeping any change that finishes the stage sooner or, at the same finish, walks less. On
a stage where nothing depends on anything that settles into one player per stretch of
wall; on a stage that is one long chain, one player walks it while the others take what
is off it. Every team size up to the one asked for is tried, so an extra player who
would only make someone wait gets an empty lane and a note rather than a slower stage.

Each player's numbers come last: a press is numbered one past the later of that
player's previous press and every press it waits on. Independent lanes count 1, 2, 3
in step; a lane that waits skips, and the skipped number is the other player's step it
waits for.

## The two transcriptions

There is no official map of the room. Two fan sites have transcribed it, and they
disagree about its shape:

| | activate-scores.ca | activate.ryflix.ca ("Gridlock") |
| --- | --- | --- |
| Levels | 1–6, 10 | 6–10 |
| Board | one 12×12 grid | two 10-wide boards, side by side |
| Guns in the line of fire | shot passes through | shot stops there and destroys the gun |
| Purple tiles | none | shooting one lights a target on the *other* board, in the mirror-image column |
| A shot into red | costs one of five lives, and clears that red | fails the wave |
| Moving reds | per-tile animation frames | column, row, quadrant and sweep patterns |

The app uses activate-scores.ca for levels 1–6 and 10 — it is the site this guide was
first built against, and its level 4 was played through to *Congratulations!* with the
sequences the solver now produces — and Gridlock for 7–9, which only it has. Both sites'
versions of 6 and 10 are kept in `tools/strategy/sources`; `LEVEL_SOURCES` in
`tools/strategy/convert.py` picks which one ships, and re-running the script rebuilds
the bundled file.

Rather than two solvers, both transcriptions become one shape of stage: a single wide
grid, with two separate boards rendered as one grid with a strip of wall between them,
plus two rule switches (`gunsBlock`, `redRule`). "The other board's mirror column" is
then simply `width - 1 - x`, which is the same cell whether or not the boards touch.

## Finding the order

`StrategySolver` is a depth-first search over the guns still standing, trying them in
clockwise-number order so the first plan found is the one that reads most like "go
round the wall". Two things keep it from being exponential on a 27-gun stage:

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
  the wall, not the phone.
- Levels 6 and 10 exist in both transcriptions as different puzzles, and there is no
  way to tell from here which matches the room. If the wall does not look like the
  board, that is the first thing to suspect — switch the source in `convert.py`.
