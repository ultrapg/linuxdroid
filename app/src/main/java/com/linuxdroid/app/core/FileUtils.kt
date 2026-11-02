package com.linuxdroid.app.core

import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

object FileUtils {
    @JvmStatic
    fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun readString(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    }

    @JvmStatic
    fun writeString(file: File, content: String): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
        true
    } catch (_: IOException) {
        false
    }

    @JvmStatic
    fun deleteRecursively(target: File): Boolean {
        try {
            val path = target.toPath()
            if (Files.isSymbolicLink(path)) {
                return Files.deleteIfExists(path)
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                chmod(target, 0b111_000_000)
                target.listFiles()?.forEach { child -> deleteRecursively(child) }
            }
            return Files.deleteIfExists(path)
        } catch (_: Exception) {
            return target.delete()
        }
    }
}
