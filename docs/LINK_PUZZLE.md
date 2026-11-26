# The Link puzzle

A wall of the same RGB buttons as Gems and Mines, lit in 3x3 panels, two bands of them
with a one-button gutter between neighbours. Every panel's pattern is lit exactly twice.
Press a panel, then its twin, and the pair is gone; the moment the first panel is pressed
every panel goes dark, so the pairs have to be remembered -- or read beforehand.

**How it is used.** Pick *Link* on the landing page (the screen turns sideways, as for
Mines). Stand at one end of the wall with both bands of panels in view, tap **Scan**, and
pan slowly to the other end. Once both ends have been seen and every panel read has its
exact twin, the scan finishes by itself (or tap **Done**). The answer covers the camera:

- the whole wall, every panel where it is, each named as the practice game's E labels name
  them -- **T** for the top band, **B** for the bottom, and the slot from the left;
- the walk numbered on it: each pair's number on both of its panels (in a filled disc on
  the one to press first, a ring on its twin), and one line, with an arrow along its middle,
  from the pair to press now's first panel to its twin -- the only line on the board, which
  is crowded enough with sixteen pairs' numbers;
- **Next** at the top right to step on to the next pair (and **Back**), so the place is
  kept however dark the wall goes, and **Rescan** at the top left. A tap on a panel jumps to
  its pair. The walk is always the shortest there is, starting at whichever pair makes it
  so -- the walk to the first pair is not counted;
- **Colours** shows the panels as read, to check against the wall *before* the first
  press -- the last moment the wall can be looked at. With **Fix** on, a tap steps a button
  through the five colours and the pairs and walk are worked out again.

## The rules, from the practice game

The team trains on [activate.ryflix.ca/link.html](https://activate.ryflix.ca/link.html),
whose source was read on 2026-11-26:

| | |
| --- | --- |
| Layout | 9 rows by `4n + 3` columns: two bands of 3x3 panels (rows 1-3 and 5-7), slots every four columns from column 2, a one-button gutter between. `n` = 14 slots on level 5 and 16 on levels 6-10, every slot filled, so 14 or 16 pairs. |
| Colours | Exactly five: `#ff3b3b` red, `#4dff6e` green, `#ffe03b` yellow, `#3b8fff` blue, `#b94dff` purple. Every button one solid colour. |
| Patterns | Level 5: eight buttons of one colour round a middle (any colour). 6: three columns, no two alike. 7: a bar across the top over three two-button columns. 8: a pinwheel of four two-button bars round a middle. 9 and 10: any colours, three to five different in a panel. Never two pairs alike. |
| Play | Press a panel, then another. Twins: both go. Not twins: a life lost (five), and every panel is shown again. Any pair may be next. |
| Clock | 90 s (75 on level 9, 60 on 10) from the moment the panels appear; the score is 5,000 plus a time bonus falling from 1,000 to 0, plus 50 a life left. |

The user reports that the room's easier levels light **8 or 12 pairs**, not 14 or 16, so
nothing in the app assumes a count, a layout, or that every slot is filled.

## Reading the wall

`LinkFrameReader`, in `core`, reads one frame; `LinkScanner` puts the frames together.

**The camera** is Gems' LED-wall preset -- 1/250 s at floor ISO, white balance locked --
on its own Camera2 source, with no lattice frames. Mines needs a second, brighter exposure
to count its unlit buttons; on the Link wall every button that matters is lit, so there is
nothing dark to count. An unlit button is black at this exposure and a lit one a disc of
its own colour.

**One frame.** Find the bright discs as Gems does, on the brightest of the three channels
so a blue or purple LED is as easy to find as a yellow one, and keep only those whose
middle is lit (the practice game draws its unlit buttons grey, and those are found too).
Join discs nearer than 1.3 buttons: a panel's discs are one apart (diagonals 1.41) and the
gutter puts two between panels, so each panel is one cluster. Fit each cluster to a 3x3
grid on its *own* axes, from the steps between its own discs, by least squares -- so a
panel seen at an angle on a curved wall, or near the edge of a frame where the rows bow,
still fits -- and read all nine places, whether or not each disc was found. A cluster wider
than 3x3, narrower, or with a place outside the frame is left for a frame that has it
whole. Each button's face is read as the gem and mines faces are: every texel bright
enough to be LED votes for the colour nearest its hue, weighted by its saturation.

**Across frames.** Each panel's place relative to its neighbours in the frame, in whole
buttons, measured along the two panels' own axes so only short distances are rounded.
Then the frame goes on the map of the wall: panels are four buttons apart and a frame
moves a fraction of one, so each panel is the one nearest where it was last frame, moved on
by the pan's velocity. If that puts its panels on map panels that read differently, or the
pan was lost for more than 0.6 s, the frame is placed instead by its patterns: the one
shift that puts two or more of its panels on map panels reading the same (seven buttons
of nine or better), clearly ahead of any other. One panel is never enough, because every
pattern is on the wall twice. The map grows either way, so a scan may start mid-wall.

**The ends, and how many panels.** An end is a frame with 4.5 buttons of nothing lit past
the outermost panel, inside the frame, three frames running, with that panel the
outermost on the map. A level that leaves slots dark has gaps like that in the middle,
so an end alone does not finish the scan: it finishes when both ends have been seen *and*
every panel on the map has its exact twin and has been read at least twice. The panels
before a gap almost never pair up among themselves.

## Naming the colours

The preset locks the white balance wherever the camera's own had settled, and that
moves between visits and between sessions: on the gem wall green has measured anywhere
from 131 to 178 degrees, and one session put yellow at 167-172, on top of green
(docs/GEM_PUZZLE.md). So the scan names every button at once, at the end, three ways:

1. each button's own votes, against fixed centroids (red 352, yellow 62, green 146, blue
   212, purple 288 -- the gem and mines walls' measurements; purple has not been seen yet);
2. five groups on the chroma plane (k-means) started from those centroids, each allowed to
   move 45 degrees;
3. five groups found from nothing (the most saturated button, then each time the one
   farthest from every group so far, then k-means), named by trying all 120 ways of giving
   the five names to the five groups, nearest the starting hues and saturations -- yellow
   and purple are two LEDs together and read paler.

The wall then checks itself: every pattern is lit exactly twice, so whichever naming gives
the most panels an exact twin is kept. On a tie, the one telling more colours apart --
yellow read as green is the same mistake on both twins of a random pattern, so the pairs
survive it and only the board shows it wrong.

## Pairing and the walk

**Pairs** (`LinkPairer`): panels with the same pattern, where exactly two share it, are
twins. The rest are paired to differ in as few buttons as possible, weighted by how sure
each reading was, exactly (a search over subsets) up to twenty of them. A pair that is not
exact is outlined on the board (and named in a warning in the log and `board.txt`); an odd
panel out is left unpaired, outlined the same way.

**The walk** (`LinkPlanner`): any pair may go next, so the round is a walk visiting every
panel with each pair's two back to back. The walk inside a pair is the same whatever the
order, so what is minimised is the walk between pairs -- a travelling-salesman path over
the pairs, each with two ways round. Up to 16 pairs it is solved exactly by dynamic
programming over subsets (Held-Karp): 2^16 sets times 32 places to stand, about 70 ms on
a desktop. Distances are straight lines between panel middles, in buttons. On the
practice board the user sent it is 60 buttons of walking between pairs, against 433 in all.

## Tests

- `LinkPlannerTest`: the practice board (the screenshot the user sent, read off its
  pixels) pairs exactly as read by eye; exact plans equal brute force over every order and
  direction on sixty small boards, from every start; a sparse eight-pair wall with dark
  slots; more pairs than the exact search takes; misread buttons that still pair right;
  an odd panel out.
- `LinkPanTest`: the screenshot, blown up to the phone's size and panned across, reads
  the whole board back and finishes by itself; rendered rooms -- black panel, glowing
  discs, roll, rows bowed by the curve, spacing changing across the frame -- read exactly
  at 16, 12 and 8 pairs (the eight in sixteen slots, with dark slots that look like ends),
  under the white balance of 2026-11-24's first Gems session (yellow on top of green, also
  with level 5's patterns, where a misnamed middle would pair wrongly), with a stronger
  roll and bow at a faster pan, and with a blackout mid-pan found again by the patterns.
- `LinkReplay` runs a scan saved on the phone back through the scanner:

```bash
./gradlew :core:test --tests '*LinkReplay*' -Dlink.frames=/path/to/link/20261127-190000
```

Every scan keeps itself on the phone under `files/link/<date-time>/` -- the frames,
`frames.txt`, the scanner's log in `scan.txt`, and the board, pairs and walk in
`board.txt`. `tools/collect-session.sh` brings them off.

A board can be put on the answer screen without a wall:

```bash
adb shell am broadcast -a com.puzzlesolver.app.DEBUG -p com.puzzlesolver.app --es linkboard "<row>/<row>/..."
```

in `board.txt`'s letters (R Y G B P, `.` for dark). `linkscan`, `linkfinish` and
`linkrescan` (booleans) press the buttons.

## Not yet verified

- **Anything in the room.** No frame of the real Link wall has been seen: its size, the
  gutters, whether the wall's own unlit buttons show at the preset, and above all what
  purple looks like on this camera. The first scan is kept for `LinkReplay`.
- **A scan on the phone.** Seen on the CPH2655 at a desk on 2026-11-26: picking Link turns
  the screen sideways and the camera takes the preset (1/250 s, ISO 100, white balance
  locked, honoured), reading about 23 frames a second at 9 ms of scan each; the practice
  board, put on the answer with `linkboard`, plans in 345 ms, and Next, Colours and Fix
  all work, with the header and the whole board on screen at once. Nothing
  has been scanned with it yet, even off a monitor.
- **The cost of a step.** The walk counts a button of reaching up or down the same as a
  button of walking sideways.
