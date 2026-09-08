# Recording a session worth debugging

You get one trip to the room and the scan probably will not work first time. This is
what to capture so the failure can be diagnosed without going back.

**Two modes, two sets of artifacts, and they have nothing in common.** Mines runs on
ARCore, so what to capture is an ARCore recording and the canvas built from it. Gems
threw all of that away -- its own camera, no world tracking, no mosaic -- so neither
exists, and in Gems the **Record** button is not even on screen. What Gems leaves behind
instead is a **Capture**: the frames the scanner actually read, each paired with the
reading it made of that frame. Where it matters, the sections below say which wall they
are about.

**Nothing here needs a cable.** Everything the app can be told to do in the room, it
has a button for, and everything it writes can leave the phone through the share sheet.
`adb` is faster where it is available and every section below gives the command, but it
is an alternative and never a precondition -- the trip is made with a phone, and a
debug workflow that assumes a laptop is one that produces nothing on the day it matters.

## Before you go

- **Install the debug build and open it once.** Grant the camera permission at your
  desk, not in the room.
- **Decide where the run will be sent from the phone.** The **Export** button raises the
  share sheet; whichever app receives it -- mail, Drive, Files -- wants to be signed in
  already. That is the one thing that is genuinely awkward to sort out standing at a wall.
- **Free up storage.** An ARCore recording is roughly 100 MB a minute. A gem capture is
  a few megabytes a burst and the log is kilobytes a minute, neither worth thinking about.

## Log and Export, in every mode

Two buttons sit under the HUD in every mode, and between them they are what makes the
rest of this possible without a cable.

**Log** writes this process's logcat -- the heartbeat, the camera lines, every mode
change and every target tapped -- to a file on the phone. Two things about it are worth
knowing because they change what it has to be pressed *for*:

- **It starts with history.** `logcat` hands over what is already buffered for the
  process before it starts following, so pressing Log ten minutes into a run still
  captures those ten minutes. Forgetting to press it first is not the loss it looks like.
- **In Gems it presses itself.** Picking Gems starts the log, on the same reasoning that
  picking Gems applies the LED-wall preset: one trip, and a setting whose absence is only
  discovered afterwards is not one to leave to memory. Stopping it by hand is respected
  and it will not restart itself.

Why a file at all, when this was always going to logcat: the ring buffer is shared with
every other process on the device and wraps without saying so, so "plug the phone in
afterwards" is not a substitute. By then the part of the run still in it may be the part
after the interesting bit.

**Export** zips the run -- captures, canvas dump, logs -- and raises the share sheet.
It stops the log first, because a log still being written goes into the zip truncated at
whatever byte the copy thread had reached, and the tail is the part that matters. ARCore
recordings are left out and the count of them is reported: at 100 MB a minute they are
not something to send, and they are still there for `adb pull`.

## In the room, on the mines wall

1. **Start recording before you start scanning.** Press **Record**. It writes an ARCore
   dataset, which is not a video: playback re-runs ARCore's real tracking over the
   recorded camera and IMU streams, so poses, feature points and depth behave exactly as
   they did in front of you. That makes it a faithful regression test of the whole
   stack, and the only artifact that lets a fix be *proven* rather than argued.
2. **Open the Mode menu and pick Mines.** Identification should manage on its own, but
   pinning removes one variable, and a half-scanned board is exactly when the evidence
   is thinnest.
3. **Pan slowly, and pan the whole wall.** The solver is `REQUIRES_FULL_SCAN` and will
   not answer until every button has been seen — a white button at the far end changes
   where mines go at this end.
4. **Do a second pass over anything that looked wrong**, rather than stopping. Cells are
   re-read when the view of them improves, so a second look is cheap and often fixes a
   misread on its own.
5. **Stop recording, but leave the app open and in the foreground.**

That last point matters more than it sounds. The canvas dump and the heartbeat log are
both live state. Killing the app first throws away the two most useful artifacts.

## In the room, on the gem wall

1. **Open the Mode menu and pick Gems.** Picking it applies the LED-wall camera preset
   immediately, which is the single thing most likely to decide whether this works; the
   auto-exposure loop then starts from there rather than from whatever the room made the
   camera do.
2. **Type the four targets in before judging anything.** Until a ring is set, nothing can
   match, and "no match in view" with empty targets looks exactly like a classifier that
   has failed. Three taps each, or push them over the broadcast.
3. **Read the status line before you look at the rings.** It is written to be acted on.
   *over-exposed: N% of the gems has no colour* is a camera problem and the dials are
   right there. *only N gems in view -- take a step back* is a framing problem: the pitch
   is the median gem-to-gem spacing and it needs six of them, and every ring radius is a
   fraction of that pitch, so nothing is read at all until it resolves.
4. **Press Capture while it is wrong.** Twelve frames just under a second apart, counting
   down on the button; keep the wall in view for those ten seconds. Capturing afterwards,
   or at the desk, records the ceiling.
5. **Capture again for each *different* way it looked wrong** -- another round, another
   exposure, another distance. Bursts continue the numbering rather than overwriting, so
   more is only ever better, and the sidecar makes it obvious afterwards which to keep.
6. **Press Export before leaving**, and send the zip to yourself. This is the step that
   replaces the cable, and it is the only one that cannot be done later -- everything
   else is already on disk by then, but only if this happened.
7. **Leave the app open** if the phone is going anywhere near a computer, since the
   canvas dump is live state. If it is not, it does not matter.

There is no Rescan and no second pass here, deliberately: every frame is read from
scratch, so a bad read is fixed by pointing the camera differently and not by waiting.

## Straight afterwards

**From the phone, with no cable.** Press **Export** and send the zip. It carries
everything in the table below except the ARCore recordings, and it says how many of
those it left behind.

**From a laptop, if the phone is going to be plugged in anyway:**

```bash
tools/collect-session.sh my-attempt --with-sessions
```

Phone still connected, app still open. Drop `--with-sessions` if you only ran Gems --
there will not be any. Either route gathers:

| file | mode | what it answers |
| --- | --- | --- |
| `canvas-dump.png` | Mines | What the app actually saw, in colour. Sharp or smeared, square or skewed, the right wall or the wrong one. |
| `sessions/` | Mines | The ARCore recording, for replaying the whole pipeline offline. |
| `gems/` | Gems | Every captured frame, as the scanner received it. Unpacked from `.ppm.gz` on the way in, which is the format `core`'s `GemFixture` loads -- so one of these dropped into `core/src/test/resources` turns the failure into a unit test. |
| `gems-log.txt` | Gems | The reading made of each of those frames: pitch, exposure asked and actual, the targets in play, and every gem's three rings with its confidence and matched slot. |
| `logs/run-NNNN.txt` | both | The heartbeat, and every camera line, mode change and target tapped. Written on the phone by the **Log** button, so it survives the ring buffer wrapping and needs no cable. Its first lines carry the device and build. |
| `logcat-app.txt` | both | The same thing pulled over adb, for a run where Log was never pressed. Whatever is still in the ring buffer, which may not be much. |
| `device.txt` | both | Model, Android and ARCore versions. Only the adb route produces this; the on-phone log carries it in its header instead. |

Then send the folder, or the zip.

The two gem artifacts are meant to be read together, and separately they are each half
an answer. The log says what the classifier decided; the frame says what it decided it
*from*. Only the pair distinguishes a gem read wrong from a gem that was never legible.

If `adb` is not found, add the platform tools to your path:

```bash
export PATH=$PATH:$HOME/AppData/Local/Android/Sdk/platform-tools
```

## The heartbeat line, and how to read it

One line every two seconds, tagged `ScanPipeline`. It is worth a glance in the room,
because most failures are visible in it and some are fixable on the spot.

**Two seconds of wall clock, not a count of frames**, and that distinction was bought on
device. Keyed to the frame loop, the heartbeat went silent the instant live Gems stopped
receiving frames -- which is to say it fell quiet in precisely the failure it exists to
report, leaving a log that ended mid-run with no error and no explanation. On a clock,
and emitted from the no-frame path too, the same failure prints itself:

```
f=285 fps=0.0 [cam2 1920x1080 rot=90 'camera running (manual 1/250s iso100 AWB-lock)']
track=NO-FRAME ...
```

- **`fps`** — frames the pipeline actually consumed since the last heartbeat. The camera
  claiming 30 in its diagnostics while this reads 0.5 is a real and specific failure, and
  the two numbers sitting next to each other is what makes it visible.
- **`track=NO-FRAME`** — this heartbeat was emitted with no frame in hand at all.

```
cov=1.3% used=630 tiles=8 chromaTiles=8 ink=11permille tileMax=255
gridWhy='bombs: pitch 17.1 texels is outside the believable range'
grid=43x45 pitch=7.0/7.0tx gconf=0.80 puzzle=- cells=0/1935 outcome=NeedMoreData
```

- **`cov`** — fraction of the canvas seen. If it barely moves while you pan, the wall
  is not being tracked and nothing downstream can work.
- **`chromaTiles`** — colour is streaming. **Zero while Mines is selected means the
  board cannot be read at all**, since every lit button is the same clipped white
  without it.
- **`gridWhy`** — why the grid was rejected, per detector. The example above is the
  lattice detector declining because the buttons looked 1.7 cm apart, which is what
  happens when the "wall" is a monitor across the desk rather than a wall.
- **`grid` / `gconf`** — beware a confident grid with an implausible pitch. `GridDetector`
  hunts ruled lines and, on a wall that has none, settles for noise and reports a fine
  pitch at high confidence. `43x45` at a 7-texel pitch is that failure, not a grid.
- **`puzzle`** — which adapter is reading. `-` means none was identified.
- **`read[...]`** — the tally from the last cell-reading pass: `unlit`, `lit`, `unnamed`,
  `uncovered`. A large `unnamed` means colours are landing between palette classes, which
  is a lighting or white-balance problem rather than a solver one.

### `gems[...]`, which only live Gems prints

Everything above comes from the engine, and live Gems has no engine -- no wall, no grid,
no adapter -- so on a gem run two thirds of that line is dashes. The `gems[...]` clause
on the end is the half that is missing, and it is the whole record of a gem run for
anyone who left the room without pressing Capture:

```
gems[blobs=13 lit=13 readable=11 matched=2 pitch=164.8px luma=41 dropped=14
     targets=1/4 scan=23.4ms cap=0 status='2 matches  ·  11 of 13 gems read']
```

Read it left to right; each number failing is a different stage giving up, and they want
opposite fixes.

- **`blobs`** — discs the detector found. Zero is a framing, focus or threshold problem
  and nothing after it can mean anything.
- **`lit`** — of those, how many had anything above panel brightness. `blobs=13 lit=0` is
  an exposure floor, not a detection failure, and is the one case the two numbers exist
  to separate.
- **`readable`** — of those, how many had all three rings named. A large gap between
  `lit` and `readable` with `washed` low is the classifier's problem; with `washed` high
  it is the camera's, and `washed` is in the `ae[...]` clause earlier on the line.
- **`pitch`** — median gem-to-gem spacing, in pixels, and the ruler every ring radius is
  a fraction of. `-1.0px` means fewer than six gems were in view and nothing was read.
- **`targets`** — active of four. **`0/4` means nothing can match**, and every other
  number on the line can be perfect while the screen shows nothing.
- **`cap`** — frames left in a capture burst.

## If you can only capture one thing

On **Mines**, the ARCore recording. Everything else can be regenerated from it by
replaying, and it is the only artifact that survives leaving the room.
`canvas-dump.png` is second.

On **Gems**, a Capture taken while the wall was misbehaving, exported. There is no
replay to fall back on: an MP4 shot by the phone's camera app carries that app's exposure
rather than this one's, and feeding one back through **Open video** does not run the gem
scanner anyway -- `VideoFrameSource` has no pose, so the live path never engages. A
capture is the only thing that puts the frames the scanner saw on a desk, and it is
cheap. Take several.

And press **Export** before leaving. A capture that never left the phone and a capture
that was never taken are the same artifact from a desk, and only one of them is still
recoverable.

## Scripted debug states

Everything below needs a cable and none of it is required -- each has a button. What a
broadcast buys is a *repeatable* sequence, and the ability to change a setting without
the tap moving the phone:

```bash
adb shell am broadcast -a com.puzzlesolver.app.DEBUG --es pin bombs --ez dump true
```

`--es pin <id>` game mode, `--ef flat <metres>` assume a flat wall at that distance and
skip wall fitting, `--ei expect <n>` supply the grid size, `--ez dump true` write the
canvas, `--ez rescan true` restart the scan, `--ez newwall true` restart everything.

For Gems, `--ez gemdump true` starts a capture, with `--ei gemframes <n>` and
`--ei gemevery <ms>` overriding the twelve-at-900 ms default:

```bash
adb shell am broadcast -a com.puzzlesolver.app.DEBUG --ez gemdump true --ei gemframes 30 --ei gemevery 300
```

Worth having over the button for two things a tap cannot do: capturing without the tap
jogging the aim, and capturing on a schedule alongside a scripted sequence of exposures.
`--ez dump true` does *nothing* in Gems and is not a substitute -- there is no canvas to
dump, and the live path returns before the dump is ever serviced.

The same is true of Terminal (`--es pin terminal`), which is on the same pose-free path:
no canvas to dump, no capture of its own yet. What it does have is a heartbeat line of
its own, which is the whole record of a run:

```
term[displays=32 numbers=31 unread=0 settled=true next=008 then=012 luma=52 dropped=0
     scan=6.4ms prof='detect 2ms (dec 1 blur 0 cc 0 filt 0) read 5ms' status='next 008 ...']
```

`displays` against `numbers` separates "cannot see the wall" from "can see it and cannot
read it", and `unread` separates both from "read most of it and gave up on a few".
`settled=false` for more than a moment means consecutive scans disagree, which is the
symptom of a wall being read at the edge of legibility -- step closer or steady the
phone. See [TERMINAL_PUZZLE.md](TERMINAL_PUZZLE.md).

## Testing against a screen first

Worth doing, and it works — but the buttons will be far smaller in view than on a real
wall, so the measured pitch can fall under the lattice detector's 3 cm floor and it will
decline with exactly the `gridWhy` above. Get the phone close enough that the buttons on
screen are roughly the angular size they would be on the wall, or accept that this
particular rejection is an artifact of the screen rather than a bug.
