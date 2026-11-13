package com.linuxdroid.app

import com.linuxdroid.app.gpu.GpuConfigGenerator
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.gpu.GpuFamily
import com.linuxdroid.app.gpu.GpuInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuConfigTest {
    @Test
    fun snapdragonAdrenoAutoSelectsTurnipAndZink() {
        val gpu = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Adreno (TM) 750",
                "vendor_id" to "0x5143",
                "api" to "1.3.256",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
                "ext.VK_EXT_external_memory_dma_buf" to "yes",
            ),
            hasKgslNode = true,
        )
        assertEquals(GpuFamily.ADRENO, gpu.family)
        assertEquals(750, gpu.adrenoModel)

        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("turnip", resolved.activeBackend)
        assertFalse(resolved.requiresVirglServer)
        assertEquals("zink", resolved.envVars["GALLIUM_DRIVER"])
        assertEquals("zink", resolved.envVars["MESA_LOADER_DRIVER_OVERRIDE"])
        assertTrue(resolved.envVars["VK_ICD_FILENAMES"]!!.contains("freedreno_icd.aarch64.json"))
    }

    @Test
    fun mediatekImmortalisAutoSelectsUnifiedVenusAndVirgl() {
        val gpu = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Immortalis-G720 MC12",
                "vendor_id" to "0x13b5",
                "api" to "1.3.274",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
            ),
            socHardware = "mt6989 dimensity",
            hasKgslNode = false,
            hasMaliNode = true,
        )
        assertEquals(GpuFamily.IMMORTALIS, gpu.family)

        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("unified", resolved.activeBackend)
        assertTrue(resolved.requiresVirglServer)
        assertTrue(resolved.enableVenus)
        assertEquals("vtest", resolved.envVars["VN_DEBUG"])
        assertEquals("/run/linuxdroid/virgl_test", resolved.envVars["VTEST_SOCKET_NAME"])
        assertEquals("virpipe", resolved.envVars["GALLIUM_DRIVER"])
        assertEquals("1", resolved.envVars["LIBGL_ALWAYS_SOFTWARE"])
        assertTrue(resolved.envVars["VK_ICD_FILENAMES"]!!.contains("virtio_icd.aarch64.json"))
    }

    @Test
    fun exynosXclipseAutoSelectsUnifiedVenusAndVirgl() {
        val gpu = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Samsung Xclipse 940",
                "vendor_id" to "0x144d",
                "api" to "1.3.264",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
            ),
            socHardware = "s5e9945",
        )
        assertEquals(GpuFamily.XCLIPSE, gpu.family)

        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("unified", resolved.activeBackend)
        assertTrue(resolved.requiresVirglServer)
        assertTrue(resolved.enableVenus)
        assertTrue(resolved.envVars["VK_DRIVER_FILES"]!!.contains("virtio_icd.aarch64.json"))
    }

    @Test
    fun maliWithKernelDrmIncludesPanVkAndSkipsSoftwareOverride() {
        val gpu = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Mali-G610 MC6",
                "vendor_id" to "0x13b5",
                "api" to "1.3.250",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
            ),
            hasDriRenderNode = true,
            hasMaliNode = true,
        )
        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("unified", resolved.activeBackend)
        assertTrue(resolved.envVars["VK_ICD_FILENAMES"]!!.contains("panfrost_icd.aarch64.json"))
        assertTrue(resolved.envVars["VK_ICD_FILENAMES"]!!.contains("virtio_icd.aarch64.json"))
        assertFalse(resolved.envVars.containsKey("LIBGL_ALWAYS_SOFTWARE"))
    }

    @Test
    fun snapdragonCanForceUnifiedDriverMode() {
        val gpu = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Adreno (TM) 740",
                "vendor_id" to "0x5143",
                "api" to "1.3.250",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
            ),
            hasKgslNode = true,
        )
        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.UNIFIED, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("unified", resolved.activeBackend)
        assertTrue(resolved.requiresVirglServer)
        assertTrue(resolved.enableVenus)
    }

    @Test
    fun samsungTabA11PlusDimensity7300MaliG615SelectsUnifiedVirpipeWithSoftwareLoader() {
        val gpu = GpuInfo.fromProbeMap(
            probe = mapOf(
                "device" to "Mali-G615 MC2",
                "vendor_id" to "0x13b5",
                "api" to "1.3.274",
                "ext.VK_ANDROID_external_memory_android_hardware_buffer" to "yes",
            ),
            socHardware = "mt6878 dimensity",
            hasKgslNode = false,
            hasDriRenderNode = false,
            hasMaliNode = true,
        )
        assertEquals(GpuFamily.MALI, gpu.family)

        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.AUTO, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("unified", resolved.activeBackend)
        assertTrue(resolved.requiresVirglServer)
        assertTrue(resolved.enableVenus)
        assertEquals("virpipe", resolved.envVars["GALLIUM_DRIVER"])
        assertEquals("1", resolved.envVars["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("/run/linuxdroid/virgl_test", resolved.envVars["VTEST_SOCKET_NAME"])
        assertFalse(resolved.envVars["VK_ICD_FILENAMES"]!!.contains("panfrost_icd"))
    }

    @Test
    fun softwareModeSelectsLlvmpipeAndLavapipe() {
        val gpu = GpuInfo.fromProbeMap(emptyMap())
        val resolved = GpuConfigGenerator.resolve(GpuDriverMode.SOFTWARE, gpu, "/run/linuxdroid/virgl_test")
        assertEquals("software", resolved.activeBackend)
        assertFalse(resolved.requiresVirglServer)
        assertEquals("llvmpipe", resolved.envVars["GALLIUM_DRIVER"])
        assertTrue(resolved.envVars["VK_ICD_FILENAMES"]!!.contains("lvp_icd.aarch64.json"))
    }
}
