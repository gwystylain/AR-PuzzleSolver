# The Strategy room (and Gridlock)

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
  stays put; the lanes scroll underneath it.
- **The board** is drawn as the wall, always landscape: two boards side by side. In
  Gridlock the gap between separate boards (levels 7 and 8) is left out and the boards
  are drawn edge to edge; Strategy's green strip on its levels 7 and 8 is drawn, since
  it is tiles on the wall. Each gun tile is filled in the colour of the
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
  in **runs**, each beside a small picture of the board. Wait until the wall looks like
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

Within that partial order, the split follows the team's rules, in this order:

1. **Even.** Every player gets the same number of presses, or one fewer. The search can
   only trade presses between players, so it cannot break this.
2. **Hand-offs first, waits last.** A press another player waits on goes at the front
   of its player's stack; a press that waits on another player goes at the back of its
   own. "Front" means as early as the press's own prerequisites allow -- a hand-off that
   needs two of the same player's presses first comes third. A wait that would be only
   one step behind the press it needs is penalised on top, since a quick player could
   overtake it. On a stage that is one long chain an even split cannot avoid waiting
   altogether, and the numbers show where (see below).
3. **Short walks.** Each player's walk along the wall, press to press: the total, and
   half again for whoever walks furthest. Walking is measured mostly sideways, since
   reaching up costs nothing like crossing the room.
4. **The level's quirks.** Walls, mirrors, reds, moving and swapping targets are all
   settled by the plan already; the split adds how often a player has to stop and wait
   for the board to come round, and that counts against it too.

These become one score, weighted in that order. The search runs in two levels:
simulated annealing over who presses what, with each candidate split put in order by a
rule that already follows the list above (hand-offs as soon as possible, waits held back
until they have a margin, the rest by shortest walk and same board look); then an
exhaustive polish of the best splits, moving single presses within the order and between
players. It starts from a stretch of wall each, the clockwise numbering dealt out, and on
a timed stage a board look each, and it is seeded, so a stage shows the same lanes every
time. Sixteen times the search effort changes the result by a few percent of walking at
most. The largest stage splits in under two tenths of a second on a laptop; on the phone
it runs in the background, a level at a time, while the first stage is on screen.

On Gridlock 7-1 with two players this comes out as a board each: each player's first two
presses are the purple tiles the other player needs, and each takes the other's
hand-offs two steps later. Each player's header on screen says how far they walk.

Each player's numbers come last: a press is numbered one past the later of that
player's previous press and every press it waits on. Independent lanes count 1, 2, 3
in step; a lane that waits skips, and the skipped number is the other player's step it
waits for.

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
rooms' bundled files.

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
