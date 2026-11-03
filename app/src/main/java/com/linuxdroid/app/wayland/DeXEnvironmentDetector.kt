package com.linuxdroid.app.wayland

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display
import com.linuxdroid.app.session.ExternalDisplayMode
import com.linuxdroid.app.session.SessionPrefs

enum class DisplayRoutingMode {
    INTERNAL_LAPTOP,
    DEX_OR_FREEFORM_WINDOW,
    EXTERNAL_MONITOR_HOTSWAP,
    WAITING_FOR_EXTERNAL_MONITOR,
}

object DeXEnvironmentDetector {
    fun evaluate(
        semDesktopModeEnabled: Int,
        uiModeType: Int,
        inMultiWindowMode: Boolean,
        activityDisplayId: Int,
        hasExternalPresentationDisplay: Boolean,
        externalMode: ExternalDisplayMode = ExternalDisplayMode.AUTO_EXTERNAL,
    ): DisplayRoutingMode {
        // When the Activity window itself is already placed on an external display (Samsung DeX or Android Desktop Mode),
        // rendering inside the Activity window already targets that external display directly.
        if (activityDisplayId != Display.DEFAULT_DISPLAY) {
            return DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW
        }

        // External Screen Only mode: always route via Presentation to the external screen,
        // or wait cleanly if no external screen is connected yet (ignoring dock UI_MODE_TYPE_DESK).
        if (externalMode == ExternalDisplayMode.EXTERNAL_ONLY) {
            return if (hasExternalPresentationDisplay) {
                DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP
            } else {
                DisplayRoutingMode.WAITING_FOR_EXTERNAL_MONITOR
            }
        }

        val isLocalDeXOrMultiWindow = semDesktopModeEnabled == 1 || inMultiWindowMode
        if (isLocalDeXOrMultiWindow) {
            return DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW
        }

        if (externalMode == ExternalDisplayMode.AUTO_EXTERNAL && hasExternalPresentationDisplay) {
            return DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP
        }

        // On standalone Samsung DeX tablets (`UI_MODE_TYPE_DESK` with no separate external Presentation display)
        if (uiModeType == Configuration.UI_MODE_TYPE_DESK && !hasExternalPresentationDisplay) {
            return DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW
        }

        return DisplayRoutingMode.INTERNAL_LAPTOP
    }

    fun findExternalDisplay(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
        if (presentation != null) return presentation

        return dm.displays?.firstOrNull { disp ->
            disp.displayId != Display.DEFAULT_DISPLAY &&
                disp.state != Display.STATE_OFF &&
                (disp.flags and Display.FLAG_PRIVATE) == 0
        }
    }

    fun describeDisplay(display: Display): String {
        val mode = display.mode
        val name = display.name?.takeIf { it.isNotBlank() } ?: "External Monitor"
        return "$name (${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.toInt()}Hz)"
    }

    fun detectForActivity(
        activity: Activity,
        overrideExternalMode: ExternalDisplayMode? = null,
    ): Pair<DisplayRoutingMode, Display?> {
        val cfg = activity.resources.configuration
        val semDesktop = readSemDesktopModeEnabled(cfg)
        val uiModeType = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK
        @Suppress("DEPRECATION")
        val activityDisplayId = activity.windowManager.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
        val externalDisplay = findExternalDisplay(activity)
        val externalMode = overrideExternalMode ?: SessionPrefs.externalDisplayMode(activity)

        val mode = evaluate(
            semDesktopModeEnabled = semDesktop,
            uiModeType = uiModeType,
            inMultiWindowMode = activity.isInMultiWindowMode,
            activityDisplayId = activityDisplayId,
            hasExternalPresentationDisplay = externalDisplay != null,
            externalMode = externalMode,
        )
        return mode to if (mode == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP) externalDisplay else null
    }

    private fun readSemDesktopModeEnabled(cfg: Configuration): Int = try {
        val field = cfg.javaClass.getField("semDesktopModeEnabled")
        val value = field.getInt(cfg)
        val enabledConst = cfg.javaClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
        if (value == enabledConst) 1 else 0
    } catch (_: Throwable) {
        0
    }
}
