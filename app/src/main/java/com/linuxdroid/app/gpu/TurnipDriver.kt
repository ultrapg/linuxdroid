package com.linuxdroid.app.gpu

import android.content.Context
import android.os.Build
import android.util.Log
import com.linuxdroid.app.core.ArchiveExtractor
import com.linuxdroid.app.core.FileUtils
import org.json.JSONObject
import java.io.File

object TurnipDriver {
    private const val TAG = "TurnipDriver"
    private const val DRIVER_A7XX = "adrenotools-turnip25.1.0"
    private const val DRIVER_A8XX = "adrenotools-turnip-sdk36"
    private const val GUEST_LINUX_TURNIP = "linux-turnip-dd-0.5.0"
    private const val EXPECTED_DD_VERSION = "DD-Turnip-v0.5.0"

    data class HostVulkanDriver(val driverDir: String, val libraryName: String)

    private fun readMetaLibraryName(dir: File, expectedVersion: String? = null): String? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return null
        return runCatching {
            val obj = JSONObject(metaFile.readText())
            if (expectedVersion != null && obj.optString("driverVersion") != expectedVersion) {
                return@runCatching null
            }
            val name = obj.optString("libraryName").trim()
            if (name.isNotEmpty() && File(dir, name).isFile) name else null
        }.getOrNull()
    }

    /**
     * Resolves an AdrenoTools host Vulkan driver for the Wayland presentation swapchain ONLY on
     * Qualcomm Adreno 7xx/8xx GPUs. On Adreno 6xx, MediaTek Mali/Immortalis, and Samsung Exynos Xclipse,
     * returns null so WaylandCompositor uses Android's native system libvulkan.so cleanly.
     */
    fun prepareHostPresenterDriver(context: Context, gpu: GpuInfo): HostVulkanDriver? {
        if (gpu.family != GpuFamily.ADRENO) return null
        if (gpu.adrenoModel in 1..699) return null

        val useA8xx = gpu.adrenoModel >= 800 || Build.VERSION.SDK_INT >= 36
        val packName = if (useA8xx) DRIVER_A8XX else DRIVER_A7XX
        val expectedVer = if (useA8xx) EXPECTED_DD_VERSION else null
        val assetPath = "graphics_driver/$packName.tzst"
        val outDir = File(context.filesDir, "graphics_driver/$packName")
        val existingLib = readMetaLibraryName(outDir, expectedVer)
        if (existingLib == null) {
            FileUtils.deleteRecursively(outDir)
            outDir.mkdirs()
            if (!ArchiveExtractor.extractAsset(context, assetPath, outDir)) {
                Log.w(TAG, "Could not unpack $assetPath; using system Android Vulkan")
                return null
            }
        }
        val soName = readMetaLibraryName(outDir, expectedVer)
            ?: outDir.listFiles()?.firstOrNull { it.name.endsWith(".so") }?.name
            ?: return null
        FileUtils.chmod(File(outDir, soName), 0b111_101_101)
        return HostVulkanDriver(outDir.absolutePath + "/", soName)
    }

    /**
     * Ensures the guest Ubuntu rootfs has the patched DD-Turnip v0.5.0 Linux driver
     * (`/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so`) installed even when upgrading an
     * existing rootfs without re-downloading the rootfs archive.
     */
    fun stageGuestLinuxTurnipDriver(context: Context, rootDir: File) {
        val usrLib = File(rootDir, "usr/lib/aarch64-linux-gnu")
        if (!usrLib.isDirectory) return
        val stampFile = File(rootDir, "etc/linuxdroid/turnip-driver-version")
        val targetSo = File(usrLib, "libvulkan_freedreno.so")
        val currentStamp = runCatching { stampFile.readText().trim() }.getOrDefault("")
        if (currentStamp == EXPECTED_DD_VERSION && targetSo.isFile && targetSo.length() > 8_000_000L) {
            return
        }
        val tmpDir = File(context.cacheDir, "guest-turnip-stage")
        try {
            FileUtils.deleteRecursively(tmpDir)
            tmpDir.mkdirs()
            if (!ArchiveExtractor.extractAsset(context, "graphics_driver/$GUEST_LINUX_TURNIP.tzst", tmpDir)) {
                Log.w(TAG, "Could not unpack guest $GUEST_LINUX_TURNIP.tzst")
                return
            }
            val extractedSo = File(tmpDir, "libvulkan_freedreno.so")
            if (!extractedSo.isFile) return
            val stagedTmp = File(usrLib, "libvulkan_freedreno.so.new")
            extractedSo.copyTo(stagedTmp, overwrite = true)
            FileUtils.chmod(stagedTmp, 0b111_101_101)
            if (!stagedTmp.renameTo(targetSo)) {
                stagedTmp.copyTo(targetSo, overwrite = true)
                stagedTmp.delete()
                FileUtils.chmod(targetSo, 0b111_101_101)
            }
            val icdDir = File(rootDir, "usr/share/vulkan/icd.d").apply { mkdirs() }
            val icdJson = """
                {
                    "ICD": {
                        "api_version": "1.4.363",
                        "library_arch": "64",
                        "library_path": "/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
                    },
                    "file_format_version": "1.0.1"
                }
            """.trimIndent() + "\n"
            for (icdName in listOf("freedreno_icd.aarch64.json", "freedreno_icd.json")) {
                val icdFile = File(icdDir, icdName)
                FileUtils.writeString(icdFile, icdJson)
                FileUtils.chmod(icdFile, 0b110_100_100)
            }
            stampFile.parentFile?.mkdirs()
            FileUtils.writeString(stampFile, "$EXPECTED_DD_VERSION\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed staging guest DD-Turnip driver", e)
        } finally {
            FileUtils.deleteRecursively(tmpDir)
        }
    }
}
