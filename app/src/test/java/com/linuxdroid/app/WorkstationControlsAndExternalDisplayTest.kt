package com.linuxdroid.app

import android.content.res.Configuration
import android.view.Display
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.runtime.CoreDescriptor
import com.linuxdroid.app.runtime.CpuCoreTier
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
        val cores = CpuInfo.assignCoreTiers(
            listOf(
                CoreDescriptor(0, 1_800_000L),
                CoreDescriptor(1, 1_800_000L),
                CoreDescriptor(2, 1_800_000L),
                CoreDescriptor(3, 1_800_000L),
                CoreDescriptor(4, 2_800_000L),
                CoreDescriptor(5, 2_800_000L),
                CoreDescriptor(6, 2_800_000L),
                CoreDescriptor(7, 3_200_000L),
            )
        )
        val topo = CpuTopology(
            totalCores = 8,
            cores = cores,
            summary = CpuInfo.buildSummary(8, cores),
        )
        assertTrue(topo.summary.contains("3.20 GHz"))
        assertEquals(CpuCoreTier.EFFICIENCY, topo.cores[0].tier)
        assertEquals(CpuCoreTier.PERFORMANCE, topo.cores[4].tier)
        assertEquals(CpuCoreTier.PRIME, topo.cores[7].tier)

        assertEquals(listOf(4, 5, 6, 7), topo.performanceCoreIndices())
        assertEquals(listOf(7), topo.primeCoreIndices())
        assertEquals(listOf(0, 1, 2, 3), topo.efficiencyCoreIndices())

        assertEquals(emptyList<Int>(), topo.selectPreferredCores(0, onlineIndices = (0..7).toSet()))
        assertEquals(emptyList<Int>(), topo.selectPreferredCores(8, onlineIndices = (0..7).toSet()))
        assertEquals(listOf(4, 5, 6, 7), topo.selectPreferredCores(4, onlineIndices = (0..7).toSet()))
        assertEquals(listOf(6, 7), topo.selectPreferredCores(2, onlineIndices = (0..7).toSet()))

        // Explicit per-core selection overrides count limit
        assertEquals(listOf(0, 1, 2, 3), topo.resolveSelectedCores("0-3", 0, (0..7).toSet()))
        assertEquals(listOf(4, 5, 6, 7), topo.resolveSelectedCores("4,5,6,7", 2, (0..7).toSet()))
        assertEquals(emptyList<Int>(), topo.resolveSelectedCores("0-7", 0, (0..7).toSet()))
    }

    @Test
    fun generateProcStatTextMatchesConfiguredCoreCount() {
        val stat4 = LinuxRuntime.generateProcStatText(4, bootTimeSec = 1_730_000_000L)
        val lines = stat4.trim().lines()
        val cpuLines = lines.filter { it.startsWith("cpu") }
        assertEquals(5, cpuLines.size)
        assertTrue(cpuLines[0].startsWith("cpu  "))
        assertTrue(cpuLines[1].startsWith("cpu0 "))
        assertTrue(cpuLines[4].startsWith("cpu3 "))
        assertTrue(lines.any { it == "btime 1730000000" })
        assertTrue(lines.any { it.startsWith("ctxt ") })
        assertTrue(lines.any { it.startsWith("intr ") })
        assertTrue(lines.any { it.startsWith("processes ") })
        assertTrue(lines.any { it.startsWith("procs_running ") })
        assertTrue(lines.any { it.startsWith("procs_blocked ") })
        assertTrue(lines.any { it.startsWith("softirq ") })

        // Boundary core counts clamp cleanly to [1, 128]
        val statMin = LinuxRuntime.generateProcStatText(0)
        assertEquals(2, statMin.trim().lines().count { it.startsWith("cpu") })
        val statMax = LinuxRuntime.generateProcStatText(256)
        assertEquals(129, statMax.trim().lines().count { it.startsWith("cpu") })

        val vmstat = LinuxRuntime.generateProcVmstatText()
        assertTrue(vmstat.contains("nr_free_pages "))
        assertTrue(vmstat.contains("pgpgin "))
        assertTrue(vmstat.contains("pgpgout "))
        assertTrue(vmstat.contains("pgfault "))
    }

    @Test
    fun externalOnlyModeRoutesToMonitorOrWaitsCleanlyWithoutDex() {
        // Verify ExternalDisplayMode titles and descriptions have no VLC references and mention USB-C display output
        for (mode in ExternalDisplayMode.entries) {
            assertFalse("ExternalDisplayMode title must not mention VLC: ${mode.title}", mode.title.contains("VLC", ignoreCase = true))
            assertFalse("ExternalDisplayMode subtitle must not mention VLC: ${mode.subtitle}", mode.subtitle.contains("VLC", ignoreCase = true))
        }
        assertTrue(ExternalDisplayMode.EXTERNAL_ONLY.subtitle.contains("USB-C"))

        // External Screen Only when monitor IS connected (even if dock set UI_MODE_TYPE_DESK!)
        val connected = DeXEnvironmentDetector.evaluate(
            semDesktopModeEnabled = 0,
            uiModeType = Configuration.UI_MODE_TYPE_DESK,
            inMultiWindowMode = false,
            activityDisplayId = Display.DEFAULT_DISPLAY,
            hasExternalPresentationDisplay = true,
            externalMode = ExternalDisplayMode.EXTERNAL_ONLY,
        )
        assertEquals(DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP, connected)

        // External Screen Only when NO monitor is connected yet -> waits for cable/cast
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

    @Test
    fun diagnosticReportV1052RegressionsAreResolved() {
        val repoRoot = java.io.File(System.getProperty("user.dir") ?: ".").let {
            if (it.name == "app") it.parentFile else it
        }
        val manifestText = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        val sessionActivityBlock = manifestText.substringAfter("android:name=\".ui.SessionActivity\"")
            .substringBefore("</activity>")
        assertFalse(
            "SessionActivity must not hardcode sensorLandscape in AndroidManifest.xml",
            sessionActivityBlock.contains("sensorLandscape"),
        )

        val compositorC = repoRoot.resolve("app/src/main/cpp/waylandcomp/src/compositor.c").readText()
        assertTrue(
            "scene_size must prefer g_output_w/g_output_h over hardcoded 1920x1080 fallback",
            compositorC.contains("*w = g_output_w > 0 ? g_output_w : INPUT_SPACE_W;") &&
                compositorC.contains("*h = g_output_h > 0 ? g_output_h : INPUT_SPACE_H;"),
        )

        val drmC = repoRoot.resolve("tools/linuxfs/preload/drm.c").readText()
        assertTrue("drm.c must synthesize drmDevice via alloc_synth_drm_device", drmC.contains("alloc_synth_drm_device"))
        assertTrue("drm.c must intercept drmGetVersion", drmC.contains("void *drmGetVersion(int fd)"))
        assertTrue("drm.c must intercept drmGetDevices2", drmC.contains("int drmGetDevices2("))
        assertTrue("drm.c must gate gbm_create_device via should_block_kgsl_gbm", drmC.contains("should_block_kgsl_gbm()"))

        val gpuScript = repoRoot.resolve("app/src/main/assets/linuxfs/usr/local/bin/linuxdroid-gpu").readText()
        assertTrue(
            "linuxdroid-gpu must set LIBDECOR_PLUGIN_DIR=/nonexistent for blender",
            gpuScript.contains("export LIBDECOR_PLUGIN_DIR=/nonexistent"),
        )

        val sessionScript = repoRoot.resolve("app/src/main/assets/linuxfs/usr/local/bin/linuxdroid-session").readText()
        assertTrue("linuxdroid-session must upgrade Blender wrapper to BL_WRAPPER_V4", sessionScript.contains("BL_WRAPPER_V4"))
    }
}
