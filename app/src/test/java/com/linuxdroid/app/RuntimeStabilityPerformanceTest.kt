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
        assertTrue(
            "libblsession.so must export drmGetDeviceFromDevId() so KWin 5.27 WaylandEglBackend devId=0 resolves to renderD128",
            funcs.contains("drmGetDeviceFromDevId"),
        )
        assertTrue(
            "libblsession.so must export wl_display_connect() so desktop apps route to nested KWin wayland-1 instead of host wayland-0",
            funcs.contains("wl_display_connect"),
        )
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
        assertEquals("noconform,sysmem", turnipCfg.envVars["TU_DEBUG"])
        assertFalse(
            "ZINK_DESCRIPTORS=lazy must not be forced on Turnip (DD-Turnip v0.5.0 includes A8xx bindless invalidation)",
            turnipCfg.envVars.containsKey("ZINK_DESCRIPTORS"),
        )
        assertFalse(
            "MESA_NO_ERROR must not be set globally because _mesa_UniformBlockBinding_no_error crashes OpenGL 4.x apps like Blender 5.x",
            turnipCfg.envVars.containsKey("MESA_NO_ERROR"),
        )

        val maliNoDri = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Mali-G715-Immortalis MC11",
                "vendor_id" to "0x13b5",
                "api" to "1.3.250",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
                "ext.VK_EXT_external_memory_dma_buf" to "yes",
            ),
            hasMaliNode = true,
            hasDriRenderNode = false,
        )
        val maliCfg = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, maliNoDri, "/run/linuxdroid/virgl_test")
        assertEquals("unified", maliCfg.activeBackend)
        assertTrue(maliCfg.enableVenus)
        assertEquals("virpipe", maliCfg.envVars["GALLIUM_DRIVER"])
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
        assertTrue(appsSh.contains("lfdevs/blender-linux-arm64/releases/latest"))
        assertTrue("Blender wrapper must unset MESA_NO_ERROR and DRI_PRIME", appsSh.contains("unset MESA_NO_ERROR DRI_PRIME"))
        assertTrue("Blender desktop file must strip PrefersNonDefaultGPU", appsSh.contains("/^PrefersNonDefaultGPU=/d"))
        assertTrue(appsSh.contains("ubuntuhandbook1/gimp-3/ubuntu"))
        assertTrue(appsSh.contains("ubuntuhandbook1/krita/ubuntu"))
        assertTrue(appsSh.contains("libreoffice/ppa/ubuntu"))
        assertTrue(appsSh.contains("flathub org.kde.kdenlive"))
        assertTrue(appsSh.contains("noble-backports obs-studio"))
        assertTrue(appsSh.contains("deb.nodesource.com/node_22.x"))
        assertTrue(appsSh.contains("https://sh.rustup.rs"))
        assertTrue(appsSh.contains("https://go.dev/dl/?mode=json"))
        assertTrue(appsSh.contains("openjdk-25-jdk"))
        assertTrue(appsSh.contains("docker-ce-cli"))

        val desktopSh = File(assetsRoot, "usr/local/bin/linuxdroid-desktop").readText()
        assertTrue(
            "KDE taskbar default pins must be Settings, Dolphin, and Chromium only",
            desktopSh.contains("applications:systemsettings.desktop,applications:org.kde.dolphin.desktop,applications:chromium.desktop"),
        )
        assertTrue(
            "KDE taskbar migration must use launchers-v5 stamp after live panel sync",
            desktopSh.contains("launchers-v5") && desktopSh.contains("LAUNCHERS_READY:"),
        )
        assertFalse(
            "KDE taskbar default pins must not include preferred:// or Discover",
            desktopSh.contains("applications:systemsettings.desktop,applications:org.kde.discover.desktop,preferred://filemanager,preferred://browser"),
        )
        assertTrue(
            "XFCE taskbar default pins must be Settings, Thunar, and Chromium only",
            desktopSh.contains("[\"xfce-settings-manager.desktop\", \"thunar.desktop\", \"chromium.desktop\"]"),
        )
        assertTrue(
            "XFCE taskbar migration must use xfce-launchers-v5 stamp",
            desktopSh.contains("xfce-launchers-v5"),
        )
        assertFalse(
            "linuxdroid-desktop must not unset WAYLAND_DISPLAY (which causes libwayland-client to default to host wayland-0)",
            desktopSh.contains("unset WAYLAND_DISPLAY"),
        )
        assertTrue(
            "linuxdroid-desktop Xwayland mode must set WAYLAND_DISPLAY=wayland-off and BL_FORCE_X11=1",
            desktopSh.contains("export WAYLAND_DISPLAY=\"wayland-off\"") && desktopSh.contains("export BL_FORCE_X11=1"),
        )

        val gpuSh = File(assetsRoot, "usr/local/bin/linuxdroid-gpu").readText()
        assertFalse(
            "linuxdroid-gpu must never unset WAYLAND_DISPLAY (unsetting WAYLAND_DISPLAY causes wl_display_connect(NULL) to connect to host fullscreen wayland-0)",
            gpuSh.contains("unset WAYLAND_DISPLAY"),
        )
        assertTrue(
            "linuxdroid-gpu must route Blender to wayland-1 when nested KWin Wayland is active",
            gpuSh.contains("export WAYLAND_DISPLAY=wayland-1"),
        )
    }

    @Test
    fun kdeAndXfceTaskbarMigrationDeterministicallyPinsSettingsFileExplorerAndChromium() {
        val assetsRoot = findAssetsLinuxfs().canonicalFile
        val desktopScript = File(assetsRoot, "usr/local/bin/linuxdroid-desktop").canonicalPath

        val pyTest = """
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

desktop_script = sys.argv[1]
expected_kde = "applications:systemsettings.desktop,applications:org.kde.dolphin.desktop,applications:chromium.desktop"

with tempfile.TemporaryDirectory(prefix="ld-taskbar-test-") as tmp:
    # 0. Brand-new fresh KDE user home with NO plasma-org.kde.plasma.desktop-appletsrc yet
    home0 = os.path.join(tmp, "home_fresh")
    os.makedirs(home0, exist_ok=True)
    env0 = dict(os.environ, HOME=home0)
    r0 = subprocess.run(
        ["/bin/bash", "-c", f'source <(sed -n "/^configure_kde_default_launchers()/,/^sync_live_kde_launchers()/p" {desktop_script!r} | sed "${'$'}d"); configure_kde_default_launchers'],
        env=env0, capture_output=True, text=True, timeout=5,
    )
    assert r0.returncode == 0, r0.stderr
    rc0 = os.path.join(home0, ".config/plasma-org.kde.plasma.desktop-appletsrc")
    assert os.path.isfile(rc0), "Expected pre-seeded appletsrc on first boot"
    txt0 = open(rc0).read()
    assert "plugin=org.kde.panel\n" in txt0, txt0
    assert "[Containments][2][Applets][5][Configuration][General]\nlaunchers=" + expected_kde in txt0, txt0
    assert os.path.isfile(os.path.join(home0, ".config/plasmashellrc")), "Expected pre-seeded plasmashellrc"
    assert os.path.isfile(os.path.join(home0, ".config/kactivitymanagerdrc")), "Expected pre-seeded kactivitymanagerdrc"
    assert os.path.isfile(os.path.join(home0, ".config/ksplashrc")), "Expected pre-seeded ksplashrc"
    assert os.path.isfile(os.path.join(home0, ".config/kdedefaults/ksplashrc")), "Expected pre-seeded kdedefaults/ksplashrc"
    assert "Engine=none" in open(os.path.join(home0, ".config/kdedefaults/ksplashrc")).read()

    # 1. KDE appletsrc with NO [Configuration][General] section (exact v1.0.24 bug)
    home1 = os.path.join(tmp, "home_no_cfg")
    os.makedirs(os.path.join(home1, ".config"), exist_ok=True)
    os.makedirs(os.path.join(home1, ".local/share/linuxdroid"), exist_ok=True)
    open(os.path.join(home1, ".local/share/linuxdroid/launchers-v4"), "w").close()
    rc1 = os.path.join(home1, ".config/plasma-org.kde.plasma.desktop-appletsrc")
    with open(rc1, "w") as f:
        f.write(
            "[Containments][2]\nplugin=org.kde.panel\n\n"
            "[Containments][2][Applets][3]\nplugin=org.kde.plasma.kickoff\n\n"
            "[Containments][2][Applets][5]\nimmutability=1\nplugin=org.kde.plasma.icontasks\n\n"
            "[Containments][2][Applets][7]\nplugin=org.kde.plasma.systemtray\n"
        )

    env1 = dict(os.environ, HOME=home1)
    r1 = subprocess.run(
        ["/bin/bash", "-c", f'source <(sed -n "/^configure_kde_default_launchers()/,/^sync_live_kde_launchers()/p" {desktop_script!r} | sed "${'$'}d"); configure_kde_default_launchers'],
        env=env1, capture_output=True, text=True, timeout=5,
    )
    assert r1.returncode == 0, r1.stderr
    txt1 = open(rc1).read()
    assert "[Containments][2][Applets][5][Configuration][General]\nlaunchers=" + expected_kde in txt1, f"Missing created section in:\n{txt1}"

    # 2. KDE appletsrc with old preferred://browser & Discover -> overwritten on first v5 run, then user pin preserved once v5 stamp exists
    home2 = os.path.join(tmp, "home_old_cfg")
    os.makedirs(os.path.join(home2, ".config"), exist_ok=True)
    rc2 = os.path.join(home2, ".config/plasma-org.kde.plasma.desktop-appletsrc")
    with open(rc2, "w") as f:
        f.write(
            "[Containments][2][Applets][5]\nplugin=org.kde.plasma.icontasks\n\n"
            "[Containments][2][Applets][5][Configuration][General]\n"
            "groupingStrategy=0\n"
            "launchers=applications:systemsettings.desktop,applications:org.kde.discover.desktop,preferred://filemanager,preferred://browser\n"
        )
    env2 = dict(os.environ, HOME=home2)
    subprocess.run(
        ["/bin/bash", "-c", f'source <(sed -n "/^configure_kde_default_launchers()/,/^sync_live_kde_launchers()/p" {desktop_script!r} | sed "${'$'}d"); configure_kde_default_launchers'],
        env=env2, check=True, timeout=5,
    )
    txt2 = open(rc2).read()
    assert f"launchers={expected_kde}\n" in txt2 and "groupingStrategy=0\n" in txt2, txt2
    assert "preferred://" not in txt2 and "discover" not in txt2, txt2

    # Stamp v5 and add custom user pin (blender.desktop) -> must be preserved!
    stamp2 = os.path.join(home2, ".local/share/linuxdroid/launchers-v5")
    os.makedirs(os.path.dirname(stamp2), exist_ok=True)
    open(stamp2, "w").close()
    custom = expected_kde + ",applications:blender.desktop"
    open(rc2, "w").write(txt2.replace(expected_kde, custom))
    subprocess.run(
        ["/bin/bash", "-c", f'source <(sed -n "/^configure_kde_default_launchers()/,/^sync_live_kde_launchers()/p" {desktop_script!r} | sed "${'$'}d"); configure_kde_default_launchers'],
        env=env2, check=True, timeout=5,
    )
    assert f"launchers={custom}\n" in open(rc2).read()

    # 3. XFCE panel with only 1 initial launcher -> expands cleanly to all 3 pinned launchers
    home3 = os.path.join(tmp, "home_xfce")
    xfconf_dir = os.path.join(home3, ".config/xfce4/xfconf/xfce-perchannel-xml")
    os.makedirs(xfconf_dir, exist_ok=True)
    with open(os.path.join(xfconf_dir, "xfce4-panel.xml"), "w") as f:
        f.write('''<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="panels" type="empty">
    <property name="panel-1" type="empty">
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="whiskermenu"/>
    <property name="plugin-2" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="exo-terminal-emulator.desktop"/>
      </property>
    </property>
    <property name="plugin-3" type="string" value="tasklist"/>
  </property>
</channel>''')
    env3 = dict(os.environ, HOME=home3)
    subprocess.run(
        ["/bin/bash", "-c", f'source <(sed -n "/^configure_xfce_scaling()/,/^run_xwayland_desktop()/p" {desktop_script!r} | sed "${'$'}d"); configure_xfce_scaling 1.5'],
        env=env3, check=True, timeout=5,
    )
    tree = ET.parse(os.path.join(xfconf_dir, "xfce4-panel.xml"))
    plugins = tree.getroot().find("./property[@name='plugins']")
    launchers = [
        lp.find("./property[@name='items']/value").get("value")
        for lp in plugins.findall("property")
        if lp.get("value") == "launcher"
    ]
    assert launchers == ["xfce-settings-manager.desktop", "thunar.desktop", "chromium.desktop"], f"Unexpected XFCE launchers: {launchers}"
""".trimIndent()

        val proc = ProcessBuilder("python3", "-B", "-c", pyTest, desktopScript)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Taskbar migration test timed out:\n$output", finished)
        assertEquals("Taskbar migration test failed:\n$output", 0, proc.exitValue())
    }

    @Test
    fun blenderDesktopWindowedXwaylandAndHostWaylandProtection() {
        val rootDir = if (File("tools/linuxfs/preload/wayland_kopper.c").isFile) File(".") else File("..")
        val preloadSrc = File(rootDir, "tools/linuxfs/preload/wayland_kopper.c").readText()
        assertTrue(preloadSrc.contains("is_force_x11_client"))
        assertTrue(preloadSrc.contains("wl_display_connect_to_fd"))
        assertTrue(preloadSrc.contains("wayland-off"))

        val linuxfs = findAssetsLinuxfs()
        val gpuScript = File(linuxfs, "usr/local/bin/linuxdroid-gpu").readText()
        assertTrue(gpuScript.contains("export WAYLAND_DISPLAY=wayland-off"))
        assertTrue(gpuScript.contains("export BL_FORCE_X11=1"))
        assertTrue(gpuScript.contains("top_y = 38"))
        assertTrue(gpuScript.contains("x11.XMoveResizeWindow(dpy, wid, sx, top_y, win_w, win_h)"))
        assertTrue(gpuScript.contains("XSetWMNormalHints"))
        assertTrue(gpuScript.contains("MotifWmHints(flags=3, functions=1, decorations=1"))

        val desktopScript = File(linuxfs, "usr/local/bin/linuxdroid-desktop").readText()
        assertTrue(desktopScript.contains("\"maxsizerule\": \"0\""))
        assertTrue(desktopScript.contains("\"ignoregeometry\": \"false\""))
        assertTrue(desktopScript.contains("\"fullscreenrule\": \"3\""))

        val kwinScript = File(linuxfs, "usr/share/kwin/scripts/linuxdroidwindowed/contents/code/main.js").readText()
        assertTrue(kwinScript.contains("_linuxdroidInitialFitDone"))
        assertFalse(kwinScript.contains("clientMaximizedStateChanged"))
        assertFalse(kwinScript.contains("captionChanged"))

        val compositorSrc = File(rootDir, "app/src/main/cpp/waylandcomp/src/compositor.c").readText()
        assertTrue(compositorSrc.contains("is_desktop_compositor_surface"))
        assertTrue(compositorSrc.contains("s->keep_on_top = 1;"))

        val chromiumScript = File(linuxfs, "usr/local/bin/chromium").readText()
        val chromiumBrowserScript = File(linuxfs, "usr/local/bin/chromium-browser").readText()
        assertEquals(chromiumScript, chromiumBrowserScript)
        assertTrue(chromiumScript.contains("unset MESA_GL_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE"))
        assertTrue(chromiumScript.contains("flags+=(--ozone-platform=x11)"))
        assertTrue(chromiumScript.contains("flags+=(--use-angle=gles-egl --in-process-gpu --ignore-gpu-blocklist --enable-gpu-rasterization --enable-zero-copy)"))
        assertTrue(chromiumScript.contains("flags+=(--use-angle=gl --in-process-gpu)"))
        assertTrue(gpuScript.contains("if [ \"\${GALLIUM_DRIVER:-}\" = \"virpipe\" ] || [ \"\${GALLIUM_DRIVER:-}\" = \"llvmpipe\" ]; then"))
        assertTrue(gpuScript.contains("set -- \"\$1\" --ozone-platform=x11 --in-process-gpu --disable-gpu-watchdog --disable-vulkan \"\${@:2}\""))
        assertTrue(gpuScript.contains("set -- \"\$ch_bin\" --ozone-platform=x11 --in-process-gpu --disable-gpu-watchdog --disable-vulkan \"\${ch_args[@]}\""))

        val gpuLaunchScript = File(linuxfs, "usr/local/bin/linuxdroid-gpu-launch").readText()
        assertTrue(gpuLaunchScript.contains("chromium|chromium-browser|google-chrome|google-chrome-stable|brave-browser)"))
        assertTrue(gpuLaunchScript.contains("--use-angle=gles-egl)"))
        assertTrue(gpuLaunchScript.contains("sw_args+=(--use-angle=gl)"))

        val runtimeSrc = File(rootDir, "app/src/main/java/com/linuxdroid/app/runtime/LinuxRuntime.kt").readText()
        assertTrue(runtimeSrc.contains("KGSL_DEVICE"))
        assertFalse(runtimeSrc.contains("MALI_DEVICE"))

        val drmSrc = File(rootDir, "tools/linuxfs/preload/drm.c").readText()
        assertFalse(drmSrc.contains("/dev/mali0"))
        assertFalse(drmSrc.contains("panfrost"))

        val webhelperSrc = File(rootDir, "tools/linuxfs/preload/webhelper.c").readText()
        assertFalse(webhelperSrc.contains("\"--in-process-gpu\""))
    }
}
