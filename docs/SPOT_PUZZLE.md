# Spot

A screen in a dark bezel, inside rings of neon, shows one picture after another. Each is
the same scene with one thing changed: something added, something taken away, something
recoloured. The team has to find what changed in each.

The room shows seven. The app's part: **take seven photos, one per picture, and get all
seven back with the difference in each boxed.** A Reset button, top right in every phase, throws the round
away and starts again.

## Using it

Pick **Spot** on the landing page. The camera comes up with a green outline round the
screen whenever the app can see all of it; if the outline is missing, step back or
re-aim until it appears. Tap the shutter once for each picture. Each photo is checked as
it is taken. One without the whole screen in it is not kept, and nor is one of the plain
green screen the room shows between pictures; either way a note says so while the picture
is still up and can be retaken. The seven slots at the bottom fill in as photos are kept.

From the first photo kept until Reset, the camera's exposure is held where it is, so every
photo of a round is taken at the same exposure; and a photo asked for while an exposure
change is still reaching the sensor waits for it, up to a second and a half.

After the seventh the photos are compared, which takes a second or two, and the answer
replaces the camera: every photo, straightened, with a magenta box round what differs.
Tap one to see it at full width. Every picture the room shows has exactly one difference,
so every photo gets exactly one box -- the strongest thing the comparison found. A weak
one says "faint -- check it by eye" on its card. A photo left out of the comparison says
why on its card: no picture on the screen, or it could not be lined up with the others.

Every round is kept on the phone under `files/spot/<date-time>/`: the photos as taken,
`1.jpg` to `7.jpg`, and `found.txt` with what was found in each. See [Replaying a round](#replaying-a-round).

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
   and the wrong one. So:
   - **A photo with no picture on the screen is left out first.** The room shows a plain
     green screen between pictures. Its detail -- the mean step in brightness between
     neighbouring pixels, on the screen straightened to 320 wide -- is 1.5, against 7 to
     20 for every picture so far; anything under 4 is not a picture.
   - **The reference is the photo the others agree with best,** by a quick version of the
     coarse search below between every pair. It used to be the photo with the biggest
     screen, and in the first round in the room that was the blank green screen, then in
     the next an outline round the neon: neither lines up with anything, so everything
     was compared against nothing.
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
   - The patches are matched once more against the median of all the photos. A photo
     where fewer than 12 patches agree at full resolution is left out, as long as three are
     left to compare: misaligned, every edge in it is a difference, and it drags the
     median for the others.
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
6. **Measure how far each photo stands out from the others,** not just from the median:
   its difference less the median of everyone else's difference at the same pixel. A
   difference is in one photo only; what several photos differ at -- edges alignment left
   a pixel out, glare, a stretch of sky the camera clips -- is not, and this takes it away.
   It matters most for a photo taken at another exposure: on 2026-11-19 one came back with
   three boxes in a clipped sky and none on the sailboat added to the shelf, which measured
   this way is its strongest thing.
7. **Box the one difference.** The threshold is the photo's own median plus ten median
   absolute deviations, never below 40. Pieces within a few pixels of each other are
   merged, so a crescent and its star make one. Two kinds of blob are not candidates:
   - A strip along the edge of the compared area -- mostly within 10 pixels of it, and
     either 8 pixels thick or less or three times as long as it is thick -- is the bezel
     seen from a different place in that photo. Only strips: the rule used to drop anything
     mostly near the edge, and so lost a moon at the top of the screen and a candle at the
     side.
   - Anything over 6% of the screen is the whole photo lit differently, not a thing in it.

   The strongest candidate is the box. If nothing clears the threshold it is halved, twice,
   and whatever is found then is marked faint, as is anything scoring under 2,500. There is
   always a box: every picture has a difference, and the old rule of reporting "no
   difference" below a score floor turned five real ones into nothing on 2026-11-19.

The camera is metered on the screen alone (`ScreenMeter`), feeding the same loop that
meters the terminal wall. The problem is the same: the phone's own metering opens up for
a dark room, and a lit screen comes out over-exposed. Here over-exposure costs the
comparison rather than a digit. A white cloud appearing on a pale sky differs by a few
levels before clipping and by none after, and one sample set has exactly that cloud. The
target is the sample photos' own exposure: screen 98th percentile 230, median 152.

Within a round the meter is stood down. On 2026-11-19 it moved the exposure two to six
times a round, by half a stop to a stop, chasing each new picture's brightness; a photo a
stop off the rest carries that stop as a difference across the whole screen, clipped
where no evening-out can reach, and those were the photos whose own difference went
unfound. One round's first photo was taken in the half second between the meter asking
for 1/250 s and the camera leaving its own 1/70 s, and came back with the sea blown to
white: hence the wait for a change to land before a photo is taken.

## Tested against

Eight rounds, hand-held, each framed a little differently, in
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
- **Set 4**, the first round in the room with the app, 2026-10-31: a beach. Two photos
  are in the room's answer phase (green neon, its own ring round the difference), and the
  last is the blank green screen between pictures, which on the phone became the
  reference.
- **Set 5**, a minute later: a desert town, with two outlines round the neon and one photo
  from twice as far off, in the answer phase.
- **Sets 6 to 8**, three of the five seven-photo rounds taken in the room on 2026-11-19: a
  coast with a moon at the top edge and a first photo a stop and a half over, and two
  rounds of a studio full of small differences -- a candle, a mark on the floor, a thin
  cloud -- with photos taken at different exposures.

Sets 1 and 2 were taken on the phone's camera app and are 720x960 here; Sets 3 to 8 are the
app's own 1080x1920, at 720x1280. Sets 4 and 5 were taken when the app still took six.
`SpotFinderTest` runs all eight, plus the two six-photo subsets of the first set: without
the windows, and without the far photo. Every photo with a picture comes back with exactly
one box, containing the place it was checked by eye to be where it could be told, and the
blank screen is left out and says so. A whole round takes about two seconds on a desktop
JVM.

Of the 35 photos taken on 2026-11-19, 26 could be told by eye; the phone found 19 of those
at the time, and the comparison as it is now finds 25. The one it misses is a rock at the
very bottom of the screen, of which only a sliver lies inside the compared area. The nine
that could not be told were nearly all taken while the exposure was changing, which the
held exposure is for; most of their boxes are marked faint.

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
