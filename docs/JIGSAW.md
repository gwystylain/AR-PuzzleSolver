# Jigsaw

A floor room. The floor is a grid of tiles that light up. In the middle, a rectangle of
green tiles -- the box -- holds shapes in other colours; outside it, at both ends of the
room, lie pieces in the same colours. The team has to find, for each colour, the pieces
that make up its shape. Pieces may be **turned but never mirrored**: a piece's mirror image
is a decoy, and so is any piece left over. **Green is only ever the box**: it is never a
shape or a piece.

The app's part: three photos -- the box, and the pieces at each end -- and every phone in
the lobby shows which pieces make which shape.

It is **online**, on the same lobby server as [Spellinator](SPELLINATOR.md), and plays the
same way: one phone hosts, the rest join from the list.

## Playing

1. Everyone picks **Jigsaw** on the landing page. One player taps **Host a lobby**; the
   rest tap its code to join. Jigsaw's lobbies are listed only in Jigsaw.
2. The lobby has three cards: **Box**, **Left** and **Right**. Anyone takes any of them:
   tap **Photo** on a card and the phone's camera app opens. Photograph the box from where
   the whole green frame is in view, or one end of the room with all its pieces. Higher up
   is better: every tile wants to be at least a dozen pixels across.
3. The photo is read on that phone (a second or so) and comes back as a grid over the photo:
   every cell read is outlined in magenta, and what was left out -- the green frame, and
   anything on the wrong side of it -- in grey. Below it is the grid that will be sent. If a
   cell is wrong, pick a colour and tap the cell to paint it; tap it again to clear it.
   **Send** puts it in the lobby, on every phone.
4. As soon as the box and at least one end are in, every phone shows the **answer**: a line
   per colour naming the pieces to use, the box with each shape's pieces outlined and
   numbered, and each end with the same numbers on those pieces and the rest faded.
5. **Reset** (the host's, two taps) wipes all three for the next round. **Leave** needs two
   taps, as in Spellinator.

Tapping a card, not its button, offers the rest: **choose a photo already taken** from the
gallery, **draw it by hand** -- or check and fix what was read -- and **clear it**.

When a colour has no answer, its line says why: no pieces of that colour were read, or the
cells and piece sizes, so a missed cell can be spotted. Where several sets of pieces would
do -- two identical pieces, say -- the line offers each in turn ("1 of 2 ›"), since the
phone cannot know which the room means.

## How it works

Three parts: the photo is read on the phone that took it (`core/.../puzzle/jigsaw/`), the
grids go through the lobby, and every phone solves them.

### Reading a photo

The floor is a flat grid of square tiles, so a photo of it from anywhere is a single
homography of that grid -- no pose, no lens model. `FloorReader` finds it from the lit
tiles themselves:

1. **The lit tiles.** Every pixel whose brightest channel is above 0.72 of the photo's
   near-brightest (the 99.5th percentile) is lit, and each 4-connected patch of them is a
   candidate tile. A tile is a square in perspective, so each patch is fitted with a
   quadrilateral -- the pixel farthest from its middle, the one farthest from that, and the
   farthest either side of the line between them -- and kept if it fills it as a tile does
   (0.75 to 1.35: rounded corners push it over one). Its centre is where the diagonals cross,
   which is exact in any perspective.
2. **One tile, then its neighbours.** One tile's corners fix a homography, and the nearest
   tiles in line with it say how far apart tiles are (1.33 tile widths, centre to centre).
   From there the grid grows: a tile is placed when it lands within a quarter of a cell of a
   cell centre, next to a tile already placed, and is the size a tile at that cell should be;
   after each round the homography is fitted again to every tile placed. The reach widens in
   steps -- two cells, three, four, six, anywhere -- as the tolerance tightens, so the pieces
   far from the rest are reached only once the fit is good enough to trust that far out. Up
   to 40 tiles are tried as the first, those with the most neighbours first, and the one
   that places the most wins: a big tile near the camera, glowing, is a poor first tile.
3. **Every cell.** With the grid known, each cell between the tiles placed is read afresh
   from the middle half of its tile: lit if most of it is, coloured by the mean of its lit
   pixels. That recovers far tiles whose glow ran together.
4. **The right way up.** The grid is turned in quarter turns so its rows run across the
   photo, farthest first. **Never mirrored**: a homography of a photo of a floor seen from
   above cannot mirror it, and the turn is a rotation, which matters because a mirrored
   piece is a different piece here.

**Colours** are by hue, then saturation (`FloorColour.classify`). The room is lit green,
so a white tile reads yellow-green (hue 60 to 75, saturation 0.1 to 0.2); and a tile's core
is clipped, so a far orange one washes out to a saturation of 0.25 to 0.3. So orange is told
from white by its hue (under 45) and yellow from white by its saturation (over 0.4). Green
takes every hue from lime to teal: the frame is teal in one round's photo and spring green in
the other's, and the walls glow pure green, all of which is dropped alike.

**The box, or the pieces.** `FloorRegions` cuts out what the photo was for. The frame is a
rectangle two tiles thick, so whatever part of it is in a photo, its bounding rectangle is
inside the box: an L or more of it, and what is strictly inside is the box's shapes and
what is outside is pieces -- on the side of the box the photo was aimed at only, since a
photo of one end taken from its far wall can take in the other end too, and a piece in both
photos could be used twice. A single straight side of it does not say which side the box is
on, so the side the photo was aimed at -- the middle of the photo -- is taken, and the phone
says so. With no green in view at all, everything lit is kept.

The photo is taken with the **phone's own camera app**, not this app's camera: its
processing merges several exposures and keeps the tiles' colours apart where a single
exposure clips them to white, its wide lens takes in a whole end of the room, and this
app's camera stays off while the lobby is up. It is decoded at about 2000 pixels on its
long side.

On the two photos of the room so far -- both of the whole floor from one corner, a round
each -- the reader places 143 and 97 tiles and reads every piece within about twenty cells
of the camera, the box whole. Past that a tile is a few pixels high and is lost, which is
why each end has a photo of its own. Those photos are git-ignored, in `testVideos/Jigsaw`,
because there are people in them; `JigsawFloorTest` reads them when they are there.

### Through the lobby

Jigsaw is a lobby mode on the Spellinator server (`GameMode.JIGSAW`), so it has everything in
*How it works* in [SPELLINATOR.md](SPELLINATOR.md): seats held across a dropped link, the
link indicator, the limits, the security. What it adds:

| Phone sends | Meaning |
| --- | --- |
| `browse {game}` | `game` is `jigsaw`: list only Jigsaw's lobbies |
| `host {mode: jigsaw}` | Open a Jigsaw lobby |
| `region {seq, region, cells}` | Region `region` -- 0 the box, 1 left, 2 right -- is now `cells`, or not read when null. Anyone |
| `reset {seq}` | Wipe all three. Host only |

A grid is its rows, top to bottom, joined by `/`, each cell a colour -- `W`hite, `Y`ellow,
`O`range, `R`ed, pin`K`, `P`urple, `B`lue -- or `.`; at most 24 by 24, which keeps the largest
message under the server's 1 KB frame limit. Green is never sent. The `state` carries the
three as `regions`, and who sent each as `regionBy`.

As with Sequence's slots, a region is a whole value, numbered: shown on the phone that sent
it at once, sent again after a reconnect until the server's `ack` passes it, and on two
phones sending one region at once, the later to reach the server wins. **The photo never
leaves the phone**; only the grid read from it does. A server from before Jigsaw lists no
Jigsaw lobbies and hosts a word lobby instead; the phone leaves it and says the server needs
updating.

### Solving

Every phone solves the three grids itself (`JigsawSolver`), off the main thread, whenever
they change; it is deterministic, so every phone shows the same answer. Each colour is its
own puzzle: its **shape** is every cell of that colour in the box, its **pieces** every
4-connected patch of that colour in the two end grids. The answer is a set of pieces that
covers the shape exactly, each piece used once at most and turned through any of its four
quarter turns, never mirrored.

The search is the usual one for tiling: take the first cell of the shape not yet covered,
row by row, and try every unused piece in every turn that puts its own first cell there,
dropping a branch as soon as no set of the unused pieces adds up to the cells left (a
subset sum, kept as a bitset). Up to six distinct sets of pieces are collected, and the
search gives up after 300,000 steps, which only a board read wildly wrong would need.

In both rounds photographed so far, the near end's biggest piece of one colour fits its
shape only mirrored -- a decoy. The pieces that fit are at the far end, which those photos
were too far from to read; a photo of each end is the way round it.

## Testing

```bash
./gradlew :core:test --tests '*Jigsaw*'
./gradlew :spell:test :spell-server:test
```

- `JigsawFloorTest`: a floor drawn in perspective, read back cell for cell and the right
  way up; the same floor from the side, turned and never mirrored; the box and pieces cut
  from it, and solved; a frame with one side in view; and the room's own photos, where this
  machine has them.
- `JigsawSolverTest`: turning, the mirror-image decoy, two pieces that would each do, green
  ignored, no answer, and a large board finishing quickly.
- `ProtocolTest`, `JigsawTest` (the lobby, against a fake clock) and two tests in
  `EndToEndTest` (two phones' real session code against the real server, one through a link
  frozen mid-send).
- `JigsawColoursTest` in `:app`: the reader's colour codes are the protocol's.

Every photo the app reads is kept on the phone under `files/jigsaw/`, with a `.txt` of what
was read from it, so a round read wrong can be replayed here.
