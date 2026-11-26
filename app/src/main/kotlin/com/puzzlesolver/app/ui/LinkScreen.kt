package com.puzzlesolver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.link.LinkController
import com.puzzlesolver.core.puzzle.link.LinkColour
import com.puzzlesolver.core.puzzle.link.LinkPairing
import com.puzzlesolver.core.puzzle.link.LinkPanel
import com.puzzlesolver.core.puzzle.link.LinkPlanner
import com.puzzlesolver.core.puzzle.link.LinkWall
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Link: pan the wall once while every panel is lit, then press the pairs in the order shown.
 *
 * Held sideways, as Mines is: the wall is two bands of panels and some sixty buttons long.
 * While aiming and scanning the camera is behind this and the controls are in a rail down
 * the right. Once the wall is read the camera is covered by the answer: the whole wall at
 * once, every panel where it is, with the walk drawn on it -- each pair's number on both
 * its panels, a line from the first press to its twin, a dashed one on to the next pair --
 * and a bar with the pair to press now, stepped on with Next. The wall goes dark at the
 * first press, so after that this board is the only copy of it there is.
 *
 * **Colours** shows the panels as read instead of the path, to check against the wall
 * before the first press. With **Fix** on, a tap steps a button through the five colours,
 * and the pairs and the walk are worked out again.
 */
@Composable
fun LinkScreen(
    state: LinkController.State,
    onScan: () -> Unit,
    onFinish: () -> Unit,
    onRescan: () -> Unit,
    onCorrect: (Int, Int) -> Unit,
    onStart: (LinkPlanner.Start) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onJump: (Int) -> Unit,
    onNudgeExposure: (Boolean) -> Unit,
) {
    when (state.phase) {
        LinkController.Phase.AIMING, LinkController.Phase.SCANNING ->
            Scanning(state, onScan, onFinish, onRescan, onNudgeExposure)
        LinkController.Phase.PLANNING, LinkController.Phase.DONE ->
            Answer(state, onRescan, onCorrect, onStart, onNext, onBack, onJump)
    }
}

@Composable
private fun Scanning(
    state: LinkController.State,
    onScan: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onNudgeExposure: (Boolean) -> Unit,
) {
    val scanning = state.phase == LinkController.Phase.SCANNING
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(RAIL)
                .safeDrawingPadding()
                .padding(8.dp)
                .background(PANEL, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (scanning) "${state.panels} panels, ${state.pairs} pairs" else "Link",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            if (scanning) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Chip("Left end", state.leftEnd)
                    Chip("Right end", state.rightEnd)
                }
            } else {
                Chip("${state.inView} panel${if (state.inView == 1) "" else "s"} in view", state.inView > 0)
            }
            Text(
                if (!scanning && state.inView > 0) "Ready: start at one end, tap Scan, and pan to the other." else state.message,
                color = if (state.lostTrack) AMBER else Color.White,
                fontSize = 12.sp,
                fontWeight = if (state.lostTrack) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (scanning) {
                state.wall?.let { wall -> MiniMap(wall, Modifier.fillMaxWidth().height(44.dp)) }
            }
            Spacer(Modifier.weight(1f))
            if (scanning) {
                Button(
                    onClick = onFinish,
                    enabled = state.panels >= 2,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GO),
                ) { Text("Done", color = Color.Black) }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = Color.White)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Exposure", color = Color.White, fontSize = 12.sp)
                        Small("-") { onNudgeExposure(false) }
                        Small("+") { onNudgeExposure(true) }
                    }
                    Text(state.camera, color = SUBTLE, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GO),
                ) { Text("Scan", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** The rail the controls sit in while aiming and scanning. */
private val RAIL = 240.dp

/** The panels read so far, small, in their colours. */
@Composable
private fun MiniMap(wall: LinkWall, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF14191F), RoundedCornerShape(8.dp)).padding(4.dp)) {
        if (wall.size == 0) return@Canvas
        val b = minOf(size.width / wall.cols, size.height / wall.rows)
        for (p in wall.panels) for (k in 0 until LinkPanel.CELLS) {
            drawCircle(
                colourOf(p.cells[k]),
                b * 0.4f,
                Offset((p.col + k % 3) * b + b / 2, (p.row + k / 3) * b + b / 2),
            )
        }
    }
}

@Composable
private fun Small(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Chip(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (on) GO else Color(0xFF55606B), RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) Color.White else SUBTLE, fontSize = 13.sp)
    }
}

// --- The answer -----------------------------------------------------------------------

@Composable
private fun Answer(
    state: LinkController.State,
    onRescan: () -> Unit,
    onCorrect: (Int, Int) -> Unit,
    onStart: (LinkPlanner.Start) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val wall = state.wall ?: return
    val path = state.path
    val pairing = state.pairing
    var showColours by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
            .safeDrawingPadding(),
    ) {
        // Sized to the wall's own shape: a button is the width over the columns, and the
        // height is the rows plus room for the names above and below. Sideways, the header,
        // the step bar and the board then all fit the screen's height at once, held still;
        // only the warnings and the steps in words scroll, below them.
        val boardWidth = maxWidth - 24.dp - 16.dp
        val button = boardWidth / (wall.cols.coerceAtLeast(LinkPanel.SIZE) + 1)
        val boardHeight = (button * (wall.rows.coerceAtLeast(LinkPanel.SIZE) + 3)).coerceIn(90.dp, 260.dp)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Link", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            state.summary ?: "",
                            color = if (path != null) GO else AMBER,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.phase == LinkController.Phase.PLANNING) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = GO, strokeWidth = 3.dp)
                    }
                    Toggle("Path", !showColours) { showColours = false }
                    Toggle("Colours", showColours) { showColours = true }
                    if (showColours) {
                        Toggle(if (fixing) "Fix: tap a button" else "Fix", fixing) { fixing = !fixing }
                    } else {
                        Spacer(Modifier.width(4.dp))
                        Text("Start", color = SUBTLE, fontSize = 12.sp)
                        for (s in LinkPlanner.Start.entries) {
                            Toggle(START_LABELS.getValue(s), state.start == s) { onStart(s) }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onRescan,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                    ) { Text("Rescan", fontSize = 14.sp) }
                }
            }

            if (path != null && !showColours) StepBar(wall, path, state.step, onNext, onBack)

            Board(
                wall = wall,
                pairing = pairing,
                path = path,
                step = state.step,
                showColours = showColours,
                height = boardHeight,
                onTap = { panel, cell ->
                    when {
                        showColours && fixing -> onCorrect(panel, cell)
                        !showColours -> onJump(panel)
                    }
                },
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (w in wall.warnings) Text(w, color = AMBER, fontSize = 13.sp)
                if (path != null) Steps(wall, path, state.step, onJump)
                Text(
                    "Panels are named as the practice game's E labels name them: T for the top band, B for the bottom, " +
                        "and the slot counted from the left. Tap a panel on the path to jump to its pair.",
                    color = SUBTLE,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The start choices, short enough for the header. */
private val START_LABELS = mapOf(
    LinkPlanner.Start.ANYWHERE to "Anywhere",
    LinkPlanner.Start.LEFT to "Left",
    LinkPlanner.Start.RIGHT to "Right",
)

/** The pair to press now, big, with Back and Next. */
@Composable
private fun StepBar(wall: LinkWall, path: LinkPlanner.Path, step: Int, onNext: () -> Unit, onBack: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = onBack, enabled = step > 0) { Text("Back", color = Color.White) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (step < path.steps.size) {
                    val s = path.steps[step]
                    Text("Pair ${step + 1} of ${path.steps.size}", color = SUBTLE, fontSize = 13.sp)
                    Text(
                        "${wall.label(s.first)}  then  ${wall.label(s.second)}",
                        color = NOW,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text("Every pair matched", color = GO, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = onNext,
                enabled = step < path.steps.size,
                modifier = Modifier.widthIn(min = 120.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GO),
            ) { Text("Next", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Every step in words, the current one lit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Steps(wall: LinkWall, path: LinkPlanner.Path, step: Int, onJump: (Int) -> Unit) {
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            path.steps.forEachIndexed { n, s ->
                val colour = when {
                    n == step -> NOW
                    n < step -> Color(0xFF55606B)
                    else -> Color.White
                }
                Text(
                    "${n + 1}. ${wall.label(s.first)}>${wall.label(s.second)}",
                    color = colour,
                    fontSize = 14.sp,
                    fontWeight = if (n == step) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (n == step) Color(0x33FF8800) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onJump(s.first) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * The wall, every panel where it is on it. On the path, each panel carries its pair's
 * number over its colours, dimmed; the first of each pair to press has the number in a
 * filled disc, its twin in a ring. A line joins each pair, and a dashed one walks on to
 * the next. The pair to press now is in orange, and pairs done are faded.
 */
@Composable
private fun Board(
    wall: LinkWall,
    pairing: LinkPairing?,
    path: LinkPlanner.Path?,
    step: Int,
    showColours: Boolean,
    height: Dp,
    onTap: (Int, Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    // Which step each panel is in, and whether it is pressed first.
    val stepOf = remember(path, wall) {
        IntArray(wall.size) { -1 }.also { a -> path?.steps?.forEachIndexed { n, s -> a[s.first] = n; a[s.second] = n } }
    }
    val firstOf = remember(path, wall) {
        BooleanArray(wall.size).also { a -> path?.steps?.forEach { a[it.first] = true } }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF14191F), RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(wall, showColours) {
                    detectTapGestures { p ->
                        val g = geometry(wall, size.width.toFloat(), size.height.toFloat())
                        val col = ((p.x - g.x0) / g.b).toInt()
                        val row = ((p.y - g.y0) / g.b).toInt()
                        for ((i, panel) in wall.panels.withIndex()) {
                            val dc = col - panel.col
                            val dr = row - panel.row
                            if (dc in 0..2 && dr in 0..2) {
                                onTap(i, dr * 3 + dc)
                                break
                            }
                        }
                    }
                },
        ) {
            if (wall.size == 0) return@Canvas
            val g = geometry(wall, size.width, size.height)
            val b = g.b
            fun centre(i: Int) = Offset(g.x0 + wall.panels[i].centreX * b + b / 2, g.y0 + wall.panels[i].centreY * b + b / 2)

            // Panels.
            for ((i, p) in wall.panels.withIndex()) {
                val n = stepOf[i]
                val done = !showColours && n in 0 until step
                val now = !showColours && n == step
                val topLeft = Offset(g.x0 + p.col * b - b * 0.15f, g.y0 + p.row * b - b * 0.15f)
                val tile = Size(b * 3.3f, b * 3.3f)
                drawRoundRect(Color(0xFF232A33), topLeft, tile, CornerRadius(b * 0.4f))
                val doubtful = pairing != null && i < pairing.partner.size &&
                    (pairing.partner[i] < 0 || pairing.differences[i] > 0)
                if (doubtful) drawRoundRect(AMBER, topLeft, tile, CornerRadius(b * 0.4f), style = Stroke(2.dp.toPx()))
                if (now) drawRoundRect(NOW, topLeft, tile, CornerRadius(b * 0.4f), style = Stroke(3.dp.toPx()))
                val alpha = when {
                    showColours -> 1f
                    done -> 0.12f
                    else -> 0.35f
                }
                for (k in 0 until LinkPanel.CELLS) {
                    val c = Offset(g.x0 + (p.col + k % 3) * b + b / 2, g.y0 + (p.row + k / 3) * b + b / 2)
                    val colour = p.cells[k]
                    drawCircle(colourOf(colour).copy(alpha = alpha), b * 0.38f, c)
                    if (showColours && colour == LinkColour.UNKNOWN) label(measurer, "?", c, AMBER, (b * 0.5f).toSp())
                    if (showColours && colour != LinkColour.UNKNOWN && p.confidence[k] < UNSURE) {
                        drawCircle(AMBER, b * 0.44f, c, style = Stroke(1.5.dp.toPx()))
                    }
                }
                // The panel's name, above the top band and below the others.
                val band = wall.bandOf(i)
                val nameY = if (band == 0) topLeft.y - b * 0.55f else topLeft.y + tile.height + b * 0.55f
                label(measurer, wall.label(i), Offset(centre(i).x, nameY), if (now) NOW else SUBTLE, (b * 0.62f).toSp())
            }

            if (path == null || showColours) return@Canvas

            // The walk: dashed from one pair to the next, solid within a pair.
            val dash = PathEffect.dashPathEffect(floatArrayOf(b * 0.4f, b * 0.3f))
            for (n in 1 until path.steps.size) {
                val from = centre(path.steps[n - 1].second)
                val to = centre(path.steps[n].first)
                val done = n <= step
                drawLine(
                    if (done) Color(0x33FFFFFF) else Color(0x99B9C4CF),
                    from, to,
                    strokeWidth = b * 0.12f,
                    pathEffect = dash,
                )
            }
            for ((n, s) in path.steps.withIndex()) {
                val from = centre(s.first)
                val to = centre(s.second)
                val colour = when {
                    n == step -> NOW
                    n < step -> Color(0x33FFFFFF)
                    else -> Color(0xCCFFFFFF)
                }
                drawLine(colour, from, to, strokeWidth = if (n == step) b * 0.32f else b * 0.16f, cap = StrokeCap.Round)
                arrowHead(from, to, colour, b * (if (n == step) 0.9f else 0.6f))
            }
            // Numbers on top of everything.
            for (i in wall.panels.indices) {
                val n = stepOf[i]
                if (n < 0) continue
                val c = centre(i)
                val colour = when {
                    n == step -> NOW
                    n < step -> Color(0xFF55606B)
                    else -> Color.White
                }
                val r = b * 1.05f
                if (firstOf[i]) {
                    drawCircle(colour, r, c)
                    label(measurer, "${n + 1}", c, Color.Black, (b * 1.05f).toSp())
                } else {
                    drawCircle(Color(0xFF0B0E11), r, c)
                    drawCircle(colour, r, c, style = Stroke(b * 0.16f))
                    label(measurer, "${n + 1}", c, colour, (b * 1.05f).toSp())
                }
            }
        }
    }
}

/** Where the wall sits in the canvas: button size and the top-left corner, room left for names above and below. */
private class Geometry(val b: Float, val x0: Float, val y0: Float)

private fun geometry(wall: LinkWall, width: Float, height: Float): Geometry {
    val cols = wall.cols.coerceAtLeast(LinkPanel.SIZE) + 1f
    val rows = wall.rows.coerceAtLeast(LinkPanel.SIZE) + 3f
    val b = minOf(width / cols, height / rows)
    val x0 = (width - wall.cols * b) / 2f
    val y0 = (height - wall.rows * b) / 2f
    return Geometry(b, x0, y0)
}

private fun DrawScope.arrowHead(from: Offset, to: Offset, colour: Color, length: Float) {
    val d = hypot(to.x - from.x, to.y - from.y)
    if (d < length * 3f) return
    // Stopped short of the twin's number disc, which sits on top of the line's end.
    val back = length * 1.4f
    val tip = Offset(to.x - (to.x - from.x) / d * back, to.y - (to.y - from.y) / d * back)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val spread = 0.5f
    val a = Offset(tip.x - length * cos(angle - spread), tip.y - length * sin(angle - spread))
    val c = Offset(tip.x - length * cos(angle + spread), tip.y - length * sin(angle + spread))
    drawLine(colour, tip, a, strokeWidth = length * 0.3f, cap = StrokeCap.Round)
    drawLine(colour, tip, c, strokeWidth = length * 0.3f, cap = StrokeCap.Round)
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, centre: Offset, colour: Color, size: TextUnit) {
    val layout = measurer.measure(text, TextStyle(color = colour, fontSize = size, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(if (on) GO else Color(0xFF232A33), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(PANEL, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Column { content() }
    }
}

/** The five colours as the practice game draws them. */
private fun colourOf(colour: Int): Color = when (colour) {
    LinkColour.RED -> Color(0xFFFF3B3B)
    LinkColour.YELLOW -> Color(0xFFFFE03B)
    LinkColour.GREEN -> Color(0xFF4DFF6E)
    LinkColour.BLUE -> Color(0xFF3B8FFF)
    LinkColour.PURPLE -> Color(0xFFB94DFF)
    else -> Color(0xFF4A535E)
}

/** Below this a reading gets a ring in Colours, to be looked at. */
private const val UNSURE = 0.3f

/** Link's accent on the landing page. */
internal val LINK_ACCENT = Color(0xFF3BC9FF)

private val PANEL = Color(0xCC101418)
private val SUBTLE = Color(0xFFB9C4CF)
private val GO = Color(0xFF39FF88)
private val AMBER = Color(0xFFFFD166)
private val NOW = Color(0xFFFF8800)
