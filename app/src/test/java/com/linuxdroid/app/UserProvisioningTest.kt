package com.linuxdroid.app

import com.linuxdroid.app.runtime.UserProvisioner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserProvisioningTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sanitizesUsernamesAndPreventsRootAsDefaultUser() {
        assertEquals("linux", UserProvisioner.sanitizeUsername(null))
        assertEquals("linux", UserProvisioner.sanitizeUsername(""))
        assertEquals("linux", UserProvisioner.sanitizeUsername("root"))
        assertEquals("linux", UserProvisioner.sanitizeUsername("123bad"))
        assertEquals("dev_user", UserProvisioner.sanitizeUsername("Dev_User"))
    }

    @Test
    fun sanitizesMountNames() {
        assertEquals("SD-Card-1", UserProvisioner.sanitizeMountName("SD Card #1!"))
        assertEquals("shared", UserProvisioner.sanitizeMountName("..."))
    }

    @Test
    fun provisionsPasswdGroupAndPasswordlessSudo() {
        val rootDir = tempFolder.newFolder("linuxfs")
        File(rootDir, "etc").mkdirs()
        File(rootDir, "etc/passwd").writeText("nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n")
        File(rootDir, "etc/group").writeText("nogroup:x:65534:\nsudo:x:27:ubuntu\n")

        val provisioned = UserProvisioner.provisionRootfs(rootDir, "workstation", 10234)
        assertEquals("workstation", provisioned)

        val passwd = File(rootDir, "etc/passwd").readText()
        assertTrue(passwd.contains("root:x:0:0:root:/root:/bin/bash"))
        assertTrue(passwd.contains("workstation:x:10234:10234:Linux Workstation User:/home/workstation:/bin/bash"))
        assertTrue(passwd.contains("nobody:x:65534:65534:"))

        val group = File(rootDir, "etc/group").readText()
        assertTrue(group.contains("sudo:x:27:ubuntu,workstation,root"))
        assertTrue(group.contains("video:x:44:workstation,root"))
        assertTrue(group.contains("render:x:109:workstation,root"))
        assertTrue(group.contains("sdcard_rw:1015").not())
        assertTrue(group.contains("sdcard_rw:x:1015:workstation,root"))

        val sudoers = File(rootDir, "etc/sudoers.d/90-linuxdroid").readText()
        assertEquals("workstation ALL=(ALL:ALL) NOPASSWD: ALL\n", sudoers)
        assertTrue(File(rootDir, "home/workstation").isDirectory)
    }
}
