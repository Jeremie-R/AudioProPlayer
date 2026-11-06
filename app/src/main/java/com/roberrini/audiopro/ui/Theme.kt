package com.roberrini.audiopro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFE8B04B)
val OnlineGreen = Color(0xFF6FCF97)
val Warn = Color(0xFFF2C94C)
val Muted = Color(0xFF8A8D96)

private val colors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF231A05),
    primaryContainer = Color(0xFF3A2E12),
    onPrimaryContainer = Color(0xFFFFE3AE),
    secondary = Color(0xFFC9C4B8),
    background = Color(0xFF15161A),
    onBackground = Color(0xFFECEAE4),
    surface = Color(0xFF15161A),
    onSurface = Color(0xFFECEAE4),
    surfaceVariant = Color(0xFF23252D),
    onSurfaceVariant = Color(0xFFB4B2AC),
    surfaceContainer = Color(0xFF1F2128),
    surfaceContainerHigh = Color(0xFF272A32),
    outline = Color(0xFF3A3D46),
    error = Color(0xFFFF8A80),
)

@Composable
fun AudioProTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
