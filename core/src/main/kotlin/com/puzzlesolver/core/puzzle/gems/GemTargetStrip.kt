package com.puzzlesolver.core.puzzle.gems

/**
 * Reads the target buttons above the wall, so they do not have to be tapped in.
 *
 * The user lines the row of target buttons up inside a slit drawn across the top of the
 * screen, and whatever lit gems sit in it are read, ordered left to right, and loaded
 * into the slots. One to four of them: a target button that is dark is not detected at
 * all, so three lit buttons load three slots and the fourth is left empty.
 *
 * Nothing new is needed to *read* them. A target button is a gem like any on the wall,
 * the same size and the same rings, and the scanner was already reading them -- on the
 * run of 2026-10-22 it read all three lit targets correctly in every frame they were in,
 * and highlighted them as matches of themselves. What was missing was knowing which of
 * the gems in a frame are targets, and the slit is that knowledge, supplied by the user's
 * aim rather than inferred. Earlier this was rejected as needing a second lattice
 * detector to tell the two rows apart; the slit makes that the user's job, which costs
 * them nothing because they are looking at the targets anyway.
 *
 * Everything here is in **screen pixels**, because the slit is drawn on the screen and the
 * user aims at it there. The caller maps each gem through the preview geometry first;
 * that is a rotation and a uniform scale, so distances keep their ratios to the pitch.
 *
 * The failure to design against is loading the wrong targets with confidence, because
 * every highlight after that is wrong and nothing on screen says so. So it refuses rather
 * than guesses whenever the slit holds something that is not plainly the target row, and
 * it loads only once several scans in a row agree. The cost of refusing is the user
 * nudging the phone; the cost of a wrong load is the round.
 */
class GemTargetStrip(private val agreeingScans: Int = AGREEING_SCANS) {

    /** A gem as the screen shows it: its centre in screen pixels and what was read. */
    class Spot(
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val pattern: GemPattern,
    )

    /** The slit, in screen pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /**
     * @param targets the targets left to right, once [agreeingScans] scans in a row have
     *        agreed on them; null until then.
     * @param status one line for the HUD: what was found, or what to do about it.
     */
    class Verdict(
        @JvmField val targets: List<GemPattern>?,
        @JvmField val status: String,
    )

    private var candidate: List<GemPattern>? = null
    private var streak = 0

    /** Forgets any agreement so far. For a fresh load, or the slit moving. */
    fun reset() {
        candidate = null
        streak = 0
    }

    /**
     * Considers one scan.
     *
     * @param spots every gem the scanner read in this frame, in screen pixels.
     * @param pitch the frame's button pitch in screen pixels, or not positive when the
     *        scanner had too few gems to measure one -- in which case it read nothing.
     */
    fun offer(spots: List<Spot>, box: Box, pitch: Float): Verdict {
        if (pitch <= 0f) {
            // The scanner reads rings at radii scaled to the wall's spacing, so with only
            // the target row in frame there is nothing to scale them by and nothing read.
            return refuse("keep some of the wall in view below the box")
        }
        val inBox = spots.filter { box.contains(it.x, it.y) }.sortedBy { it.x }
        if (inBox.isEmpty()) return refuse("line the lit target buttons up inside the box")
        if (inBox.size > MAX_TARGETS) {
            return refuse("${inBox.size} gems in the box -- only the target row should be in it")
        }

        // One row. A box straddling the bottom of the target row and the top of the wall
        // could hold four gems and still be two rows.
        val minY = inBox.minOf { it.y }
        val maxY = inBox.maxOf { it.y }
        if (maxY - minY > MAX_ROW_SPREAD * pitch) {
            return refuse("the box is catching two rows -- aim at the target row")
        }

        // The target buttons sit about two pitches apart; the wall's gems sit one apart.
        // Without this a box over the end of a wall row -- three or four gems, one row --
        // would pass both checks above and load four wall gems as targets.
        for (i in 1 until inBox.size) {
            if (inBox[i].x - inBox[i - 1].x < MIN_TARGET_SPACING * pitch) {
                return refuse("those gems are too close together to be the targets -- aim higher")
            }
        }

        val read = inBox.map { it.pattern }
        val unread = read.indexOfFirst { !it.isReadable }
        if (unread >= 0) {
            // Not loaded with the ring left blank, which would load a wildcard the user
            // never asked for and highlight gems that are not the target.
            return refuse("target ${unread + 1} would not read -- hold steady")
        }

        if (read == candidate) {
            streak++
        } else {
            candidate = read
            streak = 1
        }
        if (streak < agreeingScans) {
            val noun = if (read.size == 1) "target" else "targets"
            return Verdict(null, "reading ${read.size} $noun ($streak of $agreeingScans)")
        }
        reset()
        val noun = if (read.size == 1) "target" else "targets"
        return Verdict(read, "loaded ${read.size} $noun")
    }

    private fun refuse(status: String): Verdict {
        reset()
        return Verdict(null, status)
    }

    companion object {
        /** Four, because the room shows four target buttons. */
        const val MAX_TARGETS = GemTargets.DEFAULT_SLOTS

        /**
         * Scans in a row that must read the same targets before they are loaded.
         *
         * Three is a third of a second at the scan rate: long enough that one frame
         * misreading a ring cannot load it, short enough not to feel like waiting.
         */
        const val AGREEING_SCANS = 3

        /**
         * Most the gems in the box may differ in height, as a fraction of the pitch.
         *
         * The target strip bends with the wall, so its outer buttons sit a little above
         * the inner two -- about a quarter of a button in the room photo, under a tenth
         * of a pitch in the captures. Half a pitch admits that and nothing from the row
         * below, which is 1.3 pitches further down.
         */
        const val MAX_ROW_SPREAD = 0.5f

        /**
         * Closest two neighbouring targets may sit, as a multiple of the pitch.
         *
         * Measured at 1.8 to 2.0 on the captures of 2026-10-22, against about 1.0 for
         * neighbours on the wall. Perspective moves both by up to a quarter across a frame,
         * and 1.3 still sits between them. It also turns away the scoreboard's hearts
         * above the targets, which are blobs that read red and sit under a pitch apart.
         */
        const val MIN_TARGET_SPACING = 1.3f
    }
}
