package io.github.astromg01.clearmic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClearMicDarkColors = darkColorScheme(
    primary = Color(0xFF8FD8FF),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFFB5C9D8),
    tertiary = Color(0xFFC6C2EA),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF111820),
    surfaceVariant = Color(0xFF1A232D),
    onBackground = Color(0xFFE8EEF3),
    onSurface = Color(0xFFE8EEF3),
)

@Composable
fun ClearMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClearMicDarkColors,
        content = content,
    )
}
