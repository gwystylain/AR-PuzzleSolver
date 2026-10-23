# Recording a session worth debugging

You get one trip to the room and the scan probably will not work first time. This is
what to capture so the failure can be diagnosed without going back.

**No mode records ARCore any more.** Gems threw all of that away -- its own camera, no
world tracking, no mosaic -- and Mines, Terminal and Spot followed. What Gems leaves
behind instead is a **Capture**: the frames the scanner actually read, each paired with
the reading it made of that frame. Mines keeps every scan without being asked. Where it
matters, the sections below say which wall they are about.

**Nothing in the room needs a cable.** Everything the app can be told to do there, it
has a button for, and everything it writes waits on the phone. A cable is needed
afterwards, to bring the files off: `tools/collect-session.sh`, or `adb pull`. There used
to be an **Export** button that zipped the run for the share sheet. It was taken off the
HUD because every button there sits over the wall being read. The files are in the app's
own storage, so pull them before the app is ever uninstalled; an uninstall takes them
with it.

## Before you go

- **Install the debug build and open it once.** Grant the camera permission at your
  desk, not in the room.
- **Free up storage.** An ARCore recording is roughly 100 MB a minute. A gem capture is
  a few megabytes a burst and the log is kilobytes a minute, neither worth thinking about.

## Log, in every mode

One button sits under the HUD in every mode, and it is what makes the rest of this
possible without a laptop in the room.

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

## In the room, on the mines wall

1. **Pick Mines on the landing page.** The LED-wall preset and the lattice frames go on
   by themselves, and so does the Log.
2. **Stand at the green button with all seven rows in view**, until both chips at the
   top say so. The lattice exposure steers itself in this time.
3. **Tap Scan and pan slowly right**, rows in view all the way, to the end of the wall.
   The count at the top should climb a column at a time. If it says the count was lost,
   pan back left to lit buttons already passed; it finds its place from them.
4. **Check the board against the wall** before pressing anything. A misread is a tap.

Every scan keeps itself under `files/mines/<date-time>/` -- frames, the scanner's log,
and the board and plan -- so there is nothing to remember to press. Rescanning starts a
new folder rather than overwriting the last.

## In the room, on the gem wall

1. **Pick Gems on the landing page** (Back from the HUD returns there). Picking it applies the LED-wall camera preset
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
6. **Nothing needs sending from the room.** The captures and the log stay on the phone
   until it is next plugged in; see [straight afterwards](#straight-afterwards).
7. **Leave the app open** if the phone is going anywhere near a computer, since the
   canvas dump is live state. If it is not, it does not matter.

There is no Rescan and no second pass here, deliberately: every frame is read from
scratch, so a bad read is fixed by pointing the camera differently and not by waiting.

## Straight afterwards

Plug the phone in and run:

```bash
tools/collect-session.sh my-attempt --with-sessions
```

Phone still connected, app still open. Drop `--with-sessions` unless you made an ARCore
recording -- no mode makes one on its own any more. It gathers:

| file | mode | what it answers |
| --- | --- | --- |
| `mines/<date-time>/` | Mines | One folder a scan: the frames it read (`frames.txt` says which were lattice and which colour), `scan.txt` with the scanner's reading of each frame and every lit cell, and `board.txt` with the board and the plan. `MinesReplay` runs a folder back through the scanner. |
| `gems/` | Gems | Every captured frame, as the scanner received it. Unpacked from `.ppm.gz` on the way in, which is the format `core`'s `GemFixture` loads -- so one of these dropped into `core/src/test/resources` turns the failure into a unit test. |
| `gems-log.txt` | Gems | The reading made of each of those frames: pitch, exposure asked and actual, the targets in play, and every gem's three rings with its confidence and matched slot. |
| `terminal/`, `terminal-log.txt` | Terminal | The frames the reader read, gzipped as `TerminalReplay` and the tests load them, and a row per display per frame with the exposure metered on the wall. |
| `logs/run-NNNN.txt` | all | The heartbeat, and every camera line, mode change and target tapped. Written on the phone by the **Log** button, so it survives the ring buffer wrapping. Its first lines carry the device and build. |
| `logcat-app.txt` | all | The same thing pulled over adb, for a run where Log was never pressed. Whatever is still in the ring buffer, which may not be much. |
| `device.txt` | all | Model, Android and ARCore versions. Only the adb route produces this; the on-phone log carries it in its header instead. |

Then send the folder.

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
- **`chromaTiles`** — colour is streaming. On a self-lit wall read through ARCore, zero
  meant the board could not be read at all, since every lit button is the same clipped
  white without it. (That was Mines, before it moved to the live camera.)
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

### `mines[...]`, which only Mines prints

```
mines[read=861 lattice=430 single=3 rate=28.9/s dropped=27 convert=6.6 scan=7.0ms/frame
      lattice-cam=1/250s iso3200 -> 1/250s iso3200]
```

- **`lattice`** — lattice frames read. About half of `read` means the camera is
  alternating as asked. **Zero, with `single` climbing, means it is not**: every frame is
  one exposure, and colour is falling back to the glow.
- **`lattice-cam`** — the lattice exposure asked for, then what the sensor did. The two
  differing is the camera ignoring the burst, which this HAL has done to other requests.
- **`rate`** — frames read a second since the last heartbeat, both kinds. Half of it is
  the lattice rate; under about 15 and the count starts to need the lit buttons to stay
  on track. On the phone it runs 27 to 30 during a scan.
- **`convert` / `scan`** — the two halves of the cost of a frame, averaged: turning it
  upright, and reading it. Which one has grown says what to fix. Both tripling together
  a while after a scan is the phone lowering its clocks, not the app.

What the scanner made of each frame is not in the heartbeat; it is in the scan's own
`scan.txt`, a line a frame and a line for every lit button.

## If you can only capture one thing

On **Mines**, the scan folder, which is kept without being asked. Its frames replay
through `MinesReplay`, and its `scan.txt` carries the colour of every lit button at the
colour exposure -- the one measurement of the mines wall that does not exist yet.

On **Gems**, a Capture taken while the wall was misbehaving. There is no
replay to fall back on: an MP4 shot by the phone's camera app carries that app's exposure
rather than this one's, and feeding one back through **Open video** does not run the gem
scanner anyway -- `VideoFrameSource` has no pose, so the live path never engages. A
capture is the only thing that puts the frames the scanner saw on a desk, and it is
cheap. Take several.

And pull it before the app is reinstalled from scratch. A capture lives in the app's own
storage, so an uninstall deletes it, and a capture that was deleted and a capture that was
never taken are the same artifact from a desk.

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

For Spot (`--es pin spot`), `--ez spotshot true` takes a photo and `--ez spotreset true`
starts the round again. Every round is kept under `files/spot/` and replays at a desk;
see [SPOT_PUZZLE.md](SPOT_PUZZLE.md#replaying-a-round).
`--ez dump true` does *nothing* in Gems and is not a substitute -- there is no canvas to
dump, and the live path returns before the dump is ever serviced.

Terminal (`--es pin terminal`) is on the same pose-free path and has the same shape of
capture: `--ez termdump true`, with `--ei termframes` and `--ei termevery`. It writes
greyscale frames and a row per display into `files/terminal/`, and those replay offline
through `TerminalReplay` -- see [TERMINAL_PUZZLE.md](TERMINAL_PUZZLE.md). `--ez dump true`
does nothing there either; there is no canvas.

Its heartbeat line is the cheap record of a run, and worth reading before pulling frames:

```
term[displays=32 numbers=31 unread=0 settled=true next=008 then=012 luma=52 dropped=0
     scan=6.4ms prof='detect 2ms (dec 1 blur 0 cc 0 filt 0) read 5ms' status='next 008 ...']
     exp[bright=255 face=171 stops=+1.80 (from face) n=28
     loop='wall metering: applying (1 changes)' last=+1.84]
```

`displays` against `numbers` separates "cannot see the wall" from "can see it and cannot
read it", and `unread` separates both from "read most of it and gave up on a few".
`settled=false` for more than a moment means consecutive scans disagree, which is the
symptom of a wall being read at the edge of legibility -- step closer or steady the
phone. See [TERMINAL_PUZZLE.md](TERMINAL_PUZZLE.md).

`exp[...]` is the exposure measured on the displays themselves. `stops` is how far the
wall is from the reference clip's exposure, which is the target. Anything within half a
stop is on target, and every display reads from two under to two over. `(from face)`
means the digits had clipped and the estimate came from the tile face instead. `loop` is
what the exposure loop is doing about it, and `last` is the error it last acted on. If
`stops` stays more than half a stop off for longer than a few seconds and the loop never
says `applying`, `at the limit` or `ignoring the request`, that is the thing to report. See [metering the wall](TERMINAL_PUZZLE.md#metering-the-wall).

## Testing against a screen first

Worth doing, and it works — but the buttons will be far smaller in view than on a real
wall, so the measured pitch can fall under the lattice detector's 3 cm floor and it will
decline with exactly the `gridWhy` above. Get the phone close enough that the buttons on
screen are roughly the angular size they would be on the wall, or accept that this
particular rejection is an artifact of the screen rather than a bug.
