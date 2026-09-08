package com.puzzlesolver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.pipeline.ScanPipeline

/**
 * Outlines the next two displays on the terminal wall, straight over the camera preview.
 *
 * Green for the lowest number and yellow for the second lowest, which is the whole
 * answer this mode has: the ball takes the lowest, so green is where to look now and
 * yellow is where to look next.
 *
 * In screen space, because Terminal has no wall space -- the scanner finds a display at
 * an image coordinate and [com.puzzlesolver.app.frame.PreviewGeometry] says where that
 * lands on the viewport. For a puzzle whose answer is "that one, next" this is the same
 * answer the AR overlay would have given, arrived at without a pose, a wall fit or a
 * metric canvas.
 *
 * Drawn *around* the display rather than over it, and with a dark stroke under the
 * bright one. Both for the same reason as the gem rings: the panel underneath is the
 * thing the user is trying to read, and a wall made of coloured light will swallow a
 * thin line drawn in one colour.
 */
@Composable
fun TerminalOverlay(
    highlights: List<ScanPipeline.TerminalHighlight>,
    modifier: Modifier = Modifier,
) {
    if (highlights.isEmpty()) return
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        // Furthest back first, so the green box is drawn over the yellow one where two
        // displays are close enough to touch.
        for (h in highlights.sortedByDescending { it.rank }) {
            val colour = if (h.rank == 0) NEXT else AFTER
            // Outside the panel, not on it. A rectangle traced exactly on the display's
            // own edge reads as part of the display at arm's length.
            val pad = ((h.bottom - h.top) * PADDING_FRACTION).coerceAtLeast(4f)
            val topLeft = Offset(h.left - pad, h.top - pad)
            val size = Size(
                (h.right - h.left) + 2 * pad,
                (h.bottom - h.top) + 2 * pad,
            )
            drawRect(Color(0xCC000000), topLeft, size, style = Stroke(width = 12f))
            drawRect(colour, topLeft, size, style = Stroke(width = 6f))

            val label = measurer.measure(
                if (h.rank == 0) "NEXT ${h.label}" else "then ${h.label}",
                style = TextStyle(
                    color = colour,
                    fontSize = if (h.rank == 0) 16.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            // Above the box, and below it instead when the box is near the top of the
            // screen -- the top row of a wall this size sits under the status card.
            val above = topLeft.y - label.size.height - 6f
            drawText(
                label,
                topLeft = Offset(
                    topLeft.x + (size.width - label.size.width) / 2f,
                    if (above > 0f) above else topLeft.y + size.height + 6f,
                ),
            )
        }
    }
}

/** Green for the display the ball takes next. */
private val NEXT = Color(0xFF39FF88)

/** Yellow for the one after it. */
private val AFTER = Color(0xFFFFD166)

/** Gap between the display's own edge and the rectangle, as a fraction of its height. */
private const val PADDING_FRACTION = 0.10f
