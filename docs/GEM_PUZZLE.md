# The gem puzzle

A curved wall of round buttons, seven rows deep, each one a "gem": three concentric
rings of RGB LEDs — an **outer ring**, a **middle ring** and a **centre dot** — each
independently red, yellow, green, blue or purple. Four target gems are shown above the
wall. The game is to find the gems on the wall that match them, against a clock.

The app does not read the targets off the wall. The user taps them in — four slots
across the top of the screen, three taps each — and every gem that matches is ringed on
screen as they pan.

**Gems does not use the AR pipeline.** It has its own camera and no world tracking at
all. That is a deliberate reversal, and the reason is exposure: ARCore will not pass a
capture request through to the sensor, so with ARCore driving, the gem rings are not in
the image to be read. The next section is the whole argument.

## Why this mode threw the AR pipeline away

The rest of the app fits a wall, unwraps it to a metric canvas, and reads a lattice off
that. It is the right machinery for a printed puzzle, and Gems was built on it first.
Then the camera work established two things on a real device
(see [CAMERA_CONTROL.md](CAMERA_CONTROL.md)):

- ARCore discards the app's capture request while it is tracking — on 1.48 and on the
  current 1.54 — so the exposure cannot be set while ARCore holds the camera;
- at the exposure ARCore chooses, 47–69% of every gem carries no colour at all, and the
  outer ring alone is not reliable enough to fall back on.

So the choice was a pose or a readable image, and **matching gems never needed the
pose**. Whether a gem matches depends on that gem alone; a highlight only has to land
where the gem is on screen right now; and the wall is rearranged every round, so a
mosaic accumulated across a pan was a liability rather than an asset.

What replaced it is smaller than what it replaced:

| AR pipeline | live Gems |
| --- | --- |
| fit a wall from feature points | — |
| unwrap to a metric canvas, accumulate | — |
| fit a lattice, adopt a grid | find the bright discs in the frame |
| read cells at grid coordinates | read rings at disc coordinates |
| pitch from the grid, in texels | pitch from gem spacing, in pixels |
| overlay projected onto the wall | rings drawn in screen space |

The scale is the one part that needed thought. Ring radii are fractions of the button
pitch, and the pitch is recoverable without any lattice fit: it is the median distance
from a gem to its nearest neighbour. That is the same quantity the ring constants were
measured against, so they transfer exactly — where the *apparent size* of a lit disc
would not have, since it grows and shrinks with exposure.

The same distances answer a second question: **are these blobs a wall at all?** Gems sit
on a lattice, so their nearest-neighbour distances all agree to within the perspective
across the frame. The spread of those distances — their interquartile range over their
median — runs 0.06 to 0.14 on real frames off the wall and 0.51 to 0.85 on points
scattered at random, and above 0.30 the frame is not treated as a wall.

That check is not theoretical. Pointed at a dark room at 1/250 s the frame is black, but
sensor noise still clears the detector's local-background threshold: twenty or thirty
blobs, a third of them bright enough to read, a pitch computed from them, rings sampled
off nothing, and an exposure hint published that the auto-exposure loop then acts on and
latches. Neither obvious guard catches it — thirty blobs is a normal number of gems, and
an LED wall two stops under is nearly as dark as an empty room — but the spacing gives it
away immediately, and the pitch jumping between 82 px and 186 px frame to frame is the
same fact showing up in the heartbeat.

**It gates the exposure hint and nothing else**, which is the whole of the judgement.
The two mistakes it can make are nowhere near equal in cost. Calling a real wall
scattered and refusing to read it would stop the feature dead while the user stands in
front of it — and the measurement is only good to about one blob in ten of glare before
it starts doing that, since a spurious blob landing beside a real gem drags one
nearest-neighbour distance down and the spread up. Withholding one frame's vote on the
camera costs nothing, because the next frame votes again. So the rings are read and
matched exactly as before whatever this says; all it decides is whether the frame is
allowed an opinion about the exposure.

Three things got better in the process:

- **It works before anything is tracked.** No wall fit to wait for, which matters in a
  room with a clock.
- **Off-axis stops mattering.** A rectified canvas had to be right before anything could
  be read; a gem twenty degrees off-square is still a round blob with concentric rings.
- **It is testable.** `GemScannerTest` runs detection, scale, reading and matching over
  an actual frame of the actual wall and holds them to 90% per ring — the same accuracy
  the canvas version reached, and a test the canvas version could never have had,
  because its input was a mosaic that only existed after standing in the room.

Mines still runs on ARCore, unchanged. Its classifier reads the glow around a button
that has clipped its own face to white, so it *wants* the exposure the camera picks, and
it needs the pose because a mine plan is about the whole board at once.

## Why the targets are typed in rather than scanned

The four target buttons sit above the gem grid, outside it, and change between rounds.
Reading them would mean detecting a second, differently-shaped lattice, deciding which
of the two any given button belongs to, and re-reading it every time the round turns
over — all to save three taps, and all of it able to fail silently in a way that puts
confident wrong highlights on the wall.

Typing them in cannot be wrong in a way the user does not immediately see: the target
they entered is drawn on screen next to the wall they are looking at.

## What a match means

A target zone the user has not chosen yet is a **wildcard**. That is what makes the
first tap useful: choose the outer ring and the wall immediately narrows to every gem
with that outer ring, then narrows again with the second and third taps. Entering three
colours blind and only then finding out whether the target was read correctly would
waste most of a round.

An observation zone the reader could not name is **not** a wildcard. It fails to match
any target that asks about that ring. The asymmetry is deliberate and is pinned by a
test: the cost of a missed gem is that the user keeps panning, and the cost of a wrong
highlight is that they walk to the wrong gem while the clock runs.

An unlit gem is reported as such rather than omitted, because "off" and "not looked at
yet" behave differently — the first can never match, the second still might.

## Reading a gem

Four findings shaped `GemPalette`, all measured off
`testVideos/Gems/VID20260814182431.mp4` rather than reasoned about, and each one broke
an approach that looked obviously right first.

### The rings clip, so brightness carries nothing

Inside a gem the brightest channel sits at 240–255 essentially everywhere. The mines
wall's trick — excess over local ambient, see [BOMB_PUZZLE.md](BOMB_PUZZLE.md) —
separates nothing here, because there is no unlit panel inside a gem to subtract and
every ring is saturated. What survives clipping is **hue**: a red LED clips R and leaves
G and B low; a blue one clips B and leaves R low. So the classifier reads hue and uses
brightness only to decide whether a texel is gem or panel.

### Averaging hue across a ring is worse than useless

Each ring throws a diffuse glow over the whole gem face, so the middle ring's band
contains its own LEDs *plus* the outer ring's wash. Averaging a red wash with green dots
gives a hue near yellow — a colour neither ring is, reported confidently.

Classifying every texel and taking a saturation-weighted **vote** fixes it: the wash and
the dots each vote for their own colour and the majority is the ring. That single change
took per-ring accuracy on the reference frame from 71% to 85%; correcting the band radii
took it to 90%.

Saturation is the right vote weight because a texel where two glows overlap is a
mixture, and mixtures are less saturated than either source.

### Two clipped channels name no ring, however saturated the texel looks

The vote above defends against *mixtures*, and it defends well, because a mixture is
desaturated and the saturation weight sees that. Clipping is the mirror image of that
failure and walks straight past it.

When a red centre dot and a green middle ring both bloom into the same texel, R and G
both peg at the sensor ceiling and B stays low. The result is not a washed-out texel; it
is a vivid, fully saturated **yellow** — hue 60 degrees, six off the palette's yellow
centroid, saturation 0.92. Green over blue gives the same thing at cyan, which is where
this wall's "blue" already sits. And because every texel across the bloom is the same
vivid blend, the vote is unanimous and the confidence comes back high.

This is what broke the run of 2026-08-20. At 1/62 s — two stops brighter than the preset,
see [CAMERA_CONTROL.md](CAMERA_CONTROL.md) — 80% of every middle-ring band was
double-clipped, and across 636 gem readings the middle ring came back:

| ring | reported |
| --- | --- |
| outer | green 51% · blue 23% · red 15% · yellow 9% · purple 1% |
| **middle** | **yellow 51% · blue 40%** · green 3% · purple 2% · red 1% |
| **centre** | **yellow 43% · blue 25%** · unknown 27% · green 2% · red 1% |

Yellow and cyan are the only two hues a pair of clipped channels can make, and they are
92% of the middle ring. Centre matched middle 92% of the time, both being inside the same
bloom. The targets entered that day were `red/green/green` and `red/red/green`; replaying
them against the captured readings matches **1** gem and **0** gems respectively, while
`green/blue/blue` matches 86 — false highlights on the clipping artefact. No amount of
aiming the camera could have found the right gem.

So a texel with two channels at or above `CLIP_LEVEL` is discarded before it votes, and
counted as washed out instead. **This does not make those gems readable** — the
information was destroyed in the sensor and no code recovers it. What it does is stop the
reader claiming otherwise, which is the part that matters: the frame then measures as
over-exposed, the HUD says so, and the auto-exposure loop has a reason to darken the
camera to where the rings are actually in the image.

The old measure could not see any of this. It counted *desaturated* texels, and a
double-clipped texel is the most saturated thing in the frame, so the worst frame of that
run scored 0.19 — under the 0.35 the HUD acts on and under the 0.25 the loop aims for.
`GemClippingTest` holds a crop of that frame and requires it to read as over-exposed.

### The bands are fractions of pitch, not of apparent size

| Ring | LED radius | Band sampled |
| --- | --- | --- |
| centre dot | 0 | 0 – 0.036 |
| middle ring | 0.105 | 0.086 – 0.124 |
| outer ring | 0.20 | 0.175 – 0.230 |

All as fractions of the lattice pitch. The lens is 0.5 of the pitch across, so the two
dot rings sit at 0.42 and 0.81 of the lens radius.

**And of the pitch *at that gem*, not the frame's.** One median for the frame assumes the
spacing is constant across it, and on a curved wall photographed off-square it is not: on
a real frame the nearest-neighbour distance ran 117 to 161 px while the median came out
132. A gem at the wide end had all three bands a fifth too far in — on one measured gem
the outer ring sat at 0.245 of the global pitch, outside the 0.175–0.230 band entirely,
and what the band caught instead was the middle ring's wash. `localPitches` smooths each
gem's *own* nearest-neighbour distance over its four nearest gems, which puts that gem's
outer ring back at 0.211 of its local pitch, in the middle of the band.

The statistic has to be the same one the constants were calibrated against — a median of
nearest-neighbour distances, which on an anisotropic lattice measures the shorter axis.
An estimator that measured something between the axes instead comes out 2–6% larger, and
that bias alone turns three correct rings on the labelled fixture into misreads.

Pitch and not the gem's apparent size, because the LED rings are at fixed radii on the
board behind the lens — a physical constant of the wall — while the apparent disc grows
and shrinks with how blown out the exposure is. An earlier version scaled from the
detected blob radius and had every band 14% too far in, which put the "middle" reading
on the centre dot.

### Measured hues

| colour | hue | note |
| --- | --- | --- |
| red | 352° | |
| yellow | 66° | a bright yellow ring desaturates toward white; there is no sixth colour |
| green | 146° | |
| blue | 198° | measures **cyan**, not blue |
| purple | 326° | measures **magenta** |

From 50 hand-labelled rings. Using textbook blue at 240° and purple at 280° instead
would put every blue reading nearer green than blue. Red and purple are the closest
pair at 26° apart, and they are where a reader that mishandles clipping goes wrong
first — there is a test guarding that margin.

### Accuracy

On 29 hand-labelled gems from the reference frame — 87 rings — this reads **90%**
correctly, with the **outer ring at 100%**. The residue is almost entirely the centre
dot, which is a handful of texels across and sits under the combined glow of both rings
outside it. `RealGemFrameTest` runs the real classifier over a crop of that frame and
holds the outer ring to exact agreement and the whole gem to 80%.

## The exposure problem

**This was the one thing most likely to stop the feature working in the room. The app
now takes the camera over and fixes it — see [CAMERA_CONTROL.md](CAMERA_CONTROL.md).**

The room is dark and the LEDs are bright, so the camera's own choice of exposure blows
every gem into a flat white disc with no rings visible at all. Of the six reference
clips, four look like that. Measured as the fraction of gem area carrying no usable
hue:

| clip | washed out |
| --- | --- |
| VID20260814182232 | 47–60% |
| VID20260814182324 | 55–69% |
| VID20260814182431 (reduced exposure) | 2–4% |
| VID20260814182523 (reduced exposure) | 0% |

The two regimes do not overlap. Three things follow from that:

- the same measurement is reported as `PuzzleAdapter.exposureHint`, and `AutoExposure`
  walks the camera down a stop at a time until it clears;
- picking **Gems** applies an LED-wall camera preset straight away — 1/250 s at the
  sensor's floor sensitivity with white balance locked — which is roughly where the two
  readable clips sit, so the loop usually has nothing left to do;
- `GemAdapter` still flags the wall as **OVEREXPOSED** above 35% in its read stats,
  because a device that will not hand over its camera has no dial and the user needs to
  know that rather than guess why nothing matches.

Getting the dial at all means running ARCore in shared-camera mode and driving the
Camera2 capture request directly. That has its own rules and its own failure modes, all
of them in [CAMERA_CONTROL.md](CAMERA_CONTROL.md).

## Canvas resolution

A gem's centre dot is about 3.6% of the pitch in radius. At the default 1.5 mm/texel and
a 15 cm pitch that is a disc two or three texels across, which is right at the classifier's
`MIN_SAMPLES` floor. Launching with a finer canvas is worth it here:

```bash
adb shell am start -n com.puzzlesolver.app/.MainActivity --ef res 0.0008
```

That still covers 3.3 m of wall, which is enough for this room, and doubles the texels
across every ring.

## Driving it from adb

The target dialog is three taps deep and Compose does not publish its nodes to
`uiautomator`, so the targets can also be set over the debug broadcast:

```bash
adb shell am broadcast -a com.puzzlesolver.app.DEBUG --ei gemslot 1 --es gem red/yellow/blue
```

Trailing rings may be left off or given as `-` to leave them unset, which is the same
partial target the dialog produces after one or two taps.
`--ez gemclear true` empties all four slots.

## Getting evidence out of the room

Everything this mode gained by dropping the AR pipeline, it lost in debuggability. Mines
records an ARCore dataset that replays through ARCore's real tracker and dumps the canvas
every stage after the mosaic consumes -- a bug seen on the wall can be reproduced at a
desk. Gems has neither: no session to record, no canvas to dump, and no replay, since an
MP4 shot on the phone carries the camera app's exposure rather than this app's and
`VideoFrameSource` never engages the live path anyway. Left there, a gem run that failed
in the room would leave one heartbeat line behind.

So there is a **Capture** button, and `GemRecorder` behind it. It writes the frame the
scanner read together with the reading it made of that frame -- twelve of them, just
under a second apart, armed by the button or by
`adb shell am broadcast -a com.puzzlesolver.app.DEBUG --ez gemdump true`.

Two decisions in it are worth stating, because both were the point rather than details.

**The pair is written from inside the scanner's borrow of the frame.** Sampled anywhere
else -- on the next frame, from the published UI state -- the image and the coordinates
beside it would be one or two frames apart, and a report whose halves quietly disagree
sends a reader somewhere there is no bug.

**The pixels go out as P6 PPM**, which is what `GemFixture` already loads. That is the
whole design: a frame off the real wall goes into `core/src/test/resources` and
`RealGemFrameTest` can be pointed at it unchanged, so a misread in the room becomes a
failing unit test on a desk. The sidecar is laid out in the same shape as
`GemFixture.LABELLED` for the same reason -- once the image has been over a zoom, the
corrected row is pasted in rather than retyped. Gzipped only because a 1080p PPM is 6 MB
and an LED wall is mostly black; `tools/collect-session.sh` unpacks them on the way out.

The log is the other half, and it moved for the same reason. The heartbeat was always
written and was only ever *readable* over adb, which quietly made a cable a precondition
for the mode least likely to have one -- Gems is used by walking to a wall with a phone.
`LogCapture` runs `logcat` on the app's own pid and copies it to a file, which needs no
permission, reaches back through what is already buffered, and catches the crash if there
is one. Picking Gems starts it, on the same reasoning that picking Gems drops the
exposure. **Export** then zips the captures, the log and the canvas dump and raises the
share sheet, so the run leaves the phone without ever meeting a laptop.

`GemRecorderTest` pins the parts that fail silently: that the burst stops where it was
told to, that rows come out top-down rather than flipped like the canvas dump, that a
second burst does not overwrite the first, and that the bytes written parse as the
fixture loader parses them. All four are mistakes that produce files of a plausible size
and are discovered by trying to use them, which is the one moment going back is no longer
possible.

## Telling this wall from the mines wall

Both are lattices of round lit buttons on a curve at a similar pitch, so shape alone
cannot separate them and an adapter that guessed would silently override the user's mode
choice. The discriminator is that a gem is several colours at once: a mines button reads
the same colour at every radius, a gem usually does not. `GemAdapter.identify` scores on
the fraction of buttons whose three rings differ, which needs no assumption about which
colours the room happens to be showing. The mines adapter meanwhile scores zero on a
board where more than 75% of buttons are lit, which this wall always is.

## What it costs per frame

Measured on device rather than reasoned about, which was worth doing: neither of the two
things that dominated was where the guess would have gone.

| stage | before | after |
| --- | --- | --- |
| downsample to 480 px | 9 ms | 4 ms |
| box blur | **16 ms** | 3 ms |
| threshold + connected components | 2 ms | 1 ms |
| blob filter | 0 ms | 0 ms |
| pitch from spacing | 0 ms | 0 ms |
| read 13 gems' rings | **27 ms** | 12 ms |
| **total scan** | **57 ms** | **23 ms** |

Both fixes came straight from the numbers:

- **The blur was already a separable sliding window**, so the obvious suspect — an
  O(radius) kernel — was not it. The cost was that its vertical pass walked each column
  top to bottom, striding a cache line per pixel on a 480-wide image. Sweeping rows with
  a running sum per column is the same arithmetic and the same output, and took it from
  16 ms to 3. That function is shared, so grid detection and the mines lattice got it too.
- **Reading a ring looked at every texel in the band.** It is a vote, and a vote does not
  get more right with more voters once it has enough: the outer band of a gem at a
  70-pixel pitch holds about a thousand texels and the winner is the same from two
  hundred. Striding to roughly that many took reading from 27 ms to 12, and the
  hand-labelled accuracy is unchanged at 90%, with the same three misses. The centre dot
  is small enough that its stride stays at one, which is where samples are actually
  scarce.

At the 10 Hz scan rate that is 23% of one core.

## Cost of having two button-wall modes

`PuzzleEngine` asks every adapter with its own grid detector before falling back to the
general one, so on a button wall the lattice search now runs **twice** per detection
pass — once for Mines, once for Gems — at about 21 ms each on device. Detection only
re-runs when coverage has grown by 2%, and it runs on the solver thread rather than the
frame clock, so this is spare time rather than dropped frames. Worth knowing about
before adding a third wall of buttons, at which point the two should share one detector
and one result.

## What was removed

There was a complete canvas-based implementation of Gems — a `GemAdapter` that fitted a
lattice, read cells at grid coordinates, and a `GemMatcher` that solved against them. It
is deleted, not disabled. It could never run: the exposure it needs is one ARCore will
not deliver, and a lattice detector firing every detection pass for a mode that will
never use its answer cost 21 ms a pass for nothing. `GemAdapter` survives as a name in
the mode menu and declines everything else.

`PuzzleAdapter.exposureHint` went with it. It existed so the canvas path could report
how blown out the wall looked; the live path measures the same thing and hands it to the
auto-exposure loop directly, so the interface had no implementations left.

## What is not covered

- **A round changing mid-scan.** Nothing, as it turns out: every frame is read from
  scratch, so the wall rearranging fixes itself on the next scan a tenth of a second
  later. This is the one item on this list that the live rewrite deleted rather than
  deferred -- it was a real limitation of the canvas version, where a mosaic accumulated
  across a pan went stale the moment the round turned over. There is no **Rescan** button
  in Gems because there is nothing for it to restart.
- **Reading the four targets off the wall.** See above; deliberate.
- **Exposure on a device that refuses to share its camera.** The app falls back to
  ARCore's own session, which has no dial. It says so rather than pretending.
- **Anything on a real device.** Every word above comes from recordings and unit tests.
