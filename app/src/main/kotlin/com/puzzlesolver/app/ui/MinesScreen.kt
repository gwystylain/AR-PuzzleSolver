package com.puzzlesolver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.mines.MinesController
import com.puzzlesolver.core.puzzle.bombs.BombPlan
import com.puzzlesolver.core.puzzle.bombs.Button
import com.puzzlesolver.core.puzzle.bombs.ScannedWall

/**
 * Mines: aim at the green button, tap Scan, pan the wall, and get the answer on a board
 * of the app's own.
 *
 * While aiming and scanning the camera is behind this, with the count running at the top
 * and the wall filling in on a strip as it is read. Once the end is reached the camera is
 * covered by the answer: every button on the wall, numbered from the green, in the
 * wall's own two-column panels, with each button to mine ringed and numbered by the
 * detonation it belongs to.
 *
 * **The board can be corrected.** A tap steps a button through black, white, blue, red
 * and an armed mine, and the plan is worked out again. A misread is then a tap rather
 * than a rescan -- and the board is in front of the user next to the wall, which is the
 * best place there is to spot one.
 */
@Composable
fun MinesScreen(
    state: MinesController.State,
    onScan: () -> Unit,
    onFinish: () -> Unit,
    onRescan: () -> Unit,
    onCorrect: (Int, Int) -> Unit,
    onNudgeLattice: (Boolean) -> Unit,
    onNudgeColour: (Boolean) -> Unit,
) {
    when (state.phase) {
        MinesController.Phase.AIMING, MinesController.Phase.SCANNING ->
            Scanning(state, onScan, onFinish, onRescan, onNudgeLattice, onNudgeColour)
        MinesController.Phase.PLANNING, MinesController.Phase.DONE -> Answer(state, onRescan, onCorrect)
    }
}

@Composable
private fun Scanning(
    state: MinesController.State,
    onScan: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onNudgeLattice: (Boolean) -> Unit,
    onNudgeColour: (Boolean) -> Unit,
) {
    val scanning = state.phase == MinesController.Phase.SCANNING
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Panel {
                Text(
                    if (scanning && state.greenFound) "Mines  ·  column ${state.columns} of ${state.expectedColumns}"
                    else "Mines",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(4.dp))
                if (!scanning) {
                    Text(
                        "Stand at the green button with all seven rows in view. Tap Scan, then pan slowly right " +
                            "to the end of the wall.",
                        color = SUBTLE,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("All rows in view", state.wallInView)
                        Chip("Green in view", state.greenFound)
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { (state.columns.toFloat() / state.expectedColumns).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (state.lostTrack) AMBER else GO,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.message,
                        color = if (state.lostTrack) AMBER else Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (state.lostTrack) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    state.wall?.let { wall ->
                        Spacer(Modifier.height(8.dp))
                        Overview(wall, null, state.expectedColumns, Modifier.fillMaxWidth())
                    }
                }
                if (!scanning) {
                    Spacer(Modifier.height(6.dp))
                    Text(state.message, color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!scanning) CameraRow(state.camera, onNudgeLattice, onNudgeColour)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                    Button(
                        onClick = onFinish,
                        enabled = state.greenFound,
                        colors = ButtonDefaults.buttonColors(containerColor = GO),
                    ) { Text("Finish here", color = Color.Black) }
                } else {
                    Button(
                        onClick = onScan,
                        modifier = Modifier.width(200.dp).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GO),
                    ) { Text("Scan", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/**
 * Both exposures, and a step either way for each. Normally left alone: the colour is
 * Gems' preset and the lattice steers itself while aiming. Here for the room that
 * proves otherwise, because the trip there is the expensive part.
 */
@Composable
private fun CameraRow(camera: String, onNudgeLattice: (Boolean) -> Unit, onNudgeColour: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PANEL, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(camera, color = SUBTLE, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Colour", color = Color.White, fontSize = 12.sp)
            Small("-") { onNudgeColour(false) }
            Small("+") { onNudgeColour(true) }
            Spacer(Modifier.width(12.dp))
            Text("Lattice", color = Color.White, fontSize = 12.sp)
            Small("-") { onNudgeLattice(false) }
            Small("+") { onNudgeLattice(true) }
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
private fun Answer(state: MinesController.State, onRescan: () -> Unit, onCorrect: (Int, Int) -> Unit) {
    val wall = state.wall ?: return
    val plan = state.plan
    val rounds = remember(plan, wall) { roundsByCell(plan, wall.cols * wall.rows) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mines", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            state.planSummary ?: "",
                            color = if (plan != null) GO else AMBER,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (state.phase == MinesController.Phase.PLANNING) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = GO, strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(
                        onClick = onRescan,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                    ) { Text("Rescan", fontSize = 14.sp) }
                }
                for (w in wall.warnings) {
                    Spacer(Modifier.height(4.dp))
                    Text(w, color = AMBER, fontSize = 13.sp)
                }
                if (wall.unknownCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${wall.unknownCount} button(s) could not be read (?). They are planned around as if " +
                            "red. Tap one to set it.",
                        color = AMBER,
                        fontSize = 13.sp,
                    )
                }
            }

            Overview(wall, rounds, wall.cols, Modifier.fillMaxWidth())

            Board(wall, rounds, onCorrect)

            Text(
                "Columns count from the green button's, rows from the top. Tap a button to correct it; " +
                    "the plan is worked out again.",
                color = SUBTLE,
                fontSize = 12.sp,
            )

            if (plan != null) Rounds(plan, wall)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Which detonations each cell is pressed in, as a bitmask: bit n is round n+1. */
private fun roundsByCell(plan: BombPlan?, cells: Int): IntArray {
    val out = IntArray(cells)
    plan?.rounds?.forEachIndexed { n, round -> for (m in round.mines) if (m in out.indices) out[m] = out[m] or (1 shl n) }
    return out
}

/** The plan in words, one detonation at a time, in the order they have to happen. */
@Composable
private fun Rounds(plan: BombPlan, wall: ScannedWall) {
    Panel {
        if (plan.rounds.isEmpty()) {
            Text("Nothing to clear.", color = Color.White, fontSize = 14.sp)
            return@Panel
        }
        for ((n, round) in plan.rounds.withIndex()) {
            if (n > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(roundColour(n), RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Detonation ${n + 1}: ${round.mines.size} mine${if (round.mines.size == 1) "" else "s"}, then press green",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                round.mines.sortedWith(compareBy({ it % wall.cols }, { it / wall.cols }))
                    .joinToString("   ") { m -> "col ${m % wall.cols + 1} row ${m / wall.cols + 1}" },
                color = SUBTLE,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * The whole wall at once, small: the green at the left, every lit button in its colour,
 * and the mines to place as dots in their round's colour. A map, not something to tap.
 */
@Composable
private fun Overview(wall: ScannedWall, rounds: IntArray?, expectedColumns: Int, modifier: Modifier) {
    val cols = maxOf(wall.cols, expectedColumns)
    Canvas(
        modifier
            .background(Color(0xFF14191F), RoundedCornerShape(8.dp))
            .padding(6.dp)
            .height(54.dp),
    ) {
        val cell = minOf(size.width / cols, size.height / wall.rows)
        val r = cell * 0.38f
        for (c in 0 until cols) {
            for (row in 0 until wall.rows) {
                val centre = Offset(c * cell + cell / 2, row * cell + cell / 2)
                if (c >= wall.cols) {
                    drawCircle(Color(0xFF1E252D), r, centre)
                    continue
                }
                val kind = wall.kindAt(c, row)
                val mask = rounds?.getOrNull(row * wall.cols + c) ?: 0
                val colour = when {
                    mask != 0 -> roundColour(Integer.numberOfTrailingZeros(mask))
                    else -> buttonColour(kind)
                }
                drawCircle(colour, if (mask != 0) r * 1.25f else r, centre)
            }
        }
    }
}

/** The wall, big enough to read and tap: scrolls sideways, two columns to a panel. */
@Composable
private fun Board(wall: ScannedWall, rounds: IntArray, onCorrect: (Int, Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val cell: Dp = 40.dp
    val header: Dp = 18.dp
    val gap: Dp = 6.dp
    val density = LocalDensity.current
    val panels = (wall.cols + 1) / 2
    val width = cell * wall.cols + gap * (panels - 1)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF14191F), RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Canvas(
            Modifier
                .width(width)
                .height(header + cell * wall.rows)
                .pointerInput(wall) {
                    detectTapGestures { p ->
                        with(density) {
                            val row = ((p.y - header.toPx()) / cell.toPx()).toInt()
                            val col = columnAt(p.x, cell.toPx(), gap.toPx(), wall.cols)
                            if (col != null && row in 0 until wall.rows) onCorrect(col, row)
                        }
                    }
                },
        ) {
            val c = cell.toPx()
            val h = header.toPx()
            val g = gap.toPx()
            for (panel in 0 until panels) {
                val x0 = panel * (2 * c + g)
                val w = if (panel * 2 + 1 < wall.cols) 2 * c else c
                drawRoundRect(Color(0xFF232A33), Offset(x0, h), Size(w, c * wall.rows), CornerRadius(6.dp.toPx()))
            }
            for (col in 0 until wall.cols) {
                val x = columnX(col, c, g)
                label(measurer, "${col + 1}", Offset(x + c / 2, h / 2), if (col == 0) GREEN else SUBTLE, 10.sp)
                for (row in 0 until wall.rows) {
                    drawButton(
                        measurer,
                        wall.kindAt(col, row),
                        rounds[row * wall.cols + col],
                        Offset(x + c / 2, h + row * c + c / 2),
                        c,
                    )
                }
            }
        }
    }
}

private fun columnX(col: Int, cell: Float, gap: Float): Float = col * cell + (col / 2) * gap

private fun columnAt(x: Float, cell: Float, gap: Float, cols: Int): Int? {
    val panel = (x / (2 * cell + gap)).toInt()
    val within = x - panel * (2 * cell + gap)
    if (within >= 2 * cell) return null
    val col = panel * 2 + (within / cell).toInt()
    return col.takeIf { it in 0 until cols }
}

private fun DrawScope.drawButton(measurer: TextMeasurer, kind: Int, mask: Int, centre: Offset, cell: Float) {
    val r = cell * 0.32f
    val unknown = ScannedWall.isUnknown(kind)
    drawCircle(buttonColour(kind), r, centre)
    if (kind == Button.EMPTY || unknown) drawCircle(Color(0xFF4A535E), r, centre, style = Stroke(width = 1.5f))
    if (unknown) label(measurer, "?", centre, AMBER, 14.sp)
    if (mask != 0) {
        val first = Integer.numberOfTrailingZeros(mask)
        drawCircle(Color(0xCC000000), r + 5f, centre, style = Stroke(width = 8f))
        drawCircle(roundColour(first), r + 5f, centre, style = Stroke(width = 4.5f))
        val text = (0 until 8).filter { mask and (1 shl it) != 0 }.joinToString(",") { "${it + 1}" }
        label(measurer, text, centre, roundColour(first), if (text.length > 1) 11.sp else 15.sp)
    }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, centre: Offset, colour: Color, size: androidx.compose.ui.unit.TextUnit) {
    val layout = measurer.measure(text, TextStyle(color = colour, fontSize = size, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
}

private fun buttonColour(kind: Int): Color = when (kind) {
    Button.EMPTY -> Color(0xFF15191E)
    Button.WHITE -> Color(0xFFF2F2F2)
    Button.BLUE -> Color(0xFF3D8BFF)
    Button.HAZARD -> Color(0xFFFF3B3B)
    Button.GREEN -> GREEN
    Button.MINE -> Color(0xFFE040FB)
    else -> Color(0xFF15191E)
}

/** Detonation colours: the first is the one to do now, and the loudest. */
private fun roundColour(n: Int): Color = when (n) {
    0 -> Color(0xFFFF8800)
    1 -> Color(0xFF00D5FF)
    2 -> Color(0xFFFFE14D)
    else -> Color(0xFFB388FF)
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(PANEL, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Column { content() }
    }
}

private val PANEL = Color(0xCC101418)
private val SUBTLE = Color(0xFFB9C4CF)
private val GO = Color(0xFF39FF88)
private val AMBER = Color(0xFFFFD166)
private val GREEN = Color(0xFF33DD66)
