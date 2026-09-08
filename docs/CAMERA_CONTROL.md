# Camera control

ARCore's ordinary `Session` opens the camera itself and offers no control over it. For
most of this app that is fine — a printed puzzle on a wall is a scene the camera meters
correctly. For the two rooms full of LEDs it is the difference between reading the wall
and not, so the app takes the camera over.

## Why it is needed at all

Measured off the six reference clips in `testVideos/Gems`, as the fraction of each gem's
area carrying no usable hue:

| clip | washed out |
| --- | --- |
| VID20260814182232 | 47–60% |
| VID20260814182324 | 55–69% |
| VID20260814182431 (shot darker) | 2–4% |
| VID20260814182523 (shot darker) | 0% |

The room is dark, so the camera opens right up, so every gem clips to a flat white disc.
The colour is not dim in the image — it is *not in* the image, and nothing downstream
recovers it. The two regimes do not overlap, and the only thing separating them is
exposure.

## What happened when it was run on a device

**On a CPH2655 (Android 16, ARCore 1.48), ARCore's shared camera mode discards the
app's capture request entirely.** Measured, three ways, all negative:

| test | asked | actual |
| --- | --- | --- |
| set before ARCore's first `resume()` | manual 1/250 s ISO 100 | 1/100 s ISO 2008 |
| set by pause → re-issue → resume | manual 1/250 s ISO 100 | 1/100 s ISO 2015 |
| re-asserted 400 ms after the resume | manual 1/250 s ISO 100 | 1/100 s ISO 1946 |

`setRepeatingRequest` threw nothing and logged nothing; the request simply had no
effect. The decisive check was `CONTROL_EFFECT_MODE_MONO`, which would have turned the
preview visibly grey and did not — so the request was not partially applied, it was not
applied at all. (`--es cam mono` still runs that check.)

The obvious next question is whether it is ARCore or the phone. `CameraProbe` answers
it: the same manual request through plain Camera2, with no ARCore anywhere near it,
came back

```
probe: manualSupported=true asked 4000us iso100, got 3999us iso100 aeMode=0 -> HONOURED
```

So the hardware is willing and ARCore is not. Re-run it any time with:

```bash
adb shell am start -n com.puzzlesolver.app/.MainActivity --ez camprobe true --ei shutter 4000 --ei iso 100
```

**A newer ARCore does not fix it.** The client library was bumped from 1.48 to **1.54**,
the current release, and re-run against a device whose ARCore service is also 1.54. The
behaviour is identical — 1/250 s ISO 100 asked, 1/100 s ISO 2282 delivered. Diffing the
1.48 and 1.54 API surfaces finds no new `Config` setters and no exposure control
anywhere; the only mentions of exposure in the whole SDK are read-only metadata keys and
one line of prose about rolling shutter. This is not a gap waiting to be filled, it reads
as by design: ARCore owns 3A while it is tracking.

**Nor does the wall survive at ARCore's exposure.** The last hope was that the outer ring
alone might still be readable — it is the largest band and the one that reads at 100% on
a correctly exposed frame. Run over the over-exposed clips it names a colour for every
gem, and the answers are not trustworthy: red is mostly right, green and blue are
systematically confused, and gems whose *inner* ring is bright get that colour reported
as the outer one, because at this exposure the rim is the diffuse glow of whichever ring
is brightest rather than of the outer ring. Confidences sit between 0.10 and 0.65. A
wrong highlight on a timer is worse than no highlight, so this is not a usable fallback.

**Consequences.**

- Shared camera is **off by default**. It buys nothing here and is not free: ARCore
  cannot use a hardware depth sensor while sharing, and the wall fit converges faster
  with one. `--ez sharedcam true` turns it back on, worth doing on a new ARCore release.
- The app **detects** a camera that is ignoring it — `CameraTuning.honoured()` compares
  the request against the frame metadata — says so in red in the HUD, and stands the
  auto-exposure loop down. That last part is a correctness requirement, not a nicety:
  against an ignored dial the loop would read the wall as blown out, step down, discard
  the mosaic, and repeat six times, losing six scans and changing nothing.
- **Gems cannot work in that room through ARCore as things stand.** At the exposure
  ARCore chooses, the rings are not in the image; see the table below and the tiles in
  `docs/GEM_PUZZLE.md`.

**The resolution: Gems left ARCore behind.** `Camera2FrameSource` opens the camera
itself, with no ARCore anywhere near it, and the request is honoured — asked manual
1/250 s at ISO 100, delivered 1/250 s at ISO 100, and the dial moves: three taps of
*brighter* walked it 1/250 → 1/125 → 1/62 → 1/31 with the read-back following each step,
then *reset* returned it to metered auto at `ae:on`. All confirmed on the device. It streams to
a `SurfaceTexture` for the preview and an `ImageReader` for the scanner off one capture
request, so what the user sees and what the scanner reads are the same frame. The price
is the world pose, which that mode turned out not to need — see
[GEM_PUZZLE.md](GEM_PUZZLE.md). Mines keeps the AR pipeline, where its classifier wants
the camera's own exposure anyway.

A fourth was found later, on the device, and it is the one that actually stopped the
mode working. **The preview and the scanner starved each other.** `onImage` converted
every arriving frame to luma-plus-chroma before checking whether the consumer had taken
the previous one, and that conversion runs on the same camera thread that services the
preview `SurfaceTexture`'s `onFrameAvailable`. So a full 1920x1080 pass thirty times a
second starved the preview; the render loop only advances on a preview frame and only
dispatches a scan when it advances; so the consumer that would have taken the frame never
ran; so the next frame was discarded too. Live Gems settled at **0.5 fps** with the
scanner stopped, and every surface that might have shown it looked healthy -- the capture
session was running, the metadata read-back was current, the request was honoured. Testing
whether the consumer is ready *before* paying for the conversion breaks the loop and
returns it to 18-27 fps. The frame rate is now on the heartbeat next to the camera's own
claimed rate, because the disagreement between those two numbers is the whole diagnosis.

Three more device-specific traps were found building it, all of which fail silently:

- `SurfaceTexture(texName)` binds to the GL texture in the **calling thread's** EGL
  context. Built on the camera thread it constructs happily, the producer streams to it,
  and `updateTexImage` on the render thread simply never delivers a frame.
- The capture session and request builder are written on the camera thread and read from
  the render thread. Without `@Volatile` the render thread holds a stale null and every
  retune after the first is quietly skipped — the first one lands because it is issued
  from the camera thread itself.
- A `CaptureRequest.Builder` keeps every key ever set on it, so the request is rebuilt
  from a fresh template whenever the exposure mode changes rather than having keys
  cleared individually.
- Two things race to open the camera -- the activity resuming and the GL thread handing
  over its texture -- and `device` is not set until `onOpened`, so guarding on that alone
  let both through and opened the camera twice. Two devices, two sessions, the sensor
  following one and this class holding the other.
- **A repeating request only reaches the sensor if it is the one the session was
  configured with.** Every later `setRepeatingRequest` returned normally, kept the
  capture callbacks arriving, and left the exposure exactly where it was. Stopping the
  repeat first did not help; nor did a one-shot `capture` alongside it; nor a request
  rebuilt from a fresh template. So a retune **rebuilds the capture session**, which
  works every time. The camera device stays open, so it is a session rebuild rather than
  a camera restart, and it costs a few hundred milliseconds of no frames -- the right
  trade for a dial that moves on a deliberate tap and then stays put.

  This one took four wrong answers to find because every symptom pointed elsewhere: the
  call succeeded, frames kept flowing, the scanner kept working, and the read-back was
  the only thing that ever disagreed. It is exactly the failure the "asked versus actual"
  lines were built for.

## How the camera is taken over

`Session.Feature.SHARED_CAMERA`. The app opens the camera through Camera2, hands ARCore
the surfaces it asks for, and keeps the capture request. Three rules from ARCore's
documentation shape `SharedCameraController`, and breaking any of them fails as
something else entirely:

- **`setRepeatingRequest` must not be called while ARCore is active.** Changing a setting
  is therefore: pause ARCore, re-issue, resume ARCore. The *initial* settings go in
  before ARCore first resumes, so the common case costs nothing.
- **The repeating request must target every surface ARCore created.** Missing one starves
  motion tracking silently rather than erroring.
- **ARCore cannot drive a hardware depth sensor in this mode.** That would matter if
  anything used depth. Nothing does — the wall fitter runs off
  `Frame.acquirePointCloud`, which is motion-tracking feature points and is unaffected.
  Checked rather than assumed: there is no `acquireDepthImage` call in the app. The
  camera-config filter drops its depth-sensor preference when sharing, since otherwise it
  would filter every config out on exactly the devices that have one.

Every failure path falls back to ARCore's own session with no dial rather than throwing.
A solver that works without exposure control beats a dial that crashes the app. Turn the
whole thing off with `--ez sharedcam false` if a device misbehaves with it.

## Requested versus actual

A capture request is a request. A device may clamp exposure compensation to its own
range, ignore a manual exposure time, or have ARCore re-issue the request with values of
its own — and from behind the lens all three look identical, which is a picture that is
still wrong.

So the HUD shows two lines. **asked** is what the app set; **actual** is read back from
`Frame.getImageMetadata()`, which is what the sensor did. That works on any session,
shared camera or not, so a device that refuses to share still reports what it chose for
itself.

## The dials

| Dial | What it does | Why |
| --- | --- | --- |
| **darker / brighter** | Halves or doubles the light | Manual exposure time where the device has `MANUAL_SENSOR`, exposure compensation where it does not |
| **manual / auto** | Fixed shutter and ISO, or metered | A dark room full of bright LEDs is precisely the scene metering gets wrong: it sees mostly black wall and opens up until the LEDs are discs |
| **LED wall** | 1/250 s at floor ISO, AWB locked | Where the two readable reference clips sit. Falls back to −3 EV with AE locked on a device without a manual sensor |
| **AE lock** | Freezes metering | The canvas keeps the best look at each texel across a whole pan; exposure drifting mid-pan writes neighbouring texels from different exposures and leaves seams the classifier reads as real |
| **AWB lock** | Freezes white balance | The gem classifier decides on *hue*, and auto white balance chasing a room washed in shifting purple rotates every hue in the frame. Off at startup and turned on by the preset, because locking before the camera has converged freezes whatever it happened to begin with |
| **auto-ev** | Closes the loop, below | On by default |
| `--ez detail true` | Picks the camera config with the largest image over the highest frame rate | Frame rate buys mosaic redundancy; resolution buys detail no redundancy invents. A gem's centre dot is three texels across |

Changing any of them **restarts the scan**, deliberately. A canvas holding texels from
two exposures has seams in it, and the button classifiers are built to believe colour
edges. The restart is reported once the camera has actually changed, not when the change
was requested, so the frames still in flight at the old exposure do not land in the fresh
canvas.

## The loop

`AutoExposure` closes a loop the capture layer cannot close on its own. Exposure is
chosen before any of the solving code runs, and the only place its consequences are
visible is at the point where a colour either resolves or does not. So `GemAdapter`
measures the fraction of gem area with no usable hue and reports it as
`PuzzleAdapter.exposureHint`; the loop walks the exposure down a stop at a time until
that clears, then stops.

It is conservative rather than clever, because every step pauses ARCore and throws the
mosaic away:

- **It waits for a measurement taken at the current exposure.** Acting on a hint computed
  before the last change would step twice for one problem.
- **One step per measurement.** It cannot run away.
- **It stops.** Settled, exhausted, or backed off — there is no state it keeps adjusting
  from, and once it has turned around it never descends again. A board that reads
  marginally at two adjacent settings would otherwise oscillate between them forever.

The recovery path is the interesting one, and it is where this went wrong on the wall on
2026-08-20. If a step down leaves the wall too dark to read at all, no measurement ever
arrives, and after fifteen seconds that *silence* is the signal. It climbs back — but
only as far as the exposure the camera chose for itself, because "nothing readable" is
far more often a phone pointed at the ceiling than a room that needs more light, and a
loop that answered the ceiling by brightening without limit would end up worse than where
it started and then refuse to come back down.

Three things about that paragraph were true in intent and false in code, and together
they threw the run away before the wall was ever in frame:

- **The timeout was a pass count, not a clock.** Sixty passes was documented as fifteen
  seconds on the assumption of a 4 Hz loop; the live Gems loop runs nearer 7 Hz, so it
  fired in nine. Two of them fitted inside one walk across a room, and the preset was
  undone two stops before there was anything to measure. It is now wall-clock, with the
  pass count kept only as a floor so a stalled scanner cannot age out on time alone.
- **A negative hint cannot tell the two silences apart.** "Too dark to read" and "not
  pointed at the wall" arrive identically, and the loop may only act on the first.
  Frame brightness was the obvious separator and turned out to be the wrong one, twice
  over: an LED wall two stops under is nearly as dark as an empty room, and a black
  frame full of sensor noise is not as dark as one. What actually separates them is
  whether the blobs are arranged like a wall — which the scanner decides anyway, and
  which holds at any exposure the discs are still visible at. It passes that verdict in
  as `wallInView`, and the loop will not climb out of a frame that has no wall in it.
- **Settling took one reading, and settling is terminal.** A single pass caught the gems
  as the camera swung past — blobs were zero either side of it — measured that frame at
  10%, and locked the two-stop error in for the rest of the session. It now takes three
  readings in agreement.

A fourth turned up while validating the other three, in the one state nobody thinks to
test because nothing is supposed to happen in it: **the phone face down on a desk with
Gems selected**. At 1/250 s in a dark room the frame is black, but sensor noise still
clears the detector's local-background threshold — ten to twenty blobs, a few of them
reading as lit, and the handful of saturated texels inside them averaging to a confident
*0% washed out*. `GemScanner` published that off a single lit gem, three of them settled
the loop eight seconds in, and settled is terminal: the wall would have arrived to find
a loop that had already finished. In the heartbeat it is unmistakable once you know to
look, because the pitch jumps between 43 px and 436 px where a wall holds near 130.

The fix is a quorum, in the same place and for the same reason the pitch has one: below
six lit gems the figure is one or two measurements and as likely to describe noise as a
wall, so no answer is given at all. Declining costs one frame, because the next frame
asks again; answering wrongly is acted on and then latched. Alongside the quorum the
scanner also withholds the hint when the blobs are not arranged like a lattice at all —
see [GEM_PUZZLE.md](GEM_PUZZLE.md) — which is what stops a black frame full of noise
voting on the camera. Note what that check does *not* do: the rings are still read and
matched, because refusing to read a real wall is far more expensive than skipping one
frame's vote, and the measurement is only good to about one blob in ten of glare. The
same verdict is what reaches the loop as `wallInView`, which is the second lock on the
same door: no wall in frame now says nothing about the exposure in *either* direction,
rather than only being barred from driving it up.

The target is 25% washed out, which sits in the gap between the two regimes measured
above, so anywhere in it converges. What counts as washed out changed too, and had to:
see [GEM_PUZZLE.md](GEM_PUZZLE.md) on clipping, which is the reason 1/62 s looked
perfectly healthy to a loop measuring desaturation.

`CameraTuningTest` pins all of this off-device: the walk down, the refusal to act on a
stale reading, the step budget, the climb back out of an over-dark preset, the guarantee
that it never brightens past where the camera started, and each of the three failures
above.

## What gets the preset, and what does not

**Gems** gets the exposure dropped for it the moment the mode becomes active. **Mines**
does not, and that is deliberate: its classifier reads the glow *around* a button that
has clipped its own face to white — see [BOMB_PUZZLE.md](BOMB_PUZZLE.md) — and it was
verified on device at the camera's own exposure. Darkening for it would take away the
thing it reads. The dials are on screen for both walls; this is only about what happens
without being asked.

Mines also has no `exposureHint`, so the loop does not run for it. Adding one would mean
first deciding what correct exposure means for a classifier that wants its subject
clipped.

## Which way up the preview is

Dropping ARCore for Gems also dropped `Frame.transformCoordinates2d`, which had quietly
been handling this. `PreviewGeometry` replaced it: rotate the camera buffer clockwise by
`sensorOrientation - displayRotation`, centre-crop to fill the viewport, and derive both
the GL background's texture coordinates and the overlay's ring positions from that one
model so they cannot drift apart.

Four things about it were wrong in the field and are worth stating, because none of them
looks like a rotation bug while you are holding the phone.

**The rotation was applied twice, so it cancelled.** This is the one that actually made
the preview sideways in portrait, and it took instrumenting the finished texture
coordinates to see. `SurfaceTexture.getTransformMatrix` is documented as the map from the
coordinates you supply to the ones that sample the buffer, and it is where a producer
puts its crop and its row order; the code assumed that was all it ever held. On this
OnePlus it reports

```
[0.0 -1.0 1.0] [-1.0 0.0 1.0]        s' = 1 - t,  t' = 1 - s
```

which transposes — the camera applies the sensor's ninety-degree mount itself. Turning
the buffer upright again on top of that put two turns in opposite senses against each
other, and a 1920x1080 landscape buffer landed straight down a 1080x2376 portrait screen.
The matrix is a property of the producer and not of the display: measured at all four
display rotations it never changes. So when it transposes, the mount is already spent and
the only turn left to apply is the display's — against the dimensions the producer
presents, which are the swapped ones, because the crop is computed against the image the
shader is actually sampling.

Two consequences worth keeping in mind. The overlay was never wrong, because it maps
buffer pixels through its own geometry and never meets that matrix — which meant the
rings and the image under them disagreed by ninety degrees, and only the image was
obviously wrong. And the reason this survived so long is that the ordinary matrix, a
vertical flip and nothing else, is its own inverse *and* commutes harmlessly with our
rotation; a device that reports one shows no symptom at all.

**The app used to override the user's rotation lock.** `screenOrientation` was
`fullSensor`, which follows the accelerometer whether or not the user has auto-rotate
turned off. On a phone locked to portrait — every other app on it staying portrait — this
one flipped to landscape as soon as it was tilted up at a wall, which is the only posture
it is ever held in. It is now `fullUser`: identical to `fullSensor` where auto-rotate is
on, and honouring the lock where it is not.

**A half turn was never applied.** The geometry was handed to the frame source from
`onSurfaceChanged`, which fires for three of the four transitions because portrait and
landscape are different sizes. Portrait to reverse portrait is 1080x2376 either way: no
resize, no callback, and the source kept a rotation 180 degrees out. Nor does
`onConfigurationChanged` help, because nothing in the `Configuration` changes across that
turn either. The GL thread now compares the display's rotation against the one it last
applied, once a frame, and re-applies when they differ.

**The heartbeat reported the one number that cannot change.** `rot=90` was the sensor's
mounting angle, a property of the phone, constant for the life of the device — so a log
full of it read as though rotation were being tracked. It now carries all three:

```
[cam2 1920x1080 sensor=90 disp=0 net=90 'camera running (manual 1/250s iso100 AWB-lock)']
```

`sensor` is the mount, `disp` is the display and is what turns, and `net` is what
`PreviewGeometry` actually uses. A preview that disagrees with the phone in your hand can
now be diagnosed from the log rather than from the room.

## Driving it from adb

The screen is hard to read in a dark room while holding the phone at a wall, and exposure
is the setting most likely to need a dozen tries.

```bash
adb shell am broadcast -a com.puzzlesolver.app.DEBUG --es cam ledwall
```

`cam` takes: `darker`, `brighter`, `manual`, `auto`, `ledwall`, `reset`, `aelock`,
`aefree`, `awblock`, `awbfree`, `autoev`, `noautoev`.

Absolute values, with the shutter in microseconds because that is a number a person can
type:

```bash
adb shell am broadcast -a com.puzzlesolver.app.DEBUG --ei shutter 2000 --ei iso 100
```

`--ei ev N` sets exposure compensation in the device's own steps. Any of these turns the
auto loop off: taking over has to mean taking over.

Launch-time, since both change how the session is built and a session cannot be converted
afterwards:

```bash
adb shell am start -n com.puzzlesolver.app/.MainActivity --ez sharedcam false --ez detail true
```

The heartbeat carries all of it, so a log pulled after the fact says what the camera was
doing:

```bash
adb logcat -s ScanPipeline:I SharedCamera:I AutoExposure:I
```

## What the device run settled, and what it did not

Settled, on a CPH2655 running Android 16 with ARCore 1.48:

- ARCore does not leave the app's capture request in force — see above. This was the one
  question the **actual** line was built to answer, and it answered it in a minute.
- The shared camera comes up cleanly: camera opens, ARCore resumes on it, frames flow at
  2.5–3.7 ms each with tracking. The plumbing works; only the request is ignored.
- Two bugs the run found, both since fixed: the LED-wall preset and the auto-exposure
  loop were both behind the pipeline's wall-convergence early return, so neither ran
  until a wall had been fitted. Exposure is part of what *makes* a wall fit possible in a
  dark room, so both now run from the first frame.
- Streaming chroma costs about 18 ms a frame on this device — frame time goes from
  ~2.5 ms to ~21 ms when a colour puzzle becomes active. Comfortable at 30 fps, worth
  knowing.
- This phone offers ARCore no depth-sensor camera config either way, so on *this* device
  sharing costs nothing. On one with a depth sensor it would.

Not settled, because it needs the actual room:

- whether the LED-wall preset is close on this phone or two stops out — moot until the
  request is honoured at all.
- everything about the gem wall itself: lattice detection on the real canvas, ring
  reading at canvas resolution, and whether the highlights land where the gems are.
