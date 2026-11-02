package com.linuxdroid.app.wayland

import android.app.Activity
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display

enum class DisplayRoutingMode {
    INTERNAL_LAPTOP,
    DEX_OR_FREEFORM_WINDOW,
    EXTERNAL_MONITOR_HOTSWAP,
}

object DeXEnvironmentDetector {
    fun evaluate(
        semDesktopModeEnabled: Int,
        uiModeType: Int,
        inMultiWindowMode: Boolean,
        activityDisplayId: Int,
        hasExternalPresentationDisplay: Boolean,
    ): DisplayRoutingMode {
        val isDeX = semDesktopModeEnabled == 1 ||
            uiModeType == Configuration.UI_MODE_TYPE_DESK ||
            inMultiWindowMode ||
            activityDisplayId != Display.DEFAULT_DISPLAY
        if (isDeX) {
            return DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW
        }
        if (hasExternalPresentationDisplay) {
            return DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP
        }
        return DisplayRoutingMode.INTERNAL_LAPTOP
    }

    fun detectForActivity(activity: Activity): Pair<DisplayRoutingMode, Display?> {
        val cfg = activity.resources.configuration
        val semDesktop = readSemDesktopModeEnabled(cfg)
        val uiModeType = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK
        @Suppress("DEPRECATION")
        val activityDisplayId = activity.windowManager.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
        val dm = activity.getSystemService(DisplayManager::class.java)
        val externalDisplay = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }

        val mode = evaluate(
            semDesktopModeEnabled = semDesktop,
            uiModeType = uiModeType,
            inMultiWindowMode = activity.isInMultiWindowMode,
            activityDisplayId = activityDisplayId,
            hasExternalPresentationDisplay = externalDisplay != null,
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
