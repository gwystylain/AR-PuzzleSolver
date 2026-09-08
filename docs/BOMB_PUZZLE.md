# The bomb puzzle

A wall of RGB buttons, roughly 7 rows by 100 columns. Press a black (unlit) button to
place a mine; press the green button to detonate every mine at once. Clear all the
white buttons (one hit) and blue buttons (two hits) within a fixed number of mines.

## Rules, and how sure we are of each

Confirmed by watching the room:

| Rule | Detail |
| --- | --- |
| Placement | A mine goes on a black button. The button turns **magenta** (red *and* blue) while armed. |
| Firing | Green fires **every** armed mine on the board, not only the ones just placed. |
| Rays | Eight directions, stopping at the first coloured button that is not green. |
| Hazards | A plain **red** button is a hazard. A ray landing on one **loses the game**. It blocks rays like any other lit button, which is exactly what makes it dangerous. |
| Green | Transparent. Rays pass straight through it. |
| Damage | White clears in one hit, blue in two. |
| Cancellation | Two mines on a common line fire at each other; the rays **meet and vaporise**. Neither reaches the far side. |
| Rounds | Place, detonate, place again. The budget is the **total** across all rounds. |

Red hazards are a constraint, not a cost. The planner will not trade a hazard hit for
any amount of progress: greedy placements that light one are rejected outright, and a
plan is only a solution if every button is down *and* nothing fired on a hazard.
`RoundModel` tracks hazard hits as a running count rather than a flag, because a mine
that lights a hazard can sometimes be rescued -- a second mine further along the same
line cancels the offending ray, and the pair is legal together even though the first
was not.

Not yet checked, and modelled the conservative way. Each is a flag on `BombRules` with
the reasoning written at the field, so changing one is a line and a test re-run:

- `crossingRaysAnnihilate` — whether rays that cross at an angle, rather than head-on,
  also destroy each other. Assumed **no**: "advance towards each other" describes
  head-on motion, and assuming extra cancellation would make the solver reject plans
  that work.
- `destroyedButtonsBlockUntilRoundEnd` — whether a button destroyed partway through a
  detonation still blocks rays arriving later in it. Assumed **yes**, so a plan is
  never credited with damage it might not deal.
- `damagePersistsBetweenRounds` — whether a blue hit once stays damaged. Assumed
  **yes**; if it were not, blue could only ever be cleared by two hits in one round.

## Cancellation is the whole difficulty

Without it this is a covering problem: precompute which buttons each cell can reach,
then pick a cheap set of cells. Cancellation breaks that, because whether a mine hits
anything depends on where the *other* mines are.

Worse, it depends on **when** rays arrive rather than on what is standing in the way,
so no static line-of-sight table captures it. `Detonation` therefore advances rays a
cell per tick. Two orderings inside the tick are load-bearing and both are covered by
tests that fail loudly if flipped:

1. **Damage resolves before cancellation.** Two mines either side of a blue both land
   on it — they never get the chance to meet. Reverse this and a flanked blue becomes
   unclearable in one round, which is wrong.
2. **Traded cells resolve before shared cells.** Two rays swapping places crossed at
   `t - ½`; two rays entering one cell arrive at `t`. Half a tick earlier wins. This
   only bites when three mines sit adjacent on a line: rays from the outer two enter
   the middle cell together and look like a head-on pair, but each already met a ray
   from the middle mine. Pair the outer two and the middle mine appears to fire freely
   in both directions, and the plan comes out a mine cheaper than the room allows.

## The closed form, and why the search can trust it

Running the tick loop inside a search would be far too slow, so `RoundModel` uses a
closed form instead:

> A mine's ray along direction `d` is lost **exactly when** another mine stands along
> `d` with no button blocking the way between them — and then that neighbour loses its
> ray back along `-d` too.

The argument: only opposing rays cancel; opposing rays travel one line; so the partner
is always collinear. Along a line, mines pair off with their *nearest* neighbour, which
is why three in a row leave the middle one with nothing. A button standing between two
mines absorbs both rays before they meet, which is the "no blocker in between" clause.

That is an argument rather than an observation, so `RoundModelAgreementTest` fires
4000 random boards through both the tick simulation and the closed form and demands
identical damage, cell for cell. It is the test that found the ordering bug above.

## Planning

`BombPlanner` runs three passes, in order of what each can promise.

1. **Greedy, always.** Take the placement that removes the most hit points, repeat,
   detonate when nothing more helps. Its job is to guarantee there is an answer at all,
   and to hand the later passes an upper bound. A 7×100 board takes a few milliseconds.
2. **One detonation, exact.** Iterative deepening with a hitting-set branch and bound
   over `RoundModel`, which is exact, so the first plan found at size *k* really works
   and no plan of size *k−1* exists. Only attempted while *k* is small — exhaustive
   search at thirty mines does not finish, and pretending otherwise wastes the budget
   that pass 1 already spent well.
3. **Several detonations, bounded.** A cleared button turns black and stops blocking,
   so two small rounds can beat one large one. Beam search; not a proof.

**There is no mine budget to set.** The room always admits a solution and the only
useful answer is the cheapest one, so the planner is asked for a minimum rather than for
something that fits a number the user typed in. It still needs *a* ceiling to bound its
search, and the board supplies one: total remaining hit points. That cannot exclude a
real solution, because the greedy pass only ever buys a placement that removes at least
one hit point, so it terminates having spent at most that many mines, and every later
pass is bounded below whatever greedy found.

That also sharpens what a refusal means. Since a real board is always solvable, a plan
that comes back impossible is evidence of a **misread**, not of a hard puzzle -- which is
why `BombSolver` turns it into a `Contradiction` naming suspect cells for re-reading
rather than into a shrug.

`provenMinimal` is set only when the mine count reaches a bound nothing can beat:
a button with two hit points needs two placements (a mine fires one ray per line, so it
can never hit the same button twice), and a mine lands at most eight hits. Both count
placements, so both survive re-mining a cell in a later round.

**Impossibility has to be earned.** Greedy getting stuck and a beam coming back empty
prove nothing, so neither reports `Impossible`. Only four things do: an unreachable
button, no unlit button free, a budget below the bound above, or the exact pass
refuting every single-round arrangement when that is the only kind allowed. Everything
else is `Unfinished`, which says we stopped looking rather than that there is nothing
to find.

One subtlety in the reachability test: "no mine can reach this button *now*" is the
wrong question, because a button in the way is itself clearable and clearing it opens
the line. `.WWW` has two whites nothing can currently see and is solvable in three
rounds.

## What the solver returns

`BombSolver` is `REQUIRES_FULL_SCAN`, and not as a hedge. A white button at the far end
of the wall changes what is worth mining at this end, because a mine that reaches it
from here is a mine not spent elsewhere. No partial reading pins the answer down.

Planning runs on its own thread. The pipeline's ~12 ms step budget assumes a solver
whose search suspends and resumes, which branch and bound over mine placements does
not — there is no meaningful state to hand back partway. So `observe` returns `Pending`
until the worker has an answer, and new observations supersede a running plan rather
than merging into it.

The solution encodes, per cell, a **bitmask of which detonations to press it in** —
bit *n* meaning round *n+1*. A mask rather than a round number because a button really
can be pressed twice: a mine is spent by its detonation and the button goes dark again,
so re-mining it later is legal, and on a board where only one cell is unlit it is the
only way through. Decode with `BombSolver.minesForRound`.

## Reading colour off the wall

Measured off `testVideos/mines1.mp4` -- fifteen seconds of slow pan, 1080x1920 at
60 fps -- rather than guessed. Two findings shaped the design, and both would have
been got wrong by reasoning from first principles.

### The button itself carries no colour

Every lit button clips the sensor to pure white across its face and some way past it.
Core and inner halo both measure (255, 255, 255) at zero saturation, for red, white and
blue alike. This is not a fluke of one exposure: the room is dark and the LEDs are
bright, so any exposure that renders the unlit buttons at all will blow out the lit
ones, and the app does not control the camera.

The colour survives only in the glow thrown onto the panel *around* the button. So the
classifier samples an annulus, at 0.24 to 0.38 of the cell pitch — outside the clipped
region, comfortably inside the cell, since neighbouring buttons start at 0.5.

This rules out `CellReader.samplePatch`, which reads the middle of a cell because that
is where a glyph is. Pointed at this wall it would report every button as identical
white.

### Absolute colour is meaningless; excess over local ambient is not

The room washes the wall in shifting purple. One white button measured rgb(87, 73, 111)
in one frame and rgb(145, 114, 156) in another — over 1.5x in brightness and a visible
change in tint. Any fixed threshold on raw colour fails.

Subtracting local ambient and normalising to a chromaticity summing to one collapses
the classes tightly:

| class | r : g : b | samples |
| --- | --- | --- |
| red hazard | 0.62–0.68 : 0.08–0.13 : 0.24–0.26 | 5 |
| white | 0.33–0.36 : 0.28–0.31 : 0.35–0.38 | 20 |
| blue | 0.13–0.17 : 0.30–0.33 : 0.53–0.55 | 3 |

`ButtonPalette` classifies by nearest centroid in that space and reports a margin;
readings that fall between classes come back as `OPAQUE`, which makes the solver refuse
rather than guess. `ButtonPaletteTest` runs all 28 measured buttons through it and
requires each to win by a margin, not merely to land on the right side of a boundary.

Note that white does **not** sit at the neutral third — it measures slightly magenta,
partly the LED and partly imperfect ambient subtraction. Using a theoretical
(0.33, 0.33, 0.33) would push every white sample toward its neighbours.

### Still unmeasured

No green detonator and no armed mine appeared in the footage, so both centroids are
provisional and flagged as such in the source. The mine is the one to watch: an armed
mine lights red *and* blue, and white already leans magenta, so red-versus-blue balance
will not separate them. **The green fraction does** — about 0.29 for white against
roughly 0.10 for magenta. Worth confirming against footage with a mine placed before
trusting it.

### Carrying colour through the capture path

`CanvasAccumulator` stored `vec4(luma, confidence, 0, 1)` in an RGBA8 target and handed
every adapter a single-channel `GrayImage`. Two channels were spare and luma was already
in `.r`, so **Cb went in `.b` and Cr in `.a`**, which reconstructs colour exactly:

- the coverage downsample still reads `.g` — untouched;
- the existing tile readback still blits `.r` into its R8 staging target — untouched, so
  sudoku and nonogram cannot regress;
- a second tile path blits `.b`/`.a` into an RG8 target with its own
  `AsyncReadback(GL_RG, 2)`, streamed only while a colour-hungry adapter is active.

Additive rather than a rewrite of a path that already worked. Above it, `PuzzleAdapter`
gained default-implemented overloads taking a `CanvasView` of luma plus optional chroma,
so the existing two adapters needed no edit at all.

**Colour is off until something asks for it**, because it costs a 32 MB mirror and
triples tile bandwidth. That creates a circle: the bomb adapter cannot be identified
without colour, and colour does not stream until it is identified. The way out is that
its *grid* detector runs on luma alone — a lattice of buttons is recognisable before any
colour arrives. So a colour-requiring adapter whose lattice detector fires sets
`State.colourRequested` and the capture layer turns chroma on; the identification is
settled a frame or two later. The grid is deliberately **not** adopted at that point:
adopting an unconfirmable grid would let a lattice detector misfiring on some other
puzzle lock the engine onto something it could never verify.

One consequence of the halo finding worth noting for the accumulator: the useful signal
is *between* the buttons, which is exactly where a grid detector tuned for ruled lines
expects to find nothing. `GridDetector` will need checking against this wall — the
lattice here is the buttons themselves, not drawn lines.

## Verified on device

Built and installed on a CPH2655 running Android 16 with ARCore. The parts that only a
real GPU can settle came back clean: the chroma fragment shader compiles, the RG8
framebuffer reports complete, and the render loop holds 1.4 to 3.7 ms a frame with the
extra readback wired in. No GL errors, no crash.

What a bench test cannot cover is aiming the camera at the actual wall, so the end of
the chain is verified against a rendered one instead. `BombEndToEndTest` draws a board
with lit buttons clipped to pure white and everything under a purple ambient wash --
both measured off the reference footage -- then runs the real path over it: lattice
detection, identification, colour reading, planning, and finally detonating the plan
against the board that was drawn. All 84 cells read back the colour they were drawn
with, and the plan clears the board without firing on the hazard.

To try it on the real thing: open the **Mode** menu and pick *Mines*. Pinning is not
usually needed -- the lattice detector identifies the wall on its own -- but it skips the
ambiguity while the board is still half-scanned.

One thing the device caught that no bench test would have. Run over the whole 4096-square
canvas, the lattice search cost about **1.5 seconds** a pass, showing up as a 1528 ms
solver step. Blob detection scales with area, and almost all of that area is a cleared
buffer nobody has looked at. It now searches only the region the coverage map reports as
observed, shrunk so its longest side stays under 768 texels, taking the same pass to
**21 ms**. There is a test pinning that, deliberately loose -- it guards against losing
the bound, not against a few milliseconds of drift.
