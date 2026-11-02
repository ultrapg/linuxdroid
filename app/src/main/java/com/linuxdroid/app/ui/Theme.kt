package com.linuxdroid.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SlateBackground = Color(0xFF0B1117)
private val SlateSurface = Color(0xFF131B24)
private val SlateSurfaceVariant = Color(0xFF1B2633)
private val CyanPrimary = Color(0xFF38BDF8)
private val CyanOnPrimary = Color(0xFF04131D)
private val SlateOnSurface = Color(0xFFE5EDF5)
private val SlateMuted = Color(0xFF94A3B8)
private val ErrorRed = Color(0xFFF87171)

private val LinuxDroidColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = CyanOnPrimary,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF032016),
    background = SlateBackground,
    onBackground = SlateOnSurface,
    surface = SlateSurface,
    onSurface = SlateOnSurface,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = SlateMuted,
    outline = Color(0xFF334155),
    error = ErrorRed,
)

@Composable
fun LinuxDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LinuxDroidColorScheme,
        content = content,
    )
}
