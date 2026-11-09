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
        fetchFromUrl(BuildConfig.CATALOG_URL, isOctetStream = false)?.let { parsed ->
            if (parsed.isNotEmpty()) return parsed
        }
        fetchCatalogViaGitHubApi()?.let { parsed ->
            if (parsed.isNotEmpty()) return parsed
        }
        return FALLBACK_EDITIONS
    }

    fun resolveGitHubAssetApiUrl(repo: String, assetFileName: String, expectedSizeBytes: Long = 0L): String? {
        if (assetFileName.isBlank()) return null
        val apiEndpoints = listOf(
            "https://api.github.com/repos/$repo/releases/latest",
            "https://api.github.com/repos/$repo/releases?per_page=5",
        )
        for (endpoint in apiEndpoints) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LinuxDroid/1.0")
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val releases = if (text.trimStart().startsWith("[")) {
                    val arr = org.json.JSONArray(text)
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                } else {
                    listOf(JSONObject(text))
                }
                for (rel in releases) {
                    val assets = rel.optJSONArray("assets") ?: continue
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name") == assetFileName) {
                            val size = asset.optLong("size", 0L)
                            if (expectedSizeBytes <= 0L || size == expectedSizeBytes) {
                                val apiUrl = asset.optString("url")
                                if (apiUrl.isNotBlank()) return apiUrl
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchCatalogViaGitHubApi(): List<DesktopEdition>? {
        val assetApiUrl = resolveGitHubAssetApiUrl(BuildConfig.GITHUB_REPO, "catalog.json") ?: return null
        return fetchFromUrl(assetApiUrl, isOctetStream = true)
    }

    private fun fetchFromUrl(url: String, isOctetStream: Boolean): List<DesktopEdition>? {
        return try {
            var current = url
            repeat(5) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "LinuxDroid/1.0")
                    if (isOctetStream && current.contains("://api.github.com/")) {
                        setRequestProperty("Accept", "application/octet-stream")
                    } else {
                        setRequestProperty("Accept", "application/json")
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: run {
                        conn.disconnect()
                        return null
                    }
                    current = URL(URL(current), loc).toString()
                    conn.disconnect()
                } else if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    return parseCatalogJson(body)
                } else {
                    conn.disconnect()
                    return null
                }
            }
            null
        } catch (_: Exception) {
            null
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
