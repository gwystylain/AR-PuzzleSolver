# Puzzle Solver AR

An Android AR app that pans a camera across a large 2D puzzle painted on a **curved
wall**, builds a flat rectified image of it as you scan, and overlays the solution —
the instant the solution becomes determined, which for some puzzle types is well
before you have finished scanning.

Native Kotlin + ARCore. Fully local: no network calls, no cloud anchors, no model
downloads. Optimised for throughput; power consumption is deliberately not a
consideration.

## Install

**[Download the latest APK][latest]** and open it on the device.

[![Latest release](https://img.shields.io/github/v/release/gwystylain/AR-PuzzleSolver?label=latest%20APK&sort=semver)][latest]

[latest]: https://github.com/gwystylain/AR-PuzzleSolver/releases/latest

It needs an arm64 or armv7 Android 8.0+ phone with [Google Play Services for
AR][arcore] installed — an x86 emulator will not run it, since ARCore ships arm
ABIs only. Because the APK does not come from the Play Store, Android will ask
you to allow installing unknown apps for whichever app opened the file.

Nothing leaves the device: no network calls, no cloud anchors, no model
downloads. The camera permission is the only one requested.

Building it yourself, or cutting a new release, is covered in
[docs/RELEASING.md](docs/RELEASING.md).

[arcore]: https://play.google.com/store/apps/details?id=com.google.ar.core

## The core idea

A wall that curves horizontally is a **developable surface** — a vertical extrusion
of a horizontal profile curve, with zero Gaussian curvature. Such a surface flattens
onto a plane with *no distortion at all*. So the app:

1. Fits a vertical cylinder (or plane) to the wall from ARCore depth points.
2. Unwraps it to a flat metric canvas, where `u` is arc length along the wall and
   `v` is height. Distances on the canvas equal distances on the wall.
3. Projects every camera frame onto that canvas, keeping the best look at each texel.
4. Detects the puzzle grid on the canvas — where lines are straight and cell pitch is
   constant, because the canvas is metrically correct.
5. Reads cells and feeds them to an incremental solver as they become readable.

Because step 5 is incremental, a constraint puzzle can be answered from a partial
scan. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for why that is sound and not
a guess.

## Partial versus full scan

The distinction you asked for is explicit in the type system, as
`SolutionPolicy`:

| Policy | Meaning | Reference implementation |
| --- | --- | --- |
| `UNIQUE_COMPLETION_SUFFICES` | Once the clues seen so far admit exactly one completion, unseen clues cannot change it. Answer immediately. | Sudoku |
| `REQUIRES_FULL_SCAN` | Every clue is load-bearing; an unseen one can still flip the answer. Wait. | Nonogram |

Sudoku is answered the moment its read clues become uniquely completable — typically
long before you have panned the whole wall. A nonogram refuses until the board is
fully covered, and says so in the HUD while it waits.

## The three rooms

Three of the puzzle types are not printed puzzles at all but walls of lit panels in a
darkened room, and each needed its own detection path because none of them has a line
drawn anywhere on it.

- **Mines** — a wall of single-colour RGB buttons; place mines, detonate, clear the
  board. See [docs/BOMB_PUZZLE.md](docs/BOMB_PUZZLE.md).
- **Gems** — a wall of buttons carrying three concentric rings of colour. Four target
  gems are shown above the wall; you tap them into the HUD and the app rings every gem
  that matches as you pan. See [docs/GEM_PUZZLE.md](docs/GEM_PUZZLE.md).
- **Terminal** — a wall of split-flap displays each showing a three-digit number. A ball
  takes the lowest number showing and that display clears, over and over until the wall
  is empty. The app outlines the lowest number in green and the second lowest in yellow.
  See [docs/TERMINAL_PUZZLE.md](docs/TERMINAL_PUZZLE.md).

Gems and Terminal do **not** use the AR pipeline. Both own the camera directly and read
each frame in image space with no pose, wall fit or canvas — Gems because ARCore will not
let it set an exposure the gem rings are visible at, Terminal because the wall changes
while you are reading it and a mosaic of a wall taken at different moments would answer
with a number that is no longer on it.

Gems is the one mode where the app answers a question the *user* asked rather than one
the wall poses, so it has no "solved" moment — the answer is a live count of matches
that narrows as you enter more of a target. Terminal has no solved moment either, for a
different reason: the answer is where to look next, and it changes every few seconds.

Mines and Gems needed something the rest of the app never did: **control of the
camera**. A dark room full of bright LEDs is exactly the scene auto-exposure gets wrong,
and at the exposure the camera picks for itself half of every gem carries no colour at
all. (Terminal is the exception among the three: its panels are read from luma, and the
phone's own metering handles them.) The app
takes the camera over through ARCore's shared-camera mode, exposes the dials in the HUD,
and closes a loop from the solver's own measurement of how much colour survived back to
the shutter.

**ARCore discards that request** — on the current 1.54 release as well as 1.48, while the
same request through plain Camera2 is honoured exactly, so it is ARCore rather than the
phone and it is not a bug waiting to be fixed. Gems therefore runs on its own Camera2
source with no ARCore at all, where a manual 1/250 s at ISO 100 is honoured to the
microsecond; Mines keeps the AR pipeline, which suits it better anyway.
The app detects it, says so, and stands the loop down rather than throwing scans away for
nothing. Gems therefore cannot yet work in that room through ARCore. The measurements,
the probe that isolates it, and what would have to change are in
[docs/CAMERA_CONTROL.md](docs/CAMERA_CONTROL.md).

## Status

**Builds clean, all unit tests pass. Run on a device for the camera work only** — the
capture path, the HUD, the Gems controls and the exposure plumbing have all been
exercised on a CPH2655; nothing has yet been pointed at any of the three rooms.

- `./gradlew :core:test` — 137 tests, 137 passing, including a whole terminal wall read
  off a real frame
- `./gradlew :app:testDebugUnitTest` — 43 tests, 43 passing (camera tuning, the
  auto-exposure loop and the preview geometry, all pure state machines and maths)
- `./gradlew :app:assembleDebug` — succeeds, zero compiler warnings, ~10 MB debug APK

Verified against JDK 17.0.20 (Temurin), Gradle 8.13, AGP 8.9.1, compileSdk 35 on
Windows 11. See [docs/SETUP.md](docs/SETUP.md).

What that does and does not tell you: the geometry, solvers, grid detection and the
whole capture pipeline compile and the logic that can be tested off-device is tested.
Nothing has yet pointed a camera at a wall *live*, so the GL shaders have never been
executed and ARCore has never initialised. Those are the next things to find out.
Detection and glyph reading are a step further along than the rest: Gems and Terminal
are both tested against real frames off the real walls, and Terminal's test reads all 96
digits of a 32-display wall out of one camera frame.

The two things that will need real-world tuning rather than debugging:

- **Glyph classification.** The template matcher in `TemplateGlyphClassifier` works
  against digits rendered in the device's own font. If your puzzles use a distinctive
  typeface, accuracy will want either better templates or a small TFLite model. The
  interface (`GlyphClassifier`) is a one-method swap. Terminal has already taken the
  first of those roads and ships templates built from the wall's own digits, which took
  it from 46 misread digits in 1860 to 2 — see
  [docs/TERMINAL_PUZZLE.md](docs/TERMINAL_PUZZLE.md).
- **Your actual puzzle type.** Sudoku and nonogram are in as references for the two
  policies. Whatever your puzzles really are, they plug in as a `PuzzleAdapter`
  without touching capture, geometry or rendering — see
  [docs/PUZZLE_PLUGINS.md](docs/PUZZLE_PLUGINS.md).

## Testing against recordings

Recording and replay is built in via ARCore's Recording & Playback API, not a
homegrown video-plus-pose sidecar. During playback ARCore re-runs its real tracking
over the recorded camera and IMU streams, so poses, feature points and depth behave
exactly as they did live. A recording is a faithful regression test of the whole
stack.

- **Record** — the Record button writes an MP4 dataset to
  `/sdcard/Android/data/com.puzzlesolver.app/files/sessions/`.
- **Replay last** — re-runs the most recent recording through the identical pipeline.

Replay runs as fast as processing allows rather than in real time, so a 40-second
capture does not take 40 seconds to re-test.

**Open video** takes any MP4, but be aware of the limit: a plain MP4 carries no camera
pose, and the whole pipeline projects frames onto a world-anchored wall. With no pose
there is nothing to project onto, so that path currently previews frames and leaves
accumulation, detection and solving idle — and the HUD says so rather than pretending.
Making arbitrary footage useful means adding vision-only tracking (frame-to-frame
motion from feature correspondences, chained into a relative trajectory). Deliberately
not faked, because a wrong pose silently produces a smeared canvas and wrong answers.
Decoding, intrinsics and the GL path are all wired; supply a non-null pose in
`FrameData` and the rest engages unchanged. See `VideoFrameSource`.

**Record your own scans and replay those** — that path is complete and faithful.

For the parts that do not need a device at all, `:core` is a pure-JVM module with no
Android dependencies:

```bash
./gradlew :core:test
```

## Layout

```
core/                      pure JVM, no Android -- unit-testable in milliseconds
  math/                    vectors, poses, pinhole intrinsics
  surface/                 WallSurface (plane + cylinder), WallFitter (RANSAC)
  canvas/                  canvas spec, coverage map
  image/                   GrayImage and the image ops detection needs
  grid/                    GridModel, GridDetector
  solve/                   IncrementalSolver, SolveOutcome, SolutionPolicy
  puzzle/                  PuzzleAdapter, cell reading, glyph classification
    sudoku/                reference: answers from a partial scan
    nonogram/              reference: requires the full scan
    bombs/                 the mines wall -- see docs/BOMB_PUZZLE.md
    gems/                  the gem wall, read per frame with no pose at all
                           -- see docs/GEM_PUZZLE.md
    terminal/              the number wall, also read per frame with no pose
                           -- see docs/TERMINAL_PUZZLE.md
  PuzzleEngine.kt          detect -> read -> solve, platform-free
app/
  frame/                   FrameSource: live ARCore, AR dataset replay, plain video,
                           and a pose-free Camera2 source with a real exposure dial
                           -- see docs/CAMERA_CONTROL.md
  surface/                 WallTracker -- pools points across frames, refits
  render/                  canvas accumulator, camera background, AR overlay, glyph atlas
  pipeline/                ScanPipeline -- GL thread and solver thread, wired together
                           AutoExposure -- solver-driven exposure search
  ui/                      Compose HUD, including the Gems target controls
  record/                  where recordings live
```

## Performance shape

Roughly, per frame at 60 fps:

- Camera image stays on the GPU as an external texture; it is never copied to the CPU.
- One draw of a 129² surface mesh warps the frame onto the canvas. Per-texel
  best-sample selection is done by the depth unit (`gl_FragDepth = 1 - confidence`),
  so there is no read-modify-write and no second pass.
- Readbacks are asynchronous through PBOs with fences. A synchronous `glReadPixels`
  would stall 10–20 ms on a mobile tiler and eat the frame.
- The canvas is mirrored to the CPU a few 256² tiles per frame, only where coverage
  changed — about 512 KB/frame, refreshing the whole canvas roughly twice a second.
- Solving runs on its own thread at ~4 Hz against a frozen copy of the canvas, with a
  12 ms budget per step and `Pending` returned rather than overrunning it.

Details and the reasoning behind each choice are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
