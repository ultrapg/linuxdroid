package com.linuxdroid.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

enum class AppTheme(val id: String, val label: String, val description: String) {
    DARK("dark", "Dark", "Deep slate with cyan accents"),
    AMOLED("amoled", "AMOLED Black", "True #000000 backgrounds and cards to switch off OLED pixels"),
    LIGHT("light", "Light", "Bright surfaces"),
    SYSTEM("system", "Follow System", "Match your phone's light or dark mode"),
    DYNAMIC("dynamic", "Dynamic Colors", "Use your wallpaper colors (Android 12+)");

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: DARK
    }
}

/** Shared theme selection so the dashboard, session overlays and terminal always match. */
object ThemeState {
    val current = androidx.compose.runtime.mutableStateOf(AppTheme.DARK)
}

private val AmoledScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = CyanOnPrimary,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF032016),
    background = Color.Black,
    onBackground = SlateOnSurface,
    surface = Color.Black,
    onSurface = SlateOnSurface,
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = SlateMuted,
    surfaceContainer = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF0D0D0D),
    surfaceContainerHighest = Color(0xFF141414),
    outline = Color(0xFF1F1F1F),
    error = ErrorRed,
)

@Composable
fun LinuxDroidTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val selected = ThemeState.current.value
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (selected) {
        AppTheme.DARK -> LinuxDroidColorScheme
        AppTheme.AMOLED -> AmoledScheme
        AppTheme.LIGHT -> LightScheme
        AppTheme.SYSTEM -> if (systemDark) LinuxDroidColorScheme else LightScheme
        AppTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            if (systemDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (systemDark) DarkScheme else LightScheme
        }
    }
    val view = LocalView.current
    val lightBackground = colorScheme.background.luminance() > 0.5f
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = lightBackground
                controller.isAppearanceLightNavigationBars = lightBackground
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
