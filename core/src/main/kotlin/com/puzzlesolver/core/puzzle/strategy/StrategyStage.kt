package com.puzzlesolver.core.puzzle.strategy

import kotlin.math.atan2

/**
 * One stage of the Strategy room, as transcribed by the fan sites.
 *
 * The room is a wall of lit tiles. Orange tiles on the edge are guns: press one and it
 * fires a shot straight across the board that destroys the first blue target it meets,
 * and the gun is spent. Red tiles are hazards -- a shot into one is a life lost, or the
 * wave failed, depending on which transcription is being followed. Every stage has to be
 * cleared with the guns it gives you, so the whole puzzle is the order to press them in.
 *
 * Two transcriptions exist with different rules. activate-scores.ca (levels 1-6) has
 * shots pass through other guns and a red cost a life; activate.ryflix.ca (levels
 * 7-10) has guns that block, purple tiles that light a fresh target in the mirror-image
 * column when hit, hazards that end the wave, and on 7 and 8 two boards with a wall
 * between them. Rather than two solvers, a stage is one grid plus two rule switches:
 * [gunsBlock] and [redRule]. The wall is a strip of columns no shot crosses, and the
 * panels either side of it are what the clockwise numbering goes round.
 */
class StrategyStage(
    val level: Int,
    /** 1-based position within the level. */
    val index: Int,
    val stageCount: Int,
    val width: Int,
    val height: Int,
    /**
     * The physical panels, as column ranges. A gun is numbered clockwise around the
     * panel it sits on, so the panels decide what "clockwise" means.
     */
    val panels: List<IntRange>,
    /** Columns no shot can cross: the wall between two separate panels, or null. */
    val gap: IntRange?,
    val guns: List<Gun>,
    /** Blue tiles that stay put. Empty when [movingTargets] is set. */
    val targets: List<Cell>,
    /**
     * Purple tiles. Shooting one removes it and lights a blue target at the same row in
     * the mirror-image column, `width - 1 - x`.
     */
    val mirrors: List<Cell>,
    val reds: RedPattern?,
    val movingTargets: MovingTargets?,
    /** Whether a gun in the line of fire stops the shot (and is destroyed). */
    val gunsBlock: Boolean,
    val redRule: RedRule,
    /** Where the transcription came from, for the credit line. */
    val source: String,
) {
    init {
        require(guns.size <= 64) { "too many guns for a bitmask: ${guns.size}" }
        require(targetCount + mirrors.size <= 64) { "too many targets for a bitmask" }
    }

    /** Targets present at the start, moving or not. */
    val targetCount: Int get() = movingTargets?.ids?.size ?: targets.size

    /**
     * How many distinct looks the board has. Static stages have one; a stage with cycling
     * reds or moving targets has one per step of the cycle. Never both at once in the
     * data, and a stage that tried would need two clocks.
     */
    val frameCount: Int = reds?.frames?.size?.takeIf { reds.periodMillis > 0 }
        ?: movingTargets?.frames?.size
        ?: 1

    val isTimed: Boolean get() = frameCount > 1

    /** Milliseconds per frame, or 0 when nothing moves. */
    val periodMillis: Int get() = if (!isTimed) 0 else reds?.periodMillis ?: movingTargets!!.periodMillis

    fun redsAt(frame: Int): Set<Cell> = reds?.frames?.let { it[frame % it.size] } ?: emptySet()

    /** Target positions on a given frame, keyed by target id. */
    fun targetsAt(frame: Int): Map<Int, Cell> =
        movingTargets?.frames?.let { it[frame % it.size] }
            ?: targets.withIndex().associate { (i, c) -> i to c }

    fun isWall(x: Int): Boolean = gap != null && x in gap

    fun cellIndex(x: Int, y: Int): Int = y * width + x

    /**
     * The clockwise number of every gun, indexed like [guns].
     *
     * Numbering starts at the top-left corner of the panel and walks the border: along
     * the top, down the right side, back along the bottom, up the left side. That is the
     * order a player scans a wall from in front of it, which is the point: the solution
     * is read off a phone and pressed on a wall, and "the third orange tile from the
     * top-left, going round" has to mean the same thing in both places.
     *
     * With two panels the numbers carry an `L` or `R`. A gun that is not on a border --
     * the hub in the middle of level 6 -- is numbered after the border ones, clockwise
     * around the centre of its panel, so it never breaks the run along the edge.
     */
    val labels: List<String> = run {
        val out = arrayOfNulls<String>(guns.size)
        for ((p, panel) in panels.withIndex()) {
            val prefix = when {
                panels.size == 1 -> ""
                p == 0 -> "L"
                else -> "R"
            }
            val x0 = panel.first
            val x1 = panel.last
            val border = ArrayList<Pair<Double, Int>>()
            val inner = ArrayList<Pair<Double, Int>>()
            for ((i, g) in guns.withIndex()) {
                if (g.x !in panel) continue
                when {
                    g.y == 0 -> border += (0.0 + (g.x - x0)) to i
                    g.x == x1 -> border += (1000.0 + g.y) to i
                    g.y == height - 1 -> border += (2000.0 + (x1 - g.x)) to i
                    g.x == x0 -> border += (3000.0 + (height - 1 - g.y)) to i
                    else -> {
                        val cx = (x0 + x1) / 2.0
                        val cy = (height - 1) / 2.0
                        // Clockwise from north, then rotated so the top-left corner is
                        // zero -- the same starting point as the border walk.
                        val deg = Math.toDegrees(atan2(g.x - cx, -(g.y - cy)))
                        inner += ((deg - 315.0 + 720.0) % 360.0) to i
                    }
                }
            }
            var n = 1
            for ((_, i) in border.sortedBy { it.first } + inner.sortedBy { it.first }) {
                out[i] = "$prefix${n++}"
            }
        }
        out.map { it ?: "?" }
    }

    /** Guns in the order the labels count them: L1, L2, ..., R1, ... */
    val gunsByLabel: List<Int> = guns.indices.sortedWith(
        compareBy({ labels[it].firstOrNull { c -> c.isLetter() } ?: ' ' }, { labels[it].filter { c -> c.isDigit() }.toInt() }),
    )

    override fun toString(): String = "Strategy $level-$index (${width}x$height, ${guns.size} guns)"
}

enum class Direction(val dx: Int, val dy: Int, val letter: Char) {
    UP(0, -1, 'U'),
    DOWN(0, 1, 'D'),
    LEFT(-1, 0, 'L'),
    RIGHT(1, 0, 'R');

    companion object {
        fun fromLetter(c: Char): Direction = entries.first { it.letter == c }
    }
}

data class Cell(val x: Int, val y: Int)

data class Gun(val x: Int, val y: Int, val direction: Direction) {
    val cell: Cell get() = Cell(x, y)
}

/** What a shot into a red tile costs. */
enum class RedRule {
    /** One of the five lives; the red tile is cleared. activate-scores.ca plays it this way. */
    LIFE,

    /** The wave. activate.ryflix.ca plays it this way. */
    FAIL,
}

/**
 * Red tiles over time. One frame with [periodMillis] zero is a fixed layout; several
 * frames cycle, each shown for [periodMillis].
 */
class RedPattern(val periodMillis: Int, val frames: List<Set<Cell>>)

/**
 * Targets that move: each frame maps a target id to where it is. An id missing from a
 * frame is not on the board that frame -- level 9 shows two layouts of targets in
 * turn, and a target from the other layout is nowhere until it comes round again.
 */
class MovingTargets(val periodMillis: Int, val frames: List<Map<Int, Cell>>) {
    val ids: List<Int> = frames.flatMap { it.keys }.distinct().sorted()
}

/**
 * The rooms that play by these rules. Same tiles, same guns, same solver; a different
 * set of transcribed stages, and a different name on the door.
 */
enum class StrategyRoom(val id: String, val displayName: String) {
    STRATEGY("strategy", "Strategy"),
    GRIDLOCK("gridlock", "Gridlock"),
}

/**
 * Reads the bundled stage files, one per room.
 *
 * The format is a line per fact, blocks separated by a `stage` line, generated by
 * `tools/strategy/convert.py` from the sites' data. It is deliberately not JSON: this
 * module has no JSON library and a dozen `split` calls are cheaper to own than one.
 */
object StrategyStages {
    /** Every stage shipped for a room, in level then stage order. */
    fun bundled(room: StrategyRoom = StrategyRoom.STRATEGY): List<StrategyStage> {
        val resource = "/strategy/${room.id}.txt"
        val stream = StrategyStages::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().use { parse(it.readText()) }
    }

    /** [bundled] grouped by level number. Not every room starts at level 1. */
    fun levels(room: StrategyRoom = StrategyRoom.STRATEGY): Map<Int, List<StrategyStage>> =
        bundled(room).groupBy { it.level }.toSortedMap()

    fun parse(text: String): List<StrategyStage> {
        val out = ArrayList<StrategyStage>()
        var block = ArrayList<String>()
        fun flush() {
            if (block.isNotEmpty()) out += parseBlock(block)
            block = ArrayList()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("stage ")) flush()
            block += line
        }
        flush()
        return out
    }

    private fun cell(token: String): Cell {
        val (x, y) = token.split(",")
        return Cell(x.toInt(), y.toInt())
    }

    private fun range(token: String): IntRange {
        val (a, b) = token.split("-")
        return a.toInt()..b.toInt()
    }

    private fun parseBlock(lines: List<String>): StrategyStage {
        val head = lines.first().split(" ").drop(1).associate { kv ->
            val (k, v) = kv.split("=", limit = 2)
            k to v
        }
        val panels = ArrayList<IntRange>()
        var gap: IntRange? = null
        val guns = ArrayList<Gun>()
        val targets = ArrayList<Cell>()
        val mirrors = ArrayList<Cell>()
        var gunsBlock = false
        var redRule = RedRule.LIFE
        var redPeriod = 0
        val redFrames = ArrayList<Set<Cell>>()
        var movingPeriod = 0
        val movingFrames = ArrayList<Map<Int, Cell>>()
        for (line in lines.drop(1)) {
            val tok = line.split(" ")
            val args = tok.drop(1)
            when (tok[0]) {
                "panel" -> panels += range(args[0])
                "gap" -> gap = range(args[0])
                "rules" -> for (kv in args) {
                    val (k, v) = kv.split("=")
                    when (k) {
                        "gunsblock" -> gunsBlock = v == "1"
                        "redrule" -> redRule = if (v == "fail") RedRule.FAIL else RedRule.LIFE
                    }
                }
                "gun" -> {
                    val (x, y, d) = args[0].split(",")
                    guns += Gun(x.toInt(), y.toInt(), Direction.fromLetter(d[0]))
                }
                "target" -> targets += cell(args[0])
                "mirror" -> mirrors += cell(args[0])
                "reds" -> if (args[0] == "static") {
                    redFrames += args.drop(1).map(::cell).toSet()
                } else {
                    redPeriod = args[1].toInt()
                }
                "frame" -> redFrames += args.map(::cell).toSet()
                "moving" -> movingPeriod = args[0].toInt()
                "mframe" -> movingFrames += args.associate { t ->
                    val (id, c) = t.split(":")
                    id.toInt() to cell(c)
                }
                else -> error("unknown line in stage block: $line")
            }
        }
        return StrategyStage(
            level = head.getValue("level").toInt(),
            index = head.getValue("index").toInt(),
            stageCount = head.getValue("of").toInt(),
            width = head.getValue("width").toInt(),
            height = head.getValue("height").toInt(),
            panels = panels,
            gap = gap,
            guns = guns,
            targets = targets,
            mirrors = mirrors,
            reds = if (redFrames.isEmpty()) null else RedPattern(redPeriod, redFrames),
            movingTargets = if (movingFrames.isEmpty()) null else MovingTargets(movingPeriod, movingFrames),
            gunsBlock = gunsBlock,
            redRule = redRule,
            source = head.getValue("source"),
        )
    }
}
