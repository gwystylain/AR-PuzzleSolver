#!/usr/bin/env python3
"""Builds core/src/main/resources/strategy/levels.txt from the fan-site transcriptions.

Two sites have transcribed the Strategy room and they disagree about its shape:

  activate-scores.ca      every level is one 12x12 board; shots pass through other
                          guns; a red costs a life and is cleared.  Levels 1-6, 10.
  activate.ryflix.ca      "Gridlock": levels 6-10 as two boards side by side, guns
                          that block, purple tiles that spawn a target on the other
                          board, hazards that fail the wave.  Levels 6-10.

The app's solver has one set of rules with two switches, so this script turns both
into a single wide grid per stage: a pair of separate boards becomes one grid with a
strip of wall between them, and "the other board" becomes "the mirror-image column",
which is the same cell whether or not the boards touch.

Which site supplies which level is LEVEL_SOURCES below. Levels 7-9 exist only on
Gridlock; for 6 and 10 the two transcriptions are different puzzles and the
activate-scores.ca version is used, as the one this guide was first built against.
Swap an entry and re-run to change that.

Sources live in ./sources as fetched: activate-scores serves JSON straight from
/games/strategy/<n>; gridlock.json is its LEVELS array with the hazard patterns
expanded to explicit cells by the site's own isHazardCell(), since several of its
hazard shapes are only described procedurally.

    python tools/strategy/convert.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = os.path.join(HERE, "sources")
OUT = os.path.join(HERE, "..", "..", "core", "src", "main", "resources", "strategy", "levels.txt")

LEVEL_SOURCES = {
    1: "scores", 2: "scores", 3: "scores", 4: "scores", 5: "scores",
    6: "scores", 7: "gridlock", 8: "gridlock", 9: "gridlock", 10: "scores",
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


def gridlock_stages(level):
    stages = [s for s in json.load(open(os.path.join(SOURCES, "gridlock.json")))
              if s["tag"].startswith(f"Level {level}-")]
    n = len(stages)
    out = []
    for w, s in enumerate(stages):
        rows, cols = s["rows"], s["cols"]
        # Separate boards get a two-column wall between them, as the site draws them.
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


def main():
    blocks = []
    for level in sorted(LEVEL_SOURCES):
        blocks += scores_stages(level) if LEVEL_SOURCES[level] == "scores" else gridlock_stages(level)
    header = (
        "# Strategy room stages, one block per stage. Generated by tools/strategy/convert.py\n"
        "# from the transcriptions in tools/strategy/sources -- edit those and re-run rather\n"
        "# than this file. Coordinates are x,y from the top-left; a gun's letter is the way\n"
        "# it fires. Read by StrategyStages.parse().\n"
    )
    with open(OUT, "w", newline="\n") as f:
        f.write(header + "\n" + "\n\n".join(blocks) + "\n")
    print(f"wrote {len(blocks)} stages to {os.path.relpath(OUT)}")


if __name__ == "__main__":
    sys.exit(main())
