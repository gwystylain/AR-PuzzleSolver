package com.puzzlesolver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The landing page's id for Simon Says; no camera and no server, so not in the registry. */
const val SIMON_ID = "simon"

/** The five buttons, in the order they sit on the screen. */
enum class SimonColour(val label: String, val fill: Color, val ink: Color) {
    RED("Red", Color(0xFFFF4D4D), Color(0xFF2A0606)),
    BLUE("Blue", Color(0xFF3D8BFF), Color(0xFF04142E)),
    GREEN("Green", Color(0xFF33D17A), Color(0xFF042812)),
    YELLOW("Yellow", Color(0xFFFFD43B), Color(0xFF2E2400)),
    PURPLE("Purple", Color(0xFFB65CFF), Color(0xFF1E0533)),
}

/**
 * Simon Says: five colour buttons, and a log of every press in order, so the sequence the
 * room flashes can be tapped in as it plays and read back afterwards.
 *
 * Each press is a numbered tile in its colour with its name written on it, so blue and
 * purple are told apart by the word as well as the shade; the newest is ringed in white.
 * The buttons fire on touch-down, since they are pressed quickly and in runs. Clear wipes
 * the log for the next round.
 *
 * Nothing here talks to a server: the log lives in the activity, so a stray Back to the
 * landing page and in again finds it as it was.
 */
@Composable
fun SimonScreen(
    log: List<SimonColour>,
    onPress: (SimonColour) -> Unit,
    onClear: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SIMON_BACKGROUND)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (maxWidth > maxHeight) {
                // Sideways: the log on the left, the buttons stacked on the right.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.4f).fillMaxHeight()) {
                        Header(log, onClear)
                        Spacer(Modifier.height(8.dp))
                        Log(log, Modifier.weight(1f))
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (c in SimonColour.entries) {
                            ColourButton(c, Modifier.fillMaxWidth().weight(1f), onPress)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Header(log, onClear)
                    Spacer(Modifier.height(8.dp))
                    Log(log, Modifier.weight(1f))
                    Spacer(Modifier.height(12.dp))
                    Buttons(onPress)
                }
            }
        }
    }
}

@Composable
private fun Header(log: List<SimonColour>, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Simon Says", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Text(
                when (log.size) {
                    0 -> "No presses yet"
                    1 -> "1 press"
                    else -> "${log.size} presses"
                },
                color = Color(0xFF8A96A3),
                fontSize = 13.sp,
            )
        }
        val enabled = log.isNotEmpty()
        Box(
            Modifier
                .background(
                    if (enabled) Color(0x33FF5C5C) else Color(0x14FFFFFF),
                    RoundedCornerShape(12.dp),
                )
                .clickable(enabled = enabled, onClick = onClear)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text(
                "Clear",
                color = if (enabled) Color(0xFFFF7A7A) else Color(0xFF55606B),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

/**
 * Every press as a tile, wrapping left to right, top to bottom like reading. Kept scrolled
 * to the newest, so a long run never pushes the latest press out of sight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Log(log: List<SimonColour>, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF131920), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        if (log.isEmpty()) {
            Text(
                "Tap the colours below in the order they flash",
                color = Color(0xFF6F7B87),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth().verticalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                log.forEachIndexed { i, c -> LogTile(i + 1, c, newest = i == log.lastIndex) }
            }
        }
    }
}

@Composable
private fun LogTile(number: Int, colour: SimonColour, newest: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .size(width = 64.dp, height = 64.dp)
            .background(colour.fill, shape)
            .then(if (newest) Modifier.border(3.dp, Color.White, shape) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$number", color = colour.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(colour.label, color = colour.ink, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

/** Upright: three across on top, two below, so each is a big target. */
@Composable
private fun Buttons(onPress: (SimonColour) -> Unit) {
    val rows = listOf(SimonColour.entries.take(3), SimonColour.entries.drop(3))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in row) ColourButton(c, Modifier.weight(1f).fillMaxHeight(), onPress)
            }
        }
    }
}

/** Fires on touch-down, with a tick, and dims while held. */
@Composable
private fun ColourButton(colour: SimonColour, modifier: Modifier, onPress: (SimonColour) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(if (held) colour.fill.copy(alpha = 0.6f) else colour.fill, RoundedCornerShape(18.dp))
            .pointerInput(colour) {
                detectTapGestures(
                    onPress = {
                        press(colour)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(colour.label, color = colour.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

private val SIMON_BACKGROUND = Color(0xFF0B0E11)

internal val SIMON_ACCENT = Color(0xFFFFD43B)
