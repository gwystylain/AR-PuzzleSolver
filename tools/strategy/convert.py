#!/usr/bin/env python3
"""Builds the bundled stage files in core/src/main/resources/strategy from the fan-site
transcriptions: strategy.txt for the Strategy room and gridlock.txt for Gridlock.

Two sites have transcribed the Strategy room:

  activate-scores.ca            every level is one 12x12 board; shots pass through
                                other guns; a red costs a life and is cleared.
                                Levels 1-6.
  activate.ryflix.ca/strategy   levels 7-10, transcribed by MetaNut: one grid per
                                stage, 12x16 with a two-column wall down the middle
                                for 7 and 8, 12x12 for 9 and 10; guns block and are
                                destroyed; purple tiles spawn a target in the
                                mirror-image column; a red fails the wave; level 9's
                                blue tiles alternate between two layouts every 2 s.

Gridlock is a second room, transcribed at activate.ryflix.ca/gridlock.html (levels
3, 4 and 6-10 so far): two boards side by side, 10 rows by 10 or 11 columns each, with the
same rules as the ryflix Strategy page -- guns block, purple tiles spawn a target
on the other board in the mirror-image column, a red fails the wave. On some levels
the boards touch and a shot crosses from one to the other; on the others a wall
stands between them.

The app's solver has one set of rules with two switches, so this script turns all of
it into the same shape of stage: one wide grid, a strip of wall where the boards are
apart, and "the other board" as "the mirror-image column" -- which, with the boards
laid side by side, is the same column whether or not they touch.

Which site supplies which level is ROOMS below. Strategy level 10 exists on both
sites; the ryflix one ships (five waves; activate-scores.ca has four with other
layouts) and the other is still in the sources -- swap the entry and re-run.

Sources live in ./sources as fetched: activate-scores serves JSON straight from
/games/strategy/<n>; ryflix-strategy.json and gridlock.json are the two ryflix
pages' LEVELS arrays with each grid parsed and the hazard patterns expanded to
explicit cells by the sites' own functions, since they are only described
procedurally.

    python tools/strategy/convert.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = os.path.join(HERE, "sources")
OUT_DIR = os.path.join(HERE, "..", "..", "core", "src", "main", "resources", "strategy")

# Room -> level -> source. The room name is the bundled file's name and what the app
# lists it under.
ROOMS = {
    "strategy": {
        1: "scores", 2: "scores", 3: "scores", 4: "scores", 5: "scores",
        6: "scores", 7: "ryflix", 8: "ryflix", 9: "ryflix", 10: "ryflix",
    },
    "gridlock": {level: "gridlock" for level in (3, 4, 6, 7, 8, 9, 10)},
}

DIR = {"up": "U", "down": "D", "left": "L", "right": "R"}


def scores_stages(level):
    j = json.load(open(os.path.join(SOURCES, f"activate-scores-level-{level}.json")))
    n = len(j["targets"])
    # The site's target animation reads config.redSpeed as well (targetSpeed is never
    # used), so one period covers both.
    period = (j.get("config") or {}).get("redSpeed", 1000)
    out = []
    for w in range(n):
        lines = [f"stage level={level} index={w + 1} of={n} width=12 height=12 source=activate-scores.ca"]
        lines.append("panel 0-11")
        lines.append("rules gunsblock=0 redrule=life")
        for g in j["guns"][w]:
            lines.append(f"gun {g['x']},{g['y']},{DIR[g['direction']]}")
        tg = j["targets"][w]
        if tg.get("isDynamic"):
            lines.append(f"moving {period}")
            for frame in tg["positions"]:
                lines.append("mframe " + " ".join(f"{t['id']}:{t['x']},{t['y']}" for t in frame))
        else:
            for t in tg["positions"][0]:
                lines.append(f"target {t['x']},{t['y']}")
        reds = j["reds"][w] if j.get("reds") else None
        if reds and reds.get("positions"):
            frames = reds["positions"]
            if reds.get("isDynamic") and len(frames) > 1:
                lines.append(f"reds cycle {period}")
                for frame in frames:
                    lines.append("frame " + " ".join(f"{r['x']},{r['y']}" for r in frame))
            elif frames[0]:
                lines.append("reds static " + " ".join(f"{r['x']},{r['y']}" for r in frames[0]))
        out.append("\n".join(lines))
    return out


def ryflix_stages(level):
    stages = [s for s in json.load(open(os.path.join(SOURCES, "ryflix-strategy.json")))
              if s["tag"].startswith(f"Level {level}-")]
    n = len(stages)
    out = []
    for w, s in enumerate(stages):
        rows, cols = s["rows"], s["cols"]
        walls = sorted({c["c"] for c in s["cells"] if c["type"] == "wall"})
        lines = [f"stage level={level} index={w + 1} of={n} width={cols} height={rows} source=activate.ryflix.ca"]
        if walls:
            # The wall is a full-height strip; the panels are what is either side of it.
            assert walls == list(range(walls[0], walls[-1] + 1)), f"{s['tag']}: wall is not one strip"
            assert sum(1 for c in s["cells"] if c["type"] == "wall") == rows * len(walls), f"{s['tag']}: wall has gaps"
            lines.append(f"panel 0-{walls[0] - 1}")
            lines.append(f"panel {walls[-1] + 1}-{cols - 1}")
            lines.append(f"gap {walls[0]}-{walls[-1]}")
        else:
            lines.append(f"panel 0-{cols - 1}")
        lines.append("rules gunsblock=1 redrule=fail")
        for c in s["cells"]:
            if c["type"] == "orange":
                lines.append(f"gun {c['c']},{c['r']},{DIR[c['dir']]}")
        if s["blues"] is not None:
            for r, c in s["blues"]:
                lines.append(f"target {c},{r}")
        for c in s["cells"]:
            if c["type"] == "purple":
                lines.append(f"mirror {c['c']},{c['r']}")
        if s["swap"] is not None:
            # Two layouts of blue tiles taking turns: each slot is a target that is only
            # there on the frames its layout is showing, and stays cleared once shot.
            slots = sorted({tuple(b) for state in s["swap"] for b in state})
            ident = {slot: i + 1 for i, slot in enumerate(slots)}
            lines.append(f"moving {s['swapMs']}")
            for state in s["swap"]:
                lines.append("mframe " + " ".join(f"{ident[tuple(b)]}:{b[1]},{b[0]}" for b in sorted(map(tuple, state))))
        frames = s["frames"]
        if len(frames) == 1 and frames[0]:
            lines.append("reds static " + " ".join(f"{c},{r}" for r, c in frames[0]))
        elif len(frames) > 1:
            lines.append(f"reds cycle {round(s['tickMs'])}")
            for f in frames:
                lines.append("frame " + " ".join(f"{c},{r}" for r, c in f))
        out.append("\n".join(lines))
    return out


def gridlock_stages(level):
    stages = [s for s in json.load(open(os.path.join(SOURCES, "gridlock.json")))
              if s["tag"].startswith(f"Level {level}-")]
    n = len(stages)
    out = []
    for w, s in enumerate(stages):
        rows, cols = s["rows"], s["cols"]
        # Boards that touch are one panel a shot crosses; boards apart get a two-column
        # wall between them, as the site draws them.
        gap = 0 if s["combined"] else 2
        width = 2 * cols + gap
        off_b = cols + gap
        sides = (("A", 0), ("B", off_b))
        lines = [f"stage level={level} index={w + 1} of={n} width={width} height={rows} source=activate.ryflix.ca"]
        if gap:
            lines.append(f"panel 0-{cols - 1}")
            lines.append(f"panel {off_b}-{width - 1}")
            lines.append(f"gap {cols}-{off_b - 1}")
        else:
            lines.append(f"panel 0-{width - 1}")
        lines.append("rules gunsblock=1 redrule=fail")
        for side, off in sides:
            for g in s[side]["oranges"]:
                lines.append(f"gun {off + g['c']},{g['r']},{DIR[g['dir']]}")
        for side, off in sides:
            for r, c in s[side]["blue"]:
                lines.append(f"target {off + c},{r}")
        for side, off in sides:
            for r, c in s[side]["purple"]:
                lines.append(f"mirror {off + c},{r}")

        def cells(frame):
            return " ".join(f"{off + c},{r}" for side, off in sides for r, c in frame[side])

        frames = s["frames"]
        if len(frames) == 1 and (frames[0]["A"] or frames[0]["B"]):
            lines.append("reds static " + cells(frames[0]))
        elif len(frames) > 1:
            lines.append(f"reds cycle {round(s['tickMs'])}")
            for f in frames:
                lines.append("frame " + cells(f))
        out.append("\n".join(lines))
    return out


CONVERTERS = {"scores": scores_stages, "ryflix": ryflix_stages, "gridlock": gridlock_stages}


def main():
    for room, sources in ROOMS.items():
        blocks = []
        for level in sorted(sources):
            blocks += CONVERTERS[sources[level]](level)
        header = (
            f"# {room.capitalize()} room stages, one block per stage. Generated by\n"
            "# tools/strategy/convert.py from the transcriptions in tools/strategy/sources --\n"
            "# edit those and re-run rather than this file. Coordinates are x,y from the\n"
            "# top-left; a gun's letter is the way it fires. Read by StrategyStages.parse().\n"
        )
        out = os.path.join(OUT_DIR, f"{room}.txt")
        with open(out, "w", newline="\n") as f:
            f.write(header + "\n" + "\n\n".join(blocks) + "\n")
        print(f"wrote {len(blocks)} stages to {os.path.relpath(out)}")


if __name__ == "__main__":
    sys.exit(main())
