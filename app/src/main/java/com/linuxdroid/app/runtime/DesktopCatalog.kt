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
            description = "KDE Plasma Breeze desktop with Dolphin, Konsole, Kate, Okular, Chromium, MPV, FEX-Emu, and hardware Vulkan/OpenGL.",
            downloadUrl = "$RELEASE_BASE/linuxdroid-ubuntu-kde-arm64.tar.zst",
            sha256 = "ddbebc68c0fa3b9cd8b32976fa187b31698e0fa2cc7677ed0965228028de7875",
            sizeBytes = 771_290_400L,
        ),
        DesktopEdition(
            id = "ubuntu-xfce-arm64",
            title = "Ubuntu 24.04 LTS — XFCE4 Workstation",
            desktop = "xfce",
            description = "Lightweight XFCE4 desktop with Thunar, XFCE Terminal, Mousepad, Atril, Chromium, MPV, FEX-Emu, and hardware Vulkan/OpenGL.",
            downloadUrl = "$RELEASE_BASE/linuxdroid-ubuntu-xfce-arm64.tar.zst",
            sha256 = "efdd3058e743a81bd94f9c6583f65ec692d4c42a4d14da837e4827ba34c1b74c",
            sizeBytes = 609_085_114L,
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
