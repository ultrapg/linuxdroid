package com.linuxdroid.app.gpu

import android.content.Context
import android.os.Build
import android.util.Log
import com.linuxdroid.app.core.ArchiveExtractor
import com.linuxdroid.app.core.FileUtils
import java.io.File

object TurnipDriver {
    private const val TAG = "TurnipDriver"
    private const val DRIVER_A7XX = "adrenotools-turnip25.1.0"
    private const val DRIVER_A8XX = "adrenotools-turnip-sdk36"

    data class HostVulkanDriver(val driverDir: String, val libraryName: String)

    /**
     * Resolves an AdrenoTools host Vulkan driver for the Wayland presentation swapchain ONLY on
     * Qualcomm Adreno 7xx/8xx GPUs. On Adreno 6xx, MediaTek Mali/Immortalis, and Samsung Exynos Xclipse,
     * returns null so WaylandCompositor uses Android's native system libvulkan.so cleanly.
     */
    fun prepareHostPresenterDriver(context: Context, gpu: GpuInfo): HostVulkanDriver? {
        if (gpu.family != GpuFamily.ADRENO) return null
        if (gpu.adrenoModel in 1..699) return null

        val packName = if (gpu.adrenoModel >= 800 || Build.VERSION.SDK_INT >= 36) DRIVER_A8XX else DRIVER_A7XX
        val assetPath = "graphics_driver/$packName.tzst"
        val outDir = File(context.filesDir, "graphics_driver/$packName")
        val libFile = File(outDir, "vulkan.adreno.so")
        if (!libFile.isFile) {
            FileUtils.deleteRecursively(outDir)
            outDir.mkdirs()
            if (!ArchiveExtractor.extractAsset(context, assetPath, outDir)) {
                Log.w(TAG, "Could not unpack $assetPath; using system Android Vulkan")
                return null
            }
        }
        val soName = outDir.listFiles()?.firstOrNull { it.name.endsWith(".so") }?.name ?: return null
        FileUtils.chmod(File(outDir, soName), 0b111_101_101)
        return HostVulkanDriver(outDir.absolutePath + "/", soName)
    }
}
