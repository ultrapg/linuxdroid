package com.linuxdroid.app.runtime

import android.content.Context
import android.os.Environment
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.linuxdroid.app.BuildConfig
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.session.SessionPrefs
import com.linuxdroid.app.session.SessionState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

object LinuxRuntime {
    private const val TAG = "LinuxRuntime"
    const val DIR = "linuxfs"
    const val SESSION_SCRIPT = "/usr/local/bin/linuxdroid-session"
    const val GUEST_RUNTIME_DIR = "/run/linuxdroid"
    private const val OVERLAY_STAMP_FILE = "overlay-version"
    private const val KGSL_DEVICE = "/dev/kgsl-3d0"
    private const val MALI_DEVICE = "/dev/mali0"
    private const val SYSTEM_FONTS = "/system/fonts"
    private const val GUEST_SYSTEM_FONTS = "/usr/local/share/fonts/android"

    @Volatile
    private var cachedBinds: List<String>? = null

    @Volatile
    private var stagedOverlaySignature: String? = null

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
     * Identifies the currently installed edition ID (`"ubuntu-kde-arm64"`, `"ubuntu-xfce-arm64"`,
     * `"custom-rootfs"`, etc.), or `null` if no Linux rootfs is currently installed.
     * Auto-detects existing installations even if upgraded from an earlier APK.
     */
    fun detectInstalledEditionId(context: Context): String? {
        if (!hasRootfs(context)) return null
        val root = rootDir(context)
        val fileId = FileUtils.readString(File(root, "etc/linuxdroid/edition-id"))?.trim()
        if (!fileId.isNullOrEmpty()) return fileId
        val prefId = SessionPrefs.installedEditionId(context)
        if (!prefId.isNullOrEmpty()) return prefId
        val stamp = FileUtils.readString(File(root, "etc/linuxdroid/edition"))?.trim().orEmpty()
        return when {
            stamp.contains("KDE", ignoreCase = true) || File(root, "usr/bin/startplasma-wayland").exists() ->
                "ubuntu-kde-arm64"
            stamp.contains("XFCE", ignoreCase = true) || File(root, "usr/bin/startxfce4").exists() ->
                "ubuntu-xfce-arm64"
            else -> "custom-rootfs"
        }
    }

    fun clearCachedBinds() {
        cachedBinds = null
    }

    private fun currentApkOverlaySignature(context: Context, enableFastPath: Boolean): String {
        val pkgUpdateTime = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}:$pkgUpdateTime:$enableFastPath"
    }

    /**
     * Ensures the bundled APK overlay (bridge scripts + preload .so files) matches the currently
     * installed APK version and fastpath preference before any guest or RootSpawner process runs.
     */
    @Synchronized
    fun ensureBundledOverlayCurrent(context: Context, enableFastPath: Boolean? = null) {
        if (!hasRootfs(context)) return
        val root = rootDir(context)
        val effectiveFastPath = enableFastPath ?: (
            !SessionPrefs.prootNoSeccomp(context) &&
                !SessionState.state.value.compatibilityRetryUsed &&
                SessionPrefs.prootFastPath(context)
        )
        val expected = currentApkOverlaySignature(context, effectiveFastPath)
        val onDisk = FileUtils.readString(File(root, "etc/linuxdroid/$OVERLAY_STAMP_FILE"))?.trim()
        if (stagedOverlaySignature != expected || onDisk != expected) {
            stageBundledOverlay(context, enableFastPath = effectiveFastPath)
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
        var stagedOk = false
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
                outFile.parentFile?.let { parent ->
                    parent.mkdirs()
                    if (!parent.canWrite()) FileUtils.chmod(parent, 0b111_101_101)
                }
                val tmp = File(outFile.parentFile, ".${outFile.name}.stage")
                if (tmp.exists()) {
                    FileUtils.chmod(tmp, 0b110_000_000)
                    tmp.delete()
                }
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
                    if (outFile.exists() && !outFile.canWrite()) {
                        FileUtils.chmod(outFile, 0b110_100_100)
                    }
                    tmp.copyTo(outFile, overwrite = true)
                    tmp.delete()
                    FileUtils.chmod(outFile, octalMode)
                }
            }
            stagedOk = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed staging assets/linuxfs overlay", e)
        }

        val etcLinuxDroid = File(root, "etc/linuxdroid").apply { mkdirs() }
        File(etcLinuxDroid, "empty").mkdirs()
        File(root, GUEST_RUNTIME_DIR.removePrefix("/")).apply {
            mkdirs()
            FileUtils.chmod(this, 0b111_000_000)
        }
        File(root, "tmp").apply {
            mkdirs()
            FileUtils.chmod(this, 0b1_111_111_111)
            listFiles()?.forEach { child ->
                if (child.name.startsWith(".linuxdroid-svc-")) {
                    runCatching { child.delete() }
                }
            }
        }
        File(root, "var/tmp").apply {
            mkdirs()
            FileUtils.chmod(this, 0b1_111_111_111)
        }
        FileUtils.writeString(
            File(etcLinuxDroid, "root-socket"),
            "${RootSpawner.socketName(context)}\n"
        )
        FileUtils.writeString(
            File(etcLinuxDroid, "version"),
            "${BuildConfig.VERSION_NAME}\n"
        )

        // Ensure hardcoded /usr/bin/{sudo,pkexec,su,bwrap,flatpak-spawn} invocations also use the LinuxDroid shims
        for (tool in listOf("sudo", "pkexec", "su", "bwrap", "flatpak-spawn")) {
            val localWrapper = File(root, "usr/local/bin/$tool")
            val usrBinTarget = File(root, "usr/bin/$tool")
            if (localWrapper.isFile) {
                runCatching {
                    usrBinTarget.parentFile?.mkdirs()
                    if (usrBinTarget.isFile) {
                        val backup = File(root, "usr/bin/$tool.distrib")
                        val head = ByteArray(4)
                        val read = usrBinTarget.inputStream().use { it.read(head) }
                        val isElf = read == 4 && head[0] == 0x7f.toByte() &&
                            head[1] == 'E'.code.toByte() &&
                            head[2] == 'L'.code.toByte() &&
                            head[3] == 'F'.code.toByte()
                        if (isElf && !backup.exists()) {
                            usrBinTarget.renameTo(backup)
                        }
                    }
                    localWrapper.copyTo(usrBinTarget, overwrite = true)
                    FileUtils.chmod(usrBinTarget, 0b111_101_101)
                }
            }
        }
        File(root, "etc/sudoers").takeIf { it.isFile }?.let { FileUtils.chmod(it, 0b100_100_000) }
        File(root, "etc/sudoers.d/90-linuxdroid").takeIf { it.isFile }?.let { FileUtils.chmod(it, 0b100_100_000) }

        val preloadLines = buildString {
            append("/usr/local/lib/libblsession.so\n")
            if (enableFastPath && File(root, "usr/local/lib/libblfastpath.so").isFile) {
                append(ProotFastPath.LIBRARY).append('\n')
            }
        }
        FileUtils.writeString(File(root, "etc/ld.so.preload"), preloadLines)
        if (stagedOk) {
            val sig = currentApkOverlaySignature(context, enableFastPath)
            FileUtils.writeString(File(etcLinuxDroid, OVERLAY_STAMP_FILE), "$sig\n")
            stagedOverlaySignature = sig
        }
    }

    /**
     * Builds the base Android PRoot command vector.
     * Note: `-q` must NEVER be passed on ARM64 Android — PRoot resolves `-q` on the Android host
     * before chrooting and passes QEMU-user flags. Transparent x86/x86_64 execution via FEX-Emu is
     * handled inside the guest by `libblsession.so` (`binfmt.c`) -> `linuxdroid-open` -> `linuxdroid-fex`.
     */
    fun prootPrefix(
        context: Context,
        root: File,
        cwd: String,
        fakeRoot: Boolean,
    ): MutableList<String> {
        val cmd = ArrayList<String>()
        cmd.add(prootBinary(context).absolutePath)
        cmd.add("--link2symlink")
        cmd.add("--kill-on-exit")
        cmd.add("--kernel-release=${guestUtsname("linuxdroid")}")
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
        effectiveCpuCores: Int = SessionPrefs.effectiveCpuCores(context, CpuInfo.detect().totalCores),
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
        File(root, GUEST_RUNTIME_DIR.removePrefix("/")).apply {
            mkdirs()
            FileUtils.chmod(this, 0b111_000_000)
        }
        specs.add(rtDir.absolutePath)
        specs.add("${rtDir.absolutePath}:$GUEST_RUNTIME_DIR")
        specs.add(sessionDir(context).absolutePath)

        val sdcard = Environment.getExternalStorageDirectory()
        if (sdcard != null && sdcard.isDirectory) {
            specs.add("${sdcard.absolutePath}:/sdcard")
        }

        val shm = File(liveDir(context), "shm").apply {
            mkdirs()
            FileUtils.chmod(this, 0b1_111_111_111)
        }
        File(root, "dev/shm").apply {
            mkdirs()
            FileUtils.chmod(this, 0b1_111_111_111)
        }
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

        seedSyntheticProc(root, specs, effectiveCpuCores)
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

    internal fun defaultBootTimeEpochSeconds(): Long {
        val nowSec = System.currentTimeMillis() / 1000L
        val uptimeSec = runCatching { android.os.SystemClock.elapsedRealtime() / 1000L }
            .getOrDefault(0L)
            .takeIf { it > 0L } ?: 3600L
        return (nowSec - uptimeSec).coerceAtLeast(1_700_000_000L)
    }

    internal fun generateProcStatText(
        cpuCores: Int,
        bootTimeSec: Long = defaultBootTimeEpochSeconds(),
    ): String = buildString {
        val count = cpuCores.coerceIn(1, 128)
        append("cpu  ${1000 * count} 0 ${500 * count} ${8500 * count} 0 0 0 0 0 0\n")
        for (i in 0 until count) {
            append("cpu$i 1000 0 500 8500 0 0 0 0 0 0\n")
        }
        append("intr ${50000L * count} 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n")
        append("ctxt ${100000L * count}\n")
        append("btime $bootTimeSec\n")
        append("processes 256\n")
        append("procs_running 1\n")
        append("procs_blocked 0\n")
        append("softirq ${20000L * count} 0 ${5000L * count} 0 ${2000L * count} 0 0 0 ${8000L * count} 0 ${5000L * count}\n")
    }

    internal fun generateProcUptimeText(cpuCores: Int = 8): String {
        val count = cpuCores.coerceIn(1, 128)
        val uptimeSec = runCatching { android.os.SystemClock.elapsedRealtime() / 1000.0 }
            .getOrDefault(0.0)
            .takeIf { it > 1.0 } ?: 3600.0
        val idleSec = uptimeSec * 0.85 * count
        return String.format(java.util.Locale.US, "%.2f %.2f\n", uptimeSec, idleSec)
    }

    internal fun generateProcVmstatText(): String = buildString {
        append("nr_free_pages 262144\n")
        append("nr_zone_inactive_anon 32768\n")
        append("nr_zone_active_anon 65536\n")
        append("nr_zone_inactive_file 65536\n")
        append("nr_zone_active_file 131072\n")
        append("nr_zone_unevictable 4096\n")
        append("nr_zone_write_pending 0\n")
        append("nr_mlock 4096\n")
        append("nr_bounce 0\n")
        append("nr_zspages 0\n")
        append("nr_free_cma 0\n")
        append("nr_inactive_anon 32768\n")
        append("nr_active_anon 65536\n")
        append("nr_inactive_file 65536\n")
        append("nr_active_file 131072\n")
        append("nr_unevictable 4096\n")
        append("nr_slab_reclaimable 16384\n")
        append("nr_slab_unreclaimable 16384\n")
        append("nr_isolated_anon 0\n")
        append("nr_isolated_file 0\n")
        append("nr_anon_pages 98304\n")
        append("nr_mapped 65536\n")
        append("nr_file_pages 212992\n")
        append("nr_dirty 0\n")
        append("nr_writeback 0\n")
        append("nr_writeback_temp 0\n")
        append("nr_shmem 16384\n")
        append("nr_shmem_hugepages 0\n")
        append("nr_shmem_pmdmapped 0\n")
        append("nr_file_hugepages 0\n")
        append("nr_file_pmdmapped 0\n")
        append("nr_anon_transparent_hugepages 0\n")
        append("nr_vmscan_write 0\n")
        append("nr_vmscan_immediate_reclaim 0\n")
        append("nr_dirtied 1024\n")
        append("nr_written 1024\n")
        append("nr_kernel_misc_reclaimable 0\n")
        append("nr_foll_pin_acquired 0\n")
        append("nr_foll_pin_released 0\n")
        append("nr_kernel_stack 8192\n")
        append("nr_page_table_pages 8192\n")
        append("nr_dirty_threshold 52428\n")
        append("nr_dirty_background_threshold 26214\n")
        append("pgpgin 131072\n")
        append("pgpgout 32768\n")
        append("pswpin 0\n")
        append("pswpout 0\n")
        append("pgalloc_dma 0\n")
        append("pgalloc_dma32 0\n")
        append("pgalloc_normal 524288\n")
        append("pgalloc_movable 0\n")
        append("pgfree 786432\n")
        append("pgactivate 65536\n")
        append("pgdeactivate 16384\n")
        append("pglazyfree 0\n")
        append("pgfault 1048576\n")
        append("pgmajfault 1024\n")
        append("pglazyfreed 0\n")
        append("pgrefill 0\n")
        append("pgsteal_kswapd 0\n")
        append("pgsteal_direct 0\n")
        append("pgscan_kswapd 0\n")
        append("pgscan_direct 0\n")
        append("pgscan_direct_throttle 0\n")
        append("pgscan_anon 0\n")
        append("pgscan_file 0\n")
        append("pgsteal_anon 0\n")
        append("pgsteal_file 0\n")
        append("pginodesteal 0\n")
        append("slabs_scanned 0\n")
        append("kswapd_inodesteal 0\n")
        append("kswapd_low_wmark_hit_quickly 0\n")
        append("kswapd_high_wmark_hit_quickly 0\n")
        append("pageoutrun 0\n")
        append("pgrotated 0\n")
        append("drop_pagecache 0\n")
        append("drop_slab 0\n")
        append("oom_kill 0\n")
    }

    internal const val DEFAULT_TTY_DRIVERS =
        "/dev/tty             /dev/tty        5       0 system:/dev/tty\n" +
            "/dev/console         /dev/console    5       1 system:console\n" +
            "/dev/ptmx            /dev/ptmx       5       2 system\n" +
            "rfcomm               /dev/rfcomm   216 0-255 serial\n" +
            "ttyprintk            /dev/ttyprintk   5       3 console\n" +
            "serial               /dev/ttyS       4 64-95 serial\n" +
            "pty_slave            /dev/pts      136 0-1048575 pty:slave\n" +
            "pty_master           /dev/ptm      128 0-1048575 pty:master\n"

    private fun isHostProcReadable(path: String, requireNonEmpty: Boolean = true): Boolean {
        val f = File(path)
        if (!f.exists() || !f.canRead()) return false
        return try {
            f.inputStream().use { input ->
                val b = input.read()
                !requireNonEmpty || b >= 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun seedSyntheticProc(root: File, specs: MutableList<String>, effectiveCpuCores: Int) {
        val fakeProc = File(root, "etc/linuxdroid/proc").apply { mkdirs() }
        // Always refresh synthetic /proc/stat, /proc/uptime, and /proc/vmstat on session launch
        FileUtils.writeString(File(fakeProc, "stat"), generateProcStatText(effectiveCpuCores))
        FileUtils.writeString(File(fakeProc, "uptime"), generateProcUptimeText(effectiveCpuCores))
        FileUtils.writeString(File(fakeProc, "vmstat"), generateProcVmstatText())
        val kernelRelease = runCatching { Os.uname().release }.getOrNull()?.takeIf { it.isNotBlank() } ?: "6.1.0-linuxdroid"
        val kernelVersion = runCatching { Os.uname().version }.getOrNull()?.takeIf { it.isNotBlank() } ?: "#1 SMP PREEMPT"
        val defaults = mapOf(
            "pci_devices" to "",
            "loadavg" to "0.20 0.15 0.10 1/256 1000\n",
            "tty_drivers" to DEFAULT_TTY_DRIVERS,
            "version" to "Linux version $kernelRelease (linuxdroid@android) (gcc (Ubuntu 13.3.0) 13.3.0) $kernelVersion\n",
            "swaps" to "Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority\n",
            "diskstats" to "   7       0 loop0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n",
            "locks" to "",
            "file-nr" to "1024\t0\t9223372036854775807\n",
            "pid_max" to "32768\n",
            "osrelease" to "$kernelRelease\n",
            "sched_autogroup_enabled" to "0\n",
            "cap_last_cap" to "40\n",
            "overflowuid" to "65534\n",
            "overflowgid" to "65534\n",
            "file-max" to "9223372036854775807\n",
            "overcommit_memory" to "0\n",
        )
        for ((name, text) in defaults) {
            val f = File(fakeProc, name)
            if (!f.isFile || f.length() == 0L) FileUtils.writeString(f, text)
        }
        val mappings = listOf(
            "pci_devices" to "/proc/bus/pci/devices",
            "stat" to "/proc/stat",
            "loadavg" to "/proc/loadavg",
            "uptime" to "/proc/uptime",
            "vmstat" to "/proc/vmstat",
            "tty_drivers" to "/proc/tty/drivers",
            "version" to "/proc/version",
            "swaps" to "/proc/swaps",
            "diskstats" to "/proc/diskstats",
            "locks" to "/proc/locks",
            "file-nr" to "/proc/sys/fs/file-nr",
            "pid_max" to "/proc/sys/kernel/pid_max",
            "osrelease" to "/proc/sys/kernel/osrelease",
            "sched_autogroup_enabled" to "/proc/sys/kernel/sched_autogroup_enabled",
            "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
            "overflowuid" to "/proc/sys/kernel/overflowuid",
            "overflowgid" to "/proc/sys/kernel/overflowgid",
            "file-max" to "/proc/sys/fs/file-max",
            "overcommit_memory" to "/proc/sys/vm/overcommit_memory",
        )
        val allowEmpty = setOf("/proc/bus/pci/devices", "/proc/locks")
        for ((srcName, guestTarget) in mappings) {
            val srcFile = File(fakeProc, srcName)
            if (!srcFile.isFile) continue
            val readable = isHostProcReadable(guestTarget, requireNonEmpty = guestTarget !in allowEmpty)
            if (!readable) {
                specs.add("${srcFile.absolutePath}:$guestTarget")
            }
        }
    }

    /**
     * Exposes Qualcomm `/dev/kgsl-3d0` or Mali `/dev/mali0` as synthetic DRM render nodes
     * (`/dev/dri/renderD$minor` AND `/dev/dri/renderD128`) ONLY when native `/dev/dri/renderD128`
     * is not already present on the host.
     *
     * Note: KWin 5.27.11 hardcodes `open("/dev/dri/renderD128")` in `WaylandBackend`, and Mesa's
     * `pipe_loader_drm` only scans `renderD128..renderD191`, while `libdrm`'s `drmGetDeviceNameFromFd2`
     * reads `/sys/dev/char/$major:$minor/uevent` (`DEVNAME=dri/...`). Binding both `renderD$minor`
     * and `renderD128` satisfies KWin, Mesa, and `libdrm` simultaneously.
     */
    private fun bindKgslDrmIfNeeded(context: Context, specs: MutableList<String>) {
        if (File("/dev/dri/renderD128").exists()) return
        val (hostDevPath, ueventDriver, drvTarget) = when {
            File(KGSL_DEVICE).exists() -> Triple(
                KGSL_DEVICE,
                "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n",
                "/sys/bus/platform/drivers/msm_drm"
            )
            File(MALI_DEVICE).exists() -> Triple(
                MALI_DEVICE,
                "DRIVER=mali\nMODALIAS=platform:mali\n",
                "/sys/bus/platform/drivers/panfrost"
            )
            else -> return
        }
        val st = try {
            Os.stat(hostDevPath)
        } catch (_: ErrnoException) {
            return
        }
        val dev = st.st_rdev
        val major = ((dev shr 8) and 0xfffL) or ((dev shr 32) and 0xfffL.inv())
        val minor = (dev and 0xffL) or ((dev shr 12) and 0xffL.inv())
        val node = "renderD$minor"
        val nodes = linkedSetOf(node, "renderD128")
        val base = File(liveDir(context), "drm")
        val dri = File(base, "dri")
        val charNode = File(base, "sys/$major:$minor")
        val device = File(charNode, "device")
        try {
            dri.mkdirs()
            for (n in nodes) {
                val drm = File(device, "drm/$n")
                drm.mkdirs()
                File(dri, n).createNewFile()
                FileUtils.writeString(File(drm, "dev"), "$major:$minor\n")
            }
            FileUtils.writeString(
                File(charNode, "uevent"),
                "MAJOR=$major\nMINOR=$minor\nDEVNAME=dri/$node\n"
            )
            FileUtils.writeString(File(device, "uevent"), ueventDriver)
            val subsys = File(device, "subsystem")
            if (!Files.isSymbolicLink(subsys.toPath())) Os.symlink("/sys/bus/platform", subsys.path)
            val drv = File(device, "driver")
            if (Files.isSymbolicLink(drv.toPath())) Files.deleteIfExists(drv.toPath())
            Os.symlink(drvTarget, drv.path)
        } catch (_: Exception) {
            return
        }
        specs.add("${File(base, "sys").absolutePath}:/sys/dev/char")
        specs.add("${dri.absolutePath}:/dev/dri")
        for (n in nodes) {
            specs.add("$hostDevPath:/dev/dri/$n")
        }
        if (File("/sys/class/drm").list() == null) {
            val drmClass = File(base, "class").apply { mkdirs() }
            runCatching {
                drmClass.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                for (n in nodes) {
                    Os.symlink("/sys/dev/char/$major:$minor", File(drmClass, n).path)
                }
                specs.add("${drmClass.absolutePath}:/sys/class/drm")
            }
        }
    }

    private fun guestUtsname(hostname: String): String {
        val host = Os.uname()
        return "\\${host.sysname}\\$hostname\\${host.release}\\${host.version}\\${host.machine}\\localdomain\\-1\\"
    }
}
