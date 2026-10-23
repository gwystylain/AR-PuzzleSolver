# Spot

A screen in a dark bezel, inside rings of neon, shows one picture after another. Each is
the same scene with one thing changed: something added, something taken away, something
recoloured. The team has to find what changed in each.

The app's part: **take six photos, one per picture, and get all six back with the
difference in each boxed.** A Reset button, top right in every phase, throws the round
away and starts again.

## Using it

Pick **Spot** on the landing page. The camera comes up with a green outline round the
screen whenever the app can see all of it; if the outline is missing, step back or
re-aim until it appears. Tap the shutter once for each picture. Each photo is checked as
it is taken, and one without the whole screen in it is not kept, with a note saying so
while the picture is still up and can be retaken. The six slots at the bottom fill in as
photos are kept.

After the sixth the photos are compared, which takes a second or two, and the answer
replaces the camera: every photo, straightened, with a magenta box round what differs.
Tap one to see it at full width. A photo with no box is the original picture, if the room
showed it, though no round so far has had one.

Every round is kept on the phone under `files/spot/<date-time>/`: the photos as taken,
`1.jpg` to `6.jpg`, and `found.txt` with what was found in each. See [Replaying a round](#replaying-a-round).

## How it works

Every pixel of the scene is the original picture in all but at most one of the photos,
because each picture changes a different thing. So the **per-pixel median of the photos
is the original picture**, without anyone photographing it, and what a photo has that the
median does not is its difference. That holds from three photos up, and holds for a photo
that turns out to be the original itself.

The work is in making the photos agree, pixel for pixel, on where things are and how
bright they are. A median of misaligned photos is a blur, and every edge in the scene
would come up as a difference. In order (`core/.../puzzle/spot/`):

1. **Find the screen** in each photo (`ScreenLocator`). It is the bright thing in the
   middle of the frame. Everything at a tenth of the centre's brightness or more is
   flooded outward from the centre, holes filled. A tenth, because the bezel sits at 5 to
   15 while the darkest art either sample picture puts against the edge sits at 25 to 60:
   a palm trunk, foliage. Half, the first level tried, cut the palm trunk out of the
   screen and fitted the screen's side to it. If the flood reaches the edge of the frame
   it has leaked through the bezel into the neon, and the level goes up and it floods again.
2. **Fit its four sides.** The screen is an octagon, a rectangle with its corners cut off,
   and its short sides are barely half its height. A least-squares line through a side and
   both chamfers tilts towards the chamfers, by a dozen pixels at the corners on the first
   try. Each side is found by a Hough-style vote over lines within 19 degrees of the axis
   instead, which a 45-degree chamfer cannot win, then refined where the full-resolution
   frame crosses the level. The four lines meet at the rectangle the screen would be
   without its cut corners, and every photo is straightened to that rectangle, 640 pixels wide.
3. **Line the pictures up on their content,** trusting each outline only to be roughly
   the right way up. That distrust was learned on the phone. The bezel stands proud of the
   screen, so its edge slides against the picture between photos taken from different
   places. In the first round taken with the app, two of six outlines went round the neon
   rather than the screen, through a thin bright bridge at one corner, and a third was 20
   pixels out along one side. Lining up on the outlines, that round found one difference,
   and the wrong one. So, against the photo with the biggest view of the screen:
   - **A coarse search** over scale (0.7 to 1.4) and shift (15% each way) at an eighth of
     the resolution brings even an outline round the neon, 1.27 times too big, back into
     range.
   - **A 12 x 8 grid of patches** is found in each photo by normalised cross-correlation,
     at a quarter, a half and full resolution. Each correction is the homography that the
     most patches agree on (RANSAC), not a least-squares fit with the worst trimmed. When
     one side of a photo is out, its patches are a minority, and trimming throws away
     exactly the ones that would fix it.
   - **The rectangle is decided by majority.** Every photo's own outline is carried into
     the reference's frame, and the median of them, corner by corner, becomes the rectangle.
     A wrong outline is outvoted, the reference's own included.
   - The patches are matched once more against the median of all the photos.
4. **Even out the exposure,** per colour channel, as a gain and offset fitted to the
   median, again with outliers trimmed, because the outliers are the difference.
5. **Compare** each photo with the median: the summed absolute difference of the three
   channels, against the best-matching median pixel within two pixels, and the median
   against the best-matching photo pixel within two, whichever is larger. The allowance
   forgives what alignment left behind. Applying it one way only also forgives a small
   thing taken away, because the photo's plain wall finds plain wall a pixel or two from
   the missing detail. That is how a row of five windows, each three pixels wide, vanished
   from a castle, and the photo was taken for the unchanged original in every round that
   had it. A margin of 2% inside the screen's outline is left out, which is where the
   bezel's parallax shows.
6. **Box** what is left. The threshold is the photo's own median plus ten median absolute
   deviations, and never below 80 of a possible 765. Pieces within a few pixels of each
   other are merged, so a crescent and its star make one difference. A blob lying mostly
   within 10 pixels of the compared area's edge is dropped: it is the bezel, seen from a
   different place in that photo. Real differences near the edge have at most two fifths
   of themselves there, and the strips three quarters or more. The strongest of what
   remains is kept, plus any others at least a quarter as strong, up to three.

The camera is metered on the screen alone (`ScreenMeter`), feeding the same loop that
meters the terminal wall. The problem is the same: the phone's own metering opens up for
a dark room, and a lit screen comes out over-exposed. Here over-exposure costs the
comparison rather than a digit. A white cloud appearing on a pale sky differs by a few
levels before clipping and by none after, and one sample set has exactly that cloud. The
target is the sample photos' own exposure: screen 98th percentile 230, median 152.

## Tested against

Three rounds, hand-held, each framed a little differently, in
`core/src/test/resources/spot/`.

- **Set 1**, seven photos of a harbour: a moon added, a cloud taken away, a row of windows
  on the castle taken away, the big boat's mast taken away, a boat taken away, a lighthouse
  taken away, an airship added. One photo is from twice as far away as the rest.
- **Set 2**, six photos of a beach, where the room had already drawn its own green ring
  round each difference before they were taken. That makes it an answer key.
- **Set 3**, the first round taken with the app: six of Set 1's photos put up on a monitor
  and photographed off it, in the order 7 to 2. These are the photos that broke the first
  version (see step 3 above). The browser's tab bar above the monitor's picture is
  blacked out.

Sets 1 and 2 were taken on the phone's camera app and are 720x960 here; Set 3 is the app's
own 1080x1920, at 720x1280. `SpotFinderTest` runs all three, plus the two six-photo subsets
of the first set: without the windows, and without the far photo. Every photo comes back
with exactly one box, containing the place it was checked by eye to be. A whole round takes about a second on a desktop JVM. The weakest real
difference, the missing windows in Set 1, scores 2,300 against a floor of 1,000; in the
app's own photos it scores 9,000 to 10,000. The strongest stray that is neither on the
edge nor beside a real difference scores about 700.

Run on the phone at a desk, off a monitor, but not yet in the room.

## Replaying a round

```
./gradlew :core:test --tests '*SpotReplay*' -Dspot.photos=/path/to/round -Dspot.out=/path/to/out
```

Pull a round off the phone (`adb pull /sdcard/Android/data/com.puzzlesolver.app/files/spot/`),
point this at one of its folders, and each straightened screen is written out with its
boxes drawn on. Any directory of photos in the order taken works the same, including
photos from the phone's own camera app.

From adb, `--ez spotshot true` takes a photo and `--ez spotreset true` resets the round,
on the usual debug broadcast (see [DEBUGGING.md](DEBUGGING.md)).
