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
        assertTrue(group.contains("inet:x:3003:workstation,root"))
        assertTrue(group.contains("everybody:x:9997:workstation,root"))
        assertTrue(group.contains("u0_a234_cache:x:20234:workstation,root"))
        assertTrue(group.contains("u0_a234_ext_cache:x:40234:workstation,root"))
        assertTrue(group.contains("all_a234:x:50234:workstation,root"))

        val sudoers = File(rootDir, "etc/sudoers.d/90-linuxdroid").readText()
        assertEquals("workstation ALL=(ALL:ALL) NOPASSWD: ALL\n", sudoers)
        assertTrue(File(rootDir, "home/workstation").isDirectory)
    }

    @Test
    fun provisionsAndroidSupplementaryGidsForPrimaryAndWorkProfileUids() {
        val rootDir = tempFolder.newFolder("linuxfs_android_gids")
        UserProvisioner.provisionRootfs(
            rootDir = rootDir,
            username = "linux",
            hostUid = 10948,
            extraGids = intArrayOf(3003, 20948, 50948, 9997, 77777),
        )
        val group = File(rootDir, "etc/group").readText()
        assertTrue(group.contains("inet:x:3003:linux,root"))
        assertTrue(group.contains("u0_a948_cache:x:20948:linux,root"))
        assertTrue(group.contains("u0_a948_ext_cache:x:40948:linux,root"))
        assertTrue(group.contains("all_a948:x:50948:linux,root"))
        assertTrue(group.contains("everybody:x:9997:linux,root"))
        assertTrue(group.contains("aid_77777:x:77777:linux,root"))

        // Secondary Android user profile (u10_a948 = 1010948)
        val workGroup = UserProvisioner.buildGroup(null, "linux", 1_010_948, intArrayOf(1_020_948, 50_948))
        assertTrue(workGroup.contains("u10_a948_cache:x:1020948:linux,root"))
        assertTrue(workGroup.contains("u10_a948_ext_cache:x:1040948:linux,root"))
        assertTrue(workGroup.contains("all_a948:x:50948:linux,root"))
        assertTrue(workGroup.contains("u10_all_a948:x:1050948:linux,root"))

        // Parse /proc/self/status Groups: line
        val parsedGids = UserProvisioner.parseStatusGroups(
            "Name:\tapp_process64\nUid:\t10948\t10948\t10948\t10948\nGroups:\t3003 9997 20948 50948 \n"
        )
        org.junit.Assert.assertArrayEquals(intArrayOf(3003, 9997, 20948, 50948), parsedGids)

        // If existing /etc/group has render:x:994: (Ubuntu 24.04) or a colliding group name with a different GID,
        // Android supplementary GIDs must still be emitted under aid_<gid> rather than dropped.
        val collided = UserProvisioner.buildGroup(
            existing = "render:x:994:\ninet:x:999:\n",
            username = "linux",
            hostUid = 10948,
            extraGids = intArrayOf(109, 3003, 20948),
        )
        assertTrue(collided.contains("render:x:994:linux,root"))
        assertTrue(collided.contains("aid_109:x:109:linux,root"))
        assertTrue(collided.contains("aid_3003:x:3003:linux,root"))
        assertTrue(collided.contains("u0_a948_cache:x:20948:linux,root"))
    }
}
