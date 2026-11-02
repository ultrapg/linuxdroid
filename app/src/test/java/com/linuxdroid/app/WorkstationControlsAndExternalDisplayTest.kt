package com.linuxdroid.app

import android.content.res.Configuration
import android.view.Display
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.runtime.CoreDescriptor
import com.linuxdroid.app.runtime.CpuInfo
import com.linuxdroid.app.runtime.CpuTopology
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.session.ExternalDisplayMode
import com.linuxdroid.app.wayland.DeXEnvironmentDetector
import com.linuxdroid.app.wayland.DisplayRoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkstationControlsAndExternalDisplayTest {
    @Test
    fun parseCpuListSpecHandlesRangesAndSingletons() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), CpuInfo.parseCpuListSpec("0-7"))
        assertEquals(listOf(0, 2, 4, 5, 6, 7), CpuInfo.parseCpuListSpec("0,2,4-7"))
        assertEquals(emptyList<Int>(), CpuInfo.parseCpuListSpec(""))
    }

    @Test
    fun selectPreferredCoresPrefersHighestFrequencyPerformanceCores() {
        val cores = listOf(
            CoreDescriptor(0, 1_800_000L),
            CoreDescriptor(1, 1_800_000L),
            CoreDescriptor(2, 1_800_000L),
            CoreDescriptor(3, 1_800_000L),
            CoreDescriptor(4, 2_800_000L),
            CoreDescriptor(5, 2_800_000L),
            CoreDescriptor(6, 2_800_000L),
            CoreDescriptor(7, 3_200_000L),
        )
        val topo = CpuTopology(
            totalCores = 8,
            cores = cores,
            summary = CpuInfo.buildSummary(8, cores),
        )
        assertTrue(topo.summary.contains("3.20 GHz"))
        assertEquals(emptyList<Int>(), topo.selectPreferredCores(0, onlineIndices = (0..7).toSet()))
        assertEquals(emptyList<Int>(), topo.selectPreferredCores(8, onlineIndices = (0..7).toSet()))
        assertEquals(listOf(4, 5, 6, 7), topo.selectPreferredCores(4, onlineIndices = (0..7).toSet()))
        assertEquals(listOf(6, 7), topo.selectPreferredCores(2, onlineIndices = (0..7).toSet()))
    }

    @Test
    fun generateProcStatTextMatchesConfiguredCoreCount() {
        val stat4 = LinuxRuntime.generateProcStatText(4)
        val lines = stat4.trim().lines()
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("cpu  "))
        assertTrue(lines[1].startsWith("cpu0 "))
        assertTrue(lines[4].startsWith("cpu3 "))
    }

    @Test
    fun externalOnlyModeRoutesToMonitorOrWaitsCleanlyWithoutDex() {
        // VLC-style External Screen Only when monitor IS connected (even if dock set UI_MODE_TYPE_DESK!)
        val connected = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_DESK,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = true,
            externalMode = ExternalDisplayMode.EXTERNAL_ONLY,
        )
        assertEquals(DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP, connected)

        // VLC-style External Screen Only when NO monitor is connected yet -> waits for cable/cast
        val waiting = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = false,
            externalMode = ExternalDisplayMode.EXTERNAL_ONLY,
        )
        assertEquals(DisplayRoutingMode.WAITING_FOR_EXTERNAL_MONITOR, waiting)

        // Non-DeX USB-C dock setting UI_MODE_TYPE_DESK in AUTO_EXTERNAL still uses external Presentation
        val dockedAuto = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_DESK,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = true,
            externalMode = ExternalDisplayMode.AUTO_EXTERNAL,
        )
        assertEquals(DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP, dockedAuto)

        // PHONE_ONLY keeps rendering inside phone window even if external screen is attached
        val phoneOnly = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = true,
            externalMode = ExternalDisplayMode.PHONE_ONLY,
        )
        assertEquals(DisplayRoutingMode.INTERNAL_LAPTOP, phoneOnly)
    }

    @Test
    fun deleteRecursivelyHandlesReadOnlyDirsWithoutTraversingSymlinks() {
        val tempBase = Files.createTempDirectory("linuxdroid-del-test").toFile()
        try {
            val fakeSdcard = tempBase.resolve("sdcard").apply { mkdirs() }
            val preciousPhoto = fakeSdcard.resolve("photo.jpg").apply { writeText("keep-me") }

            val rootfs = tempBase.resolve("linuxfs").apply { mkdirs() }
            val roDir = rootfs.resolve("usr/share/readonly").apply { mkdirs() }
            roDir.resolve("data.txt").writeText("hello")
            FileUtils.chmod(roDir, 0b101_000_000) // 0500 read-only dir

            val homeDir = rootfs.resolve("home/linux").apply { mkdirs() }
            Files.createSymbolicLink(homeDir.resolve("Android").toPath(), fakeSdcard.toPath())

            assertTrue(FileUtils.deleteRecursively(rootfs))
            assertFalse("Rootfs must be completely deleted", rootfs.exists())
            assertTrue("Symlink target file outside rootfs must never be deleted", preciousPhoto.isFile)
        } finally {
            FileUtils.deleteRecursively(tempBase)
        }
    }
}
