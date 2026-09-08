package com.puzzlesolver.core.puzzle.terminal

import com.puzzlesolver.core.image.GrayImage
import com.puzzlesolver.core.puzzle.GlyphClassifier

/**
 * One camera frame in, two rectangles out.
 *
 * The whole of Terminal. The wall is a scatter of displays each showing a three-digit
 * number; a ball hits the lowest one, that display clears, and the round continues until
 * none are left. So the only question the app has to answer is *which display is next*,
 * and after that which one is after it -- the lowest gets a green rectangle and the
 * second lowest a yellow one.
 *
 * Frame by frame with no pose, no wall fit and no canvas, for the same reasons as Gems:
 * a number is read from its own display and nothing else, a rectangle only has to land
 * where that display is on screen right now, and the wall changes under you as it is
 * played. There is nothing worth accumulating and nothing to accumulate it onto.
 *
 * The one piece of memory it does keep is [settled]: a ranking is published only once
 * two consecutive scans agree on it. Digit reading on the reference clip is right about
 * 999 times in 1000, which sounds like enough and is not -- with thirty-two displays in
 * frame at ten scans a second, a once-in-a-thousand misread is a green rectangle jumping
 * to the wrong display every few seconds. Requiring the answer twice costs a tenth of a
 * second after the wall changes and removes it.
 */
class TerminalScanner(
    classifier: GlyphClassifier = TerminalDigits.classifier(),
    private val detector: DisplayDetector = DisplayDetector(),
) {
    private val reader = DigitReader(classifier)

    /** One display as read, in full-resolution image pixels. */
    class Display(
        @JvmField val box: DisplayDetector.Box,
        /** The number shown, or null when the display is cleared or was not read. */
        @JvmField val value: Int?,
        /** What the reader saw, digit by digit; a dash per cleared tile. */
        @JvmField val text: String,
        @JvmField val confidence: Float,
        @JvmField val cleared: Boolean,
        /** 0 for the lowest number on the wall, 1 for the second lowest, -1 otherwise. */
        @JvmField val rank: Int,
    )

    class Result(
        @JvmField val displays: List<Display>,
        /** Displays that still show a number. */
        @JvmField val remaining: Int,
        /** Displays found but not fully read; a diagnostic, not an answer. */
        @JvmField val unread: Int,
        /**
         * Whether the ranking has been confirmed by two consecutive scans.
         *
         * False for the tenth of a second after the wall changes, and for as long as
         * the reading is genuinely unstable -- which is worth saying out loud rather
         * than covering up with a rectangle that moves.
         */
        @JvmField val settled: Boolean,
        @JvmField val status: String,
    ) {
        val lowest: Display? get() = displays.firstOrNull { it.rank == 0 }
        val second: Display? get() = displays.firstOrNull { it.rank == 1 }

        companion object {
            val EMPTY = Result(emptyList(), 0, 0, false, "looking for displays")
        }
    }

    /** Microseconds in each stage of the last scan: detect, read. */
    @JvmField
    val stageMicros = LongArray(2)

    /** The ranking the previous scan computed, waiting to be confirmed by this one. */
    private var pendingLowest: Int? = null
    private var pendingSecond: Int? = null

    /** The ranking two consecutive scans have agreed on, which is what gets drawn. */
    private var publishedLowest: Int? = null
    private var publishedSecond: Int? = null

    /** One line of profile, for the HUD and the heartbeat. */
    fun describeProfile(): String {
        val d = detector.stageMicros
        return "detect ${stageMicros[0] / 1000}ms (dec ${d[0] / 1000} blur ${d[1] / 1000} " +
            "cc ${d[2] / 1000} filt ${d[3] / 1000}) read ${stageMicros[1] / 1000}ms"
    }

    /** Forgets the confirmed ranking. For a mode switch or a new round. */
    fun reset() {
        pendingLowest = null
        pendingSecond = null
        publishedLowest = null
        publishedSecond = null
    }

    fun scan(luma: GrayImage): Result {
        var mark = System.nanoTime()
        val boxes = detector.detect(luma)
        stageMicros[0] = (System.nanoTime() - mark) / 1000
        if (boxes.isEmpty()) {
            // Not a reason to forget the ranking. The user pans, the wall leaves frame
            // for a moment and comes back, and it is the same wall.
            pendingLowest = null
            pendingSecond = null
            return Result(emptyList(), 0, 0, false, "no displays in view")
        }

        mark = System.nanoTime()
        val readings = ArrayList<Reading>(boxes.size)
        var remaining = 0
        var unread = 0
        for (box in boxes) {
            val r = reader.read(luma, box)
            val value = r.value
            if (r.isBlank) {
                readings.add(Reading(box, null, r.describe(), r.confidence, cleared = true))
                continue
            }
            if (value == null) unread++ else remaining++
            readings.add(Reading(box, value, r.describe(), r.confidence, cleared = false))
        }
        stageMicros[1] = (System.nanoTime() - mark) / 1000

        val sorted = readings.filter { it.value != null }.sortedBy { it.value }
        val lowest = sorted.getOrNull(0)?.value
        val second = sorted.getOrNull(1)?.value

        // Confirmation, not smoothing. Either this scan says the same thing the last one
        // did -- in which case believe it -- or the ranking on screen stays where it was
        // and the HUD says it is not settled. Averaging or voting over a longer window
        // would look steadier and would also keep drawing a rectangle on a display that
        // has already been hit.
        val settled = lowest != null && lowest == pendingLowest && second == pendingSecond
        if (settled) {
            publishedLowest = lowest
            publishedSecond = second
        }
        pendingLowest = lowest
        pendingSecond = second

        val displays = ArrayList<Display>(readings.size)
        var greenTaken = false
        var yellowTaken = false
        for (r in readings) {
            val rank = when {
                r.value == null -> -1
                !greenTaken && r.value == publishedLowest -> { greenTaken = true; 0 }
                !yellowTaken && r.value == publishedSecond -> { yellowTaken = true; 1 }
                else -> -1
            }
            displays.add(Display(r.box, r.value, r.text, r.confidence, r.cleared, rank))
        }

        return Result(
            displays, remaining, unread, settled,
            describe(boxes.size, remaining, unread, greenTaken, yellowTaken),
        )
    }

    private class Reading(
        val box: DisplayDetector.Box,
        val value: Int?,
        val text: String,
        val confidence: Float,
        val cleared: Boolean,
    )

    private fun describe(
        found: Int,
        remaining: Int,
        unread: Int,
        haveLowest: Boolean,
        haveSecond: Boolean,
    ): String = when {
        remaining == 0 && unread == 0 -> "all $found displays cleared"
        // Said before anything else, because it is the one state with an action attached
        // and every other line would read as though the answer were trustworthy.
        !haveLowest -> "$remaining numbers in view -- reading them" +
            if (unread > 0) "  ·  $unread not legible" else ""
        else -> {
            val next = publishedSecond
            val lowText = format(publishedLowest)
            val secondText = if (haveSecond && next != null) "  ·  then ${format(next)}" else ""
            val doubt = if (unread > 0) "  ·  $unread not legible" else ""
            "next $lowText$secondText  ·  $remaining left$doubt"
        }
    }

    private fun format(value: Int?): String =
        if (value == null) "?" else value.toString().padStart(DigitReader.TILES, '0')
}
