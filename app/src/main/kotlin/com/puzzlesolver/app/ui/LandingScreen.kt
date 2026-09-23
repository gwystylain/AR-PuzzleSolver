package com.puzzlesolver.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.pipeline.ScanPipeline
import com.puzzlesolver.core.puzzle.bombs.BombAdapter
import com.puzzlesolver.core.puzzle.gems.GemAdapter
import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import com.puzzlesolver.core.puzzle.terminal.TerminalAdapter
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The front door.
 *
 * One screen, one question -- which room are you standing in -- answered with a tap on
 * a card. It is drawn over the HUD rather than instead of it: the camera and the
 * pipeline boot underneath exactly as they always did, so the mode picked here is live
 * the moment the cards clear. The drawer remains the way to change rooms afterwards;
 * this is for the first choice, when nothing on the HUD means anything yet.
 *
 * The entries come from the same registry as the drawer, plus the guide rooms, so a new
 * solver shows up here by being registered. Its colour, line and glyph are looked up by
 * id; a mode without an entry in that table gets a plain tile rather than being left off.
 *
 * The animation is the point of the screen, so it is not shy about it: the cards pop in
 * one after another, each carries a little moving picture of its puzzle, the one that is
 * tapped lifts while the rest are dealt off to the sides, and only then does the screen
 * give way to the HUD.
 */
@Composable
fun LandingScreen(
    modes: List<ScanPipeline.PuzzleMode>,
    visible: Boolean,
    onSelectMode: (String) -> Unit,
    onSelectGuide: (StrategyRoom) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)),
        // Grows slightly as it fades, so the HUD reads as being *behind* the cards
        // rather than the cards simply switching off.
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.06f),
    ) {
        val entries = remember(modes) {
            modes.map { m ->
                Entry(m.id, m.displayName, camera = true, LOOKS[m.id] ?: PLAIN) { onSelectMode(m.id) }
            } + StrategyRoom.entries.map { r ->
                Entry(r.id, r.displayName, camera = false, LOOKS[r.id] ?: PLAIN) { onSelectGuide(r) }
            }
        }

        // The tap is honoured after the pick animation, not on the tap itself: the card
        // has to be seen to be chosen, or the screen vanishing reads as a misfire.
        var chosen by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(chosen) {
            val id = chosen ?: return@LaunchedEffect
            delay(CHOICE_HOLD_MS.toLong())
            entries.first { it.id == id }.pick()
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E11))
                // Swallows every touch. Without it the drawer's edge swipe and the HUD's
                // buttons underneath would still answer to fingers meant for the cards.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        ) {
            Aurora(Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(36.dp))
                Title()
                Spacer(Modifier.height(24.dp))

                // One running index across both groups, so the stagger reads as a single
                // deal from top to bottom rather than two that start together.
                var index = 0
                SectionLabel("Point the camera", index++)
                for (entry in entries.filter { it.camera }) {
                    RoomCard(entry, index++, chosen) { chosen = entry.id }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(10.dp))
                SectionLabel("No camera needed", index++)
                for (entry in entries.filter { !it.camera }) {
                    RoomCard(entry, index++, chosen) { chosen = entry.id }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** How long the chosen card is held up before the screen clears. */
private const val CHOICE_HOLD_MS = 380

/** Gap between one card popping in and the next. */
private const val STAGGER_MS = 70

private class Entry(
    val id: String,
    val name: String,
    val camera: Boolean,
    val look: Look,
    val pick: () -> Unit,
)

/** What a card looks like: an accent, a line, and a little picture that moves. */
private class Look(
    val accent: Color,
    val tagline: String,
    /** One cycle of the glyph's animation, in ms. */
    val periodMs: Int,
    val glyph: DrawScope.(phase: Float, accent: Color) -> Unit,
)

private val LOOKS: Map<String, Look> = mapOf(
    "sudoku" to Look(Color(0xFF4C8DFF), "Answered as soon as the clues allow", 2700) { p, c -> sudokuGlyph(p, c) },
    "nonogram" to Look(Color(0xFF33D1C9), "Rows and columns, filled in for you", 4200) { p, c -> nonogramGlyph(p, c) },
    BombAdapter.ID to Look(Color(0xFFFF6B4A), "Every mine on the wall, before you step on one", 2400) { p, c -> minesGlyph(p, c) },
    GemAdapter.ID to Look(Color(0xFFE066FF), "Rings every gem that matches your targets", 3000) { p, c -> gemsGlyph(p, c) },
    TerminalAdapter.ID to Look(Color(0xFFFFC53D), "The two lowest numbers, live", 2100) { p, c -> terminalGlyph(p, c) },
    StrategyRoom.STRATEGY.id to Look(Color(0xFFFF9F1C), "Every wave's pressing order, split between players", 2400) { p, c -> strategyGlyph(p, c) },
    StrategyRoom.GRIDLOCK.id to Look(Color(0xFFB388FF), "Two boards, one plan, split between players", 2000) { p, c -> gridlockGlyph(p, c) },
)

private val PLAIN = Look(Color(0xFF9AA6B2), "", 1000) { _, c -> plainGlyph(c) }

// --- Pieces ------------------------------------------------------------

/**
 * Three soft blobs of colour drifting slowly behind everything. Each coordinate is a
 * whole number of cycles of the same angle, so the loop closes without a jump.
 */
@Composable
private fun Aurora(modifier: Modifier) {
    val drift = rememberInfiniteTransition(label = "aurora")
    val t by drift.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing)),
        label = "drift",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val a = t * 2f * PI.toFloat()
        fun blob(color: Color, cx: Float, cy: Float, r: Float) {
            val c = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), c, r), r, c)
        }
        blob(Color(0x364C8DFF), w * (0.20f + 0.12f * cos(a)), h * (0.14f + 0.06f * sin(a)), w * 0.75f)
        blob(Color(0x2CE066FF), w * (0.88f + 0.10f * sin(a + 2f)), h * (0.48f + 0.08f * cos(2f * a)), w * 0.65f)
        blob(Color(0x2433DD77), w * (0.30f + 0.15f * sin(2f * a + 1f)), h * (0.92f + 0.05f * cos(a + 4f)), w * 0.70f)
    }
}

@Composable
private fun Title() {
    val shimmer = rememberInfiniteTransition(label = "title")
    val sweep by shimmer.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "sweep",
    )
    val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = entrance,
        enter = fadeIn(tween(500)) +
            slideInVertically(tween(600, easing = EaseOutBack)) { -it / 2 },
    ) {
        Column {
            // A band of the accent sweeping across white. Mirrored tiling makes the
            // pattern periodic in the sweep, so it loops with no seam.
            val period = 800f
            val brush = Brush.linearGradient(
                colors = listOf(Color.White, Color(0xFF4C8DFF), Color.White),
                start = Offset(sweep * period, 0f),
                end = Offset(sweep * period + period / 2f, 0f),
                tileMode = TileMode.Mirror,
            )
            Text(
                "Puzzle Solver",
                style = TextStyle(
                    brush = brush,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text("Which room are you in?", color = Color(0xFF9AA6B2), fontSize = 15.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String, index: Int) {
    Staggered(index) {
        Text(
            text.uppercase(),
            color = Color(0xFF6F7C8A),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
    }
}

/** Pops [content] in from below, [index] places down the deal. */
@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val delay = index * STAGGER_MS
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(350, delayMillis = delay)) +
            slideInVertically(tween(480, delayMillis = delay, easing = EaseOutBack)) { it / 2 },
    ) {
        content()
    }
}

@Composable
private fun RoomCard(entry: Entry, index: Int, chosen: String?, onChoose: () -> Unit) {
    val isChosen = chosen == entry.id
    val passedOver = chosen != null && !isChosen

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val lift by animateFloatAsState(if (isChosen) 1f else 0f, tween(CHOICE_HOLD_MS), label = "lift")
    val fade by animateFloatAsState(if (passedOver) 1f else 0f, tween(CHOICE_HOLD_MS), label = "fade")
    // The ones not picked leave to alternate sides, like a hand being cleared.
    val exitSide = if (index % 2 == 0) -1f else 1f

    val cycle = rememberInfiniteTransition(label = entry.id)
    val phase by cycle.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(entry.look.periodMs, easing = LinearEasing)),
        label = "phase",
    )

    val shape = RoundedCornerShape(18.dp)
    val accent = entry.look.accent
    Staggered(index) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val s = pressScale * (1f + 0.06f * lift)
                    scaleX = s
                    scaleY = s
                    alpha = 1f - fade
                    translationX = exitSide * fade * 64.dp.toPx()
                }
                .background(Color(0xE6151A20), shape)
                .border(1.dp, accent.copy(alpha = 0.28f + 0.72f * lift), shape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = chosen == null,
                    onClick = onChoose,
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(38.dp)) { entry.look.glyph(this, phase, accent) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (entry.look.tagline.isNotEmpty()) {
                    Text(entry.look.tagline, color = Color(0xFF9AA6B2), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("›", color = accent, fontSize = 26.sp)
        }
    }
}

// --- Glyphs ------------------------------------------------------------
//
// Each is drawn into a square and given the card's animation phase, 0..1 and looping.
// They are pictures of the puzzles, not the app's readings of them, so nothing here is
// shared with the real overlays.

private fun DrawScope.cellInset(): Float = 1.5.dp.toPx()

/** A 3x3 grid with the cells lighting one by one, the way the solver fills them. */
private fun DrawScope.sudokuGlyph(phase: Float, accent: Color) {
    val s = size.minDimension
    val cell = s / 3f
    val inset = cellInset()
    val k = (phase * 9f).toInt().coerceIn(0, 8)
    for (c in 0..k) {
        val alpha = if (c == k) 1f else 0.35f
        drawRoundRect(
            accent.copy(alpha = alpha),
            Offset(c % 3 * cell + inset, c / 3 * cell + inset),
            Size(cell - 2 * inset, cell - 2 * inset),
            CornerRadius(inset),
        )
    }
    val line = Color(0xFFDDE5EC)
    for (i in 0..3) {
        val alpha = if (i == 0 || i == 3) 0.9f else 0.4f
        val w = if (i == 0 || i == 3) 2.dp.toPx() else 1.dp.toPx()
        drawLine(line.copy(alpha = alpha), Offset(i * cell, 0f), Offset(i * cell, s), w)
        drawLine(line.copy(alpha = alpha), Offset(0f, i * cell), Offset(s, i * cell), w)
    }
}

/** A 5x5 picture revealing itself cell by cell, holding, then clearing for another go. */
private fun DrawScope.nonogramGlyph(phase: Float, accent: Color) {
    val heart = intArrayOf(
        0, 1, 0, 1, 0,
        1, 1, 1, 1, 1,
        1, 1, 1, 1, 1,
        0, 1, 1, 1, 0,
        0, 0, 1, 0, 0,
    )
    val s = size.minDimension
    val cell = s / 5f
    val inset = cellInset()
    val shown = when {
        phase > 0.88f -> 0
        else -> ((phase / 0.62f).coerceAtMost(1f) * 25f).toInt()
    }
    for (i in 0 until 25) {
        val on = heart[i] == 1 && i < shown
        drawRoundRect(
            if (on) accent else Color(0x33DDE5EC),
            Offset(i % 5 * cell + inset, i / 5 * cell + inset),
            Size(cell - 2 * inset, cell - 2 * inset),
            CornerRadius(inset),
        )
    }
}

/** A wall of buttons with one mine glowing and hopping about. */
private fun DrawScope.minesGlyph(phase: Float, accent: Color) {
    val s = size.minDimension
    val cell = s / 4f
    val hop = (phase * 6f).toInt()
    val k = (hop * 7 + 3) % 16
    val pulse = 0.55f + 0.45f * sin(phase * 6f * 2f * PI.toFloat()).let { abs(it) }
    for (i in 0 until 16) {
        val c = Offset(i % 4 * cell + cell / 2f, i / 4 * cell + cell / 2f)
        if (i == k) {
            drawCircle(accent.copy(alpha = 0.35f * pulse), cell * 0.55f, c)
            drawCircle(accent, cell * 0.3f, c)
        } else {
            drawCircle(Color(0x55DDE5EC), cell * 0.26f, c)
        }
    }
}

/** Three rings of colour with a highlight running round the outside. */
private fun DrawScope.gemsGlyph(phase: Float, accent: Color) {
    val s = size.minDimension
    val c = Offset(s / 2f, s / 2f)
    val stroke = s * 0.13f
    val rings = listOf(accent, Color(0xFF33DD77), Color(0xFF4C8DFF))
    rings.forEachIndexed { i, colour ->
        val r = s / 2f - stroke / 2f - i * stroke * 1.15f
        drawCircle(colour, r, c, style = Stroke(stroke))
    }
    val r = s / 2f - stroke / 2f
    drawArc(
        Color.White.copy(alpha = 0.85f),
        startAngle = phase * 360f,
        sweepAngle = 48f,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(2 * r, 2 * r),
        style = Stroke(stroke),
    )
}

/** Three split-flap digits, one of them mid-flip. */
private fun DrawScope.terminalGlyph(phase: Float, accent: Color) {
    val s = size.minDimension
    val gap = s * 0.06f
    val slotW = (s - 2 * gap) / 3f
    val h = s * 0.8f
    val top = (s - h) / 2f
    val flipping = (phase * 3f).toInt().coerceIn(0, 2)
    val local = phase * 3f - flipping
    for (i in 0 until 3) {
        val x = i * (slotW + gap)
        val half = h / 2f
        // The top half of the flipping slot folds down to the hinge and back up,
        // which is what a flap looks like edge-on.
        val fold = if (i == flipping) abs(cos(local * PI.toFloat())) else 1f
        drawRoundRect(accent.copy(alpha = 0.9f), Offset(x, top + half - half * fold), Size(slotW, half * fold), CornerRadius(gap))
        drawRoundRect(accent.copy(alpha = 0.6f), Offset(x, top + half), Size(slotW, half), CornerRadius(gap))
        drawLine(Color(0xFF0B0E11), Offset(x, top + half), Offset(x + slotW, top + half), 1.5.dp.toPx())
    }
}

/** Eight tiles round a ring, lighting clockwise from the top-left with a trail. */
private fun DrawScope.strategyGlyph(phase: Float, accent: Color) {
    val ring = listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1)
    val s = size.minDimension
    val cell = s / 3f
    val inset = cellInset()
    val lit = (phase * 8f).toInt().coerceIn(0, 7)
    ring.forEachIndexed { i, (x, y) ->
        val behind = (lit - i + 8) % 8
        val alpha = when (behind) {
            0 -> 1f
            1 -> 0.55f
            2 -> 0.3f
            else -> 0.15f
        }
        drawRoundRect(
            accent.copy(alpha = alpha),
            Offset(x * cell + inset, y * cell + inset),
            Size(cell - 2 * inset, cell - 2 * inset),
            CornerRadius(inset * 2),
        )
    }
}

/** Two boards with a wall between: a purple tile on one lights its mirror on the other. */
private fun DrawScope.gridlockGlyph(phase: Float, accent: Color) {
    val s = size.minDimension
    val wall = s * 0.12f
    val boardW = (s - wall) / 2f
    val cell = boardW / 3f
    val inset = cellInset() * 0.8f
    val purple = Color(0xFFB388FF)
    val target = Color(0xFF33DD77)
    val firing = phase < 0.5f
    val flash = if (firing) 1f - phase * 2f else 1f - (phase - 0.5f) * 2f
    for (board in 0 until 2) {
        val x0 = board * (boardW + wall)
        for (i in 0 until 9) {
            val x = i % 3
            val y = i / 3
            val colour = when {
                board == 0 && x == 0 && y == 1 -> purple.copy(alpha = if (firing) 0.5f + 0.5f * flash else 0.5f)
                board == 1 && x == 2 && y == 1 -> if (firing) Color(0x33DDE5EC) else target.copy(alpha = 0.4f + 0.6f * flash)
                else -> Color(0x33DDE5EC)
            }
            drawRoundRect(
                colour,
                Offset(x0 + x * cell + inset, y * cell + inset),
                Size(cell - 2 * inset, cell - 2 * inset),
                CornerRadius(inset),
            )
        }
    }
    drawRect(accent.copy(alpha = 0.35f), Offset(boardW + wall * 0.25f, 0f), Size(wall * 0.5f, s))
}

private fun DrawScope.plainGlyph(accent: Color) {
    val s = size.minDimension
    drawRoundRect(accent, Offset.Zero, Size(s, s), CornerRadius(s * 0.2f), style = Stroke(2.dp.toPx()))
}
