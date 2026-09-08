# Adding a puzzle type

Sudoku and nonogram are in the tree as references for the two solution policies. Your
actual puzzles plug in the same way, without touching capture, geometry, or rendering.

You write two things: a `PuzzleAdapter` (how to recognise and read it) and an
`IncrementalSolver` (how to solve it, incrementally). Then you register the adapter.

## 1. Decide the policy first

This is the decision that shapes everything else, so make it deliberately.

**`UNIQUE_COMPLETION_SUFFICES`** — choose this only if the following argument holds for
your puzzle:

> The puzzle has exactly one valid solution by construction. Therefore if the clues read
> so far admit exactly one completion **C**, then **C** is the answer: any unread clue
> belongs to the true puzzle, whose solution also completes the read subset, and the
> subset has only **C**.

That holds for sudoku, kakuro, most logic grids — anything where clues are constraints
on a uniquely-determined grid. It does **not** hold if a clue can *add* possibilities
rather than remove them, or if the puzzle admits several intended answers.

**`REQUIRES_FULL_SCAN`** — everything else. Nonograms, word searches, jigsaws, anything
where a single unseen clue can flip cells anywhere.

If you are unsure, pick `REQUIRES_FULL_SCAN`. A late answer is a mild annoyance; a
confident wrong answer overlaid on a wall is the failure mode users remember.

## 2. Write the solver

```kotlin
class MyPuzzleSolver(private val cols: Int, private val rows: Int) : IncrementalSolver {

    override val policy = SolutionPolicy.UNIQUE_COMPLETION_SUFFICES

    override fun observe(delta: ObservationDelta, deadlineNanos: Long): SolveOutcome {
        // delta.cells    -- newly readable cells
        // delta.revised  -- cells re-read after a better look
        // delta.fullyScanned -- the pipeline believes the whole board is covered
        //
        // Return one of:
        //   SolveOutcome.Solved(solution)   -- provably the answer
        //   SolveOutcome.NeedMoreData(n)    -- consistent, not yet determined
        //   SolveOutcome.Contradiction(...) -- observations conflict; name the suspects
        //   SolveOutcome.Pending            -- hit the deadline, call me again
    }

    override fun reset() { /* drop all state */ }
}
```

Three contract points that are easy to miss:

- **Respect the deadline.** Check `System.nanoTime() > deadlineNanos` inside any search
  loop and return `Pending`. The pipeline calls you again; it does not wait.
- **Be incremental if you can.** Re-solving from scratch per delta is correct but
  wasteful. Constraint-propagation state is usually cheap to keep.
- **Name suspects on contradiction.** `PuzzleEngine` clears exactly the cells you name
  and re-reads them. Returning an empty list means nothing gets re-read and you will
  contradict forever. For sudoku that is the lowest-confidence givens plus the specific
  conflicting peers.

## 3. Write the adapter

```kotlin
class MyPuzzleAdapter(private val classifier: GlyphClassifier) : PuzzleAdapter {

    override val id = "mypuzzle"
    override val displayName = "My Puzzle"

    override fun identify(canvasLuma, grid, coverage, spec): Float {
        // 0..1 confidence that this canvas holds YOUR puzzle.
        // Be honest about negatives: return near zero when the evidence is absent,
        // not merely when it disagrees. The registry takes the winner, so an
        // over-confident adapter starves the others.
    }

    override fun readCells(canvasLuma, grid, coverage, spec, alreadyRead): List<CellObservation> {
        // Use CellReader to sample and normalise each cell.
        // Skip cells whose coverage is below ~0.95 -- a half-seen glyph is worse
        // than no glyph, because it feeds the solver a confident wrong given.
        // Report visibly-blank cells as CellObservation.EMPTY: blank is a clue.
    }

    override fun createSolver(grid) = MyPuzzleSolver(grid.cols, grid.rows)

    override fun overlayFor(solution, grid): OverlayModel {
        // Glyphs, strokes and highlights in GRID coordinates. The renderer maps
        // those to canvas texels, then to the wall, then to the screen.
    }
}
```

`identify` is the part worth most care. Good signals, in rough order of usefulness:

- **Aspect ratio of cells** in metres via `grid.cellSizeMetres(spec)` — distinguishes a
  square-cell puzzle from a crossword or table immediately.
- **Grid line weight structure** — sudoku draws box rules heavier every √n lines, which
  is cheap to measure and very discriminative. See `SudokuAdapter.boxLineEvidence`.
- **Fill ratio** of readable cells — published puzzles sit in characteristic ranges.
- **Region statistics** — nonograms have clue strips that look statistically unlike the
  board. See `NonogramAdapter.identify`.

Multiply your score by `grid.confidence` so a shaky grid does not produce a confident
puzzle identification.

## 4. Register it

In `ScanPipeline.onSurfaceCreated`:

```kotlin
registry = PuzzleRegistry(
    listOf(
        SudokuAdapter(classifier),
        NonogramAdapter(classifier),
        MyPuzzleAdapter(classifier),
    )
)
```

Identification is sticky: once an adapter is committed and observations are
accumulating, a rival must score 1.4× better on three consecutive detections to take
over. That stops a momentary flicker from discarding a scan's worth of work.

While developing, skip identification entirely by picking your mode from the **Game
mode** flyout, or with `pipeline.selectPuzzleMode("mypuzzle")`. Passing null hands the
choice back to the evidence. Nothing needs adding to the UI -- the menu already lists
whatever the registry holds.

## 5. Test it without a device

`:core` is pure JVM, so write the solver test first and run it in milliseconds:

```bash
./gradlew :core:test
```

Follow the pattern in `SudokuSolverTest`: find a case where the *mathematics* pins down
the expected behaviour rather than a threshold you chose. For sudoku that is the
17-clue minimum. For your puzzle it might be a known-minimal instance, a symmetry
argument, or a construction with a provably ambiguous variant. A test built on a tuned
threshold will pass for the wrong reasons and fail when you tune it again.

For detection, synthesise a canvas the way `GridDetectionTest.drawGrid` does. For the
end-to-end path, record a real scan once and replay it — that covers the parts a
synthetic canvas cannot.

## If your puzzle is not on a grid

`GridDetector` assumes a regular grid, which is what makes cell addressing a similarity
transform. If your puzzles are irregular, the layer to replace is `GridDetector` plus
`GridModel`; everything above it (canvas, coverage, solver framework) is agnostic, and
everything below it (surface fitting, accumulation) never looks at cells at all. The
adapter interface would need `readCells` re-expressed in whatever addressing scheme
replaces `(col, row)`.

## If clues span multiple cells

`NonogramAdapter` currently assumes one clue digit per cell, which is the common
printed layout but not the only one. Multi-digit clues need `readCells` to segment the
strip cell into digits before classification — crop to the ink bounding box, split on
vertical projection minima, classify each fragment, then compose. The `CellReader`
primitives (`inkStats`, `normalize`) already do the per-fragment work; what is missing is
only the segmentation step.
