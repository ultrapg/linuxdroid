package com.linuxdroid.app.gpu

import android.os.Build
import java.io.File

enum class GpuFamily(val label: String) {
    ADRENO("Qualcomm Adreno"),
    IMMORTALIS("ARM Immortalis (MediaTek)"),
    MALI("ARM Mali (MediaTek / Exynos / Tensor)"),
    XCLIPSE("Samsung Xclipse (AMD RDNA)"),
    OTHER("Mobile SoC GPU"),
}

data class GpuInfo(
    val deviceName: String,
    val driverName: String,
    val apiVersion: String,
    val vendorId: String,
    val family: GpuFamily,
    val adrenoModel: Int,
    val hasVulkan: Boolean,
    val hasAhbImport: Boolean,
    val hasDmaBuf: Boolean,
    val hasDrmModifier: Boolean,
    val hasKgslNode: Boolean,
    val hasDriRenderNode: Boolean,
    val hasMaliNode: Boolean,
    val deviceHeapMb: Long,
) {
    val supportsTurnipKgsl: Boolean
        get() = family == GpuFamily.ADRENO && hasKgslNode

    val supportsUnifiedVenus: Boolean
        get() = hasVulkan && (hasAhbImport || hasDmaBuf)

    fun shortSummary(): String {
        val name = deviceName.ifBlank { family.label }
        val vk = if (hasVulkan) "Vulkan $apiVersion" else "GLES Only"
        return "$name ($vk)"
    }

    companion object {
        @Volatile
        private var cached: GpuInfo? = null

        fun detect(): GpuInfo {
            cached?.let { return it }
            val map = VulkanInfo.querySafe()
            val detected = fromProbeMap(
                probe = map,
                socHardware = "${Build.HARDWARE} ${Build.BOARD} ${socManufacturerOrEmpty()} ${socModelOrEmpty()}",
                hasKgslNode = File("/dev/kgsl-3d0").exists(),
                hasDriRenderNode = File("/dev/dri/renderD128").exists(),
                hasMaliNode = File("/dev/mali0").exists(),
            )
            cached = detected
            return detected
        }

        fun fromProbeMap(
            probe: Map<String, String>,
            socHardware: String = "",
            hasKgslNode: Boolean = false,
            hasDriRenderNode: Boolean = false,
            hasMaliNode: Boolean = false,
        ): GpuInfo {
            val device = probe["device"].orEmpty()
            val driver = probe["driver_name"].orEmpty()
            val vendorId = probe["vendor_id"].orEmpty().lowercase()
            val api = probe["api"].orEmpty()
            val hasVulkan = device.isNotEmpty() && !probe.containsKey("error")
            val combined = "$device $driver $socHardware".lowercase()

            val family = classifyFamily(combined, vendorId, hasKgslNode, hasMaliNode)
            val adrenoModel = parseAdrenoModel(device)

            return GpuInfo(
                deviceName = device.ifEmpty { family.label },
                driverName = driver,
                apiVersion = api.ifEmpty { "1.1" },
                vendorId = vendorId,
                family = family,
                adrenoModel = adrenoModel,
                hasVulkan = hasVulkan,
                hasAhbImport = probe["ext.VK_ANDROID_external_memory_android_hardware_buffer"] == "yes",
                hasDmaBuf = probe["ext.VK_EXT_external_memory_dma_buf"] == "yes",
                hasDrmModifier = probe["ext.VK_EXT_image_drm_format_modifier"] == "yes",
                hasKgslNode = hasKgslNode,
                hasDriRenderNode = hasDriRenderNode,
                hasMaliNode = hasMaliNode,
                deviceHeapMb = probe["device_heap_mb"]?.toLongOrNull() ?: 0L,
            )
        }

        fun classifyFamily(
            combinedLower: String,
            vendorIdLower: String,
            hasKgslNode: Boolean = false,
            hasMaliNode: Boolean = false,
        ): GpuFamily = when {
            "adreno" in combinedLower || "qualcomm" in combinedLower || vendorIdLower == "0x5143" || hasKgslNode ->
                GpuFamily.ADRENO
            "xclipse" in combinedLower || "samsung" in combinedLower && "amd" in combinedLower || vendorIdLower == "0x144d" ->
                GpuFamily.XCLIPSE
            "immortalis" in combinedLower ->
                GpuFamily.IMMORTALIS
            "mali" in combinedLower || vendorIdLower == "0x13b5" || hasMaliNode || "mt6" in combinedLower || "dimensity" in combinedLower || "helio" in combinedLower ->
                GpuFamily.MALI
            else ->
                GpuFamily.OTHER
        }

        fun parseAdrenoModel(deviceName: String): Int {
            val match = Regex("""(?i)adreno[^\d]*(\d{3,4})""").find(deviceName) ?: return 0
            return match.groupValues[1].toIntOrNull() ?: 0
        }

        private fun socManufacturerOrEmpty(): String =
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""

        private fun socModelOrEmpty(): String =
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
    }
}
