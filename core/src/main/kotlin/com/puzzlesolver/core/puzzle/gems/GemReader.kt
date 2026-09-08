package com.puzzlesolver.core.puzzle.gems

import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.image.CanvasView

/**
 * Reads one gem's three rings off the canvas.
 *
 * Thin on purpose -- [GemPalette] holds the judgement and this holds the geometry --
 * but separate because the geometry is the part a test wants to drive directly. The
 * real-frame fixture test hands it gem centres measured by hand, so it can check
 * colour reading without also depending on lattice detection getting the origin right.
 */
class GemReader {

    private val votes = FloatArray(GemColour.ALL.size)
    private val scratch = FloatArray(3)

    /**
     * @param pattern the three rings, with [GemColour.UNKNOWN] where a ring could not
     *        be named.
     * @param confidence the *weakest* of the three rings' confidences. A gem is only
     *        as trustworthy as its shakiest ring, since a match needs all three.
     * @param washedOut fraction of the gem's lit texels that carried no usable hue.
     *        High across the board means the exposure has blown the rings out and no
     *        amount of care downstream will recover them.
     * @param lit whether anything on this gem is above the panel's brightness at all.
     */
    class Result(
        @JvmField val pattern: GemPattern,
        @JvmField val confidence: Float,
        @JvmField val washedOut: Float,
        @JvmField val lit: Boolean,
    )

    /** Reads the gem at the centre of cell ([col], [row]). */
    fun read(view: CanvasView, grid: GridModel, col: Int, row: Int): Result {
        val centre = grid.cellCentre(col, row)
        return readAt(view, centre[0], centre[1], grid.pitchX)
    }

    /**
     * Reads a gem centred at a canvas texel, with the lattice [pitch] in texels.
     *
     * The pitch is what sets every radius -- see [GemPalette.CENTRE_OUTER] and friends
     * for why that, rather than the gem's apparent size, is the right ruler.
     */
    fun readAt(view: CanvasView, cx: Float, cy: Float, pitch: Float): Result {
        val outer = GemPalette.classifyAnnulus(
            view, cx, cy, pitch * GemPalette.OUTER_INNER, pitch * GemPalette.OUTER_OUTER,
            votes, scratch,
        )
        val middle = GemPalette.classifyAnnulus(
            view, cx, cy, pitch * GemPalette.MIDDLE_INNER, pitch * GemPalette.MIDDLE_OUTER,
            votes, scratch,
        )
        val centre = GemPalette.classifyAnnulus(
            view, cx, cy, pitch * GemPalette.CENTRE_INNER, pitch * GemPalette.CENTRE_OUTER,
            votes, scratch,
        )
        val pattern = GemPattern(
            outer = outer.colour,
            middle = middle.colour,
            centre = centre.colour,
        )
        // The outer ring dominates the washed-out figure because it is by far the
        // largest band; the centre dot is a handful of texels and would make the
        // number noise if it counted equally.
        val washed = outer.washedOut * 0.6f + middle.washedOut * 0.3f + centre.washedOut * 0.1f
        return Result(
            pattern = pattern,
            confidence = minOf(outer.confidence, middle.confidence, centre.confidence),
            washedOut = washed,
            lit = outer.lit || middle.lit || centre.lit,
        )
    }
}
