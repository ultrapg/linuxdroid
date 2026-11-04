package com.linuxdroid.app

import com.linuxdroid.app.runtime.DesktopCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesktopCatalogSyncTest {

    private val sha256Regex = Regex("^[0-9a-f]{64}$")

    @Test
    fun fallbackEditionsHaveValidIntegrityMetadataAndLatestUrls() {
        val fallback = DesktopCatalog.FALLBACK_EDITIONS
        assertEquals(listOf("ubuntu-kde-arm64", "ubuntu-xfce-arm64"), fallback.map { it.id })
        for (edition in fallback) {
            assertTrue("Invalid sha256 for ${edition.id}: ${edition.sha256}", sha256Regex.matches(edition.sha256))
            assertTrue("Invalid sizeBytes for ${edition.id}: ${edition.sizeBytes}", edition.sizeBytes > 100_000_000L)
            assertTrue(
                "Fallback downloadUrl must use releases/latest/download: ${edition.downloadUrl}",
                edition.downloadUrl.startsWith("https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/")
            )
        }
    }

    @Test
    fun fallbackEditionsMatchDistCatalogJsonAndLocalArchivesWhenPresent() {
        val catalogFile = listOf(
            File("dist/catalog.json"),
            File("../dist/catalog.json"),
        ).firstOrNull { it.isFile } ?: return

        val parsed = DesktopCatalog.parseCatalogJson(catalogFile.readText())
        assertTrue(
            "DesktopCatalog.parseCatalogJson returned empty list for ${catalogFile.path}",
            parsed.isNotEmpty()
        )

        val fallback = DesktopCatalog.FALLBACK_EDITIONS
        assertEquals(
            "Edition IDs in DesktopCatalog.FALLBACK_EDITIONS must match dist/catalog.json",
            parsed.map { it.id },
            fallback.map { it.id }
        )

        val expectedReleasePrefix =
            "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/v${BuildConfig.VERSION_NAME}/"
        val distDir = catalogFile.parentFile

        for (i in parsed.indices) {
            val fromJson = parsed[i]
            val fromFallback = fallback[i]
            assertEquals("Edition desktop mismatch for ${fromJson.id}", fromJson.desktop, fromFallback.desktop)
            assertEquals("Edition title mismatch for ${fromJson.id}", fromJson.title, fromFallback.title)
            assertEquals(
                "Stale FALLBACK_EDITIONS sha256 for ${fromJson.id} (must match dist/catalog.json)",
                fromJson.sha256,
                fromFallback.sha256
            )
            assertEquals(
                "Stale FALLBACK_EDITIONS sizeBytes for ${fromJson.id} (must match dist/catalog.json)",
                fromJson.sizeBytes,
                fromFallback.sizeBytes
            )
            assertTrue(
                "dist/catalog.json URL for ${fromJson.id} must target v${BuildConfig.VERSION_NAME}, got ${fromJson.downloadUrl}",
                fromJson.downloadUrl.startsWith(expectedReleasePrefix)
            )

            val archiveName = fromJson.downloadUrl.substringAfterLast('/')
            val archiveFile = File(distDir, archiveName)
            if (archiveFile.isFile) {
                assertEquals(
                    "Archive byte length on disk for $archiveName must match catalog sizeBytes",
                    archiveFile.length(),
                    fromFallback.sizeBytes
                )
            }
        }
    }
}
