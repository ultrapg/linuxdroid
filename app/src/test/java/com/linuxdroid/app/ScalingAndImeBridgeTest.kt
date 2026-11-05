package com.linuxdroid.app

import com.linuxdroid.app.input.ImeBridge
import com.linuxdroid.app.input.ImeCaptureView
import com.linuxdroid.app.runtime.DesktopCatalog
import com.linuxdroid.app.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalingAndImeBridgeTest {
    @Test
    fun kdeWaylandLogicalGeometryHalvesLogicalSizeAt200PercentWhilePreservingFullPixels() {
        val geom = SessionPrefs.computeCompositorGeometry(
            pixelWidth = 2400,
            pixelHeight = 1080,
            explicitScale = 2.0f,
            usesWaylandLogicalScale = true,
        )
        assertEquals(2400, geom.pixelWidth)
        assertEquals(1080, geom.pixelHeight)
        assertEquals(1200, geom.compositorWidth)
        assertEquals(540, geom.compositorHeight)
        assertEquals(2.0f, geom.compositorScale, 0.001f)
        assertEquals(2.0f, geom.effectiveScale, 0.001f)
    }

    @Test
    fun xfceXwaylandGeometryKeepsPhysicalCompositorSizeAndExportsEffectiveScale() {
        val geom = SessionPrefs.computeCompositorGeometry(
            pixelWidth = 2400,
            pixelHeight = 1080,
            explicitScale = 2.0f,
            usesWaylandLogicalScale = false,
        )
        assertEquals(2400, geom.pixelWidth)
        assertEquals(1080, geom.pixelHeight)
        assertEquals(2400, geom.compositorWidth)
        assertEquals(1080, geom.compositorHeight)
        assertEquals(1.0f, geom.compositorScale, 0.001f)
        assertEquals(2.0f, geom.effectiveScale, 0.001f)
    }

    @Test
    fun autoHiDpiScaleSelects175PercentOnQhdPlusFlagshipDisplaysWithEvenDimensions() {
        val geom = SessionPrefs.computeCompositorGeometry(
            pixelWidth = 3120,
            pixelHeight = 1440,
            explicitScale = 0f,
            usesWaylandLogicalScale = true,
        )
        assertEquals(1.75f, geom.effectiveScale, 0.001f)
        assertEquals(0, geom.compositorWidth % 2)
        assertEquals(0, geom.compositorHeight % 2)
        assertEquals(2.0f, geom.compositorScale, 0.001f)
        assertEquals(1560, geom.compositorWidth)
        assertEquals(720, geom.compositorHeight)
    }

    @Test
    fun composingDiffAvoidsRedundantBackspacesAndHandlesAutocorrect() {
        assertEquals(0 to "h", ImeCaptureView.computeComposingDiff("", "h"))
        assertEquals(0 to "e", ImeCaptureView.computeComposingDiff("h", "he"))
        assertEquals(0 to "llo", ImeCaptureView.computeComposingDiff("he", "hello"))
        assertEquals(0 to "", ImeCaptureView.computeComposingDiff("hello", "hello"))
        assertEquals(2 to "he", ImeCaptureView.computeComposingDiff("teh", "the"))
        assertEquals(5 to "", ImeCaptureView.computeComposingDiff("hello", ""))
    }

    @Test
    fun parseImeStateJsonSupportsActiveAndLegacyTextFieldKeys() {
        val activeState = ImeBridge.parseImeStateJson("""{"active":true,"textField":true,"seq":4}""")
        assertNotNull(activeState)
        assertEquals(true to 4, activeState)

        val legacyState = ImeBridge.parseImeStateJson("""{"textField":true,"seq":9}""")
        assertEquals(true to 9, legacyState)

        val inactiveState = ImeBridge.parseImeStateJson("""{"active":false,"textField":false,"seq":10}""")
        assertEquals(false to 10, inactiveState)

        assertNull(ImeBridge.parseImeStateJson(""))
    }

    @Test
    fun fallbackDesktopCatalogUsesChromiumBrowser() {
        for (edition in DesktopCatalog.FALLBACK_EDITIONS) {
            assertTrue(edition.description.contains("Chromium"))
        }
    }
}
