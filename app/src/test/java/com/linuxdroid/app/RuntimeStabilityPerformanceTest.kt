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
}
