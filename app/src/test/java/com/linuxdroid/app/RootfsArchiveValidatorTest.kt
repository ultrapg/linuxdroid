package com.linuxdroid.app

import com.linuxdroid.app.core.ArchiveCompression
import com.linuxdroid.app.core.ArchiveExtractor
import com.linuxdroid.app.core.RootfsArchiveValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsArchiveValidatorTest {
    @Test
    fun detectsCompressionFromMagicBytes() {
        val zstdHeader = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x00, 0x00)
        val xzHeader = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0x00)
        val gzipHeader = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)

        assertEquals(ArchiveCompression.ZSTD, RootfsArchiveValidator.detectCompression(null, zstdHeader))
        assertEquals(ArchiveCompression.XZ, RootfsArchiveValidator.detectCompression(null, xzHeader))
        assertEquals(ArchiveCompression.GZIP, RootfsArchiveValidator.detectCompression(null, gzipHeader))
    }

    @Test
    fun detectsCompressionFromFilenameFallback() {
        val unknownHeader = ByteArray(16)
        assertEquals(ArchiveCompression.ZSTD, RootfsArchiveValidator.detectCompression("ubuntu-kde.tar.zst", unknownHeader))
        assertEquals(ArchiveCompression.XZ, RootfsArchiveValidator.detectCompression("debian-arm64.tar.xz", unknownHeader))
        assertEquals(ArchiveCompression.GZIP, RootfsArchiveValidator.detectCompression("alpine.tgz", unknownHeader))
        assertNull(RootfsArchiveValidator.detectCompression("notes.txt", unknownHeader))
        assertTrue(RootfsArchiveValidator.isSupportedFilename("linuxdroid-ubuntu-xfce-arm64.tar.zst"))
        assertFalse(RootfsArchiveValidator.isSupportedFilename("archive.zip"))
    }

    @Test
    fun sanitizesTarPathsAndRejectsTraversal() {
        assertEquals("usr/bin/bash", ArchiveExtractor.sanitizeEntryPath("./usr/bin/bash"))
        assertEquals("etc/passwd", ArchiveExtractor.sanitizeEntryPath("/etc/passwd"))
        assertEquals("usr/local/bin", ArchiveExtractor.sanitizeEntryPath("usr/share/../../usr/local/bin"))
        assertNull(ArchiveExtractor.sanitizeEntryPath("../../etc/shadow"))
    }
}
