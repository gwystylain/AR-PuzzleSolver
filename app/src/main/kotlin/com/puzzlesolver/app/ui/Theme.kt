package com.puzzlesolver.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark only, on purpose. The UI floats over a live camera feed, so a light theme
 * would fight the image for attention and wash out the AR overlay.
 */
private val scheme = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFF33DD77),
    background = Color(0xFF0B0E11),
    surface = Color(0xFF101418),
)

@Composable
fun PuzzleSolverTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
