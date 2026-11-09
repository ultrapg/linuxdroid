package com.linuxdroid.app.session

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.system.Os
import com.linuxdroid.app.BuildConfig
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.gpu.GpuConfigGenerator
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.gpu.VulkanInfo
import com.linuxdroid.app.runtime.CpuInfo
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.runtime.ProotFastPath
import com.linuxdroid.app.runtime.RootSpawner
import com.linuxdroid.app.runtime.UserProvisioner
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class DebugLogSection(val id: String, val label: String) {
    FULL_REPORT("full", "Full Report"),
    HARDWARE_GPU("gpu", "Hardware & GPU"),
    SESSION_BOOT("session", "Session & Boot"),
    WAYLAND_VIRGL("display", "Wayland & VirGL"),
    APPS_CRASHES("apps", "Apps & Crashes"),
    PROOT_AUDIO("runtime", "PRoot & Audio"),
}

data class DebugReportBundle(
    val generatedAtUtc: String,
    val fullReport: String,
    val sections: Map<DebugLogSection, String>,
) {
    fun textFor(section: DebugLogSection): String =
        if (section == DebugLogSection.FULL_REPORT) fullReport else sections[section].orEmpty().ifBlank { fullReport }
}

object DebugLogger {
    fun collectReport(context: Context): DebugReportBundle {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowStr = sdf.format(Date())
        val logDir = LinuxRuntime.logDir(context)
        val root = LinuxRuntime.rootDir(context)
        val snap = SessionState.state.value

        val gpuSection = buildHardwareAndGpuSection(context, root, logDir)
        val sessionSection = buildSessionAndBootSection(context, root, logDir, snap)
        val displaySection = buildWaylandAndVirglSection(context, root, logDir)
        val appsSection = buildAppsAndCrashesSection(context, root, logDir)
        val runtimeSection = buildProotAndAudioSection(context, root, logDir)

        val full = buildString {
            appendLine("========================================================================")
            appendLine("LINUXDROID FULL DEBUG & DIAGNOSTIC REPORT")
            appendLine("Generated: $nowStr")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("Session State: ${snap.phase} (${snap.statusText})")
            appendLine("========================================================================")
            appendLine()
            appendLine(gpuSection)
            appendLine()
            appendLine(sessionSection)
            appendLine()
            appendLine(displaySection)
            appendLine()
            appendLine(appsSection)
            appendLine()
            appendLine(runtimeSection)
        }

        val map = mapOf(
            DebugLogSection.FULL_REPORT to full,
            DebugLogSection.HARDWARE_GPU to gpuSection,
            DebugLogSection.SESSION_BOOT to sessionSection,
            DebugLogSection.WAYLAND_VIRGL to displaySection,
            DebugLogSection.APPS_CRASHES to appsSection,
            DebugLogSection.PROOT_AUDIO to runtimeSection,
        )
        return DebugReportBundle(
            generatedAtUtc = nowStr,
            fullReport = full,
            sections = map,
        )
    }

    private fun buildHardwareAndGpuSection(context: Context, root: File, logDir: File): String = buildString {
        appendLine("### [1] HARDWARE, SOC & GPU STACK ###")
        val socMan = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "unknown"
        val socMod = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
        val uname = runCatching { Os.uname() }.getOrNull()
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT} / ${Build.DEVICE})")
        appendLine("Board / Hardware: ${Build.BOARD} / ${Build.HARDWARE}")
        appendLine("SoC: $socMan $socMod")
        appendLine("Android SDK: ${Build.VERSION.SDK_INT} (Release ${Build.VERSION.RELEASE}, Fingerprint=${Build.FINGERPRINT})")
        if (uname != null) {
            appendLine("Host Kernel: ${uname.sysname} ${uname.release} ${uname.version} (${uname.machine})")
        }

        val cpu = CpuInfo.detect()
        val selectedCores = SessionPrefs.resolveSelectedCpuCores(context, cpu)
        val effCores = SessionPrefs.effectiveCpuCores(context, cpu.totalCores)
        appendLine("CPU Topology: ${cpu.summary} | Effective Cores: $effCores/${cpu.totalCores} (Pinned: ${if (selectedCores.isEmpty()) "all" else selectedCores.joinToString(",")})")

        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                appendLine("System RAM: ${mi.totalMem / (1024 * 1024)} MB total, ${mi.availMem / (1024 * 1024)} MB available, lowMemory=${mi.lowMemory}")
            }
        }
        runCatching {
            val st = StatFs(context.filesDir.absolutePath)
            appendLine("Internal Storage: ${st.availableBytes / (1024 * 1024)} MB free / ${st.totalBytes / (1024 * 1024)} MB total")
        }

        val gpuInfo = GpuInfo.detect()
        val gpuMode = SessionPrefs.gpuMode(context)
        val resolvedGpu = GpuConfigGenerator.resolve(gpuMode, gpuInfo, LinuxRuntime.VIRGL_SOCKET_GUEST_PATH)
        appendLine("Detected GPU: ${gpuInfo.deviceName} | Family=${gpuInfo.family} | AdrenoModel=${gpuInfo.adrenoModel}")
        appendLine("GPU Capabilities: hasVulkan=${gpuInfo.hasVulkan}, api=${gpuInfo.apiVersion}, vendorId=${gpuInfo.vendorId}, heap=${gpuInfo.deviceHeapMb}MB")
        appendLine("GPU Extensions: AHB=${gpuInfo.hasAhbImport}, DMA_BUF=${gpuInfo.hasDmaBuf}, DRM_MOD=${gpuInfo.hasDrmModifier}")
        appendLine("Host Device Nodes: kgsl=${gpuInfo.hasKgslNode}, driRenderD128=${gpuInfo.hasDriRenderNode}, mali0=${gpuInfo.hasMaliNode}")
        appendLine("Selected GPU Mode: ${gpuMode.id} -> Active Backend=${resolvedGpu.activeBackend} (${resolvedGpu.summary})")
        appendLine("GPU Settings: gpuDesktop=${SessionPrefs.gpuDesktop(context)}, gpuApps=${SessionPrefs.gpuApps(context)}, virglServer=${resolvedGpu.requiresVirglServer}, venus=${resolvedGpu.enableVenus}")

        appendLine()
        appendLine("--- Host Device Nodes Detail ---")
        val hostNodes = listOf(
            "/dev/kgsl-3d0",
            "/dev/mali0",
            "/dev/dri",
            "/dev/dri/card0",
            "/dev/dri/renderD128",
            "/dev/ion",
            "/dev/dma_heap",
            "/dev/dma_heap/system",
            "/dev/ashmem",
        )
        for (p in hostNodes) {
            appendLine("$p -> ${describeHostNode(File(p))}")
        }

        appendLine()
        appendLine("--- Host Native Vulkan Probe (libdeviceinfo.so) ---")
        val vkProbe = VulkanInfo.querySafe()
        if (vkProbe.isEmpty()) {
            appendLine("(empty Vulkan probe response)")
        } else {
            for ((k, v) in vkProbe) {
                appendLine("$k=$v")
            }
        }

        appendLine()
        appendLine("--- Guest /etc/linuxdroid/gpu.env ---")
        val gpuEnvFile = File(root, "etc/linuxdroid/gpu.env")
        appendLine(readTailOrPlaceholder(gpuEnvFile, maxBytes = 8192, maxLines = 60, placeholder = "(gpu.env not yet written)"))

        val cacheDirs = findGuestCacheDirs(root)
        appendLine()
        appendLine("--- Guest GPU Diagnostics (gpu-diag.txt) ---")
        val diagFile = cacheDirs.map { File(it, "gpu-diag.txt") }.firstOrNull { it.isFile && it.length() > 0L }
        appendLine(readTailOrPlaceholder(diagFile, maxBytes = 24_576, maxLines = 160, placeholder = "(gpu-diag.txt not generated yet — tap 'Run GPU Self-Test' or start a session)"))

        appendLine()
        appendLine("--- Guest GPU Self-Test (gpu-test.txt) ---")
        val testFile = cacheDirs.map { File(it, "gpu-test.txt") }.firstOrNull { it.isFile && it.length() > 0L }
        appendLine(readTailOrPlaceholder(testFile, maxBytes = 24_576, maxLines = 160, placeholder = "(gpu-test.txt not generated yet — tap 'Run GPU Self-Test' above)"))
    }

    private fun buildSessionAndBootSection(
        context: Context,
        root: File,
        logDir: File,
        snap: SessionSnapshot,
    ): String = buildString {
        appendLine("### [2] SESSION, PREFERENCES & BOOT LOGS ###")
        appendLine("Rootfs Installed: ${LinuxRuntime.hasRootfs(context)} | Ready: ${LinuxRuntime.isReady(context)}")
        appendLine("Installed Edition: ${LinuxRuntime.detectInstalledEditionId(context) ?: "none"} (${LinuxRuntime.detectInstalledDesktopLabel(context)})")
        val overlayStamp = FileUtils.readString(File(root, "etc/linuxdroid/overlay-version"))?.trim() ?: "none"
        appendLine("Overlay Stamp: $overlayStamp")
        appendLine("Session Phase: ${snap.phase} | Step: ${snap.bootStep}/${snap.bootTotalSteps} | FirstFrame: ${snap.firstFrameReady} | GuestPID: ${snap.guestPid}")
        appendLine("Status Text: ${snap.statusText}")
        if (!snap.errorMessage.isNullOrBlank()) {
            appendLine("Last Error Message: ${snap.errorMessage}")
        }
        appendLine("Compatibility Retry Used: ${snap.compatibilityRetryUsed}")
        appendLine("User Preferences: username=${SessionPrefs.username(context)}, res=${SessionPrefs.resolutionPreset(context).id}, scale=${SessionPrefs.displayScale(context)}, hz=${SessionPrefs.targetRefreshHz(context)}, orient=${SessionPrefs.orientation(context).id}")
        appendLine("Execution Flags: fastPath=${SessionPrefs.prootFastPath(context)}, noSeccomp=${SessionPrefs.prootNoSeccomp(context)}, bgMode=${SessionPrefs.backgroundMode(context).id} (${SessionPrefs.backgroundDelaySec(context)}s), extDisplay=${SessionPrefs.externalDisplayMode(context).id}")

        val bootStatus = File(LinuxRuntime.sessionDir(context), "boot-status.txt")
        appendLine("Boot Status File: ${FileUtils.readString(bootStatus)?.trim() ?: "(none)"}")

        appendLine()
        appendLine("--- Active Session Log (session.log) ---")
        appendLine(readTailOrPlaceholder(File(logDir, "session.log"), maxBytes = 32_768, maxLines = 180, placeholder = "(session.log is empty)"))

        val prevSession = File(logDir, "session.prev.log")
        if (prevSession.isFile && prevSession.length() > 0L) {
            appendLine()
            appendLine("--- Previous Session Log (session.prev.log, last 60 lines) ---")
            appendLine(readTailOrPlaceholder(prevSession, maxBytes = 16_384, maxLines = 60, placeholder = "(empty)"))
        }
    }

    private fun buildWaylandAndVirglSection(context: Context, root: File, logDir: File): String = buildString {
        appendLine("### [3] WAYLAND COMPOSITOR, XWAYLAND & VIRGL SERVER ###")
        val xdgDir = LinuxRuntime.runtimeDir(context)
        val xdgEntries = xdgDir.listFiles()?.joinToString(", ") { f ->
            "${f.name}(${if (f.isDirectory) "dir" else "${f.length()}b"})"
        } ?: "(none)"
        appendLine("Host XDG Runtime Dir (${xdgDir.absolutePath}): $xdgEntries")

        appendLine()
        appendLine("--- Host Wayland Compositor Log (wayland.log) ---")
        appendLine(readTailOrPlaceholder(File(logDir, "wayland.log"), maxBytes = 20_480, maxLines = 120, placeholder = "(wayland.log is empty)"))

        appendLine()
        appendLine("--- Host VirGL / Venus Server Log (virgl.log) ---")
        appendLine(readTailOrPlaceholder(File(logDir, "virgl.log"), maxBytes = 20_480, maxLines = 120, placeholder = "(virgl.log is empty or VirGL server not active)"))

        val xwaylandLog = File(root, "tmp/linuxdroid-xwayland.log")
        appendLine()
        appendLine("--- Guest Xwayland Log (/tmp/linuxdroid-xwayland.log) ---")
        appendLine(readTailOrPlaceholder(xwaylandLog, maxBytes = 16_384, maxLines = 80, placeholder = "(no Xwayland log present)"))

        val cacheDirs = findGuestCacheDirs(root)
        val kwinLog = cacheDirs.map { File(it, "kwin-gpu.log") }.firstOrNull { it.isFile && it.length() > 0L }
        appendLine()
        appendLine("--- Guest KWin GPU Log (kwin-gpu.log) ---")
        appendLine(readTailOrPlaceholder(kwinLog, maxBytes = 16_384, maxLines = 100, placeholder = "(kwin-gpu.log is empty)"))

        val userHome = File(root, "home/${SessionPrefs.username(context)}")
        val kwinrc = File(userHome, ".config/kwinrc")
        val kwinrulesrc = File(userHome, ".config/kwinrulesrc")
        appendLine()
        appendLine("--- Guest KWin Config (kwinrc / kwinrulesrc) ---")
        appendLine("[kwinrc]")
        appendLine(readTailOrPlaceholder(kwinrc, maxBytes = 4096, maxLines = 40, placeholder = "(missing)"))
        appendLine("[kwinrulesrc]")
        appendLine(readTailOrPlaceholder(kwinrulesrc, maxBytes = 4096, maxLines = 40, placeholder = "(missing)"))
    }

    private fun buildAppsAndCrashesSection(context: Context, root: File, logDir: File): String = buildString {
        appendLine("### [4] GPU APP LAUNCHES, FALLBACK MARKERS & CRASH LOGS ###")
        val cacheDirs = findGuestCacheDirs(root)
        var foundAnyMarker = false
        for (cache in cacheDirs) {
            val deskFail = File(cache, "gpu-desktop-failed")
            if (deskFail.exists()) {
                foundAnyMarker = true
                appendLine("Desktop GPU Fallback Marker (${deskFail.absolutePath}): ${FileUtils.readString(deskFail)?.trim()}")
            }
            val fbDir = File(cache, "gpu-fallback")
            val fbFiles = fbDir.listFiles()
            if (!fbFiles.isNullOrEmpty()) {
                foundAnyMarker = true
                for (f in fbFiles.sortedBy { it.name }) {
                    val reason = FileUtils.readString(f)?.trim().orEmpty().ifEmpty { "marked" }
                    appendLine("App Fallback Marker [${f.name}]: $reason")
                }
            }
        }
        if (!foundAnyMarker) {
            appendLine("Fallback Markers: None (all GPU paths clean)")
        }

        val gpuAppsLog = cacheDirs.map { File(it, "gpu-apps.log") }.firstOrNull { it.isFile && it.length() > 0L }
        appendLine()
        appendLine("--- GPU App Launch Trace (gpu-apps.log) ---")
        appendLine(readTailOrPlaceholder(gpuAppsLog, maxBytes = 20_480, maxLines = 120, placeholder = "(no GPU app launches recorded yet)"))

        for (cache in cacheDirs) {
            val errFiles = cache.listFiles { f -> f.isFile && f.name.startsWith("last-") && f.name.endsWith("-gpu.err") && f.length() > 0L }
            if (!errFiles.isNullOrEmpty()) {
                for (ef in errFiles.sortedBy { it.name }) {
                    appendLine()
                    appendLine("--- Last App Stderr (${ef.name}) ---")
                    appendLine(readTailOrPlaceholder(ef, maxBytes = 8192, maxLines = 50, placeholder = "(empty)"))
                }
            }
        }

        val crashFiles = logDir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
        appendLine()
        appendLine("--- Android Uncaught Crash Logs ---")
        if (crashFiles.isNullOrEmpty()) {
            appendLine("(no Android app crash logs recorded)")
        } else {
            for (cf in crashFiles) {
                appendLine("[${cf.name}]")
                appendLine(readTailOrPlaceholder(cf, maxBytes = 8192, maxLines = 50, placeholder = "(empty)"))
            }
        }
    }

    private fun buildProotAndAudioSection(context: Context, root: File, logDir: File): String = buildString {
        appendLine("### [5] PROOT HOST, BINDS & PULSEAUDIO ###")
        val binds = runCatching { LinuxRuntime.lastBinds(context) }.getOrDefault(emptyList())
        appendLine("Active PRoot Binds (${binds.size}):")
        for (b in binds) {
            appendLine("  -b $b")
        }
        val preload = File(root, "etc/ld.so.preload")
        appendLine("Guest /etc/ld.so.preload: ${FileUtils.readString(preload)?.trim()?.replace("\n", ", ") ?: "(none)"}")

        appendLine()
        appendLine("--- PRoot Host Output (proot-host.log) ---")
        appendLine(readTailOrPlaceholder(File(logDir, "proot-host.log"), maxBytes = 20_480, maxLines = 120, placeholder = "(proot-host.log is empty)"))

        val prevProot = File(logDir, "proot-host.prev.log")
        if (prevProot.isFile && prevProot.length() > 0L) {
            appendLine()
            appendLine("--- Previous PRoot Host Output (proot-host.prev.log, last 40 lines) ---")
            appendLine(readTailOrPlaceholder(prevProot, maxBytes = 12_288, maxLines = 40, placeholder = "(empty)"))
        }

        appendLine()
        appendLine("--- PulseAudio Server Log (pulseaudio.log) ---")
        appendLine(readTailOrPlaceholder(File(logDir, "pulseaudio.log"), maxBytes = 16_384, maxLines = 80, placeholder = "(pulseaudio.log is empty)"))
    }

    /**
     * Runs `linuxdroid-gpu-diag` and `linuxdroid-gpu-test` inside the installed rootfs on demand,
     * even if the desktop session is not currently running, so the user can test and capture full
     * GPU/Vulkan/EGL/Blender diagnostics at any time with a single button press.
     */
    fun runLiveDiagnosticsNow(context: Context): String {
        if (!LinuxRuntime.isReady(context)) {
            return "Linux rootfs is not installed yet. Install an edition on the Home tab first."
        }
        val useNoSeccomp = SessionPrefs.prootNoSeccomp(context) ||
            SessionState.state.value.compatibilityRetryUsed
        val enableFastPath = !useNoSeccomp && SessionPrefs.prootFastPath(context)
        LinuxRuntime.ensureBundledOverlayCurrent(context, enableFastPath = enableFastPath)
        RootSpawner.start(context)

        val root = LinuxRuntime.rootDir(context)
        val username = UserProvisioner.provisionRootfs(root, SessionPrefs.username(context), Process.myUid())
        val homePath = "/home/$username"

        val gpuInfo = GpuInfo.detect()
        val gpuConfig = GpuConfigGenerator.resolve(
            requested = SessionPrefs.gpuMode(context),
            gpu = gpuInfo,
            virglSocketGuestPath = LinuxRuntime.VIRGL_SOCKET_GUEST_PATH,
        )
        val envFile = File(root, "etc/linuxdroid/gpu.env")
        if (!envFile.isFile || envFile.length() == 0L) {
            FileUtils.writeString(envFile, gpuConfig.toEnvFileContent())
        }

        val binds = LinuxRuntime.lastBinds(context).filter { !it.startsWith("/proc/self/fd/") }
        val fastPathKey = if (enableFastPath) ProotFastPath.key(root, binds) else null
        val cmd = LinuxRuntime.prootPrefix(context, root, homePath, fakeRoot = false)
        for (spec in binds) {
            cmd.add("-b")
            cmd.add(spec)
        }
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=$homePath")
        cmd.add("USER=$username")
        cmd.add("LOGNAME=$username")
        cmd.add("SHELL=/bin/bash")
        cmd.add("LANG=C.UTF-8")
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        cmd.add("GLIBC_TUNABLES=${SessionPrefs.GLIBC_TUNABLES}")
        cmd.add("XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}")
        cmd.add("WAYLAND_DISPLAY=wayland-0")
        cmd.add("DISPLAY=:0")
        cmd.add("BL_GPU_APPS=${if (SessionPrefs.gpuApps(context) && gpuConfig.activeBackend != "software") "1" else "0"}")
        cmd.add("BL_GPU_DESKTOP=${if (SessionPrefs.gpuDesktop(context) && gpuConfig.activeBackend != "software") "1" else "0"}")
        for ((k, v) in gpuConfig.envVars) {
            cmd.add("$k=$v")
        }
        if (fastPathKey != null) {
            for ((k, v) in ProotFastPath.guestEnv(root, binds, fastPathKey)) {
                cmd.add("$k=$v")
            }
        }
        cmd.add("/bin/bash")
        cmd.add("-c")
        cmd.add("/usr/local/bin/linuxdroid-gpu-diag >/dev/null 2>&1; /usr/local/bin/linuxdroid-gpu-test >/dev/null 2>&1")

        var proc: java.lang.Process? = null
        return try {
            val pb = ProcessBuilder(cmd)
                .directory(root)
                .redirectErrorStream(true)
            pb.environment().apply {
                clear()
                put("PROOT_LOADER", LinuxRuntime.prootLoader(context).absolutePath)
                put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).absolutePath)
                if (useNoSeccomp) put("PROOT_NO_SECCOMP", "1")
                if (fastPathKey != null) {
                    val (k, v) = ProotFastPath.hostEnv(fastPathKey)
                    put(k, v)
                }
            }
            proc = pb.start()
            val finished = proc.waitFor(45, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                "GPU self-test timed out after 45s (partial results saved)"
            } else {
                "GPU diagnostics & self-test completed (exit ${proc.exitValue()})"
            }
        } catch (e: Exception) {
            "Failed running diagnostics: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            if (proc != null && proc.isAlive) proc.destroyForcibly()
        }
    }

    fun clearAllLogs(context: Context): Int {
        var count = SessionService.clearGpuFallbacks(context)
        val logDir = LinuxRuntime.logDir(context)
        logDir.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0L) {
                if (f.name.startsWith("crash-") || f.name.endsWith(".prev.log")) {
                    if (f.delete()) count++
                } else {
                    FileUtils.writeString(f, "")
                    count++
                }
            }
        }
        val root = LinuxRuntime.rootDir(context)
        for (cache in findGuestCacheDirs(root)) {
            for (name in listOf("gpu-diag.txt", "gpu-test.txt", "kwin-gpu.log", "gpu-apps.log")) {
                val f = File(cache, name)
                if (f.isFile && f.delete()) count++
            }
            cache.listFiles { f -> f.isFile && f.name.startsWith("last-") && f.name.endsWith("-gpu.err") }
                ?.forEach { if (it.delete()) count++ }
        }
        return count
    }

    internal fun findGuestCacheDirs(root: File): List<File> = buildList {
        add(File(root, "home/linux/.cache/linuxdroid"))
        File(root, "home").listFiles()?.forEach { homeChild ->
            if (homeChild.isDirectory && homeChild.name != "linux") {
                add(File(homeChild, ".cache/linuxdroid"))
            }
        }
        add(File(root, "root/.cache/linuxdroid"))
    }

    private fun describeHostNode(file: File): String {
        if (!file.exists()) return "missing"
        return try {
            val st = Os.stat(file.absolutePath)
            val modeOct = (st.st_mode and 0xfff).toString(8)
            val r = if (file.canRead()) "r" else "-"
            val w = if (file.canWrite()) "w" else "-"
            if (file.isDirectory) {
                val children = file.list()?.joinToString(",") ?: "unreadable"
                "dir(mode=0$modeOct, access=$r$w, entries=[$children])"
            } else {
                val rdev = st.st_rdev
                val maj = ((rdev shr 8) and 0xfffL) or ((rdev shr 32) and 0xfffL.inv())
                val min = (rdev and 0xffL) or ((rdev shr 12) and 0xffL.inv())
                "present(mode=0$modeOct, uid=${st.st_uid}, gid=${st.st_gid}, rdev=$maj:$min, access=$r$w)"
            }
        } catch (e: Exception) {
            "exists(statErr=${e.javaClass.simpleName})"
        }
    }

    internal fun readTailOrPlaceholder(
        file: File?,
        maxBytes: Int = 16_384,
        maxLines: Int = 100,
        placeholder: String = "(empty)",
    ): String {
        if (file == null || !file.isFile || file.length() <= 0L) return placeholder
        return try {
            val len = file.length()
            val readLen = minOf(len, maxBytes.toLong()).toInt()
            val buf = ByteArray(readLen)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - readLen)
                raf.readFully(buf)
            }
            val lines = String(buf, Charsets.UTF_8)
                .lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() && !it.startsWith("Warning:          ") }
                .toList()
            if (lines.isEmpty()) placeholder else lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "(error reading ${file.name}: ${e.message})"
        }
    }
}
