package com.linuxdroid.app.runtime

import android.content.Context
import android.os.Environment
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.linuxdroid.app.core.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

object LinuxRuntime {
    private const val TAG = "LinuxRuntime"
    const val DIR = "linuxfs"
    const val SESSION_SCRIPT = "/usr/local/bin/linuxdroid-session"
    const val GUEST_RUNTIME_DIR = "/run/linuxdroid"
    private const val KGSL_DEVICE = "/dev/kgsl-3d0"
    private const val SYSTEM_FONTS = "/system/fonts"
    private const val GUEST_SYSTEM_FONTS = "/usr/local/share/fonts/android"

    @Volatile
    private var cachedBinds: List<String>? = null

    fun rootDir(context: Context): File = File(context.filesDir, DIR)

    fun sessionDir(context: Context): File = File(context.filesDir, "session").apply { mkdirs() }

    fun logDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    fun liveDir(context: Context): File = File(context.noBackupFilesDir, "guest-runtime").apply { mkdirs() }

    fun runtimeDir(context: Context): File = File(liveDir(context), "xdg").apply {
        mkdirs()
        FileUtils.chmod(this, 0b111_000_000)
    }

    fun virglSocketHostFile(context: Context): File = File(runtimeDir(context), "virgl_test")

    const val VIRGL_SOCKET_GUEST_PATH = "$GUEST_RUNTIME_DIR/virgl_test"

    fun prootTempDir(context: Context): File = File(liveDir(context), "proot").apply { mkdirs() }

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun prootLoader(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")

    fun hasRootfs(context: Context): Boolean {
        val root = rootDir(context)
        return File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists()
    }

    fun isReady(context: Context): Boolean =
        hasRootfs(context) && prootBinary(context).isFile && prootLoader(context).isFile

    fun fexInterpreterGuestPath(root: File): String? = when {
        File(root, "usr/bin/FEXInterpreter").exists() -> "/usr/bin/FEXInterpreter"
        File(root, "usr/bin/FEX").exists() -> "/usr/bin/FEX"
        File(root, "usr/bin/FEXLoader").exists() -> "/usr/bin/FEXLoader"
        else -> null
    }

    fun fexInterpreterPresent(root: File): Boolean =
        fexInterpreterGuestPath(root) != null

    fun detectInstalledDesktopLabel(context: Context): String {
        val root = rootDir(context)
        if (!hasRootfs(context)) return "Not installed"
        val stamp = FileUtils.readString(File(root, "etc/linuxdroid/edition"))?.trim()
        if (!stamp.isNullOrEmpty()) return stamp
        return when {
            File(root, "usr/bin/startplasma-wayland").exists() -> "Ubuntu ARM64 (KDE Plasma)"
            File(root, "usr/bin/startxfce4").exists() -> "Ubuntu ARM64 (XFCE4)"
            else -> "Custom ARM64 Linux Rootfs"
        }
    }

    /**
     * Stages all bundled bridge scripts, FEX launchers, D-Bus helpers, and LD_PRELOAD libraries
     * from assets/linuxfs/MANIFEST.txt into the active Linux rootfs before session launch.
     */
    @Synchronized
    fun stageBundledOverlay(context: Context, enableFastPath: Boolean) {
        val root = rootDir(context)
        if (!root.isDirectory) return
        try {
            val manifest = context.assets.open("linuxfs/MANIFEST.txt").bufferedReader().readLines()
            val buf = ByteArray(64 * 1024)
            for (raw in manifest) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val space = line.indexOf(' ')
                if (space <= 0) continue
                val octalMode = line.substring(0, space).trim().toIntOrNull(8) ?: 0b110_100_100
                val relPath = line.substring(space + 1).trim()
                val outFile = File(root, relPath)
                outFile.parentFile?.mkdirs()
                val tmp = File(outFile.parentFile, ".${outFile.name}.stage")
                context.assets.open("linuxfs/$relPath").use { input ->
                    FileOutputStream(tmp).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                FileUtils.chmod(tmp, octalMode)
                if (!tmp.renameTo(outFile)) {
                    tmp.copyTo(outFile, overwrite = true)
                    tmp.delete()
                    FileUtils.chmod(outFile, octalMode)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed staging assets/linuxfs overlay", e)
        }

        val etcLinuxDroid = File(root, "etc/linuxdroid").apply { mkdirs() }
        File(etcLinuxDroid, "empty").mkdirs()
        FileUtils.writeString(
            File(etcLinuxDroid, "root-socket"),
            "@${RootSpawner.socketName(context)}\n"
        )

        val preloadLines = buildString {
            append("/usr/local/lib/libblsession.so\n")
            if (enableFastPath && File(root, "usr/local/lib/libblfastpath.so").isFile) {
                append(ProotFastPath.LIBRARY).append('\n')
            }
        }
        FileUtils.writeString(File(root, "etc/ld.so.preload"), preloadLines)
    }

    fun prootPrefix(
        context: Context,
        root: File,
        cwd: String,
        fakeRoot: Boolean,
    ): MutableList<String> {
        val cmd = ArrayList<String>()
        cmd.add(prootBinary(context).absolutePath)
        cmd.add("--kill-on-exit")
        cmd.add("--kernel-release=${guestUtsname("linuxdroid")}")
        val fexGuest = fexInterpreterGuestPath(root)
        if (fexGuest != null) {
            cmd.add("-q")
            cmd.add(fexGuest)
        }
        val uid = Process.myUid()
        if (fakeRoot) {
            cmd.add("-0")
        } else {
            cmd.add("-i")
            cmd.add("$uid:$uid")
        }
        cmd.add("-r")
        cmd.add(root.absolutePath)
        cmd.add("-w")
        cmd.add(cwd)
        return cmd
    }

    fun buildBinds(
        context: Context,
        batterySysfsDir: File?,
        customMounts: Map<String, String>,
    ): List<String> {
        val root = rootDir(context)
        val specs = ArrayList<String>()

        specs.add("/dev")
        specs.add("/proc")
        specs.add("/sys")
        specs.add("/dev/urandom:/dev/random")
        specs.add("/proc/self/fd:/dev/fd")
        specs.add("/proc/self/fd/0:/dev/stdin")
        specs.add("/proc/self/fd/1:/dev/stdout")
        specs.add("/proc/self/fd/2:/dev/stderr")

        val emptyDir = File(root, "etc/linuxdroid/empty").apply { mkdirs() }
        specs.add("${emptyDir.absolutePath}:/sys/fs/selinux")

        specs.add(context.filesDir.absolutePath)
        specs.add(context.cacheDir.absolutePath)
        specs.add(liveDir(context).absolutePath)

        val rtDir = runtimeDir(context)
        specs.add(rtDir.absolutePath)
        specs.add("${rtDir.absolutePath}:$GUEST_RUNTIME_DIR")
        specs.add(sessionDir(context).absolutePath)

        val sdcard = Environment.getExternalStorageDirectory()
        if (sdcard != null && sdcard.isDirectory) {
            specs.add("${sdcard.absolutePath}:/sdcard")
        }

        val shm = File(liveDir(context), "shm").apply { mkdirs() }
        specs.add("${shm.absolutePath}:/dev/shm")

        if (batterySysfsDir != null) {
            batterySysfsDir.mkdirs()
            specs.add("${batterySysfsDir.absolutePath}:/sys/class/power_supply")
        }

        val fontsDir = File(SYSTEM_FONTS)
        if (fontsDir.isDirectory && fontsDir.canRead()) {
            File(root, GUEST_SYSTEM_FONTS.removePrefix("/")).mkdirs()
            specs.add("$SYSTEM_FONTS:$GUEST_SYSTEM_FONTS")
        }

        seedSyntheticProc(root, specs)
        bindKgslDrmIfNeeded(context, specs)

        if (customMounts.isNotEmpty()) {
            val mntBase = File(root, "mnt/linuxdroid").apply { mkdirs() }
            for ((rawName, hostPath) in customMounts) {
                val hostDir = File(hostPath)
                if (!hostDir.exists()) continue
                val safeName = UserProvisioner.sanitizeMountName(rawName)
                File(mntBase, safeName).mkdirs()
                specs.add("${hostDir.absolutePath}:/mnt/linuxdroid/$safeName")
            }
        }

        cachedBinds = specs
        return specs
    }

    fun lastBinds(context: Context): List<String> =
        cachedBinds ?: buildBinds(context, null, emptyMap())

    private fun seedSyntheticProc(root: File, specs: MutableList<String>) {
        val fakeProc = File(root, "etc/linuxdroid/proc").apply { mkdirs() }
        val defaults = mapOf(
            "pci_devices" to "",
            "stat" to "cpu  1000 0 500 8500 0 0 0 0 0 0\ncpu0 1000 0 500 8500 0 0 0 0 0 0\n",
            "loadavg" to "0.20 0.15 0.10 1/256 1000\n",
            "uptime" to "3600.00 3500.00\n",
            "vmstat" to "nr_free_pages 262144\n",
            "cap_last_cap" to "40\n",
            "overflowuid" to "65534\n",
            "overflowgid" to "65534\n",
            "file-max" to "9223372036854775807\n",
            "overcommit_memory" to "0\n",
        )
        for ((name, text) in defaults) {
            val f = File(fakeProc, name)
            if (!f.isFile) FileUtils.writeString(f, text)
        }
        val mappings = listOf(
            "pci_devices" to "/proc/bus/pci/devices",
            "stat" to "/proc/stat",
            "loadavg" to "/proc/loadavg",
            "uptime" to "/proc/uptime",
            "vmstat" to "/proc/vmstat",
            "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
            "overflowuid" to "/proc/sys/kernel/overflowuid",
            "overflowgid" to "/proc/sys/kernel/overflowgid",
            "file-max" to "/proc/sys/fs/file-max",
            "overcommit_memory" to "/proc/sys/vm/overcommit_memory",
        )
        for ((srcName, guestTarget) in mappings) {
            val hostCheck = File(guestTarget)
            val srcFile = File(fakeProc, srcName)
            if (!hostCheck.canRead() && srcFile.isFile) {
                specs.add("${srcFile.absolutePath}:$guestTarget")
            }
        }
    }

    /**
     * Exposes Qualcomm /dev/kgsl-3d0 as a synthetic DRM render node ONLY when /dev/kgsl-3d0 exists
     * and native /dev/dri/renderD128 is not already present (preserving native PanVK/Panfrost DRM on Mali).
     */
    private fun bindKgslDrmIfNeeded(context: Context, specs: MutableList<String>) {
        if (File("/dev/dri/renderD128").exists()) return
        val st = try {
            Os.stat(KGSL_DEVICE)
        } catch (_: ErrnoException) {
            return
        }
        val dev = st.st_rdev
        val major = ((dev shr 8) and 0xfffL) or ((dev shr 32) and 0xfffL.inv())
        val minor = (dev and 0xffL) or ((dev shr 12) and 0xffL.inv())
        val node = "renderD$minor"
        val base = File(liveDir(context), "drm")
        val dri = File(base, "dri")
        val device = File(base, "sys/$major:$minor/device")
        val drm = File(device, "drm/$node")
        try {
            dri.mkdirs()
            drm.mkdirs()
            File(dri, node).createNewFile()
            FileUtils.writeString(File(drm, "dev"), "$major:$minor\n")
            FileUtils.writeString(File(device, "uevent"), "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n")
            val subsys = File(device, "subsystem")
            if (!Files.isSymbolicLink(subsys.toPath())) Os.symlink("/sys/bus/platform", subsys.path)
            val drv = File(device, "driver")
            if (!Files.isSymbolicLink(drv.toPath())) Os.symlink("/sys/bus/platform/drivers/msm_drm", drv.path)
        } catch (_: Exception) {
            return
        }
        specs.add("${File(base, "sys").absolutePath}:/sys/dev/char")
        specs.add("${dri.absolutePath}:/dev/dri")
        specs.add("$KGSL_DEVICE:/dev/dri/$node")
        if (File("/sys/class/drm").list() == null) {
            val drmClass = File(base, "class").apply { mkdirs() }
            runCatching {
                drmClass.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                Os.symlink("/sys/dev/char/$major:$minor", File(drmClass, node).path)
                specs.add("${drmClass.absolutePath}:/sys/class/drm")
            }
        }
    }

    private fun guestUtsname(hostname: String): String {
        val host = Os.uname()
        return "\\${host.sysname}\\$hostname\\${host.release}\\${host.version}\\${host.machine}\\localdomain\\-1\\"
    }
}
