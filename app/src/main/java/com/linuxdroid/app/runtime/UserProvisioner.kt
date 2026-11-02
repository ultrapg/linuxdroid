package com.linuxdroid.app.runtime

import com.linuxdroid.app.core.FileUtils
import java.io.File

object UserProvisioner {
    private val USERNAME_REGEX = Regex("^[a-z_][a-z0-9_-]{0,30}$")
    private val MOUNT_NAME_REGEX = Regex("[^A-Za-z0-9._-]+")

    private val SUPPLEMENTAL_GROUPS = listOf(
        Triple("sudo", 27, true),
        Triple("video", 44, true),
        Triple("audio", 29, true),
        Triple("render", 109, true),
        Triple("plugdev", 46, true),
        Triple("sdcard_rw", 1015, true),
        Triple("media_rw", 1023, true),
        Triple("everybody", 9997, true),
    )

    fun sanitizeUsername(raw: String?): String {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty() || trimmed == "root" || !USERNAME_REGEX.matches(trimmed)) {
            return "linux"
        }
        return trimmed
    }

    fun sanitizeMountName(raw: String?): String {
        val cleaned = raw?.trim()?.replace(MOUNT_NAME_REGEX, "-")?.trim('-', '.').orEmpty()
        return cleaned.ifEmpty { "shared" }
    }

    fun buildPasswd(existing: String?, username: String, hostUid: Int): String {
        val safeUser = sanitizeUsername(username)
        val home = "/home/$safeUser"
        val userEntry = "$safeUser:x:$hostUid:$hostUid:Linux Workstation User:$home:/bin/bash"
        val rootEntry = "root:x:0:0:root:/root:/bin/bash"
        val uidStr = hostUid.toString()

        return buildString {
            append(rootEntry).append('\n')
            append(userEntry).append('\n')
            existing?.lineSequence()?.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val fields = line.split(':')
                if (fields.size < 3) return@forEach
                val name = fields[0]
                val uid = fields[2]
                if (name == "root" || name == safeUser || uid == "0" || uid == uidStr) return@forEach
                append(line).append('\n')
            }
        }
    }

    fun buildGroup(existing: String?, username: String, hostUid: Int): String {
        val safeUser = sanitizeUsername(username)
        val uidStr = hostUid.toString()
        val seenNames = HashSet<String>()
        val seenGids = HashSet<String>()
        val extraLines = ArrayList<String>()

        existing?.lineSequence()?.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val fields = line.split(':', limit = 4).toMutableList()
            if (fields.size < 4) return@forEach
            val gname = fields[0]
            val gid = fields[2]
            if (gname == "root" || gname == safeUser || gid == "0" || gid == uidStr) return@forEach
            val supMatch = SUPPLEMENTAL_GROUPS.firstOrNull { it.first == gname || it.second.toString() == gid }
            if (supMatch != null) {
                val members = fields[3].split(',').filter { it.isNotBlank() }.toMutableSet()
                members.add(safeUser)
                members.add("root")
                fields[3] = members.joinToString(",")
                seenNames.add(supMatch.first)
                seenGids.add(supMatch.second.toString())
            }
            seenNames.add(gname)
            seenGids.add(gid)
            extraLines.add(fields.joinToString(":"))
        }

        return buildString {
            append("root:x:0:root\n")
            append("$safeUser:x:$hostUid:$safeUser\n")
            for (line in extraLines) append(line).append('\n')
            for ((gname, gid, _) in SUPPLEMENTAL_GROUPS) {
                if (gname !in seenNames && gid.toString() !in seenGids) {
                    append("$gname:x:$gid:$safeUser,root\n")
                }
            }
        }
    }

    fun provisionRootfs(rootDir: File, username: String, hostUid: Int): String {
        val safeUser = sanitizeUsername(username)
        val etcDir = File(rootDir, "etc").apply { mkdirs() }
        val passwdFile = File(etcDir, "passwd")
        val groupFile = File(etcDir, "group")

        FileUtils.writeString(passwdFile, buildPasswd(FileUtils.readString(passwdFile), safeUser, hostUid))
        FileUtils.writeString(groupFile, buildGroup(FileUtils.readString(groupFile), safeUser, hostUid))

        val sudoersDir = File(etcDir, "sudoers.d").apply { mkdirs() }
        val sudoRule = File(sudoersDir, "90-linuxdroid")
        FileUtils.writeString(sudoRule, "$safeUser ALL=(ALL:ALL) NOPASSWD: ALL\n")
        FileUtils.chmod(sudoRule, 0b100_100_000)

        val homeDir = File(rootDir, "home/$safeUser")
        homeDir.mkdirs()
        FileUtils.chmod(homeDir, 0b111_101_101)
        File(rootDir, "root").mkdirs()
        return safeUser
    }
}
