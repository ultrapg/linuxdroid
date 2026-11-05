package com.linuxdroid.app

import com.linuxdroid.app.gpu.GpuConfigGenerator
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.session.ResolutionPreset
import com.linuxdroid.app.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RuntimeStabilityPerformanceTest {

    private fun findAssetsLinuxfs(): File {
        val dir = listOf(
            File("src/main/assets/linuxfs"),
            File("app/src/main/assets/linuxfs"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected src/main/assets/linuxfs directory", dir)
        return dir!!
    }

    /**
     * Parses ELF64 little-endian .dynsym + .dynstr to verify defined exported function symbols.
     * Prevents regression of the 64-bit `link()` omission that caused libICE/ksmserver SIGABRT on Android SELinux.
     */
    private fun readDefinedElf64DynamicFunctions(elfFile: File): Set<String> {
        val bytes = elfFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x7f, bytes[0].toInt() and 0xff)
        assertEquals('E'.code, bytes[1].toInt() and 0xff)
        assertEquals('L'.code, bytes[2].toInt() and 0xff)
        assertEquals('F'.code, bytes[3].toInt() and 0xff)
        assertEquals("Expected ELFCLASS64", 2, bytes[4].toInt() and 0xff)
        assertEquals("Expected EM_AARCH64 (183)", 183, buf.getShort(18).toInt() and 0xffff)

        val eShoff = buf.getLong(40).toInt()
        val eShentsize = buf.getShort(58).toInt() and 0xffff
        val eShnum = buf.getShort(60).toInt() and 0xffff
        assertTrue(eShoff > 0 && eShnum > 0)

        val exported = HashSet<String>()
        for (i in 0 until eShnum) {
            val sh = eShoff + i * eShentsize
            val shType = buf.getInt(sh + 4)
            if (shType != 11) continue // SHT_DYNSYM == 11
            val shLink = buf.getInt(sh + 40)
            val symOffset = buf.getLong(sh + 24).toInt()
            val symSize = buf.getLong(sh + 32).toInt()
            val symEntSize = buf.getLong(sh + 56).toInt().takeIf { it > 0 } ?: 24

            val strSh = eShoff + shLink * eShentsize
            val strOffset = buf.getLong(strSh + 24).toInt()
            val strSize = buf.getLong(strSh + 32).toInt()

            val count = symSize / symEntSize
            for (s in 0 until count) {
                val entry = symOffset + s * symEntSize
                val stName = buf.getInt(entry)
                val stInfo = bytes[entry + 4].toInt() and 0xff
                val stShndx = buf.getShort(entry + 6).toInt() and 0xffff
                val bind = stInfo ushr 4
                val type = stInfo and 0xf
                // STB_GLOBAL (1) or STB_WEAK (2), STT_FUNC (2), defined section (stShndx != SHN_UNDEF)
                if ((bind == 1 || bind == 2) && type == 2 && stShndx != 0 && stName in 1 until strSize) {
                    var end = strOffset + stName
                    while (end < strOffset + strSize && bytes[end].toInt() != 0) end++
                    exported.add(String(bytes, strOffset + stName, end - (strOffset + stName), Charsets.UTF_8))
                }
            }
        }
        return exported
    }

    @Test
    fun libblsessionExportsBoth64BitLinkAndLinkatForAndroidHardlinkLockFallback() {
        val assetsRoot = findAssetsLinuxfs()
        val sessionSo = File(assetsRoot, "usr/local/lib/libblsession.so")
        assertTrue(sessionSo.isFile)

        val funcs = readDefinedElf64DynamicFunctions(sessionSo)
        assertTrue("libblsession.so must export 64-bit link() for libICE/ksmserver & libXau", funcs.contains("link"))
        assertTrue("libblsession.so must export 64-bit linkat()", funcs.contains("linkat"))
        assertTrue("libblsession.so must export getgrgid() for Android supplementary GIDs", funcs.contains("getgrgid"))
        assertTrue("libblsession.so must export getgrgid_r() for Android supplementary GIDs", funcs.contains("getgrgid_r"))
        assertTrue("libblsession.so must export getpwuid() for Android/PRoot peer UIDs", funcs.contains("getpwuid"))
        assertTrue("libblsession.so must export getpwuid_r() for D-Bus SO_PEERCRED auth", funcs.contains("getpwuid_r"))
        assertTrue("libblsession.so must export fopen() for /proc/stat synthesis", funcs.contains("fopen"))
        assertTrue("libblsession.so must export fopen64() for /proc/stat synthesis", funcs.contains("fopen64"))
        assertTrue("libblsession.so must export __open_2() for fortified open calls", funcs.contains("__open_2"))
        assertTrue("libblsession.so must export __openat_2() for fortified openat calls", funcs.contains("__openat_2"))
        assertTrue("libblsession.so must export lseek() for live /proc refresh", funcs.contains("lseek"))
        assertTrue("libblsession.so must export fseek() for live /proc refresh", funcs.contains("fseek"))
        assertTrue("libblsession.so must export rewind() for live /proc refresh", funcs.contains("rewind"))
        assertTrue("libblsession.so must export readdir() for PRoot .l2s hardlink hiding", funcs.contains("readdir"))
        assertTrue("libblsession.so must export readdir64() for PRoot .l2s hardlink hiding", funcs.contains("readdir64"))
    }

    @Test
    fun fastpathPreloadsAreValidArm64Elf64AndFreeOfLegacyBranding() {
        val assetsRoot = findAssetsLinuxfs()
        for (rel in listOf("usr/local/lib/libblfastpath.so", "usr/local/lib/libblaudit.so")) {
            val f = File(assetsRoot, rel)
            assertTrue("Missing $rel", f.isFile && f.length() > 0L)
            val raw = f.readBytes()
            assertEquals("Expected ELFCLASS64 in $rel", 2, raw[4].toInt() and 0xff)
            val ascii = String(raw, Charsets.ISO_8859_1).lowercase()
            assertFalse("Unexpected droiddeck string in $rel", ascii.contains("droiddeck"))
            assertFalse("Unexpected vapor string in $rel", ascii.contains("vapor"))
        }

        val fastpathFuncs = readDefinedElf64DynamicFunctions(File(assetsRoot, "usr/local/lib/libblfastpath.so"))
        assertTrue("libblfastpath.so must export futimens()", fastpathFuncs.contains("futimens"))
        assertTrue("libblfastpath.so must export futimes()", fastpathFuncs.contains("futimes"))
        assertTrue("libblfastpath.so must export utimensat()", fastpathFuncs.contains("utimensat"))
        assertTrue("libblfastpath.so must export statx()", fastpathFuncs.contains("statx"))
    }

    @Test
    fun aspectMatchedPerformancePresetsPreserveExactAspectRatioAndEvenAlignment() {
        // 20:9 FHD+ phone (2400x1080)
        assertEquals(2400 to 1080, ResolutionPreset.NATIVE.resolveForSurface(2400, 1080))
        assertEquals(2000 to 900, ResolutionPreset.BALANCED_900P.resolveForSurface(2400, 1080))
        assertEquals(1600 to 720, ResolutionPreset.PERFORMANCE_720P.resolveForSurface(2400, 1080))

        // QHD+ 20:9 phone (3200x1440) -> massive pixel reduction with zero black bars
        assertEquals(2000 to 900, ResolutionPreset.BALANCED_900P.resolveForSurface(3200, 1440))
        assertEquals(1600 to 720, ResolutionPreset.PERFORMANCE_720P.resolveForSurface(3200, 1440))

        // Portrait orientation (1080x2400)
        assertEquals(900 to 2000, ResolutionPreset.BALANCED_900P.resolveForSurface(1080, 2400))
        assertEquals(720 to 1600, ResolutionPreset.PERFORMANCE_720P.resolveForSurface(1080, 2400))

        // Odd pixel surface dimensions always round to even integers
        val (evenW, evenH) = ResolutionPreset.BALANCED_900P.resolveForSurface(3121, 1441)
        assertEquals(0, evenW % 2)
        assertEquals(0, evenH % 2)
        assertEquals(900, evenH)
    }

    @Test
    fun glibcTunablesAndMesaDatabaseCacheAreConfiguredForProotThroughput() {
        assertTrue(SessionPrefs.GLIBC_TUNABLES.contains("glibc.pthread.rseq=0"))
        assertTrue(SessionPrefs.GLIBC_TUNABLES.contains("glibc.malloc.top_pad=16777216"))

        val adreno = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Adreno (TM) 750",
                "vendor_id" to "0x5143",
                "api" to "1.3.256",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
                "ext.VK_EXT_external_memory_dma_buf" to "yes",
            ),
            hasKgslNode = true,
        )
        val turnipCfg = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, adreno, "/run/linuxdroid/virgl_test")
        assertEquals("1", turnipCfg.envVars["MESA_DISK_CACHE_DATABASE"])
        assertEquals("lazy", turnipCfg.envVars["ZINK_DESCRIPTORS"])
        assertEquals("1", turnipCfg.envVars["MESA_NO_ERROR"])
    }

    @Test
    fun preloadsAvoidGlibc238SymbolsAndExportFlatpakVfsInterposers() {
        val assetsRoot = findAssetsLinuxfs()
        for (rel in listOf(
            "usr/local/lib/libblsession.so",
            "usr/local/lib/libblfastpath.so",
            "usr/local/lib/libblaudit.so",
        )) {
            val raw = File(assetsRoot, rel).readBytes()
            val latin = String(raw, Charsets.ISO_8859_1)
            assertFalse("$rel must not depend on GLIBC_2.38 symbols inside Flatpak runtimes", latin.contains("GLIBC_2.38"))
            assertFalse("$rel must not depend on GLIBC_2.39 symbols inside Flatpak runtimes", latin.contains("GLIBC_2.39"))
        }

        val sessionFuncs = readDefinedElf64DynamicFunctions(File(assetsRoot, "usr/local/lib/libblsession.so"))
        for (sym in listOf("opendir", "stat", "lstat", "fstatat", "statx", "readlink", "readlinkat", "realpath")) {
            assertTrue("libblsession.so must export $sym() for per-process Flatpak VFS", sessionFuncs.contains(sym))
        }

        val fastpathFuncs = readDefinedElf64DynamicFunctions(File(assetsRoot, "usr/local/lib/libblfastpath.so"))
        assertTrue(
            "libblfastpath.so must export bl_fastpath_is_active()",
            fastpathFuncs.contains("bl_fastpath_is_active"),
        )
    }

    @Test
    fun manifestListsAllFlatpakAndHelperAssetsAndEveryEntryExistsOnDisk() {
        val assetsRoot = findAssetsLinuxfs()
        val manifest = File(assetsRoot, "MANIFEST.txt")
        assertTrue(manifest.isFile)
        val entries = manifest.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val modesByPath = HashMap<String, String>()
        for (line in entries) {
            val parts = line.split(Regex("\\s+"), limit = 2)
            assertEquals("Malformed MANIFEST.txt line: $line", 2, parts.size)
            val f = File(assetsRoot, parts[1])
            assertTrue("Missing asset listed in MANIFEST.txt: ${parts[1]}", f.exists())
            modesByPath[parts[1]] = parts[0]
        }

        for (bin in listOf(
            "usr/local/bin/flatpak",
            "usr/local/bin/bwrap",
            "usr/local/bin/flatpak-spawn",
            "usr/local/bin/linuxdroid-flatpak-helper",
            "usr/local/bin/linuxdroid-apps",
        )) {
            assertEquals("$bin must be 0755 in MANIFEST.txt", "0755", modesByPath[bin])
        }
    }

    @Test
    fun flatpakAndWarehouseStackUsesUnprivilegedUserScopePerProcessVfsAndPortalHelper() {
        val assetsRoot = findAssetsLinuxfs()

        val flatpakPy = File(assetsRoot, "usr/local/bin/flatpak").readText()
        assertTrue(flatpakPy.contains("USER_DEFAULT_CMDS"))
        assertTrue(flatpakPy.contains("bare-user-only"))
        assertTrue(flatpakPy.contains("\"fsync\", \"false\""))
        assertTrue(flatpakPy.contains("linuxdroid-flatpak-helper"))

        val bwrapPy = File(assetsRoot, "usr/local/bin/bwrap").readText()
        assertTrue(bwrapPy.contains("BL_FLATPAK_INFO"))
        assertTrue(bwrapPy.contains("BL_FLATPAK_SANDBOX_DIR"))
        assertTrue(bwrapPy.contains("BL_FLATPAK_EXTS"))
        assertTrue(bwrapPy.contains("BL_FLATPAK_EXEC"))
        assertTrue(bwrapPy.contains("glib-compile-schemas"))
        assertTrue(bwrapPy.contains("GSK_RENDERER"))
        assertTrue(bwrapPy.contains(".bwrap-meta-"))
        assertTrue(bwrapPy.contains("\"-C\""))

        val spawnPy = File(assetsRoot, "usr/local/bin/flatpak-spawn").readText()
        assertTrue(spawnPy.contains("--forward-fd"))
        assertTrue(spawnPy.contains("set_inheritable"))
        assertTrue(spawnPy.contains("BL_FLATPAK_INFO"))
        assertTrue(spawnPy.contains("BL_FLATPAK_EXEC"))
        assertTrue(spawnPy.contains("/.var/app/"))

        val helperPy = File(assetsRoot, "usr/local/bin/linuxdroid-flatpak-helper").readText()
        assertTrue(helperPy.contains("org.freedesktop.Flatpak"))
        assertTrue(helperPy.contains("org.freedesktop.portal.Flatpak"))
        assertTrue(helperPy.contains("HostCommand"))
        assertTrue(helperPy.contains("get_unix_fd_list"))

        val appsSh = File(assetsRoot, "usr/local/bin/linuxdroid-apps").readText()
        assertTrue(appsSh.contains("flatpak --user install -y --or-update flathub io.github.flattool.Warehouse"))

        val desktopSh = File(assetsRoot, "usr/local/bin/linuxdroid-desktop").readText()
        assertTrue(
            "KDE taskbar default pins must be Settings, Dolphin, and Firefox only",
            desktopSh.contains("applications:systemsettings.desktop,applications:org.kde.dolphin.desktop,applications:firefox.desktop"),
        )
        assertFalse(
            "KDE taskbar default pins must not include preferred:// or Discover",
            desktopSh.contains("applications:systemsettings.desktop,applications:org.kde.discover.desktop,preferred://filemanager,preferred://browser"),
        )
        assertTrue(
            "XFCE taskbar default pins must be Settings, Thunar, and Firefox only",
            desktopSh.contains("[\"xfce-settings-manager.desktop\", \"thunar.desktop\", \"firefox.desktop\"]"),
        )
    }
}
