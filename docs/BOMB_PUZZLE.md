# The bomb puzzle

A wall of RGB buttons, 7 rows by 64 columns. Press a black (unlit) button to
place a mine; press the green button to detonate every mine at once. Clear all the
white buttons (one hit) and blue buttons (two hits) within a fixed number of mines.

**How it is used.** Pick *Mines* on the landing page, stand at the green button with all
seven rows in view, tap **Scan**, and pan slowly right to the end of the wall. The app
reads the whole wall into a board of its own, plans the fewest mines, and shows the
answer on that board -- every button numbered from the green, each one to mine ringed and
numbered by its detonation. A misread is a tap on the board, not a rescan. Nothing is
drawn on the wall any more; the next section says why, and how the pan becomes a board.

## Scanning the wall in one pan

Until 2026-10-23 Mines ran on the AR pipeline: ARCore fitted the wall, a mosaic
accumulated as the user panned, and the plan was drawn back onto the wall through the
camera. It is replaced by something much smaller, for three reasons:

- **The answer needs the whole wall anyway.** The planner cannot say anything until the
  last button is read (see [What the solver returns](#what-the-solver-returns)), so there
  is no moment where an overlay helps before the scan is over -- and after it, holding
  the phone up to the wall to see which button to press is worse than looking at a board.
- **Colour reads better at Gems' exposure**, and ARCore will not set one
  ([CAMERA_CONTROL.md](CAMERA_CONTROL.md)). Gems moved to its own Camera2 source for that
  reason and now reads its LEDs reliably; Mines follows it.
- **The wall has a landmark.** The green button is always in the first column, third row,
  so a board indexed from it is the same board every round, and "column 23, row 4" is a
  button the user can walk to. The wall's two-column panels, drawn the same way on the
  board, make the count easy.

### Two exposures, alternating

Gems' exposure (1/250 s at floor ISO, white balance locked) keeps an LED's face in
colour, and leaves every **unlit** button black. Measured on a Gems frame at that
exposure, an unlit button sits at a luma of about 8 against a panel of 2. But unlit
buttons are most of what there is to count columns by: on the reference pan, stretches
of ten columns and more have nothing lit at all.

So the camera alternates, frame by frame, as one repeating burst
(`Camera2FrameSource.writeAlternating`):

| frame | exposure | goes to | used for |
| --- | --- | --- | --- |
| lattice | 1/250 s at ISO 3200 (two stops under the phone's own choice for the reference pan) | preview and scanner | every button's position; lit or not |
| colour | the LED-wall preset, as Gems | scanner only | the colour of each lit button |

The preview shows only the lattice frames, because a preview strobing between the two
is unwatchable. Each frame is tagged with its request and read back by sensor timestamp;
the two kinds get a ready slot each in the hand-off, because one slot refilled at the
preview's rate would only ever hold one kind. While aiming, the lattice exposure steers
itself a stop at a time (up to three brighter, two darker) to keep the unlit buttons
between 45 and 205; during a scan it is left alone, because a retune stops the camera for
a moment and the count cannot afford a gap. A lattice frame still too dim is lifted by
the scanner before the buttons are looked for in it (`WallScanner.liftDim`), and the
steer logs when it wants more light than it may take. A camera without manual control sends one exposure, every
frame is a lattice frame, and colour falls back to the glow (below).

### Counting the columns

`WallScanner`, in `core`, testable at a desk:

1. **Find the buttons** in each lattice frame (`GemBlobDetector`, as Gems), take the
   camera's roll off, group the discs into columns and the columns into rows. A column
   with exactly seven evenly spaced discs fixes the row lines for the whole frame -- which
   is why all seven rows have to be in view -- and every cell's position comes from where
   its column's line crosses its row's, so a lit button too bloomed to be found as a disc
   is still where it should be.
2. **Place the frame on the wall.** Every button of the last placed frame is moved on
   by the pan's velocity and each disc in this one votes for the column offset that puts
   it next to one. A slow pan moves a fraction of a column between frames, so the vote is
   lopsided. When the lit buttons contradict the winning offset -- the vote can only be
   wrong by a whole column, and a whole column moves every lit button off the ones
   already recorded -- the neighbouring offset that agrees with them wins.
3. **Find the place again** after losing it. Past 0.6 s untracked the velocity says
   nothing useful, and only the lit buttons can place a frame: it is matched against the
   board read so far, and accepted only if exactly one offset puts two or more lit
   buttons on lit buttons and none on dark ones.
4. **Anchor on the green.** Columns are counted from wherever the scan starts until a
   cell reads green; that cell is column 1, row 3, and everything is renumbered from it.
5. **Find the end.** The wall just stops. A frame whose last column is whole and has a
   pitch and a half of clear panel after it, inside the frame, three frames running, is
   the end. The count is then checked against the 64 the wall has, and the board says so
   if it differs.

Every look at a cell is a vote -- lit or not, and if lit, which colour -- and the board is
the vote. A face reading counts for 1, a glow reading for 0.35, and a cell's colour is
the face's verdict whenever there is one. A split vote is *unknown*.

**Measured on `testVideos/Mines/mines1.mp4`**, a single-exposure pan of the whole wall
on the phone's own camera app, at 10 frames a second and 270x480 (the fixture
`core/src/test/resources/mines/pan`): it counts exactly 64 columns from the green to the
end, and reads every one of the 21 lit buttons the right colour on every look, against a
board read by eye off full-resolution crops of each. At 7.5 frames a second it still
counts 64; at 5, the first step of that pan is more than a column and position alone
cannot tell which. The phone reads both kinds of frame each time the preview advances,
which should keep it well above 10.

**Measured on a rendered pan** (`WallScanPanTest`), alternating as the app does, with
the lattice frames' blooms all plain white so every colour on the board has to have come
from the colour frames: the whole wall reads exactly, an armed mine included. Black out
a second of it mid-pan and the count is lost, then found again by the lit buttons.

### Reading colour

**At the colour exposure, off the face** (`ButtonFace`). Gems' vote with the rings taken
out: every texel bright enough to be LED votes, white if barely saturated, otherwise for
the nearest hue -- red 352, green 146, blue 198, measured on the gem wall on this camera
at this preset, and an armed mine (red *and* blue) at 275 between them. **Not yet
measured on the mines wall itself**; no footage of it at this exposure exists yet. The
scan log records every lit cell's mean colour, so the first scan in the room is the
measurement.

**At the lattice exposure, off the glow.** The fallback, and what the reference pan can
test. The face of a lit button is clipped there, so the colour is read off the light it
throws on the panel, the way the original classifier was -- but against the **panel's own
colour, taken from between the unlit buttons in the same frame**, not against the panel
right round the button. That change is the difference between working and not. A green
or blue LED lights its whole neighbourhood, so the panel next to it is already its
colour: the green's surround measured (23, 134, 40) against a panel of about
(45, 43, 78), and subtracting it subtracted the answer -- the green came back unnamed and
the blues flickered between blue and unknown. Measured against the frame's panel and
named by hue, on the reference pan: whites at saturation 0.09-0.22, every colour above
0.66; blue at 207-211 degrees, red at 344-349, green at 117.

### What is planned

The board goes to the same planner as before, with the same budget. A cell nobody could
read is planned as a **red hazard**: it might be anything, red is the one thing a ray
must never land on, and so it is the only assumption that cannot lose the room. The board
shows it as a "?" for the user to look at and tap. On the reference wall the plan is five
mines in two detonations, found in about a third of a second, and checked by detonating
it against the board read by eye.

### Keeping the evidence

Every scan writes itself to the phone under `files/mines/<date-time>/`: the frames it read
at half size, `frames.txt` saying when each was taken and which kind it was, `scan.txt`
with the scanner's reading of every frame and every lit cell, and `board.txt` with the
board, each correction made by hand, and each plan. `tools/collect-session.sh` brings them
off, and `MinesReplay` runs one back through the scanner at a desk:

```bash
./gradlew :core:test --tests '*MinesReplay*' -Dmines.frames=/path/to/mines/20261023-190000
```

### Verified on the phone, at a desk

Run on the CPH2655 on 2026-10-23, pointed at a desk and a monitor rather than the wall:

- **The burst is honoured.** Colour frames asked 1/250 s at ISO 100 and got it; lattice
  frames asked their own exposure and got that, read back separately. Half the frames
  read were lattice frames, and the saved ones average a brightness of 50 against the
  colour frames' 5. Frames come out upright.
- **Every frame is read.** 27 to 30 frames a second during a scan -- 14 or so lattice
  frames, against the 7.5 the count needs -- at about 7 ms a frame to turn upright and
  7 ms to scan an empty view. Two things had to change to get there, both found by this
  run. Taking two frames a dispatch managed only 7.5 lattice frames a second; taking all
  that are waiting fixed it. And the camera thread was at 70% of a core converting every
  frame a byte at a time; reading the chroma a row at a time took it to 17%.
- **The aiming steer** pushed the lattice two stops brighter with the phone face down on
  the desk -- a black frame, read as a room too dark. It now only brightens a black frame
  when the colour frames show lit buttons, which a phone pointed at the floor never has.
- Some seconds after a scan ends, the phone's own clocks drop and both costs roughly
  triple; the rate stays above 20 frames a second.

### In the room, 2026-10-27

The first scan of the wall itself found nothing. The colour frames were right -- the
LED-wall preset, only the lit buttons showing -- but the room was far darker than the
reference video the lattice default was set from. The steer took both of its stops in
five seconds (1/250 s at ISO 3200 to 1/125 s at ISO 6400) and stopped at its limit, with
the unlit buttons at 15-30 on a panel of 5. The button finder keeps a pixel only if it
clears its local background by a fixed twelve levels, and half the buttons sat under that
line: not one lattice frame in 147 found a column of seven, and the green was never seen.

Replaying that pan at a desk (`MinesReplay`) with the lattice frames scaled up settles
what it needed: at 1.5 times the light the wall was found in 78 frames of 147, at twice
the light in 145. So the steer may now take a third stop -- 1/62 s at ISO 6400, as long
as a lattice frame may run before a pan smears it, and that doubling exactly -- and the
scanner lifts a dim lattice frame toward a 99th percentile of 95, at most threefold,
before looking for buttons in it. Lifted, the pan as shot finds the wall in 146 frames of
147 and the green on the first. Scaling only stretches what the sensor recorded, so this
works because the fixed line and not the noise was in the way; the camera is still steered
toward real light, measured on the frame as it arrived. `WallScannerTest` holds the pan's
first 40 frames (`mines/dim-room`) to finding the wall in 18 of their 20 lattice frames.

Two smaller things came out of it. The steer's step count was never reset, so a second
visit to Mines in one session would have started at the default already counted three
stops up, unable to brighten; it is now counted from wherever the lattice frames last
started. And the steer logged under a tag the phone's log capture did not record, so the
run left no trace of it; that tag is captured now.

### Not yet verified

- **A whole scan of the wall itself**, and with it the scan's cost with buttons in view.
  The one attempt never found the wall (above); the fix is measured on its frames, not
  on a new run.
- **Face colours at the colour exposure on this wall**, above.
- **The lattice exposure's default** is still two stops under one auto-exposed video. The
  room needed three stops more than that on 2026-10-27; the steer covers the gap in about
  five seconds of aiming, and the lift covers it before then.

## How it used to work: the AR overlay

Everything from here down describes the AR pipeline's Mines -- `BombAdapter`, the mosaic
and the overlay -- which the scan above replaced. It is kept because the rules, the
planner and the solver are shared, and the colour findings are what the glow reading
grew from.

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
  the grayscale adapters of the time (sudoku and nonogram, since removed) could not
  regress;
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

Picking *Mines* on the landing page now starts the one-pan scan above rather than this
path.

One thing the device caught that no bench test would have. Run over the whole 4096-square
canvas, the lattice search cost about **1.5 seconds** a pass, showing up as a 1528 ms
solver step. Blob detection scales with area, and almost all of that area is a cleared
buffer nobody has looked at. It now searches only the region the coverage map reports as
observed, shrunk so its longest side stays under 768 texels, taking the same pass to
**21 ms**. There is a test pinning that, deliberately loose -- it guards against losing
the bound, not against a few milliseconds of drift.
