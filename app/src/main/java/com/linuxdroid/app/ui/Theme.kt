package com.linuxdroid.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SlateBackground = Color(0xFF0B1117)
private val SlateSurface = Color(0xFF131B24)
private val SlateSurfaceVariant = Color(0xFF1B2633)
private val CyanPrimary = Color(0xFF38BDF8)
private val CyanOnPrimary = Color(0xFF04131D)
private val SlateOnSurface = Color(0xFFE5EDF5)
private val SlateMuted = Color(0xFF94A3B8)
private val ErrorRed = Color(0xFFF87171)

/** Fixed dark scheme used by the in-session overlays and terminal, which sit on top of the desktop. */
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

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00658E),
    secondary = Color(0xFF4F616E),
    tertiary = Color(0xFF8B5000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DD0FF),
    secondary = Color(0xFFB6C9D8),
    tertiary = Color(0xFFFFB86B),
)

@Composable
fun LinuxDroidTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val colorScheme = if (!dynamic) {
        LinuxDroidColorScheme
    } else {
        val dark = isSystemInDarkTheme()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (dark) DarkScheme else LightScheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
