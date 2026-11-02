package com.linuxdroid.app.core

import android.content.Context
import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

enum class ArchiveCompression {
    ZSTD,
    XZ,
    GZIP,
    PLAIN_TAR,
}

object RootfsArchiveValidator {
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0x00)
    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

    fun detectCompression(fileName: String?, header: ByteArray): ArchiveCompression? {
        if (startsWith(header, ZSTD_MAGIC)) return ArchiveCompression.ZSTD
        if (startsWith(header, XZ_MAGIC)) return ArchiveCompression.XZ
        if (startsWith(header, GZIP_MAGIC)) return ArchiveCompression.GZIP
        if (isUstarHeader(header)) return ArchiveCompression.PLAIN_TAR

        val lower = fileName?.trim()?.lowercase() ?: return null
        return when {
            lower.endsWith(".tar.zst") || lower.endsWith(".tzst") -> ArchiveCompression.ZSTD
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveCompression.XZ
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveCompression.GZIP
            lower.endsWith(".tar") -> ArchiveCompression.PLAIN_TAR
            else -> null
        }
    }

    fun isSupportedFilename(fileName: String): Boolean {
        val lower = fileName.trim().lowercase()
        return lower.endsWith(".tar.zst") ||
            lower.endsWith(".tzst") ||
            lower.endsWith(".tar.xz") ||
            lower.endsWith(".txz") ||
            lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") ||
            lower.endsWith(".tar")
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun isUstarHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 262) return false
        return bytes[257] == 'u'.code.toByte() &&
            bytes[258] == 's'.code.toByte() &&
            bytes[259] == 't'.code.toByte() &&
            bytes[260] == 'a'.code.toByte() &&
            bytes[261] == 'r'.code.toByte()
    }
}

object ArchiveExtractor {
    private data class DeferredLink(val target: File, val linkDest: String, val hardLink: Boolean)

    fun extractAsset(context: Context, assetPath: String, destDir: File): Boolean = try {
        context.assets.open(assetPath).use { raw ->
            extractStream(raw, assetPath, destDir, null)
        }
        true
    } catch (_: Exception) {
        false
    }

    fun extractFile(archiveFile: File, destDir: File, onEntry: ((Long, String) -> Unit)? = null) {
        FileInputStream(archiveFile).use { raw ->
            extractStream(raw, archiveFile.name, destDir, onEntry)
        }
    }

    fun extractStream(
        rawStream: InputStream,
        fileNameHint: String?,
        destDir: File,
        onEntry: ((Long, String) -> Unit)? = null,
    ) {
        destDir.mkdirs()
        val canonDest = destDir.canonicalPath + File.separator
        val buffered = BufferedInputStream(rawStream, 256 * 1024)
        buffered.mark(512)
        val header = ByteArray(512)
        var totalRead = 0
        while (totalRead < header.size) {
            val n = buffered.read(header, totalRead, header.size - totalRead)
            if (n <= 0) break
            totalRead += n
        }
        buffered.reset()
        val actualHeader = if (totalRead == header.size) header else header.copyOf(totalRead)
        val compression = RootfsArchiveValidator.detectCompression(fileNameHint, actualHeader)
            ?: throw IOException("Unsupported archive format (expected .tar.zst, .tar.xz, or .tar.gz)")

        val decompressed: InputStream = when (compression) {
            ArchiveCompression.ZSTD -> ZstdInputStream(buffered)
            ArchiveCompression.XZ -> XZInputStream(buffered)
            ArchiveCompression.GZIP -> GzipCompressorInputStream(buffered, true)
            ArchiveCompression.PLAIN_TAR -> buffered
        }

        val deferredLinks = ArrayList<DeferredLink>()
        val copyBuf = ByteArray(128 * 1024)
        var extractedCount = 0L

        TarArchiveInputStream(BufferedInputStream(decompressed, 256 * 1024)).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val relName = sanitizeEntryPath(entry.name) ?: continue
                if (relName.isEmpty()) continue
                val outFile = File(destDir, relName)
                val parentCanon = (outFile.parentFile ?: destDir).apply { mkdirs() }.canonicalPath + File.separator
                if (!parentCanon.startsWith(canonDest) && outFile.parentFile?.canonicalPath != destDir.canonicalPath) {
                    continue
                }

                // Skip special device nodes (c/b/fifo) which cannot be mknod'd unprivileged; PRoot binds /dev.
                if (entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO) continue

                extractedCount++
                if (extractedCount % 200L == 0L) {
                    onEntry?.invoke(extractedCount, relName)
                }

                when {
                    entry.isDirectory -> {
                        if (Files.isSymbolicLink(outFile.toPath())) Files.deleteIfExists(outFile.toPath())
                        outFile.mkdirs()
                        applyMode(outFile, entry.mode, true)
                    }
                    entry.isSymbolicLink -> {
                        deferredLinks.add(DeferredLink(outFile, entry.linkName, hardLink = false))
                    }
                    entry.isLink -> {
                        val targetRel = sanitizeEntryPath(entry.linkName) ?: continue
                        deferredLinks.add(DeferredLink(outFile, targetRel, hardLink = true))
                    }
                    entry.isFile -> {
                        if (Files.exists(outFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(outFile.toPath())
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val r = tar.read(copyBuf)
                                if (r < 0) break
                                if (r > 0) out.write(copyBuf, 0, r)
                            }
                        }
                        applyMode(outFile, entry.mode, false)
                    }
                }
            }
        }

        for (link in deferredLinks) {
            try {
                val outPath = link.target.toPath()
                if (Files.exists(outPath, LinkOption.NOFOLLOW_LINKS)) {
                    FileUtils.deleteRecursively(link.target)
                }
                link.target.parentFile?.mkdirs()
                if (link.hardLink) {
                    val src = File(destDir, link.linkDest)
                    if (!src.exists()) continue
                    try {
                        Os.link(src.absolutePath, link.target.absolutePath)
                    } catch (_: Exception) {
                        // Fall back to relative symlink or file copy if hardlink not allowed
                        if (Files.isSymbolicLink(src.toPath())) {
                            Os.symlink(Os.readlink(src.absolutePath), link.target.absolutePath)
                        } else {
                            src.copyTo(link.target, overwrite = true)
                            if (src.canExecute()) FileUtils.chmod(link.target, 0b111_101_101)
                        }
                    }
                } else {
                    Os.symlink(link.linkDest, link.target.absolutePath)
                }
            } catch (_: Exception) {
            }
        }
        onEntry?.invoke(extractedCount, "done")
    }

    internal fun sanitizeEntryPath(raw: String): String? {
        var cleaned = raw.trim().replace('\\', '/')
        while (cleaned.startsWith("./")) cleaned = cleaned.substring(2)
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1)
        if (cleaned.isEmpty() || cleaned == ".") return ""
        val parts = ArrayList<String>()
        for (segment in cleaned.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") {
                if (parts.isEmpty()) return null
                parts.removeAt(parts.lastIndex)
            } else {
                parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun applyMode(file: File, tarMode: Int, isDir: Boolean) {
        val perms = tarMode and 0xFFF
        val effective = when {
            perms != 0 -> perms or (if (isDir) 0b111_000_000 else 0b110_000_000)
            isDir -> 0b111_101_101 // 0755
            else -> 0b110_100_100 // 0644
        }
        FileUtils.chmod(file, effective)
    }
}
