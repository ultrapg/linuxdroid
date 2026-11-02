package com.linuxdroid.app

import android.content.res.Configuration
import android.view.Display
import com.linuxdroid.app.wayland.DeXEnvironmentDetector
import com.linuxdroid.app.wayland.DisplayRoutingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DeXEnvironmentDetectorTest {
    @Test
    fun normalPhoneWithoutExternalDisplaySelectsInternalLaptop() {
        val mode = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = false,
        )
        assertEquals(DisplayRoutingMode.INTERNAL_LAPTOP, mode)
    }

    @Test
    fun samsungDexDoesNotTriggerPresentationHijack() {
        val mode = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 1,
            uiModeType = Configuration.UI_MODE_TYPE_DESK,
            inMultiWindowMode = true,
            activityDisplayId = 2,
            hasExternalPresentationDisplay = true,
        )
        assertEquals(DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW, mode)
    }

    @Test
    fun multiWindowFreeformSelectsDexOrFreeformWindow() {
        val mode = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
            inMultiWindowMode = true,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = false,
        )
        assertEquals(DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW, mode)
    }

    @Test
    fun usbCHdmiMonitorOutsideDexSelectsExternalMonitorHotswap() {
        val mode = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = true,
        )
        assertEquals(DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP, mode)
    }
}
