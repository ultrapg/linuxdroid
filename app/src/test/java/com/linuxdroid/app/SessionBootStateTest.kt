package com.linuxdroid.app

import com.linuxdroid.app.session.SessionPhase
import com.linuxdroid.app.session.SessionService
import com.linuxdroid.app.session.SessionSnapshot
import com.linuxdroid.app.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionBootStateTest {
    @Before
    fun setUp() {
        SessionState.reset()
    }

    @Test
    fun bootProgressAdvancesThroughSixStepsAndTransitionsToRunningOnFirstFrame() {
        assertFalse(SessionState.isRunningOrPaused)
        assertEquals(SessionPhase.STOPPED, SessionState.state.value.phase)

        SessionState.update {
            SessionSnapshot(
                phase = SessionPhase.STARTING,
                statusText = "Staging Linux runtime & user account...",
                bootStep = 1,
                bootTotalSteps = 6,
            )
        }
        assertTrue(SessionState.isRunningOrPaused)
        assertFalse(SessionState.state.value.firstFrameReady)
        assertEquals(1, SessionState.state.value.bootStep)

        SessionState.update {
            it.copy(
                bootStep = 5,
                statusText = "Starting KDE Plasma Wayland compositor (1920x1080)...",
            )
        }
        assertEquals(5, SessionState.state.value.bootStep)

        SessionState.update {
            it.copy(
                firstFrameReady = true,
                phase = SessionPhase.RUNNING,
                bootStep = 6,
                statusText = "Running (linux)",
            )
        }
        assertTrue(SessionState.state.value.firstFrameReady)
        assertEquals(SessionPhase.RUNNING, SessionState.state.value.phase)
    }

    @Test
    fun failedBootRetainsErrorAndLogsInsteadOfResettingToSilentStoppedState() {
        val logDir = Files.createTempDirectory("linuxdroid-logs").toFile()
        try {
            File(logDir, "proot-host.log").writeText("proot warning: test kernel message\n")
            File(logDir, "session.log").writeText(
                "== linuxdroid-session\n" +
                    "== [BOOT 4/6] Preparing Ubuntu environment, locale & system D-Bus...\n" +
                    "ERROR: Android Wayland compositor socket not found at /run/linuxdroid/wayland-0\n"
            )

            val tail = SessionService.collectDiagnosticLogTail(logDir, maxLines = 10)
            assertTrue(tail.any { it.contains("[proot] proot warning: test kernel message") })
            assertTrue(tail.any { it.contains("ERROR: Android Wayland compositor socket not found") })

            SessionState.update {
                SessionSnapshot(
                    phase = SessionPhase.FAILED,
                    statusText = "Startup failed",
                    errorMessage = "Linux desktop exited before rendering its first frame (exit code 2).",
                    bootLogTail = tail,
                )
            }

            val snap = SessionState.state.value
            assertEquals(SessionPhase.FAILED, snap.phase)
            assertFalse(SessionState.isRunningOrPaused)
            assertNotNull(snap.errorMessage)
            assertTrue(snap.bootLogTail.isNotEmpty())
        } finally {
            logDir.deleteRecursively()
        }
    }

    @Test
    fun linuxfsAssetManifestReferencesExistingFilesAndPreservesWaylandKeymap() {
        val assetsRoot = listOf(
            File("src/main/assets/linuxfs"),
            File("app/src/main/assets/linuxfs"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected src/main/assets/linuxfs directory", assetsRoot)

        val manifestFile = File(assetsRoot!!, "MANIFEST.txt")
        assertTrue(manifestFile.isFile)

        val entries = manifestFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(entries.isNotEmpty())

        val paths = HashSet<String>()
        for (line in entries) {
            val space = line.indexOf(' ')
            assertTrue("Invalid manifest line: $line", space > 0)
            val mode = line.substring(0, space).toIntOrNull(8)
            assertNotNull("Invalid octal mode in: $line", mode)
            val relPath = line.substring(space + 1).trim()
            val target = File(assetsRoot, relPath)
            assertTrue("Missing asset referenced in MANIFEST.txt: $relPath", target.isFile && target.length() > 0L)
            paths.add(relPath)
        }

        assertTrue(paths.contains("usr/local/bin/kwin_wayland_wrapper"))
        assertTrue(paths.contains("usr/local/bin/linuxdroid-gpu"))
        assertTrue(paths.contains("etc/xdg/startkderc"))
        assertTrue(paths.contains("etc/xdg/ksplashrc"))
        assertTrue(paths.contains("etc/xdg/kwinrc"))
        assertTrue(paths.contains("etc/xdg/autostart/powerdevil.desktop"))

        val desktopScript = File(assetsRoot, "usr/local/bin/linuxdroid-desktop").readText()
        assertTrue("linuxdroid-desktop must emit READY| status when desktop shell maps", desktopScript.contains("READY|"))
        assertTrue("linuxdroid-desktop must disable KWin PRoot permission mismatch checks", desktopScript.contains("KWIN_WAYLAND_NO_PERMISSION_CHECKS=1"))

        val sessionScript = File(assetsRoot, "usr/local/bin/linuxdroid-session").readText()
        assertTrue("linuxdroid-session must preserve keymap.xkb in XDG_RUNTIME_DIR", sessionScript.contains("keymap.xkb"))
        assertTrue("linuxdroid-session must export BL_DESKTOP=1 for FEX binfmt hook", sessionScript.contains("BL_DESKTOP=1"))

        val runtimeSource = listOf(
            File("src/main/java/com/linuxdroid/app/runtime/LinuxRuntime.kt"),
            File("app/src/main/java/com/linuxdroid/app/runtime/LinuxRuntime.kt"),
        ).first { it.isFile }.readText()
        assertFalse(
            "LinuxRuntime.prootPrefix must never pass -q to host Android PRoot",
            runtimeSource.contains("cmd.add(\"-q\")")
        )
    }
}
