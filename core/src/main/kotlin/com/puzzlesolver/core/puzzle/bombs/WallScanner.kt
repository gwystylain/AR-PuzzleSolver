package com.puzzlesolver.core.puzzle.bombs

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.image.RgbImage
import com.puzzlesolver.core.puzzle.gems.GemBlobDetector
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reads the whole mines wall off one slow pan, from the green button to the far end.
 *
 * The wall is seven rows by sixty-four columns and the solver needs every button on it at
 * once, so the answer has to wait for the whole wall -- and once it has the whole wall,
 * there is no reason to draw the answer back onto it. It goes on the phone's screen,
 * on a board of the app's own. What this class has to do is turn a pan into that board,
 * and the only hard part is **knowing which column is which**.
 *
 * There is one fixed landmark: the green button, always in the first column, third row.
 * Everything else is counted from it. So the pan is tracked column by column:
 *
 *  - In each frame the buttons are found as discs ([GemBlobDetector], the same finder
 *    Gems uses), the frame's roll is taken off, and the discs are grouped into columns
 *    and the columns into rows. A column with exactly seven evenly spaced discs fixes
 *    the rows for the whole frame; that is why all seven rows have to be in view.
 *  - Consecutive frames are matched by prediction: every button of the last frame is
 *    moved on by the pan's velocity, and each disc in this frame votes for the column
 *    offset that puts it next to one. A slow pan moves a fraction of a column between
 *    frames, so the vote is lopsided. Where position alone is ambiguous, the pattern of
 *    lit buttons breaks the tie, and it is also what re-finds the place after the
 *    track is lost.
 *  - The end is the one place there is nothing to count from: the wall simply stops. A
 *    frame whose last column has clear panel past it, three frames running, is the end.
 *    The count is then checked against the sixty-four the wall has.
 *
 * **Two kinds of frame.** Counting columns needs the *unlit* buttons, and the exposure
 * that reads LED colour well -- Gems' 1/250 s at floor ISO -- leaves them black. So the
 * camera alternates ([Exposure]): a bright *lattice* frame for the geometry, and a dark
 * *colour* frame at the Gems exposure for the colours. A colour frame has no lattice of
 * its own; it borrows the last lattice frame's, moved on by the pan's velocity and then
 * snapped onto the lit buttons it does see. A camera that cannot alternate sends only
 * lattice frames, and the lattice frame then reads colour too: off the glow around a
 * clipped button the way [ButtonPalette] always has, or off the face when it is not
 * clipped. Either way every look at a cell is a vote, and the board is the vote.
 *
 * Measured against `testVideos/Mines/mines1.mp4`, a single-exposure pan of the whole
 * wall: tracked at 10 frames a second it counts exactly 64 columns from the green to
 * the end, and still does at 7.5; at 5 the first step of the pan is more than a column
 * and position alone cannot tell which. See `WallScannerTest`.
 *
 * Not thread-safe: one caller, one frame at a time.
 */
class WallScanner(
    val expectedColumns: Int = COLUMNS,
    private val log: (String) -> Unit = {},
) {

    /** What the camera was set to for a frame. */
    enum class Exposure {
        /** Bright enough to see every button, lit or not. Geometry, and colour if it can. */
        LATTICE,

        /** Gems' LED exposure: the lit buttons' faces and nothing else. Colour only. */
        COLOUR,
    }

    enum class Phase { FINDING_GREEN, SCANNING, LOST, DONE }

    class Progress(
        val phase: Phase,
        /** Columns read, counting the green's as the first. Zero until the green is found. */
        val columns: Int,
        val expectedColumns: Int,
        /** The last lattice frame had the wall in it, all seven rows. */
        val wallInView: Boolean,
        val message: String,
        /**
         * How bright the typical button was in the last lattice frame that had the wall
         * in it, on the brightest channel; -1 before there was one. The typical button is
         * unlit, so this is what says whether the lattice exposure can see them.
         */
        val buttonBrightness: Float,
        /** Mean luma of the last lattice frame, wall or not. */
        val frameBrightness: Float,
        /**
         * Lit buttons in the last colour frame; -1 before there was one. Lit LEDs in view
         * are what say the camera is pointed at the wall, when the lattice frame is too
         * dark to say anything -- a black frame is otherwise far more often a phone
         * pointed at the floor than a wall that needs more light.
         */
        val litInView: Int,
    )

    // --- Per-frame geometry ----------------------------------------------

    /** One button position in a frame, by its column within the frame. */
    private class LocalCell(
        @JvmField val j: Int,
        @JvmField val row: Int,
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val detected: Boolean,
    )

    private class FrameLattice(
        val pitch: Float,
        val cells: List<LocalCell>,
        /** Local index of the rightmost column. */
        val lastColumn: Int,
        /** The rightmost column has clear panel after it, inside the frame. */
        val endInView: Boolean,
        val discs: Int,
        val columns: Int,
        val roll: Float,
    )

    /** A button position in a frame, by its column on the wall (relative to the first frame). */
    private class Cell(
        @JvmField val col: Int,
        @JvmField val row: Int,
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val detected: Boolean,
    )

    /** The last lattice frame that was placed on the wall, and how the pan was moving. */
    private class Placed(
        val cells: List<Cell>,
        val t: Long,
        val vx: Float,
        val vy: Float,
        val pitch: Float,
    )

    /** One look at one cell in one frame. */
    private class Look(
        @JvmField val lit: Boolean,
        /** A colour vote, or -1 for none. */
        @JvmField val button: Int,
        @JvmField val weight: Float,
        /** Whether the vote came off the face, at the colour exposure, or off the glow. */
        @JvmField val fromFace: Boolean,
        /** What was measured, for the scan log; empty for an unlit button. */
        @JvmField val detail: String = "",
    )

    private class Evidence {
        var lit = 0f
        var unlit = 0f
        val face = FloatArray(8)
        val halo = FloatArray(8)
    }

    // --- State -------------------------------------------------------------

    private val latticeDetector = GemBlobDetector(
        GemBlobDetector.Config(
            // A lit button blooms well past an unlit one at the lattice exposure, and it
            // is still a button: the gem bounds would throw it out.
            maxAreaRatio = 4.0f,
            minCircularity = 0.50f,
            minSquareness = 0.55f,
            maxBlobs = 200,
        )
    )
    private val colourDetector = GemBlobDetector()

    private val evidence = HashMap<Int, Array<Evidence>>()
    private var placed: Placed? = null
    private var greenCol: Int? = null
    private var greenRow = -1
    private var endCol: Int? = null
    private var endCandidate = Int.MIN_VALUE
    private var endStreak = 0
    private var maxCol = Int.MIN_VALUE
    private var lastPlacedT = Long.MIN_VALUE
    private var wallInView = false
    private var lastFailure = "no frame yet"
    private var buttonBrightness = -1f
    private var frameBrightness = -1f
    private var litInView = -1
    private var frames = 0
    private var lumaBuf: GrayImage? = null

    fun reset() {
        evidence.clear()
        placed = null
        greenCol = null
        greenRow = -1
        endCol = null
        endCandidate = Int.MIN_VALUE
        endStreak = 0
        maxCol = Int.MIN_VALUE
        lastPlacedT = Long.MIN_VALUE
        wallInView = false
        lastFailure = "no frame yet"
        buttonBrightness = -1f
        frameBrightness = -1f
        litInView = -1
        frames = 0
    }

    /** Takes one frame, upright, with the wall running left to right across it. */
    fun offer(frame: RgbImage, exposure: Exposure, timestampNanos: Long): Progress {
        frames++
        when (exposure) {
            Exposure.LATTICE -> offerLattice(frame, timestampNanos)
            Exposure.COLOUR -> offerColour(frame, timestampNanos)
        }
        return progress(timestampNanos)
    }

    fun progress(nowNanos: Long = lastPlacedT): Progress {
        val green = greenCol
        val columns = if (green == null) 0 else ((endCol ?: maxCol) - green + 1).coerceAtLeast(1)
        val phase = when {
            green == null -> Phase.FINDING_GREEN
            endCol != null -> Phase.DONE
            lastPlacedT != Long.MIN_VALUE && nowNanos - lastPlacedT > LOST_NANOS -> Phase.LOST
            else -> Phase.SCANNING
        }
        val message = when (phase) {
            Phase.FINDING_GREEN ->
                if (!wallInView) "Fit all seven rows in view ($lastFailure)"
                else "Get the green button in view"
            Phase.SCANNING -> "Pan right slowly  ·  column $columns of $expectedColumns"
            Phase.LOST -> "Lost the count at column $columns. Pan back left to the last lit buttons."
            Phase.DONE ->
                if (columns == expectedColumns) "Reached the end: all $columns columns"
                else "Reached the end at $columns columns, expected $expectedColumns"
        }
        return Progress(
            phase, if (green == null) 0 else columns, expectedColumns, wallInView, message,
            buttonBrightness, frameBrightness, litInView,
        )
    }

    /**
     * The wall as read so far, from the green's column to the end (or to the furthest
     * column seen, if the end has not been). Null until the green has been found.
     */
    fun result(): ScannedWall? {
        val green = greenCol ?: return null
        val last = endCol ?: maxCol
        val cols = (last - green + 1).coerceAtLeast(1)
        val kinds = ByteArray(cols * ROWS) { Button.UNSEEN.toByte() }
        val confidence = FloatArray(cols * ROWS)
        var strayGreens = 0
        for (c in 0 until cols) {
            val column = evidence[green + c] ?: continue
            for (r in 0 until ROWS) {
                val (button, conf) = decide(column[r])
                val kind = if (button == Button.GREEN && !(c == 0 && r == greenRow)) {
                    // There is one green button on the wall and this is not it.
                    strayGreens++
                    Button.OPAQUE
                } else {
                    button
                }
                kinds[r * cols + c] = kind.toByte()
                confidence[r * cols + c] = conf
            }
        }
        val warnings = ArrayList<String>()
        if (endCol == null) warnings.add("The scan stopped before the end of the wall.")
        if (cols != expectedColumns) {
            warnings.add("Counted $cols columns; the wall has $expectedColumns. Rescan if the board looks shifted.")
        }
        if (greenRow != GREEN_ROW) {
            warnings.add("The green button read in row ${greenRow + 1}, not row ${GREEN_ROW + 1}.")
        }
        if (strayGreens > 0) warnings.add("$strayGreens other button(s) read green; marked unknown.")
        return ScannedWall(cols, ROWS, kinds, confidence, endCol != null, warnings)
    }

    // --- Lattice frames ------------------------------------------------------

    private fun offerLattice(image: RgbImage, t: Long) {
        val luma = image.luma(lumaFor(image))
        var sum = 0L
        for (v in luma.data) sum += v.toInt() and 0xFF
        frameBrightness = sum.toFloat() / luma.data.size
        val lattice = findLattice(luma)
        if (lattice == null) {
            wallInView = false
            log("lattice t=${ms(t)} none: $lastFailure")
            return
        }
        wallInView = true

        val looks = readLatticeCells(image, lattice)
        val previous = placed
        val offset: Int
        val how: String
        if (previous == null) {
            offset = 0
            how = "first"
        } else {
            val o = place(lattice, looks, previous, t)
            if (o == null) {
                log("lattice t=${ms(t)} unplaced: $lastFailure")
                return
            }
            offset = o.first
            how = o.second
        }

        // Velocity off the buttons both frames saw, in pixels a second.
        var vx = 0f
        var vy = 0f
        if (previous != null) {
            val dt = (t - previous.t) / 1e9f
            if (dt > 0f) {
                val byKey = HashMap<Int, Cell>()
                for (c in previous.cells) byKey[key(c.col, c.row)] = c
                val dxs = ArrayList<Float>()
                val dys = ArrayList<Float>()
                for (c in lattice.cells) {
                    if (!c.detected) continue
                    val p = byKey[key(c.j + offset, c.row)] ?: continue
                    dxs.add((c.x - p.x) / dt)
                    dys.add((c.y - p.y) / dt)
                }
                if (dxs.size >= 3) {
                    vx = median(dxs)
                    vy = median(dys)
                }
            }
        }

        val cells = lattice.cells.map { Cell(it.j + offset, it.row, it.x, it.y, it.detected) }
        placed = Placed(cells, t, vx, vy, lattice.pitch)
        lastPlacedT = t

        var litHere = 0
        val litDetail = StringBuilder()
        for ((k, c) in cells.withIndex()) {
            val look = looks[k] ?: continue
            record(c.col, c.row, look)
            if (look.lit) {
                litHere++
                litDetail.append("\n    lit ${c.col - (greenCol ?: 0)},${c.row}: ${look.detail}")
            }
            if (c.col > maxCol) maxCol = c.col
        }
        findGreen(cells)

        val green = greenCol
        if (green != null && lattice.endInView) {
            val candidate = lattice.lastColumn + offset
            if (candidate == endCandidate) endStreak++ else {
                endCandidate = candidate
                endStreak = 1
            }
            if (endStreak >= END_FRAMES && candidate > green && endCol == null) {
                endCol = candidate
                log("end of the wall at column ${candidate - green + 1}")
            }
        } else {
            endStreak = 0
        }

        log(
            "lattice t=${ms(t)} $how off=$offset discs=${lattice.discs} cols=${lattice.columns} " +
                "pitch=%.1f roll=%.1f v=%.0f,%.0f lit=$litHere cols=%d..%d end=${lattice.endInView}".format(
                    lattice.pitch, Math.toDegrees(lattice.roll.toDouble()), vx, vy,
                    cells.minOf { it.col } - (green ?: 0), cells.maxOf { it.col } - (green ?: 0),
                ) + litDetail
        )
    }

    /**
     * Works out where this frame sits on the wall: the offset from its own column
     * numbers to the wall's. Null when it cannot be placed.
     */
    private fun place(lattice: FrameLattice, looks: List<Look?>, previous: Placed, t: Long): Pair<Int, String>? {
        val dt = (t - previous.t) / 1e9f
        val pitch = lattice.pitch
        if (dt <= 0f || dt > MAX_PREDICT_SECONDS) return relocate(lattice, looks, "after %.1fs".format(dt))

        val votes = HashMap<Int, Int>()
        var detected = 0
        for (c in lattice.cells) {
            if (!c.detected) continue
            detected++
            var best: Cell? = null
            var bestD = Float.MAX_VALUE
            for (p in previous.cells) {
                if (p.row != c.row) continue
                val d = hypot(p.x + previous.vx * dt - c.x, p.y + previous.vy * dt - c.y)
                if (d < bestD) {
                    bestD = d
                    best = p
                }
            }
            if (best != null && bestD < MATCH_RADIUS * pitch) {
                votes.merge(best.col - c.j, 1, Int::plus)
            }
        }
        if (votes.isEmpty()) return relocate(lattice, looks, "nothing matched")
        val byPosition = votes.maxByOrNull { it.value }!!
        if (byPosition.value < maxOf(MIN_VOTES, (detected * MIN_VOTE_FRACTION).roundToInt())) {
            return relocate(lattice, looks, "only ${byPosition.value} of $detected matched")
        }

        // The lit buttons have the last word when they contradict the positions: the
        // vote can only go wrong by a whole column, and a whole column moves every lit
        // button off the ones already recorded.
        val (agree, disagree) = litAgreement(lattice, looks, byPosition.key)
        if (disagree >= 2 && disagree > agree) {
            for (alt in intArrayOf(byPosition.key - 1, byPosition.key + 1)) {
                val (a, d) = litAgreement(lattice, looks, alt)
                if (a >= 2 && d == 0) return alt to "lit-pattern(was ${byPosition.key})"
            }
            lastFailure = "lit buttons disagree with the count"
            return null
        }
        return byPosition.key to "tracked(${byPosition.value}/$detected)"
    }

    /**
     * Finds this frame's place on the wall from its lit buttons alone, after the track
     * was lost. Only an unambiguous match counts: a frame with one lit button would fit
     * anywhere that button's row has one.
     */
    private fun relocate(lattice: FrameLattice, looks: List<Look?>, why: String): Pair<Int, String>? {
        val lit = looks.count { it?.lit == true }
        if (lit < 2 || evidence.isEmpty()) {
            lastFailure = "lost ($why), and too few lit buttons in view to find the place again"
            return null
        }
        val known = evidence.keys
        val lo = known.min() - lattice.lastColumn - 1
        val hi = known.max() + 1
        var best = Int.MIN_VALUE
        var bestScore = Int.MIN_VALUE
        var tied = false
        for (o in lo..hi) {
            val (a, d) = litAgreement(lattice, looks, o)
            if (a < 2 || d > 0) continue
            when {
                a > bestScore -> {
                    bestScore = a
                    best = o
                    tied = false
                }
                a == bestScore -> tied = true
            }
        }
        if (best == Int.MIN_VALUE || tied) {
            lastFailure = "lost ($why); lit buttons fit ${if (tied) "more than one place" else "nowhere"}"
            return null
        }
        return best to "relocated($why, $bestScore lit agree)"
    }

    /**
     * How many lit buttons in this frame land on cells already recorded lit, and how
     * many land on cells recorded dark (or the reverse), at offset [o].
     */
    private fun litAgreement(lattice: FrameLattice, looks: List<Look?>, o: Int): Pair<Int, Int> {
        var agree = 0
        var disagree = 0
        for ((k, c) in lattice.cells.withIndex()) {
            val look = looks[k] ?: continue
            val e = evidence[c.j + o]?.get(c.row) ?: continue
            val wasLit = e.lit >= e.unlit + 1f
            val wasDark = e.unlit >= e.lit + 1f
            if (look.lit && wasLit) agree++
            if ((look.lit && wasDark) || (!look.lit && wasLit)) disagree++
        }
        return agree to disagree
    }

    /**
     * Reads every button the lattice frame models.
     *
     * Lit means one of two things. At the lattice exposure a lit button clips its face:
     * all three channels for a white one, only its own for a green or a red, which is
     * why the test is any channel rather than all of them. At anything dark enough for
     * the face to keep its colour, a lit button is simply far brighter than the frame's
     * typical button, which is unlit. The colour is then read off whichever survives:
     * the glow round a clipped face, or the face itself.
     */
    private fun readLatticeCells(image: RgbImage, lattice: FrameLattice): List<Look?> {
        val pitch = lattice.pitch
        val faces = lattice.cells.map { c ->
            if (!inside(image, c.x, c.y, pitch * EDGE_MARGIN)) null
            else ButtonFace.read(image, c.x, c.y, pitch * ButtonFace.FACE_FRACTION)
        }
        val brightness = faces.mapNotNull { it?.brightness }
        if (brightness.isEmpty()) return faces.map { null }
        val typical = median(brightness)
        buttonBrightness = typical

        val lit = BooleanArray(faces.size) { k ->
            val face = faces[k] ?: return@BooleanArray false
            face.clipped >= CLIPPED_LIT ||
                (face.brightness >= ButtonFace.MIN_VALUE && face.brightness >= typical * LIT_RATIO)
        }

        // The panel's own colour, from between the unlit buttons. Not from round the lit
        // one being read: a green or blue LED lights its whole neighbourhood, so the
        // panel next to it is already its colour, and subtracting that subtracts the
        // answer. Measured on the reference pan, a green LED's own surround was
        // (23, 134, 40) against a panel of about (45, 43, 78).
        val ambient = FloatArray(3)
        val panelR = ArrayList<Float>()
        val panelG = ArrayList<Float>()
        val panelB = ArrayList<Float>()
        for ((k, c) in lattice.cells.withIndex()) {
            if (faces[k] == null || lit[k] || !inside(image, c.x, c.y, pitch * 0.5f + 1f)) continue
            if (meanRgbInAnnulus(image, c.x, c.y, pitch * 0.42f, pitch * 0.50f, ambient) < 8) continue
            panelR.add(ambient[0])
            panelG.add(ambient[1])
            panelB.add(ambient[2])
        }
        val panel = if (panelR.size >= 3) floatArrayOf(median(panelR), median(panelG), median(panelB)) else null

        val halo = FloatArray(3)
        return lattice.cells.mapIndexed { k, c ->
            val face = faces[k] ?: return@mapIndexed null
            val clipped = face.clipped >= CLIPPED_LIT
            when {
                !lit[k] -> Look(false, -1, 0f, false)
                !clipped -> Look(true, face.button, face.confidence * FACE_WEIGHT, true, "face $face")
                panel == null -> Look(true, -1, 0f, false, "clipped, no panel to measure the glow against")
                !inside(image, c.x, c.y, pitch * ButtonPalette.HALO_OUTER_FRACTION + 1f) ->
                    Look(true, -1, 0f, false, "clipped, glow off the frame")
                else -> {
                    val haloN = meanRgbInAnnulus(
                        image, c.x, c.y,
                        pitch * ButtonPalette.HALO_INNER_FRACTION, pitch * ButtonPalette.HALO_OUTER_FRACTION, halo,
                    )
                    if (haloN < 8) {
                        Look(true, -1, 0f, false, "clipped, glow unreadable ($haloN texels)")
                    } else {
                        val reading = ButtonFace.nameGlow(halo[0] - panel[0], halo[1] - panel[1], halo[2] - panel[2])
                        val detail = "glow %.0f,%.0f,%.0f over panel %.0f,%.0f,%.0f -> %s".format(
                            halo[0], halo[1], halo[2], panel[0], panel[1], panel[2], reading,
                        )
                        if (reading.button < 0) {
                            Look(true, -1, 0f, false, detail)
                        } else {
                            Look(true, reading.button, reading.confidence * HALO_WEIGHT, false, detail)
                        }
                    }
                }
            }
        }
    }

    // --- Colour frames ---------------------------------------------------------

    private fun offerColour(image: RgbImage, t: Long) {
        val luma = image.luma(lumaFor(image))
        val blobs = colourDetector.detect(luma)
        val last = placed
        // Counted whether or not there is a lattice to map them onto: they are what say
        // the camera is on the wall at all. Without a pitch the disc's own size will do.
        val litBlobs = blobs.filter { b ->
            val radius = last?.pitch?.times(ButtonFace.FACE_FRACTION) ?: (b.radius * 0.8f).coerceAtLeast(3f)
            ButtonFace.read(image, b.x, b.y, radius)?.lit == true
        }
        litInView = litBlobs.size
        if (last == null) return
        val dt = (t - last.t) / 1e9f
        if (abs(dt) > MAX_COLOUR_GAP_SECONDS) {
            log("colour t=${ms(t)} skipped: ${"%.2f".format(dt)}s from the last lattice frame")
            return
        }
        val pitch = last.pitch
        val n = last.cells.size
        val px = FloatArray(n) { last.cells[it].x + last.vx * dt }
        val py = FloatArray(n) { last.cells[it].y + last.vy * dt }

        // The prediction is a pan's worth of guess. The lit buttons in this frame say
        // where the lattice really is, so it is moved onto them before anything is read.
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        for (b in litBlobs) {
            var bestD = Float.MAX_VALUE
            var best = -1
            for (k in 0 until n) {
                val d = hypot(px[k] - b.x, py[k] - b.y)
                if (d < bestD) {
                    bestD = d
                    best = k
                }
            }
            if (best >= 0 && bestD < SNAP_RADIUS * pitch) {
                dxs.add(b.x - px[best])
                dys.add(b.y - py[best])
            }
        }
        val sx = if (dxs.isEmpty()) 0f else median(dxs)
        val sy = if (dys.isEmpty()) 0f else median(dys)

        var lit = 0
        var read = 0
        val litDetail = StringBuilder()
        for (k in 0 until n) {
            val x = px[k] + sx
            val y = py[k] + sy
            if (!inside(image, x, y, pitch * EDGE_MARGIN)) continue
            val face = ButtonFace.read(image, x, y, pitch * ButtonFace.FACE_FRACTION) ?: continue
            read++
            val c = last.cells[k]
            if (face.lit) {
                lit++
                record(c.col, c.row, Look(true, face.button, face.confidence * FACE_WEIGHT, true))
                litDetail.append("\n    lit ${c.col - (greenCol ?: 0)},${c.row}: face $face")
            } else {
                record(c.col, c.row, Look(false, -1, 0f, true))
            }
        }
        if (greenCol == null) findGreen(last.cells)
        log(
            "colour t=${ms(t)} dt=%.3f snap=%.1f,%.1f from ${dxs.size} lit blobs; read=$read lit=$lit".format(dt, sx, sy) +
                litDetail
        )
    }

    // --- Evidence --------------------------------------------------------------

    private fun record(col: Int, row: Int, look: Look) {
        if (row !in 0 until ROWS) return
        val e = evidence.getOrPut(col) { Array(ROWS) { Evidence() } }[row]
        if (look.lit) e.lit += 1f else e.unlit += 1f
        if (look.button >= 0 && look.weight > 0f) {
            if (look.fromFace) e.face[look.button] += look.weight else e.halo[look.button] += look.weight
        }
    }

    /**
     * What a cell's looks add up to: the button, and how sure.
     *
     * Lit or not is a straight majority of looks. The colour is the face's vote if the
     * face was ever read -- that is the reading the Gems exposure exists for -- and the
     * glow's otherwise. A split vote is [Button.OPAQUE], which the board shows as a
     * question and the planner treats as a hazard, rather than a guess.
     */
    private fun decide(e: Evidence): Pair<Int, Float> {
        val looks = e.lit + e.unlit
        if (looks <= 0f) return Button.UNSEEN to 0f
        if (e.unlit >= e.lit) return Button.EMPTY to e.unlit / looks
        val votes = when {
            e.face.sum() >= FACE_MIN -> e.face
            e.halo.sum() >= HALO_MIN -> e.halo
            else -> return Button.OPAQUE to 0f
        }
        var best = -1
        var bestV = 0f
        var second = 0f
        for (k in votes.indices) {
            if (votes[k] > bestV) {
                second = bestV
                bestV = votes[k]
                best = k
            } else if (votes[k] > second) {
                second = votes[k]
            }
        }
        val margin = (bestV - second) / bestV
        if (margin < MIN_MARGIN) return Button.OPAQUE to 0f
        return best to margin * (e.lit / looks)
    }

    /**
     * Anchors the count on the green button the first time a cell decides it is one.
     *
     * One look is enough. The pan starts on the green, so the first green seen is the
     * green; and on the reference pan the green is fully in frame exactly once before
     * the pan carries it off the edge.
     */
    private fun findGreen(cells: List<Cell>) {
        if (greenCol != null) return
        for (c in cells) {
            val e = evidence[c.col]?.get(c.row) ?: continue
            if (decide(e).first != Button.GREEN) continue
            greenCol = c.col
            greenRow = c.row
            log("green found at frame column ${c.col}, row ${c.row + 1}")
            return
        }
    }

    // --- Finding the lattice in one frame ----------------------------------------

    private fun findLattice(luma: GrayImage): FrameLattice? {
        val blobs = latticeDetector.detect(luma)
        if (blobs.size < MIN_DISCS) {
            lastFailure = "${blobs.size} buttons in view"
            return null
        }
        val pitch = latticeDetector.estimatePitch(blobs)
        if (pitch <= 0f) {
            lastFailure = "no spacing"
            return null
        }

        // Take the roll off, so columns are vertical and rows horizontal.
        val roll = estimateRoll(blobs)
        val cr = cos(roll)
        val sr = sin(roll)
        val n = blobs.size
        val xr = FloatArray(n) { blobs[it].x * cr + blobs[it].y * sr }
        val yr = FloatArray(n) { -blobs[it].x * sr + blobs[it].y * cr }

        // Columns: runs of discs closer than half a pitch across.
        val order = (0 until n).sortedBy { xr[it] }
        val clusters = ArrayList<ArrayList<Int>>()
        var sum = 0f
        for (i in order) {
            val current = clusters.lastOrNull()
            if (current == null || xr[i] - sum / current.size > COLUMN_GAP * pitch) {
                clusters.add(arrayListOf(i))
                sum = xr[i]
            } else {
                current.add(i)
                sum += xr[i]
            }
        }
        val columns = clusters.filter { it.size >= MIN_COLUMN_DISCS }.map { c -> c.sortedBy { yr[it] } }
        if (columns.size < 2) {
            lastFailure = "${columns.size} column(s) in view"
            return null
        }

        // A column with exactly seven evenly spaced discs is a whole column, and its
        // order down the frame is the row of each. Those fix the row lines.
        val complete = columns.filter { isWholeColumn(it, yr) }
        if (complete.isEmpty()) {
            lastFailure = "no column with all seven rows"
            return null
        }
        val rowA = FloatArray(ROWS)
        val rowB = FloatArray(ROWS)
        for (r in 0 until ROWS) {
            val xs = FloatArray(complete.size) { xr[complete[it][r]] }
            val ys = FloatArray(complete.size) { yr[complete[it][r]] }
            fitLine(xs, ys, pitch, rowA, rowB, r)
        }

        // Every disc onto its row; a column keeps only discs that sit on one.
        val cells = ArrayList<LocalCell>()
        val localIndex = IntArray(columns.size)
        val colX = FloatArray(columns.size) { k -> median(columns[k].map { xr[it] }) }
        for (k in 1 until columns.size) {
            val step = ((colX[k] - colX[k - 1]) / pitch).roundToInt().coerceAtLeast(1)
            localIndex[k] = localIndex[k - 1] + step
        }
        var lastColumn = Int.MIN_VALUE
        var lastColumnX = Float.MIN_VALUE
        var lastColumnDiscs = 0
        var kept = 0
        for ((k, column) in columns.withIndex()) {
            val atRow = IntArray(ROWS) { -1 }
            val atRowD = FloatArray(ROWS) { Float.MAX_VALUE }
            for (i in column) {
                var bestR = -1
                var bestD = Float.MAX_VALUE
                for (r in 0 until ROWS) {
                    val d = abs(yr[i] - (rowA[r] + rowB[r] * xr[i]))
                    if (d < bestD) {
                        bestD = d
                        bestR = r
                    }
                }
                if (bestR >= 0 && bestD < ROW_TOLERANCE * pitch && bestD < atRowD[bestR]) {
                    atRow[bestR] = i
                    atRowD[bestR] = bestD
                }
            }
            val assigned = atRow.count { it >= 0 }
            if (assigned < MIN_COLUMN_DISCS) continue
            kept++

            // The column's own line, so a wall seen at an angle still puts every cell
            // where its button is rather than straight down from the column's middle.
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (r in 0 until ROWS) if (atRow[r] >= 0) {
                xs.add(yr[atRow[r]])
                ys.add(xr[atRow[r]])
            }
            val colA = FloatArray(1)
            val colB = FloatArray(1)
            fitLine(xs.toFloatArray(), ys.toFloatArray(), pitch, colA, colB, 0)

            for (r in 0 until ROWS) {
                // Intersect the column line x = cA + cB*y with the row line y = rA + rB*x.
                val denom = 1f - rowB[r] * colB[0]
                if (abs(denom) < 1e-3f) continue
                val y = (rowA[r] + rowB[r] * colA[0]) / denom
                val x = colA[0] + colB[0] * y
                cells.add(LocalCell(localIndex[k], r, x * cr - y * sr, x * sr + y * cr, atRow[r] >= 0))
            }
            if (localIndex[k] > lastColumn) {
                lastColumn = localIndex[k]
                lastColumnX = colX[k]
                lastColumnDiscs = assigned
            }
        }
        if (kept < 2) {
            lastFailure = "$kept column(s) on the rows"
            return null
        }

        // The end of the wall: the last column is whole, and there is clear panel after
        // it inside the frame with nothing that looks like a button.
        val strayBeyond = (0 until n).any { xr[it] > lastColumnX + COLUMN_GAP * pitch }
        val lastCells = cells.filter { it.j == lastColumn }
        val rightmost = lastCells.maxOfOrNull { it.x } ?: Float.MAX_VALUE
        val endInView = !strayBeyond && lastColumnDiscs >= END_COLUMN_DISCS &&
            luma.width - rightmost >= END_CLEARANCE * pitch

        return FrameLattice(pitch, cells, lastColumn, endInView, n, kept, roll)
    }

    private fun isWholeColumn(column: List<Int>, yr: FloatArray): Boolean {
        if (column.size != ROWS) return false
        val gaps = FloatArray(ROWS - 1) { yr[column[it + 1]] - yr[column[it]] }
        val typical = median(gaps.toList())
        if (typical <= 0f) return false
        return gaps.all { it in typical * 0.65f..typical * 1.35f }
    }

    /**
     * The frame's roll, from the direction to each disc's nearest neighbour.
     *
     * On a square lattice that neighbour is either beside or above, so every direction is
     * folded into a quarter turn before taking the median. A median, because a disc at
     * the end of a row has its nearest neighbour diagonally.
     */
    private fun estimateRoll(blobs: List<GemBlobDetector.Blob>): Float {
        val angles = ArrayList<Float>(blobs.size)
        for (i in blobs.indices) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (j in blobs.indices) {
                if (i == j) continue
                val d = hypot(blobs[i].x - blobs[j].x, blobs[i].y - blobs[j].y)
                if (d < bestD) {
                    bestD = d
                    best = j
                }
            }
            if (best < 0) continue
            var a = atan2(blobs[best].y - blobs[i].y, blobs[best].x - blobs[i].x)
            val quarter = (Math.PI / 2).toFloat()
            while (a >= quarter / 2) a -= quarter
            while (a < -quarter / 2) a += quarter
            angles.add(a)
        }
        if (angles.isEmpty()) return 0f
        return median(angles).coerceIn(-MAX_ROLL, MAX_ROLL)
    }

    /** Least squares y = a + b*x into a[slot], b[slot]; flat through the mean when x barely varies. */
    private fun fitLine(xs: FloatArray, ys: FloatArray, pitch: Float, a: FloatArray, b: FloatArray, slot: Int) {
        val n = xs.size
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        var sxx = 0f
        var sxy = 0f
        for (i in 0 until n) {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
        }
        val spread = (xs.max() - xs.min())
        val slope = if (n >= 2 && spread > pitch * 0.5f && sxx > 0f) (sxy / sxx).coerceIn(-0.5f, 0.5f) else 0f
        b[slot] = slope
        a[slot] = my - slope * mx
    }

    // --- Small things --------------------------------------------------------------

    private fun lumaFor(image: RgbImage): GrayImage {
        val b = lumaBuf
        if (b != null && b.width == image.width && b.height == image.height) return b
        return GrayImage(image.width, image.height).also { lumaBuf = it }
    }

    private fun inside(image: RgbImage, x: Float, y: Float, margin: Float): Boolean =
        x >= margin && y >= margin && x < image.width - margin && y < image.height - margin

    /** Mean RGB over an annulus, skipping clipped texels, as [com.puzzlesolver.core.image.CanvasView] does. */
    private fun meanRgbInAnnulus(image: RgbImage, cx: Float, cy: Float, inner: Float, outer: Float, out: FloatArray): Int {
        out.fill(0f)
        var n = 0
        val i2 = inner * inner
        val o2 = outer * outer
        val x0 = (cx - outer).toInt().coerceAtLeast(0)
        val x1 = (cx + outer).toInt().coerceAtMost(image.width - 1)
        val y0 = (cy - outer).toInt().coerceAtLeast(0)
        val y1 = (cy + outer).toInt().coerceAtMost(image.height - 1)
        for (y in y0..y1) {
            val dy = y - cy
            for (x in x0..x1) {
                val dx = x - cx
                val d2 = dx * dx + dy * dy
                if (d2 < i2 || d2 > o2) continue
                val r = image.r(x, y)
                val g = image.g(x, y)
                val b = image.b(x, y)
                if (r >= ButtonFace.CLIP || g >= ButtonFace.CLIP || b >= ButtonFace.CLIP) continue
                out[0] += r.toFloat()
                out[1] += g.toFloat()
                out[2] += b.toFloat()
                n++
            }
        }
        if (n > 0) for (k in 0..2) out[k] /= n
        return n
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun key(col: Int, row: Int): Int = col * 16 + row

    private fun ms(t: Long): Long = t / 1_000_000

    companion object {
        /** The wall: seven rows, sixty-four columns, the green in the first column, third row. */
        const val ROWS = 7
        const val COLUMNS = 64
        const val GREEN_ROW = 2

        /** Fewer buttons than this in a lattice frame and it is not a view of the wall. */
        private const val MIN_DISCS = 12

        /** Discs further apart than this, in pitches, start a new column. */
        private const val COLUMN_GAP = 0.5f
        private const val MIN_COLUMN_DISCS = 3

        /** How far off its row line a disc may sit, in pitches. */
        private const val ROW_TOLERANCE = 0.35f

        /** A disc counts towards a column offset if it lands this close to a predicted button. */
        private const val MATCH_RADIUS = 0.45f
        private const val MIN_VOTES = 3
        private const val MIN_VOTE_FRACTION = 0.25f

        /** Past this gap the velocity says little and only the lit buttons can place a frame. */
        private const val MAX_PREDICT_SECONDS = 0.6f

        /** A colour frame further than this from a lattice frame is not mapped at all. */
        private const val MAX_COLOUR_GAP_SECONDS = 0.25f

        /** How far a lit blob may be from its predicted button and still pull the lattice onto it. */
        private const val SNAP_RADIUS = 0.5f

        /** Untracked for this long and the count is called lost. */
        private const val LOST_NANOS = 800_000_000L

        /** The end: a last column with this many discs, and this much clear panel after it. */
        private const val END_COLUMN_DISCS = 5
        private const val END_CLEARANCE = 1.5f
        private const val END_FRAMES = 3

        /** A face this much clipped is lit, at the lattice exposure. */
        private const val CLIPPED_LIT = 0.45f

        /** A face this much brighter than the frame's typical button is lit, at a darker one. */
        private const val LIT_RATIO = 2.5f

        /** Margin from the frame edge for a cell to be read at all, in pitches. */
        private const val EDGE_MARGIN = 0.3f

        /**
         * A face reading counts for this much against one off the glow. The face is the
         * reading the Gems exposure was chosen for; the glow is what is left when that
         * exposure is not available.
         */
        private const val FACE_WEIGHT = 1.0f
        private const val HALO_WEIGHT = 0.35f
        private const val FACE_MIN = 0.6f
        private const val HALO_MIN = 0.2f
        private const val MIN_MARGIN = 0.15f

        private const val MAX_ROLL = 0.35f
    }
}
