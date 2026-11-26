package com.puzzlesolver.core.puzzle.link

import com.puzzlesolver.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Reads a Link wall in one pan, frame by frame, into a [LinkWall].
 *
 * Much less to do than the mines count ([com.puzzlesolver.core.puzzle.bombs.WallScanner]),
 * because here what is lit is what is being read: every panel is nine lit buttons, so the
 * wall carries its own landmarks, and each one is nearly unique. A pattern appears on the
 * wall exactly twice, and two panels side by side, or one above the other, almost never
 * repeat anywhere.
 *
 * Every frame's panels are placed on a map of the wall, in buttons:
 *
 *  1. **Within the frame**, each panel's place relative to its neighbours, in whole
 *     buttons, measured along the two panels' own axes so a curved wall seen at an angle
 *     does not bend the count. Neighbouring slots are four buttons apart; a gutter is one.
 *  2. **On the wall**, by following the pan: panels are four buttons apart and a frame
 *     moves a fraction of a button, so each panel is the one nearest where it was last
 *     frame. If what that puts it on disagrees with what was read there before, or the pan
 *     was lost for more than [LOST_NANOS], the frame is placed instead by its patterns: the
 *     one offset that puts two or more of its panels on panels that read the same.
 *
 * Each look at a panel is a vote for each of its nine buttons. The map starts wherever
 * the scan does and grows either way, so a scan may start mid-wall and go back.
 *
 * **It does not know how many panels there are.** The practice game lights 14 or 16
 * pairs; the room's easier levels 8 or 12, and may leave slots dark. The scan is finished
 * when both ends of the wall have been seen -- a stretch of nothing lit past the outermost
 * panel, inside the frame -- *and* every panel read has its exact twin. A dark slot in the
 * middle of a sparse wall looks like an end, which is why the pairs have to agree too: the
 * panels before a gap almost never pair up among themselves. The user can always finish
 * by hand.
 */
class LinkScanner(private val log: (String) -> Unit = {}) {

    enum class Phase { WAITING, TRACKING, LOST, DONE }

    class Progress(
        val phase: Phase,
        /** Whole panels in this frame. */
        val inView: Int,
        /** Panels on the wall so far. */
        val panels: Int,
        /** Panels with an exact twin among them. */
        val twinned: Int,
        val leftEnd: Boolean,
        val rightEnd: Boolean,
        val message: String,
    ) {
        val pairs: Int get() = twinned / 2
    }

    /** Votes for one panel on the map. Keyed by its middle button. */
    private class Track(val col: Int, val row: Int) {
        val votes = FloatArray(LinkPanel.CELLS * LinkColour.COUNT)
        val sum = FloatArray(LinkPanel.CELLS * 3)
        val weight = FloatArray(LinkPanel.CELLS)
        var looks = 0

        fun add(p: LinkFrameReader.FramePanel) {
            looks++
            for (k in 0 until LinkPanel.CELLS) {
                val look = p.looks[k]
                if (!look.lit || look.colour < 0) continue
                votes[k * LinkColour.COUNT + look.colour] += 0.25f + look.confidence
                sum[k * 3] += look.r
                sum[k * 3 + 1] += look.g
                sum[k * 3 + 2] += look.b
                weight[k] += 1f
            }
        }

        fun colour(k: Int): Int {
            var best = LinkColour.UNKNOWN
            var bestV = 0f
            for (c in 0 until LinkColour.COUNT) {
                val v = votes[k * LinkColour.COUNT + c]
                if (v > bestV) {
                    bestV = v
                    best = c
                }
            }
            return best
        }

        /** Winner's share of the votes over the runner-up's, 0..1. */
        fun confidence(k: Int): Float {
            var first = 0f
            var second = 0f
            for (c in 0 until LinkColour.COUNT) {
                val v = votes[k * LinkColour.COUNT + c]
                if (v > first) {
                    second = first
                    first = v
                } else if (v > second) {
                    second = v
                }
            }
            return if (first <= 0f) 0f else (first - second) / first
        }

        /** Buttons agreeing and disagreeing with [p]'s, among those both have a colour for. */
        fun agreement(p: LinkFrameReader.FramePanel): Int {
            var agree = 0
            var disagree = 0
            for (k in 0 until LinkPanel.CELLS) {
                val mine = colour(k)
                val theirs = p.looks[k].colour
                if (mine < 0 || theirs < 0) continue
                if (mine == theirs) agree++ else disagree++
            }
            return (agree shl 8) or disagree
        }

        fun pattern(): String = String(CharArray(LinkPanel.CELLS) { LinkColour.letter(colour(it)) })
    }

    /** A panel placed in the last frame: where it was in the image, and which track it is. */
    private class Placed(val x: Float, val y: Float, val track: Track)

    private val reader = LinkFrameReader()
    private val tracks = HashMap<Long, Track>()
    private var previous: List<Placed> = emptyList()
    private var previousNanos = 0L
    private var lastPlacedNanos = 0L
    private var velX = 0f
    private var velY = 0f
    private var leftSeen = 0
    private var rightSeen = 0
    private var frames = 0
    private var phase = Phase.WAITING
    private var twinned = 0

    /** What the last frame was read as, for the screen and the log. */
    var lastRead: LinkFrameReader.Result? = null
        private set

    fun reset() {
        tracks.clear()
        previous = emptyList()
        previousNanos = 0L
        lastPlacedNanos = 0L
        velX = 0f
        velY = 0f
        leftSeen = 0
        rightSeen = 0
        frames = 0
        phase = Phase.WAITING
        twinned = 0
        lastRead = null
    }

    fun offer(frame: RgbImage, timestampNanos: Long): Progress {
        frames++
        val read = reader.read(frame)
        lastRead = read
        if (phase == Phase.DONE) return progress(read, "Whole wall read")

        val panels = read.panels
        if (panels.isEmpty()) {
            noteUnplaced(timestampNanos)
            log("f=$frames t=${timestampNanos / 1_000_000} ${read.report}: nothing to place")
            return progress(read, null)
        }

        val coords = frameCoords(panels)
        var shift: IntArray? = null
        var how: String
        if (tracks.isEmpty()) {
            shift = intArrayOf(0, 0)
            how = "first"
        } else {
            val motion = byMotion(panels, coords, timestampNanos)
            if (motion != null) {
                val score = contentScore(panels, coords, motion)
                if (score.overlapping == 0 || score.score >= 0) {
                    shift = motion
                    how = "followed"
                } else {
                    shift = byContent(panels, coords)
                    how = if (shift != null) "re-placed by pattern (the pan disagreed by ${score.score})" else "pan disagrees with the patterns"
                }
            } else {
                shift = byContent(panels, coords)
                how = if (shift != null) "found again by pattern" else "not placed"
            }
        }

        if (shift == null) {
            noteUnplaced(timestampNanos)
            log("f=$frames t=${timestampNanos / 1_000_000} ${read.report}: $how")
            return progress(read, null)
        }

        // Place every panel, and follow the pan by the ones that were there last frame.
        val placed = ArrayList<Placed>()
        val usedThisFrame = HashSet<Track>()
        var added = 0
        var snapped = 0
        var conflicts = 0
        for (i in panels.indices) {
            val c = coords[i] ?: continue
            val col = c[0] + shift[0]
            val row = c[1] + shift[1]
            var track = near(col, row, 1)
            if (track == null) {
                if (near(col, row, CONFLICT_REACH) != null) {
                    conflicts++
                    continue
                }
                track = Track(col, row)
                tracks[key(col, row)] = track
                added++
            } else if (track.col != col || track.row != row) {
                snapped++
            }
            if (!usedThisFrame.add(track)) continue
            track.add(panels[i])
            placed.add(Placed(panels[i].cx, panels[i].cy, track))
        }
        followPan(placed, timestampNanos, how == "followed")
        previous = placed
        previousNanos = timestampNanos
        lastPlacedNanos = timestampNanos
        phase = Phase.TRACKING

        // The ends: nothing lit past the outermost panel for a stretch, inside the frame,
        // with that panel the outermost the wall has. Counted over a few frames.
        val minCol = tracks.values.minOf { it.col }
        val maxCol = tracks.values.maxOf { it.col }
        val leftmost = placed.minByOrNull { it.x }?.track
        val rightmost = placed.maxByOrNull { it.x }?.track
        if (leftmost != null && leftmost.col <= minCol + 1 && read.blankLeft >= END_BLANK) leftSeen++
        if (rightmost != null && rightmost.col >= maxCol - 1 && read.blankRight >= END_BLANK) rightSeen++

        if (frames % PAIR_CHECK_EVERY == 0 || added > 0) twinned = countTwinned()
        val complete = leftSeen >= END_FRAMES && rightSeen >= END_FRAMES && tracks.size >= 2 &&
            twinned == tracks.size && tracks.values.all { it.looks >= MIN_LOOKS }
        if (complete) phase = Phase.DONE

        log(
            "f=$frames t=${timestampNanos / 1_000_000} ${read.report}: $how shift=${shift[0]},${shift[1]} " +
                "placed=${placed.size} new=$added snapped=$snapped conflicts=$conflicts " +
                "blank=%.1f/%.1f ends=$leftSeen/$rightSeen wall=${tracks.size} twinned=$twinned".format(read.blankLeft, read.blankRight) +
                (if (added > 0) " " + placed.joinToString(" ") { "(${it.track.col},${it.track.row})${it.track.pattern()}" } else ""),
        )
        return progress(read, null)
    }

    /** The wall as read so far, or null before any panel. */
    fun result(): LinkWall? {
        if (tracks.isEmpty()) return null
        val list = tracks.values.sortedWith(compareBy({ it.col }, { it.row }))
        val named = name(list)
        val panels = list.mapIndexed { i, t ->
            LinkPanel(t.col - 1, t.row - 1, named.cells[i], named.confidence[i], t.looks)
        }
        val warnings = ArrayList<String>()
        if (named.adapted) warnings.add("Colours named by where this visit's camera put them: ${named.note}")
        if (leftSeen < END_FRAMES || rightSeen < END_FRAMES) {
            val missing = listOfNotNull(if (leftSeen < END_FRAMES) "left" else null, if (rightSeen < END_FRAMES) "right" else null)
            warnings.add("The ${missing.joinToString(" and ")} end of the wall was not seen: panels past it may be missing.")
        }
        return LinkWall(panels, warnings)
    }

    /** Every button named at once; see [LinkPalette]. */
    private fun name(list: List<Track>): LinkPalette.Named {
        val votes = list.map { t -> IntArray(LinkPanel.CELLS) { t.colour(it) } }
        val voteConfidence = list.map { t -> FloatArray(LinkPanel.CELLS) { t.confidence(it) } }
        val features = ArrayList<FloatArray>()
        for (t in list) {
            for (k in 0 until LinkPanel.CELLS) {
                val w = t.weight[k]
                features.add(
                    if (w <= 0f) floatArrayOf(0f, 0f, 0f, 0f)
                    else floatArrayOf(t.sum[k * 3] / w, t.sum[k * 3 + 1] / w, t.sum[k * 3 + 2] / w, w)
                )
            }
        }
        return LinkPalette.name(votes, voteConfidence, features)
    }

    // --- Placing a frame -------------------------------------------------------------

    /**
     * Each panel's middle button relative to the first's, in whole buttons. Built outward
     * from neighbour to neighbour, each step measured along the two panels' own axes, so
     * only short distances are ever rounded. A panel whose step is not near a whole number
     * of buttons, or that would overlap another, is left out.
     */
    private fun frameCoords(panels: List<LinkFrameReader.FramePanel>): Array<IntArray?> {
        val n = panels.size
        val coords = arrayOfNulls<IntArray>(n)
        coords[0] = intArrayOf(0, 0)
        val pending = (1 until n).toMutableSet()
        val placed = mutableListOf(0)
        while (pending.isNotEmpty()) {
            var bi = -1
            var bj = -1
            var bd = Float.MAX_VALUE
            for (i in pending) for (j in placed) {
                val d = hypot(panels[i].cx - panels[j].cx, panels[i].cy - panels[j].cy)
                if (d < bd) {
                    bd = d
                    bi = i
                    bj = j
                }
            }
            pending.remove(bi)
            val a = panels[bi]
            val b = panels[bj]
            val ux = (a.ux + b.ux) / 2f
            val uy = (a.uy + b.uy) / 2f
            val vx = (a.vx + b.vx) / 2f
            val vy = (a.vy + b.vy) / 2f
            val det = ux * vy - vx * uy
            if (abs(det) < 1e-3f) continue
            val dx = a.cx - b.cx
            val dy = a.cy - b.cy
            val s = (dx * vy - vx * dy) / det
            val t = (ux * dy - dx * uy) / det
            val rs = s.roundToInt()
            val rt = t.roundToInt()
            if (abs(s - rs) > STEP_SLACK || abs(t - rt) > STEP_SLACK) continue
            if (abs(rs) < LinkPanel.SIZE + 1 && abs(rt) < LinkPanel.SIZE + 1) continue
            val base = coords[bj]!!
            coords[bi] = intArrayOf(base[0] + rs, base[1] + rt)
            placed.add(bi)
        }
        return coords
    }

    /** The shift that keeps each panel on the one nearest where it was a frame ago, or null. */
    private fun byMotion(panels: List<LinkFrameReader.FramePanel>, coords: Array<IntArray?>, now: Long): IntArray? {
        if (previous.isEmpty() || now - previousNanos > LOST_NANOS) return null
        val dt = (now - previousNanos) / 1e9f
        val votes = HashMap<Long, Int>()
        var matched = 0
        for (i in panels.indices) {
            val c = coords[i] ?: continue
            val p = panels[i]
            val px = p.cx - velX * dt
            val py = p.cy - velY * dt
            val reach = p.pitch * MOTION_REACH
            val prev = previous.minByOrNull { hypot(it.x - px, it.y - py) } ?: continue
            if (hypot(prev.x - px, prev.y - py) > reach) continue
            matched++
            val k = key(prev.track.col - c[0], prev.track.row - c[1])
            votes[k] = (votes[k] ?: 0) + 1
        }
        if (matched == 0) return null
        val best = votes.maxByOrNull { it.value } ?: return null
        if (best.value * 10 < matched * 6) return null
        return intArrayOf(colOf(best.key), rowOf(best.key))
    }

    private class Score(val score: Int, val overlapping: Int, val strong: Int)

    /**
     * How well a shift puts the frame's patterns on what the map has read: buttons agreeing
     * less disagreeing, over the panels it lands on map panels, and a panel's worth off for
     * each that lands half on one.
     */
    private fun contentScore(panels: List<LinkFrameReader.FramePanel>, coords: Array<IntArray?>, shift: IntArray): Score {
        var score = 0
        var overlapping = 0
        var strong = 0
        for (i in panels.indices) {
            val c = coords[i] ?: continue
            val col = c[0] + shift[0]
            val row = c[1] + shift[1]
            val track = near(col, row, 1)
            if (track != null) {
                val a = track.agreement(panels[i])
                val agree = a shr 8
                val disagree = a and 0xFF
                score += agree - disagree
                overlapping++
                if (agree >= STRONG_AGREEMENT) strong++
            } else if (near(col, row, CONFLICT_REACH) != null) {
                score -= LinkPanel.CELLS
            }
        }
        return Score(score, overlapping, strong)
    }

    /**
     * The one shift that puts two or more of the frame's panels on map panels reading the
     * same, clearly better than any other -- or null. One panel is never enough: every
     * pattern is on the wall twice.
     */
    private fun byContent(panels: List<LinkFrameReader.FramePanel>, coords: Array<IntArray?>): IntArray? {
        val candidates = HashSet<Long>()
        for (i in panels.indices) {
            val c = coords[i] ?: continue
            for (t in tracks.values) {
                if ((t.agreement(panels[i]) shr 8) >= STRONG_AGREEMENT) candidates.add(key(t.col - c[0], t.row - c[1]))
            }
        }
        var best: Long? = null
        var bestScore = Int.MIN_VALUE
        var second = Int.MIN_VALUE
        for (k in candidates) {
            val s = contentScore(panels, coords, intArrayOf(colOf(k), rowOf(k)))
            if (s.strong < 2) continue
            if (s.score > bestScore) {
                second = bestScore
                bestScore = s.score
                best = k
            } else if (s.score > second) {
                second = s.score
            }
        }
        val b = best ?: return null
        if (bestScore < CONTENT_MIN) return null
        if (second != Int.MIN_VALUE && bestScore - second < CONTENT_MARGIN) return null
        return intArrayOf(colOf(b), rowOf(b))
    }

    private fun followPan(placed: List<Placed>, now: Long, followed: Boolean) {
        if (!followed || previous.isEmpty()) {
            velX = 0f
            velY = 0f
            return
        }
        val dt = (now - previousNanos) / 1e9f
        if (dt <= 0f) return
        var sx = 0f
        var sy = 0f
        var n = 0
        for (p in placed) {
            val before = previous.firstOrNull { it.track === p.track } ?: continue
            sx += p.x - before.x
            sy += p.y - before.y
            n++
        }
        if (n == 0) return
        velX = 0.5f * velX + 0.5f * (sx / n / dt)
        velY = 0.5f * velY + 0.5f * (sy / n / dt)
    }

    private fun noteUnplaced(now: Long) {
        if (tracks.isNotEmpty() && now - lastPlacedNanos > LOST_NANOS) phase = Phase.LOST
    }

    /** The track whose middle is within [reach] buttons of ([col], [row]) both ways, nearest first. */
    private fun near(col: Int, row: Int, reach: Int): Track? {
        tracks[key(col, row)]?.let { return it }
        var best: Track? = null
        var bestD = Int.MAX_VALUE
        for (dc in -reach..reach) for (dr in -reach..reach) {
            val t = tracks[key(col + dc, row + dr)] ?: continue
            val d = abs(dc) + abs(dr)
            if (d < bestD) {
                bestD = d
                best = t
            }
        }
        return best
    }

    /** Panels with an exact twin, under the naming [result] would use. */
    private fun countTwinned(): Int = LinkPalette.twinned(name(tracks.values.toList()).cells)

    private fun progress(read: LinkFrameReader.Result, override: String?): Progress {
        val left = leftSeen >= END_FRAMES
        val right = rightSeen >= END_FRAMES
        val message = override ?: when (phase) {
            Phase.WAITING -> if (read.panels.isEmpty()) {
                if (read.partial > 0) "Step back: no whole panel in view" else "No panels in view"
            } else {
                "${read.panels.size} panel${if (read.panels.size == 1) "" else "s"} in view"
            }
            Phase.LOST -> "Lost the place: pan back over panels already read"
            Phase.TRACKING -> buildString {
                append("${tracks.size} panels, ${twinned / 2} pairs")
                when {
                    left && right && twinned < tracks.size -> append(" -- ${tracks.size - twinned} without a twin yet")
                    left && !right -> append(" -- keep panning to the right end")
                    right && !left -> append(" -- the left end not seen yet")
                    !left && !right -> append(" -- pan from one end to the other")
                }
            }
            Phase.DONE -> "Whole wall read: ${tracks.size / 2} pairs"
        }
        return Progress(phase, read.panels.size, tracks.size, twinned, left, right, message)
    }

    /**
     * Where along the wall the last placed frame was: 0 at the leftmost panel, 1 at the
     * rightmost, or NaN before any. Says which end a scan finished at -- where whoever
     * held the phone is standing.
     */
    val lastPlace: Float
        get() {
            if (previous.isEmpty() || tracks.isEmpty()) return Float.NaN
            val min = tracks.values.minOf { it.col }
            val max = tracks.values.maxOf { it.col }
            if (max == min) return 0.5f
            return ((previous.map { it.track.col }.average().toFloat() - min) / (max - min)).coerceIn(0f, 1f)
        }

    companion object {
        /** The mode's id, as the landing page and the debug broadcast name it. */
        const val ID = "link"

        /** Past this without placing a frame, the pan says nothing about where the next one is. */
        const val LOST_NANOS = 600_000_000L

        /** How far a panel may have moved from where the pan predicts it, in buttons. Panels are four apart. */
        const val MOTION_REACH = 1.8f

        /** How far a step between panels may be from whole buttons. */
        const val STEP_SLACK = 0.35f

        /** A panel landing this near a different one, in buttons both ways, is a contradiction. */
        const val CONFLICT_REACH = 3

        /** Of nine, buttons agreeing for a panel to count as found again. */
        const val STRONG_AGREEMENT = 7

        /** Least score for a frame to be placed by its patterns, and its lead over the next shift. */
        const val CONTENT_MIN = 10
        const val CONTENT_MARGIN = 5

        /** Buttons of nothing lit past the outermost panel for the frame to show an end. */
        const val END_BLANK = 4.5f

        /** Frames that must show an end before it counts. */
        const val END_FRAMES = 3

        /** Looks every panel needs before the scan can finish by itself. */
        const val MIN_LOOKS = 2

        private const val PAIR_CHECK_EVERY = 5

        private fun key(col: Int, row: Int): Long = (col.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)
        private fun colOf(key: Long): Int = (key shr 32).toInt()
        private fun rowOf(key: Long): Int = key.toInt()
    }
}
