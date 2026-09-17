package com.puzzlesolver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.frame.FrameSource
import com.puzzlesolver.app.pipeline.ScanPipeline
import com.puzzlesolver.core.puzzle.gems.GemPattern
import com.puzzlesolver.core.puzzle.strategy.StrategyRoom
import kotlinx.coroutines.launch

/**
 * The scanning HUD.
 *
 * Two jobs, in priority order: tell the user where to point the camera, and make the
 * moment the puzzle is solved unmissable. Everything else is debug detail and lives
 * behind a toggle, because during a scan the user is holding a phone at arm's length
 * and cannot read a wall of numbers.
 */
@Composable
fun ScanScreen(
    state: ScanPipeline.UiState,
    isRecording: Boolean,
    replayName: String?,
    puzzleModes: List<ScanPipeline.PuzzleMode>,
    onSelectPuzzleMode: (String?) -> Unit,
    gemTargets: List<GemPattern>,
    onSetGemTarget: (Int, GemPattern) -> Unit,
    onNudgeExposure: (Boolean) -> Unit,
    onSetCameraManual: (Boolean) -> Unit,
    onSetCameraLocks: (Boolean, Boolean) -> Unit,
    onLedPreset: () -> Unit,
    onAutoExposure: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
    onRestartScan: () -> Unit,
    onRestartAll: () -> Unit,
    onToggleRecording: () -> Unit,
    onCapture: () -> Unit,
    isLogging: Boolean,
    logSummary: String?,
    onToggleLogging: () -> Unit,
    onExport: () -> Unit,
    onPickVideo: () -> Unit,
    onOpenLastRecording: () -> Unit,
    onReturnToLive: () -> Unit,
    onForceFlatWall: (Float) -> Unit,
    onExpectCells: (Int?) -> Unit,
    // The guide rooms -- Strategy, Gridlock -- are game modes with no camera behind
    // them. They are chosen from the same menu as the scanning modes, because to the
    // user it is the same decision -- which room am I standing in -- and a guide
    // replaces the HUD outright rather than hiding pieces of it, since none of the HUD
    // is about anything it does.
    guideRoom: StrategyRoom? = null,
    onSelectGuide: (StrategyRoom) -> Unit = {},
    guideContent: @Composable (StrategyRoom) -> Unit = {},
) {
    var showDebug by remember { mutableStateOf(false) }
    // Closed to begin with, and remembered for the session. The card is tall enough that
    // leaving it open is a decision the user should make once, not one the app makes for
    // them every time the mode changes.
    var showCamera by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GameModeDrawer(
                modes = puzzleModes,
                state = state,
                showDebug = showDebug,
                guideRoom = guideRoom,
                onSelect = {
                    onSelectPuzzleMode(it)
                    scope.launch { drawerState.close() }
                },
                onSelectGuide = {
                    onSelectGuide(it)
                    scope.launch { drawerState.close() }
                },
                onToggleDebug = {
                    showDebug = !showDebug
                    // Closes on the flip, like a mode pick does. The panel it turns on
                    // is behind the sheet, so leaving the sheet open would hide the only
                    // confirmation that the toggle did anything.
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
    if (guideRoom != null) {
        guideContent(guideRoom)
        return@ModalNavigationDrawer
    }
    Box(Modifier.fillMaxSize()) {
        // Under the HUD panels and over the camera: the rings have to be on top of the
        // wall and behind the controls, or a target button lands on the gem it found.
        GemOverlay(state.gemOverlay, Modifier.fillMaxSize())
        TerminalOverlay(state.terminalOverlay, Modifier.fillMaxSize())

        // The two HUD columns are inset from the system bars; the camera and the overlays
        // behind them are not. The rings are drawn in camera coordinates and have to
        // stay full-bleed to land on the wall, but a button under a three-button
        // navigation bar is a button that cannot be pressed, and the top card under the
        // status bar has the clock printed over it.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Above the status card rather than below it: these are the controls the
            // user reaches for while pointing the phone at the wall, and the top of the
            // screen is the only part of it their thumb is nowhere near.
            if (state.isGemsMode) {
                GemTargetBar(targets = gemTargets, onSetTarget = onSetGemTarget)
            }
            StatusCard(state, replayName)
            if (showDebug) {
                DebugCard(state, onForceFlatWall, onExpectCells)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.solved) SolvedBanner(state)

            // Confirmation that the capture landed, and where. Worth the line: the whole
            // value of a capture is realised hours later at a desk, so the one chance to
            // notice it silently wrote nothing is while still standing at the wall.
            val captureMessage = when {
                state.liveTerminal -> state.terminalCaptureMessage
                state.liveGems -> state.gemCaptureMessage
                else -> null
            }
            captureMessage?.let { Text(it, color = Color.White, fontSize = 11.sp) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Rescan and New wall both act on the accumulated canvas and the wall
                // fit. The live modes have neither -- every frame is read from scratch --
                // so they would be two buttons that do nothing, taking room from a view
                // of the wall.
                if (!state.livePoseFree) {
                    Button(onClick = onRestartScan) { Text("Rescan") }
                    Button(onClick = onRestartAll) { Text("New wall") }
                }
                // The live modes' answer to Record, in the place the buttons it replaces
                // would have been, because it is the same job: do not leave the room
                // without evidence. It has to be a button and not only a broadcast -- a
                // capture is worth having while the phone is pointed at the wall, which
                // is exactly when nobody is at a terminal.
                if (state.livePoseFree) {
                    val left = if (state.liveTerminal) {
                        state.terminalCaptureRemaining
                    } else {
                        state.gemCaptureRemaining
                    }
                    Button(onClick = onCapture) {
                        Text(if (left > 0) "Capturing $left" else "Capture")
                    }
                }
            }

            // Present in every mode, unlike the row below it. The log and the export
            // are the two things that do not care which pipeline is running, and they
            // are the two that make the difference between coming back from the room
            // with a diagnosis and coming back with a description.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onToggleLogging,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLogging) {
                            Color(0xFFB3261E)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Text(if (isLogging) "Stop log" else "Log")
                }
                Button(onClick = onExport) { Text("Export") }
                // The elapsed time and the byte count together, because either alone has
                // a failure that reads as success -- a timer climbing against a byte
                // count that is not means the log is not actually being written.
                logSummary?.let {
                    Text(it, color = Color.White, fontSize = 11.sp)
                }
            }

            // Recording and replay are ARCore's, and the whole point of them is
            // reproducing tracked geometry. There is none here, so in the live modes the
            // row is simply absent.
            if (!state.livePoseFree) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onToggleRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFB3261E) else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(if (isRecording) "Stop recording" else "Record")
                }
                Button(onClick = onOpenLastRecording) { Text("Replay last") }
                Button(onClick = onPickVideo) { Text("Open video") }
                if (state.sourceKind != FrameSource.Kind.LIVE) {
                    Button(onClick = onReturnToLive) { Text("Live") }
                }
            }
            }

            // The camera dials, last and hard right: the button is the bottom corner of
            // the screen and the card opens upward out of it. Everything else in this
            // column is left-aligned and short, so nothing can collide with it at any
            // width -- which is the reason it is a line of its own rather than the tail
            // of the scan row above, where a canvas mode would have run out of room in
            // portrait and clipped it.
            if (state.isSelfLitWallMode || showDebug) {
                if (showCamera) {
                    CameraCard(
                        state = state,
                        modifier = Modifier.align(Alignment.End).widthIn(max = 400.dp),
                        verbose = showDebug,
                        onNudgeExposure = onNudgeExposure,
                        onSetManual = onSetCameraManual,
                        onSetLocks = onSetCameraLocks,
                        onLedPreset = onLedPreset,
                        onAutoExposure = onAutoExposure,
                        onResetCamera = onResetCamera,
                    )
                }
                CameraToggle(
                    state = state,
                    open = showCamera,
                    modifier = Modifier.align(Alignment.End),
                    onToggle = { showCamera = !showCamera },
                )
            }
        }
    }
    }
}

/**
 * Opens and closes the camera card, and says the one thing the card used to be on screen
 * unprompted to say.
 *
 * That unprompted place was earned: on a self-lit wall the exposure decides whether the
 * puzzle is in the image at all. But the card is tall, and in landscape on the terminal
 * wall it covered the top two rows of displays -- so it was hiding the thing it exists to
 * make readable. Folding it behind a button only works if the number a user actually acts
 * on comes with the button, which is what this is: the shutter the sensor delivered, and
 * a mark when the sensor is ignoring what it was asked for.
 */
@Composable
private fun CameraToggle(
    state: ScanPipeline.UiState,
    open: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    // First token of the reported settings, which is the shutter -- "1/100s iso1084 ev0
    // ae:on" becomes "1/100s". Taken from what the frame metadata says the sensor did
    // rather than from what was asked for, for the reason the card gives two lines to.
    val shutter = state.cameraActual
        ?.substringBefore(' ')
        ?.takeIf { it.isNotBlank() && it != "-" }
    val ignoring = state.cameraHonoured == false
    Button(
        onClick = onToggle,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                ignoring -> Color(0xFFB3261E)
                open -> Color(0xFF39424C)
                else -> MaterialTheme.colorScheme.primary
            },
        ),
    ) {
        Text(
            when {
                ignoring -> "Camera !"
                shutter != null -> shutter
                else -> "Camera"
            },
            fontSize = 13.sp,
        )
    }
}

/**
 * The game-mode flyout, opened by swiping in from the left edge.
 *
 * The list comes from the registry rather than from here, so a new solver appears in
 * the menu by being registered and nothing else. A hardcoded list would be one more
 * place to forget.
 *
 * It is also where the session's settings live -- currently the debug panel. Anything
 * chosen once and then left alone belongs here rather than on the HUD, which is read
 * over the top of the wall being scanned and has no room for buttons that are not
 * about this frame.
 *
 * Every entry pins a mode. Identification is still what the pipeline does until one is
 * picked, but it is not offered here: a menu item that hands the choice back to the
 * evidence reads as a mode in its own right, and one that is never the one wanted.
 */
@Composable
private fun GameModeDrawer(
    modes: List<ScanPipeline.PuzzleMode>,
    state: ScanPipeline.UiState,
    showDebug: Boolean,
    guideRoom: StrategyRoom?,
    onSelect: (String?) -> Unit,
    onSelectGuide: (StrategyRoom) -> Unit,
    onToggleDebug: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            Text(
                "Game mode",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 12.dp),
            )

            for (mode in modes) {
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(mode.displayName)
                            val note = when {
                                mode.needsColour -> "needs colour capture"
                                mode.needsLiveCamera -> "live camera -- no AR, no replay"
                                else -> null
                            }
                            if (note != null) {
                                Text(note, fontSize = 12.sp, color = Color(0xFF9AA6B2))
                            }
                        }
                    },
                    selected = guideRoom == null && state.pinnedPuzzleId == mode.id,
                    onClick = { onSelect(mode.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }

            // Not from the registry, because they are not solvers: these rooms' stages
            // are fixed, so the answers are worked out from a transcription and the
            // camera stays off. They sit with the scanning modes all the same, since
            // picking a room is one decision however the app then goes about it.
            for (room in StrategyRoom.entries) {
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(room.displayName)
                            Text(
                                when (room) {
                                    StrategyRoom.STRATEGY -> "no camera -- the room's solutions, split between players"
                                    StrategyRoom.GRIDLOCK -> "no camera -- levels 6 to 10 so far, split between players"
                                },
                                fontSize = 12.sp,
                                color = Color(0xFF9AA6B2),
                            )
                        }
                    },
                    selected = guideRoom == room,
                    onClick = { onSelectGuide(room) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))

            // In the menu rather than on the HUD, where it used to be a button. It is a
            // setting -- flipped once and then lived with -- and the HUD is for things
            // read every frame with a phone held at arm's length. A permanent button for
            // an occasional decision costs a strip of wall on every scan.
            NavigationDrawerItem(
                label = {
                    Column {
                        Text("Debug panel")
                        Text(
                            if (showDebug) {
                                "on -- timings, coverage, wall and grid overrides"
                            } else {
                                "off"
                            },
                            fontSize = 12.sp,
                            color = Color(0xFF9AA6B2),
                        )
                    }
                },
                selected = showDebug,
                onClick = onToggleDebug,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

@Composable
private fun StatusCard(state: ScanPipeline.UiState, replayName: String?) {
    Panel {
        Text(
            state.status,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        // The wall line is about geometry the live paths do not have, and the progress
        // bar counts what is currently in frame -- which is always all of it, so it is
        // permanently full and says nothing. Both are dropped there rather than shown
        // saying nothing, because screen space over a wall being scanned is the scarcest
        // thing in this app.
        if (!state.livePoseFree || replayName != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                state.wallDescription + (replayName?.let { "  ·  replay: $it" } ?: ""),
                color = Color(0xFFB9C4CF),
                fontSize = 13.sp,
            )
        }

        if (state.cellCount > 0 && !state.livePoseFree) {
            Spacer(Modifier.height(8.dp))
            val progress = state.cellsRead.toFloat() / state.cellCount
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (state.solved) Color(0xFF33DD77) else Color(0xFF4C8DFF),
                trackColor = Color(0x33FFFFFF),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.puzzleName ?: "puzzle"} ${state.gridSummary ?: ""}  ·  " +
                    "${state.cellsRead}/${state.cellCount} cells",
                color = Color(0xFFB9C4CF),
                fontSize = 12.sp,
            )
        }

        if (state.replayFinished) {
            Spacer(Modifier.height(6.dp))
            Text("Replay finished", color = Color(0xFFFFD166), fontSize = 13.sp)
        }
    }
}

/**
 * The payoff. Called out separately from the status line because "solved before the
 * scan finished" is the single most interesting thing this app can tell you, and it
 * would be lost in a paragraph of status text.
 */
@Composable
private fun SolvedBanner(state: ScanPipeline.UiState) {
    // Gems has no "solved" moment -- it is a live filter, and its answer is a count
    // that keeps moving as the user pans. Giving it the same green "Solved early"
    // banner as a sudoku would claim something that is not true and bury the one
    // number it actually has to report. So a solution that names itself gets to.
    if (state.solutionLabel.isNotEmpty()) {
        Panel(background = Color(0xCC0B2E3D)) {
            Text(
                state.solutionLabel,
                color = Color(0xFF9AD8F0),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        return
    }
    Panel(background = Color(0xCC0B3D2E)) {
        Text(
            if (state.solvedEarly) "Solved early" else "Solved",
            color = Color(0xFF7BE8A8),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.solvedEarly) {
                "The clues read so far already have exactly one answer, so the rest of " +
                    "the wall cannot change it."
            } else {
                "Read the whole board and deduced the answer."
            },
            color = Color(0xFFCDEBDB),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DebugCard(
    state: ScanPipeline.UiState,
    onForceFlatWall: (Float) -> Unit,
    onExpectCells: (Int?) -> Unit,
) {
    Panel {
        Mono("frame        ${"%.2f".format(state.frameMillis)} ms")
        Mono("solve step   ${"%.2f".format(state.solveMillis)} ms")
        Mono("coverage     ${"%.1f".format(state.coverageFraction * 100)} %")
        Mono("frames used  ${state.accumulatedFrames}")
        Mono("source       ${state.sourceKind ?: "none"}")
        Mono("wall locked  ${state.wallConverged}")
        state.wallIssue?.let { Mono("blocked      $it") }
        state.readStats?.takeIf { it.isNotEmpty() }?.let { Mono("read         $it") }
        Spacer(Modifier.height(8.dp))
        // Bypasses wall fitting, which is the only stage that needs a real wall. Lets the
        // accumulator, detector and solver be tested against a puzzle on any flat thing.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("flat wall:", color = Color(0xFFB9C4CF), fontSize = 12.sp)
            TextButton(onClick = { onForceFlatWall(0.5f) }) { Text("0.5m", fontSize = 12.sp) }
            TextButton(onClick = { onForceFlatWall(1f) }) { Text("1m", fontSize = 12.sp) }
            TextButton(onClick = { onForceFlatWall(2f) }) { Text("2m", fontSize = 12.sp) }
            TextButton(onClick = { onForceFlatWall(0f) }) { Text("off", fontSize = 12.sp) }
        }
        // Supplies the grid size rather than making the detector infer where the grid
        // stops -- the fragile half of detection when the puzzle shares a wall with
        // margins, frames or window chrome.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("expect:", color = Color(0xFFB9C4CF), fontSize = 12.sp)
            TextButton(onClick = { onExpectCells(4) }) { Text("4x4", fontSize = 12.sp) }
            TextButton(onClick = { onExpectCells(9) }) { Text("9x9", fontSize = 12.sp) }
            TextButton(onClick = { onExpectCells(16) }) { Text("16x16", fontSize = 12.sp) }
            TextButton(onClick = { onExpectCells(null) }) { Text("auto", fontSize = 12.sp) }
        }
        if (state.wallForced) {
            Text(
                "wall is FORCED -- geometry is assumed, not measured",
                color = Color(0xFFFFD166),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, color = Color(0xFFDDE5EC), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun Panel(
    background: Color = Color(0xCC101418),
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Column { content() }
    }
}
