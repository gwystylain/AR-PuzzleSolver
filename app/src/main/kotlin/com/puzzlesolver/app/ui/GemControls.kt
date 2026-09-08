package com.puzzlesolver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.puzzlesolver.app.pipeline.ScanPipeline
import com.puzzlesolver.core.puzzle.gems.GemColour
import com.puzzlesolver.core.puzzle.gems.GemPattern

/**
 * The Gems mode controls: four target slots across the top of the screen, and the
 * colour-picking dialog behind them.
 *
 * The four slots mirror the four target buttons above the wall in the room, so the
 * user is copying a layout they are already looking at rather than translating it.
 * They sit over the camera preview at low opacity for the obvious reason -- the wall
 * behind them is the thing being scanned, and an opaque bar would hide the top row of
 * gems exactly when the user is panning across it.
 *
 * A slot with nothing in it is not a filter. A slot with only its outer ring chosen
 * is: the unset rings are wildcards, so highlights appear from the first tap and
 * narrow as the other two are entered, rather than the user entering three colours
 * blind and finding out at the end whether it was worth it.
 */
@Composable
fun GemTargetBar(
    targets: List<GemPattern>,
    onSetTarget: (Int, GemPattern) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which slot the dialog is editing, or -1 for closed. Held here rather than in the
    // pipeline because it is pure UI state: nothing downstream cares that a dialog is
    // open, and routing it through the render thread would only add a frame of lag.
    var editing by remember { mutableStateOf(-1) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (slot in targets.indices) {
            GemSlotButton(
                slot = slot,
                pattern = targets[slot],
                onClick = { editing = slot },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (editing >= 0) {
        val slot = editing
        GemInputDialog(
            slot = slot,
            onPattern = { onSetTarget(slot, it) },
            onDismiss = { editing = -1 },
        )
    }
}

@Composable
private fun GemSlotButton(
    slot: Int,
    pattern: GemPattern,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pattern.isBlank) SLOT_EMPTY else SLOT_FILLED)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GemGlyph(pattern, Modifier.size(52.dp))
        Text(
            "${slot + 1}",
            color = Color(0xCCFFFFFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 4.dp),
        )
    }
}

/**
 * One gem drawn as three concentric discs, outermost first.
 *
 * Discs rather than rings because at 52 dp a true annulus of the right proportions is
 * two or three pixels wide and reads as a smudge. The nesting still says which colour
 * belongs to which ring, which is the only thing this has to communicate.
 */
@Composable
fun GemGlyph(
    pattern: GemPattern,
    modifier: Modifier = Modifier,
    highlightZone: Int = -1,
) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawZone(centre, radius, pattern.outer)
        drawZone(centre, radius * MIDDLE_FRACTION, pattern.middle)
        drawZone(centre, radius * CENTRE_FRACTION, pattern.centre)

        if (highlightZone >= 0) {
            val ringRadius = when (highlightZone) {
                GemPattern.ZONE_OUTER -> radius
                GemPattern.ZONE_MIDDLE -> radius * MIDDLE_FRACTION
                else -> radius * CENTRE_FRACTION
            }
            drawCircle(
                color = Color.White,
                radius = ringRadius,
                center = centre,
                style = Stroke(width = radius * 0.09f),
            )
        }
    }
}

private fun DrawScope.drawZone(centre: Offset, radius: Float, colour: Int) {
    drawCircle(color = swatch(colour), radius = radius, center = centre)
}

private fun swatch(colour: Int): Color =
    if (colour == GemColour.UNKNOWN) UNSET
    else Color(GemColour.displayRgb(colour) or 0xFF000000.toInt())

/**
 * Enters one gem, ring by ring from the outside in.
 *
 * The order is fixed and the dialog advances itself, so entering a target is three
 * taps and no mode switching. Each tap commits immediately rather than waiting for a
 * confirm: a half-entered target is already a useful filter, and the wall starts
 * highlighting behind the dialog as the user goes.
 */
@Composable
private fun GemInputDialog(
    slot: Int,
    onPattern: (GemPattern) -> Unit,
    onDismiss: () -> Unit,
) {
    // Re-tapping a slot enters a *new* set of three rather than editing the old one,
    // which is what the room asks of you: the targets change every round, so the
    // common action is replacing a target, not correcting one.
    var pattern by remember { mutableStateOf(GemPattern.BLANK) }
    var zoneIndex by remember { mutableStateOf(0) }
    val zone = GemPattern.ZONE_ORDER.getOrElse(zoneIndex) { GemPattern.ZONE_CENTRE }
    val done = zoneIndex >= GemPattern.ZONE_ORDER.size

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF2101418))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Target ${slot + 1}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (done) "all three rings set" else "pick the ${GemPattern.zoneName(zone)}",
                color = Color(0xFF9AA6B2),
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(16.dp))
            GemGlyph(
                pattern = pattern,
                modifier = Modifier.size(132.dp),
                highlightZone = if (done) -1 else zone,
            )
            Spacer(Modifier.height(20.dp))

            // 46 dp and 8 dp of gap, measured on a 411 dp-wide phone: five chips at
            // 52 dp overflowed the dialog and clipped the last colour off the right
            // edge, which on a five-colour palette is not a cosmetic problem.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (colour in GemColour.ALL) {
                    ColourChip(colour) {
                        val next = pattern.withZone(zone, colour)
                        pattern = next
                        onPattern(next)
                        zoneIndex++
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = {
                    pattern = GemPattern.BLANK
                    zoneIndex = 0
                    onPattern(GemPattern.BLANK)
                }) { Text("Clear", color = Color(0xFFFF8A80)) }
                TextButton(onClick = onDismiss) {
                    Text(if (done) "Done" else "Close", color = Color.White)
                }
            }
        }
    }

    // Closing on the third pick keeps the wall visible for the thing the third pick
    // just changed. Anything less than all three is a filter the user may still be
    // building, so the dialog stays.
    if (done) {
        LaunchedDismiss(onDismiss)
    }
}

/**
 * Fires [onDismiss] once, after the recomposition that set the third ring.
 *
 * Dismissing inline from the click handler would drop the frame that shows the third
 * colour landing, and the user would never see the gem they just finished.
 */
@Composable
private fun LaunchedDismiss(onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        onDismiss()
    }
}

@Composable
private fun ColourChip(colour: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color(GemColour.displayRgb(colour) or 0xFF000000.toInt()))
            .border(2.dp, Color(0x55FFFFFF), RoundedCornerShape(23.dp))
            .clickable(onClick = onClick),
    )
}

private val UNSET = Color(0xFF39424C)
private val SLOT_EMPTY = Color(0x55101418)
private val SLOT_FILLED = Color(0x88101418)

/** Ring radii for the on-screen glyph, as fractions of the gem's radius. */
private const val MIDDLE_FRACTION = 0.62f
private const val CENTRE_FRACTION = 0.26f

/**
 * Rings the gems that match, drawn straight over the camera preview.
 *
 * In screen space, because live Gems has no wall space to draw in: the scanner finds a
 * gem at an image coordinate and [com.puzzlesolver.app.frame.PreviewGeometry] says where
 * that lands on the viewport. For a puzzle whose answer is "that one, there" this is the
 * same answer the AR overlay would have given, arrived at without a pose, a wall fit or a
 * metric canvas.
 *
 * A ring rather than a filled disc, and drawn outside the lens rather than over it: the
 * gem underneath is the thing the user is trying to look at, and covering it up to say
 * "this one" would be self-defeating.
 */
@Composable
fun GemOverlay(highlights: List<ScanPipeline.GemHighlight>, modifier: Modifier = Modifier) {
    if (highlights.isEmpty()) return
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        for (h in highlights) {
            val colour = SLOT_COLOURS[(h.slot - 1).coerceIn(0, SLOT_COLOURS.size - 1)]
            val centre = Offset(h.x, h.y)
            // A dark ring under the bright one, so the highlight survives being drawn
            // over a wall that is itself made of coloured light.
            drawCircle(Color(0xCC000000), radius = h.radius, center = centre, style = Stroke(width = 10f))
            drawCircle(colour, radius = h.radius, center = centre, style = Stroke(width = 5f))
            val label = measurer.measure(
                h.slot.toString(),
                style = TextStyle(color = colour, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            drawText(
                label,
                topLeft = Offset(
                    h.x - label.size.width / 2f,
                    h.y - h.radius - label.size.height - 4f,
                ),
            )
        }
    }
}

/** One per target slot, chosen to stay legible over saturated LEDs. */
private val SLOT_COLOURS = listOf(
    Color(0xFFFFFFFF),
    Color(0xFF00E5FF),
    Color(0xFFFFD166),
    Color(0xFFFF66C4),
)
