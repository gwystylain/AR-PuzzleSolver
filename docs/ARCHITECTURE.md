# Architecture

## Why a developable surface is the right model

The puzzle is always 2D but hangs on a curved wall. The naive approaches both fail:

- **A single homography** assumes the target is planar. On a curved wall it is wrong
  everywhere except near the point you fitted it at, and glyphs smear as you pan.
- **A general 3D mesh** (from depth, say) is correct but gives you no canonical 2D
  frame to detect a grid in, and its noise shows up directly as grid-line wobble.

An ordinary curved wall is neither: it is a **vertical extrusion of a horizontal
profile curve**, which has zero Gaussian curvature. Gauss's theorema egregium says such
a surface can be flattened isometrically — no stretching, no compression, distances
preserved exactly. So there is a canonical 2D frame after all, and it is metrically
faithful.

That gives us the coordinate system everything else uses:

- `u` = arc length along the wall, in metres
- `v` = height, in metres (world Y)

Both are world-anchored, so they are stable for the whole session, and `u` is
independent of `v` because the surface is an extrusion. A `WallSurfaceTest` case
asserts the isometry directly by integrating chord lengths along a constant-`v` curve;
if that ever fails, the canvas has stopped being metric and grid detection has lost
its right to assume constant cell pitch.

The wall is modelled as `WallSurface`, with two implementations. A flat wall is a
separate case rather than a cylinder of enormous radius, because `u = R·θ` with `R`
around 10⁶ is nothing but floating-point cancellation. `WallFitter` decides which,
and deliberately gives up on curvature past a 60 m radius — a 60 m radius sags 1.9 cm
over a 3 m span, which is inside our inlier tolerance, so "fitting" it is fitting
noise.

## Fitting the wall

Because the surface is an extrusion, the 3D fit collapses to a **2D circle fit in the
XZ plane**. That is closed form (Kåsa), so RANSAC over it costs microseconds and can
be re-run continuously as the point pool grows.

`WallTracker` pools points across frames in world space rather than fitting per frame.
This matters: a single frame sees a narrow arc, and a circle fitted to a narrow arc is
badly conditioned — the radius can be wrong by a factor of several while residuals stay
tiny. The estimate typically starts as "flat", becomes a large radius after the first
metre of panning, and converges once enough arc has been swept.

The pool is decimated by voxel, not by age. Points from the far end of the wall are the
most valuable ones for constraining curvature, so discarding them for being old would
defeat the purpose.

When the fit changes *materially*, the accumulated mosaic is discarded and rebuilt.
"Materially" is measured in surface displacement over the observed span, not in radius:
a 10% radius change on a gentle curve moves the surface less than a canvas texel and
must not trigger a rebuild, while the same 10% on a tight curve smears glyphs and must.

## Building the canvas

`CanvasAccumulator` renders a 129² mesh over canvas `(u,v)`, computing each vertex's
world position on the fitted surface and projecting it into the current camera frame.
The fragment shader samples the camera's external texture and writes luma.

**Best-sample, not blending.** Averaging frames is the obvious mosaic strategy and it
is wrong here. Frames of a wall taken while panning differ in scale, incidence angle
and motion blur; the mean of one sharp look and five smeared ones is a smeared look.
The target is static, so we can afford to be picky: each texel keeps the single
highest-confidence observation it has ever had.

Confidence combines, in order of how much each actually matters:

1. **Incidence angle** — a texel seen at 70° is smeared across a third of the pixels it
   deserves, and its glyph edges go with it.
2. **Distance** — sampling density falls off as 1/d, so a distant look carries less
   information even when perfectly square-on.
3. **Frame border falloff** — rolling shutter and lens distortion are both worst at the
   edges, and a hard cut leaves a visible mosaic seam exactly where glyphs get misread.

The per-texel maximum is computed **by the depth unit**, not in the shader: writing
`gl_FragDepth = 1 - confidence` with a `GL_LESS` depth test makes the hardware perform
the reduction for free, with no read-modify-write hazard and no second pass. The cost
is that early-Z is disabled for this draw, which is irrelevant — the draw is bound on a
texture fetch anyway.

The canvas is fixed-size, not growing. Reallocating a GPU texture mid-scan would stall
the pipeline and invalidate every frame already warped into it. At the default 1.5
mm/texel a 4096-wide canvas covers 6.1 m of wall.

## Getting data back to the CPU

Two channels, both asynchronous:

**Coverage** (256×256, 64 KB) — a downsample pass takes the *max* confidence over each
16×16 block. Max rather than mean because a cell counts as observed if any part of it
was seen well, and the detector wants generous bounds. Read back every accumulated
frame.

**Canvas tiles** (256², 64 KB each) — the full canvas is 16 MB, so pulling it whole on
every detection pass would cost more than everything else combined. Instead the
pipeline flags tiles whose coverage improved and streams eight per frame — about
512 KB/frame, which refreshes the whole canvas roughly twice a second, far faster than
anyone can pan across it.

Both go through `AsyncReadback`: `glReadPixels` into a PBO, a fence, and collection one
or two frames later. A synchronous read stalls until every queued GPU command drains —
routinely 10–20 ms on a mobile tiler, i.e. the entire frame budget. The CPU therefore
always works on data a couple of frames stale, which for a puzzle painted on a wall is
no staleness at all.

## Threading

```
GL thread                          Solver thread
---------                          -------------
pull frame from FrameSource
fit/update wall
accumulate mosaic
issue coverage + tile readbacks
collect what has landed
copy canvas -> snapshot  ────────► PuzzleEngine.step(snapshot, coverage)
draw background + overlay          detect grid
                                   identify puzzle type
                        ◄────────  read cells, solve
publish UiState
```

The handoff is a **copy** of the canvas mirror, not a lock over it. A 16 MB memcpy at
4 Hz is cheap, and the alternative is worse than slow: a torn read of a half-updated
cell can yield a *confidently wrong* glyph, which then has to be discovered by
contradiction and unwound. Paying two milliseconds to make that impossible is the right
trade.

The solver thread holds at most one queued request. Queueing more would only mean
solving against canvases a newer snapshot has already superseded.

`PuzzleEngine` takes a per-step time budget and returns `SolveOutcome.Pending` rather
than overrunning it, resuming next call. A hard board slows convergence; it never
stalls capture.

## Solving from a partial scan

This is the part worth being careful about, so here is the argument in full for sudoku.

A well-posed sudoku has exactly one completion. Suppose we have read only some of its
givens, and that subset already admits exactly one completion **C**. Every unread given
belongs to the true puzzle, whose completion is also a completion of the subset. The
subset has only **C** as a completion. Therefore the true answer is **C**, and the
unread clues cannot change anything. We can draw the answer immediately.

Two consequences worth stating plainly:

- The premise that matters is **well-posed**. If the wall holds a puzzle with multiple
  valid answers, an early answer is *one* of them rather than the one the setter
  intended. That is a property of the puzzle, not a bug — and the app surfaces it: a
  `Solved` outcome arriving before full coverage is reported as "solved from a partial
  scan", and the HUD says why.
- The solver never needs an exact solution count, only whether it is one. So it counts
  completions **up to two** and stops. Finding a second completion is usually far
  cheaper than exhausting the space, which is what makes "still ambiguous" a fast
  answer and lets the loop go back to waiting for more wall.

`SudokuSolverTest` pins this down using the fact that **17 is the proven minimum clue
count** for a uniquely solvable sudoku: with all 17 read, the solver must answer; with
any 16 of them, there are provably at least two completions and it must refuse. That is
a mathematically guaranteed boundary rather than a hand-tuned threshold.

Nonograms get the opposite treatment. Every clue constrains a whole line, and an unseen
clue can flip cells anywhere along it; a partially read nonogram is not an
under-determined nonogram but a *different, under-constrained puzzle* whose answers
generally have nothing to do with the real one. So `NonogramSolver` declares
`REQUIRES_FULL_SCAN` and refuses until the pipeline reports the board covered, reporting
how many clue lines are still missing while it waits.

## Handling misreads

A contradiction is treated as evidence of a bad glyph read, not a bad puzzle — because
it almost always is. The solver returns the cells it suspects (for sudoku, the
lowest-confidence givens, or the specific conflicting peers), and `PuzzleEngine` clears
those cells so they are re-read from whatever better pixels have arrived since. Cells
whose canvas coverage has *improved* materially are also re-opened, since a better look
can correct a bad call.

## Where the remaining cost is, if you need more speed

In rough order of payoff:

1. **Glyph classification** is the accuracy bottleneck, not the speed one. A small
   TFLite CNN behind the existing `GlyphClassifier` interface would beat template NCC
   substantially on real-world typefaces.
2. **`ImageOps` on the CPU.** Everything works on plain arrays specifically so it can
   move to JNI/NEON or a GPU compute pass without touching callers. The adaptive
   threshold and Sobel are the obvious candidates; both are trivially parallel.
3. **Grid detection frequency.** It currently re-runs when coverage grows by 2%. Since
   grids do not move, the refinement could be restricted to the phase and pitch, keeping
   the topology fixed once confidence is high.
4. **NDK for the solver.** Only worth it for very large boards; the Kotlin bitmask
   propagation is already fast enough that the 12 ms budget is rarely exhausted on 9×9.

## Notable trade-offs, stated once

- **Fixed canvas** — bounded wall size, in exchange for never stalling to reallocate.
- **Best-sample mosaic** — no noise averaging, in exchange for sharp strokes.
- **Copy over lock** — 2 ms per solve dispatch, in exchange for no torn reads.
- **Flat wall as its own case** — a little more code, in exchange for not losing float
  precision on the common case.
- **Plain-video replay is degraded** — no world pose, so geometry is approximate. It
  exists to iterate on detection and solving, not to validate tracking. Use an ARCore
  recording when the geometry matters.
