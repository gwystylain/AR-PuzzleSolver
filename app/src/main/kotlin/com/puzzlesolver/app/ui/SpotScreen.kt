package com.puzzlesolver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.app.pipeline.ScanPipeline
import com.puzzlesolver.app.spot.SpotController
import com.puzzlesolver.core.puzzle.spot.SpotFinder

/**
 * Spot: six photos of the screen, then every one of them back with what changed boxed.
 *
 * While photos are being taken the camera is behind this, and the only things over it are
 * the screen's outline -- green when the app can see the whole screen, so the user knows
 * the next photo will count -- the six slots filling up, and the shutter at the bottom
 * where the thumb is. Once the sixth is in, the camera is covered by the answer.
 *
 * **Reset is always in the same place**, top right, whatever the round is doing: halfway
 * through the photos, while they are being compared, or over the answer. A round goes
 * wrong in the room for reasons the app cannot see -- a photo of the wrong picture, the
 * room starting again -- and starting over has to be one tap away at every moment.
 */
@Composable
fun SpotScreen(
    state: SpotController.State,
    camera: ScanPipeline.UiState,
    onTakePhoto: () -> Unit,
    onReset: () -> Unit,
) {
    when (state.phase) {
        SpotController.Phase.DONE -> Results(state, onReset)
        else -> Taking(state, camera, onTakePhoto, onReset)
    }
}

@Composable
private fun Taking(
    state: SpotController.State,
    camera: ScanPipeline.UiState,
    onTakePhoto: () -> Unit,
    onReset: () -> Unit,
) {
    val taken = state.thumbnails.size
    val finding = state.phase == SpotController.Phase.FINDING
    val seen = camera.spotOutline.size == 8
    Box(Modifier.fillMaxSize()) {
        if (!finding) ScreenOutline(camera.spotOutline, Modifier.fillMaxSize())

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpotPanel {
                Header(
                    title = if (finding) "Spot  ·  comparing" else "Spot  ·  photo ${minOf(taken + 1, state.target)} of ${state.target}",
                    onReset = onReset,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Fit the whole screen in the frame and take a photo each time the picture changes.",
                    color = Color(0xFFB9C4CF),
                    fontSize = 13.sp,
                )
                if (!finding) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (seen) "Screen in view" else "Looking for the screen",
                        color = if (seen) OUTLINE else Color(0xFFFFD166),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                state.message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp)
                }
            }
        }

        if (finding) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE6101418), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BOX)
                    Spacer(Modifier.height(12.dp))
                    Text("Finding the differences", color = Color.White, fontSize = 16.sp)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Slots(state.thumbnails, state.target)
            Shutter(
                enabled = !finding && !state.busy,
                busy = state.busy,
                onClick = onTakePhoto,
            )
        }
    }
}

/** The six photos of the round: each one taken so far, then an empty numbered slot. */
@Composable
private fun Slots(thumbnails: List<ImageBitmap>, target: Int) {
    Row(
        Modifier
            .background(Color(0xCC101418), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until target) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .width(52.dp)
                    .aspectRatio(1.75f)
                    .clip(shape)
                    .border(1.dp, if (i < thumbnails.size) OUTLINE else Color(0x55FFFFFF), shape),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = thumbnails.getOrNull(i)
                if (thumb != null) {
                    Image(thumb, contentDescription = "Photo ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("${i + 1}", color = Color(0x88FFFFFF), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, if (enabled) Color.White else Color(0x66FFFFFF), CircleShape)
            .padding(8.dp)
            .background(if (enabled) Color.White else Color(0x44FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color(0xFF101418), strokeWidth = 3.dp)
    }
}

/**
 * Where the app sees the screen, drawn over the camera. Dark under bright, like the other
 * overlays, because a screen in a ring of neon swallows a thin line of one colour.
 */
@Composable
private fun ScreenOutline(points: List<Float>, modifier: Modifier) {
    if (points.size != 8) return
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(points[0], points[1])
            for (i in 1 until 4) lineTo(points[i * 2], points[i * 2 + 1])
            close()
        }
        drawPath(path, Color(0xCC000000), style = Stroke(width = 10f))
        drawPath(path, OUTLINE, style = Stroke(width = 4f))
    }
}

@Composable
private fun Results(state: SpotController.State, onReset: () -> Unit) {
    var zoomed by remember { mutableStateOf<Int?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            SpotPanel {
                Header(title = "Spot  ·  the differences", onReset = onReset)
                Spacer(Modifier.height(4.dp))
                Text(state.status, color = Color(0xFFB9C4CF), fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.results) { i, shown ->
                    ResultCard(i, shown) { if (shown.image != null) zoomed = i }
                }
            }
        }

        // Tapped open to the full width, for a difference too small to make out in the
        // grid; tapped anywhere to close.
        zoomed?.let { i ->
            val shown = state.results.getOrNull(i)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF00B0E11))
                    .clickable { zoomed = null }
                    .systemBarsPadding()
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (shown?.image != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Photo ${i + 1}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        BoxedImage(shown.image, shown.differences, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Tap to close", color = Color(0xFF6F7B87), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(index: Int, shown: SpotController.Shown, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF151A20), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Photo ${index + 1}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    shown.image == null -> "screen not found"
                    shown.differences.isEmpty() -> "no difference -- the original picture"
                    shown.differences.size == 1 -> "1 difference"
                    else -> "${shown.differences.size} possible differences"
                },
                color = if (shown.image != null && shown.differences.isNotEmpty()) BOX else Color(0xFF9AA6B2),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (shown.image != null) {
            BoxedImage(shown.image, shown.differences, Modifier.fillMaxWidth())
        }
    }
}

/** The straightened screen with a box round each difference, strongest boldest. */
@Composable
private fun BoxedImage(image: ImageBitmap, differences: List<SpotFinder.Difference>, modifier: Modifier) {
    Image(
        image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .aspectRatio(image.width.toFloat() / image.height)
            .clip(RoundedCornerShape(8.dp))
            .drawWithContent {
                drawContent()
                differences.forEachIndexed { i, d -> drawDifference(d, strongest = i == 0) }
            },
    )
}

private fun DrawScope.drawDifference(d: SpotFinder.Difference, strongest: Boolean) {
    // Round the thing, not on it: a box traced on the difference's own edge would cover
    // the very pixels the user is meant to look at.
    val pad = size.width * BOX_PADDING
    val left = d.left * size.width - pad
    val top = d.top * size.height - pad
    val topLeft = Offset(left, top)
    val box = Size((d.right - d.left) * size.width + 2 * pad, (d.bottom - d.top) * size.height + 2 * pad)
    val width = if (strongest) 4.dp.toPx() else 2.5.dp.toPx()
    drawRect(Color(0xCC000000), topLeft, box, style = Stroke(width = width + 3.dp.toPx()))
    drawRect(if (strongest) BOX else BOX.copy(alpha = 0.7f), topLeft, box, style = Stroke(width = width))
}

@Composable
private fun Header(title: String, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onReset,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
        ) {
            Text("Reset", fontSize = 14.sp)
        }
    }
}

@Composable
private fun SpotPanel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xCC101418), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Column { content() }
    }
}

/** The screen outline: the same green the terminal wall uses for "here". */
private val OUTLINE = Color(0xFF39FF88)

/**
 * The difference boxes: magenta, the one colour neither sample picture uses anywhere and
 * not the green the room itself rings its differences in.
 */
private val BOX = Color(0xFFFF3DCB)

/** Gap between a difference and its box, as a fraction of the screen's width. */
private const val BOX_PADDING = 0.012f
