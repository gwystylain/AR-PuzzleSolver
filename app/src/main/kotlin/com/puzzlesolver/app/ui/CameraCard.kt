package com.puzzlesolver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.pipeline.ScanPipeline

/**
 * The camera dials, and -- more importantly -- what the camera did with them.
 *
 * Requested and actual are shown on separate lines because they disagree often enough
 * that assuming otherwise wastes a session. A device may clamp exposure compensation to
 * its own range, refuse a manual exposure time, or have ARCore re-issue the capture
 * request with values of its own; from behind the lens all three look like "still too
 * bright". The actual line comes from the frame metadata, so it is what the sensor did,
 * not what we asked for.
 *
 * On screen without being asked for whenever a self-lit wall is in play, because there
 * the exposure is not a refinement -- it decides whether the puzzle is in the image at
 * all. See docs/GEM_PUZZLE.md for the measurement behind that.
 */
@Composable
fun CameraCard(
    state: ScanPipeline.UiState,
    /** Whether the debug toggle is on; adds the lines only a developer wants. */
    verbose: Boolean = false,
    onNudgeExposure: (Boolean) -> Unit,
    onSetManual: (Boolean) -> Unit,
    onSetLocks: (Boolean, Boolean) -> Unit,
    onLedPreset: () -> Unit,
    onAutoExposure: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xCC101418), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Camera",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                state.exposureHint.takeIf { it >= 0f }?.let {
                    Text(
                        "  ${(it * 100).toInt()}% of the board has no colour",
                        color = if (it > 0.35f) Color(0xFFFF8A65) else Color(0xFF7BE8A8),
                        fontSize = 12.sp,
                    )
                }
            }

            if (!state.cameraControllable) {
                Spacer(Modifier.height(4.dp))
                // Worth saying rather than showing dead buttons: with no control the
                // whole feature is absent, and the user needs to know that before
                // blaming the exposure for a scan going nowhere. Careful about *whose*
                // limitation it is -- the phone will take a manual exposure quite
                // happily, ARCore is what will not pass it on.
                Text(
                    state.cameraStatus
                        ?: "ARCore owns the camera -- relaunch with --ez sharedcam true to try taking it",
                    color = Color(0xFFFFD166),
                    fontSize = 12.sp,
                )
                return@Column
            }

            Spacer(Modifier.height(2.dp))
            // One line normally. The exposure asked for and the exposure delivered are
            // the same thing almost always, and when they are the same there is nothing
            // to compare -- so the second value only appears when it disagrees, which is
            // exactly when someone needs to see it.
            val actual = state.cameraActual ?: "-"
            if (state.cameraHonoured == false) {
                Mono("asked  ${state.cameraRequest ?: "-"}")
                Text(
                    "camera is ignoring it -- running $actual",
                    color = Color(0xFFFF8A65),
                    fontSize = 12.sp,
                )
            } else {
                Mono(actual)
            }

            if (verbose) {
                state.cameraCapabilities?.let { Mono("can    $it") }
                state.autoExposure?.let { Mono(it) }
                state.cameraStatus?.let { Mono("state  $it") }
                state.readStats?.let { Mono("frames $it") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Tiny("darker") { onNudgeExposure(true) }
                Tiny("brighter") { onNudgeExposure(false) }
                Tiny(if (state.cameraManual) "auto" else "manual") {
                    onSetManual(!state.cameraManual)
                }
                Tiny("LED wall", highlighted = true, onClick = onLedPreset)
            }
            // Locks, the auto-exposure loop and reset are set once and forgotten -- and
            // two of them are only meaningful if you know what 3A is. Behind the toggle.
            if (verbose) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Tiny("AE ${if (state.aeLocked) "lock" else "free"}") {
                        onSetLocks(!state.aeLocked, state.awbLocked)
                    }
                    Tiny("AWB ${if (state.awbLocked) "lock" else "free"}") {
                        onSetLocks(state.aeLocked, !state.awbLocked)
                    }
                    Tiny("auto-ev on") { onAutoExposure(true) }
                    Tiny("reset", onClick = onResetCamera)
                }
            }
        }
    }
}

@Composable
private fun Tiny(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValuesTiny) {
        Text(
            label,
            color = if (highlighted) Color(0xFF7BE8A8) else Color(0xFFDDE5EC),
            fontSize = 12.sp,
        )
    }
}

private val PaddingValuesTiny = PaddingValues(
    horizontal = 8.dp,
    vertical = 2.dp,
)

@Composable
private fun Mono(text: String) {
    Text(text, color = Color(0xFFB9C4CF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}
