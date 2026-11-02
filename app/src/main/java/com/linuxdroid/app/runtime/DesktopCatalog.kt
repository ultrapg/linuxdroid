package com.linuxdroid.app.runtime

import com.linuxdroid.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DesktopEdition(
    val id: String,
    val title: String,
    val desktop: String,
    val description: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    fun formattedSize(): String {
        if (sizeBytes <= 0L) return "ARM64 Archive"
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }
}

object DesktopCatalog {
    private const val RELEASE_BASE = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download"

    val FALLBACK_EDITIONS: List<DesktopEdition> = listOf(
        DesktopEdition(
            id = "ubuntu-kde-arm64",
            title = "Ubuntu 24.04 LTS — KDE Plasma (Wayland)",
            desktop = "kde",
            description = "Full authentic KDE Plasma Breeze desktop with Konsole, Dolphin, Kate, Okular, Firefox, MPV, FEX-Emu, and hardware SoC Vulkan/OpenGL.",
            downloadUrl = "$RELEASE_BASE/linuxdroid-ubuntu-kde-arm64.tar.zst",
            sha256 = "b776d1b94232170a80d793df4bba5bc80d3c85eef6bdc58903fdaf70f4e8a50f",
            sizeBytes = 721_088_199L,
        ),
        DesktopEdition(
            id = "ubuntu-xfce-arm64",
            title = "Ubuntu 24.04 LTS — XFCE4 Workstation",
            desktop = "xfce",
            description = "Lightweight stock XFCE4 desktop with Thunar, XFCE Terminal, Mousepad, Atril, Firefox, MPV, FEX-Emu, and hardware SoC Vulkan/OpenGL.",
            downloadUrl = "$RELEASE_BASE/linuxdroid-ubuntu-xfce-arm64.tar.zst",
            sha256 = "9775d3a07345e6281af69b9c4207761649fd8afdf4754df7f34604a984ceed4a",
            sizeBytes = 559_735_428L,
        ),
    )

    fun fetchCatalogOrFallback(): List<DesktopEdition> {
        return try {
            val conn = (URL(BuildConfig.CATALOG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return FALLBACK_EDITIONS
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseCatalogJson(body).ifEmpty { FALLBACK_EDITIONS }
        } catch (_: Exception) {
            FALLBACK_EDITIONS
        }
    }

    fun parseCatalogJson(jsonText: String): List<DesktopEdition> {
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("editions") ?: return emptyList()
        val list = ArrayList<DesktopEdition>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            list.add(
                DesktopEdition(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    desktop = item.optString("desktop", "auto"),
                    description = item.optString("description", ""),
                    downloadUrl = item.getString("url"),
                    sha256 = item.optString("sha256", ""),
                    sizeBytes = item.optLong("size_bytes", 0L),
                )
            )
        }
        return list
    }
}
