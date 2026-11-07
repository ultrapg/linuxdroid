package com.linuxdroid.app.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.linuxdroid.app.App
import com.linuxdroid.app.audio.PulseAudioComponent
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.core.HostProcess
import com.linuxdroid.app.gpu.GpuConfigGenerator
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.gpu.VirglServerComponent
import com.linuxdroid.app.runtime.CpuInfo
import com.linuxdroid.app.runtime.LinuxNetworkLinkComponent
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.runtime.ProotFastPath
import com.linuxdroid.app.runtime.RootSpawner
import com.linuxdroid.app.runtime.UserProvisioner
import com.linuxdroid.app.ui.SessionActivity
import com.linuxdroid.app.wayland.CompositorHost
import com.linuxdroid.app.wayland.WaylandCompositor
import java.io.File
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var pulseAudio: PulseAudioComponent? = null
    private var virglServer: VirglServerComponent? = null
    private var batteryComponent: BatteryComponent? = null
    private var networkComponent: LinuxNetworkLinkComponent? = null
    private var clipboard: SessionClipboard? = null
    private var suspendController: SessionSuspendController? = null

    private val bootGeneration = AtomicInteger(0)

    @Volatile
    private var guestPid: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, 1920).coerceAtLeast(640)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1080).coerceAtLeast(480)
                val refreshHz = intent.getFloatExtra(EXTRA_REFRESH_HZ, 60f).coerceIn(30f, 240f)
                val forceCompat = intent.getBooleanExtra(EXTRA_FORCE_COMPAT, false)
                val desiredSig = SessionPrefs.currentConfigSignature(this)
                val curSnap = SessionState.state.value
                startForeground(NOTIFICATION_ID, buildNotification("Starting Linux workstation...", paused = false))
                if (!SessionState.isRunningOrPaused) {
                    val gen = bootGeneration.incrementAndGet()
                    Thread(
                        { bootWorkstation(width, height, refreshHz, forceCompatRetry = forceCompat, generation = gen) },
                        "session-boot-$gen"
                    ).start()
                } else if (curSnap.activeConfigSignature != null && curSnap.activeConfigSignature != desiredSig) {
                    val gen = bootGeneration.incrementAndGet()
                    Thread(
                        { restartIntoBoot(width, height, refreshHz, forceCompatRetry = forceCompat, generation = gen) },
                        "session-restart-$gen"
                    ).start()
                } else if (SessionState.isPaused) {
                    resumeWorkstation()
                } else {
                    refreshNotification()
                }
            }
            ACTION_RESTART -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, 1920).coerceAtLeast(640)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1080).coerceAtLeast(480)
                val refreshHz = intent.getFloatExtra(EXTRA_REFRESH_HZ, 60f).coerceIn(30f, 240f)
                val forceCompat = intent.getBooleanExtra(EXTRA_FORCE_COMPAT, false)
                startForeground(NOTIFICATION_ID, buildNotification("Applying settings & restarting Linux...", paused = false))
                val gen = bootGeneration.incrementAndGet()
                Thread(
                    { restartIntoBoot(width, height, refreshHz, forceCompatRetry = forceCompat, generation = gen) },
                    "session-restart-$gen"
                ).start()
            }
            ACTION_TOGGLE_PAUSE -> {
                if (SessionState.isPaused) resumeWorkstation() else pauseWorkstation()
            }
            ACTION_PAUSE -> pauseWorkstation()
            ACTION_RESUME -> resumeWorkstation()
            ACTION_SHUTDOWN -> shutdownWorkstation()
        }
        return START_STICKY
    }

    private fun restartIntoBoot(
        width: Int,
        height: Int,
        refreshHz: Float,
        forceCompatRetry: Boolean,
        generation: Int,
    ) {
        val oldPid = guestPid
        guestPid = -1
        val ctrl = suspendController
        suspendController = null
        SessionState.update {
            it.copy(
                phase = SessionPhase.STARTING,
                statusText = "Applying updated settings & restarting...",
                firstFrameReady = false,
                guestPid = -1,
                bootStep = 1,
                errorMessage = null,
            )
        }
        refreshNotification()
        if (ctrl != null) {
            val latch = CountDownLatch(1)
            ctrl.shutdownAndResume { latch.countDown() }
            runCatching { latch.await(1_500L, TimeUnit.MILLISECONDS) }
        }
        if (oldPid > 1) {
            killProcessTree(oldPid)
        }
        stopHelpersOnly()
        if (bootGeneration.get() != generation) return
        bootWorkstation(width, height, refreshHz, forceCompatRetry = forceCompatRetry, generation = generation)
    }

    private fun bootWorkstation(
        width: Int,
        height: Int,
        refreshHz: Float,
        forceCompatRetry: Boolean,
        generation: Int,
    ) {
        val logDir = LinuxRuntime.logDir(this)
        if (!LinuxRuntime.isReady(this)) {
            failBoot(
                generation = generation,
                message = "Linux rootfs or PRoot native binaries are missing. Please install or re-import a desktop edition.",
                logDir = logDir,
            )
            return
        }

        acquireWakeLock()

        val fastPathPref = !forceCompatRetry &&
            SessionPrefs.prootFastPath(this) &&
            !SessionPrefs.prootNoSeccomp(this)
        LinuxRuntime.stageBundledOverlay(this, enableFastPath = fastPathPref)
        RootSpawner.start(this)

        val root = LinuxRuntime.rootDir(this)
        val sessionDir = LinuxRuntime.sessionDir(this)
        val xdgDir = LinuxRuntime.runtimeDir(this)
        val bootStatusFile = File(sessionDir, "boot-status.txt")
        val sessionLogFile = File(logDir, "session.log")
        val prootLogFile = File(logDir, "proot-host.log")

        rotateFreshBootLog(sessionLogFile)
        rotateFreshBootLog(prootLogFile)
        bootStatusFile.delete()
        runCatching {
            val shmDir = File(LinuxRuntime.liveDir(this), "shm")
            shmDir.deleteRecursively()
            shmDir.mkdirs()
        }

        val geom = SessionPrefs.computeCompositorGeometry(this, width, height)
        val configSig = SessionPrefs.currentConfigSignature(this)
        val scale = geom.effectiveScale
        val scaleText = if (scale % 1.0f == 0f) {
            scale.toInt().toString()
        } else {
            "%.2f".format(java.util.Locale.US, scale).trimEnd('0').trimEnd('.')
        }
        val wlScale = geom.compositorScale
        val wlScaleText = if (wlScale % 1.0f == 0f) wlScale.toInt().toString() else "%.2f".format(java.util.Locale.US, wlScale)
        val scalePct = (scale * 100f).roundToInt()
        val resolutionSummary = "${geom.pixelWidth}×${geom.pixelHeight} (${scalePct}%) @ ${refreshHz.toInt()}Hz"

        val startedMs = SessionState.state.value.startedAtMs.takeIf { forceCompatRetry && it > 0L }
            ?: System.currentTimeMillis()

        SessionState.update {
            SessionSnapshot(
                phase = SessionPhase.STARTING,
                statusText = if (forceCompatRetry) {
                    "Retrying in compatibility mode..."
                } else {
                    "Preparing Linux runtime & user account..."
                },
                resolutionText = resolutionSummary,
                bootStep = 1,
                bootTotalSteps = 6,
                compatibilityRetryUsed = forceCompatRetry,
                startedAtMs = startedMs,
                activeConfigSignature = configSig,
            )
        }
        refreshNotification()

        val username = UserProvisioner.provisionRootfs(root, SessionPrefs.username(this), Process.myUid())

        val cpuTopology = CpuInfo.detect()
        val affinityCores = SessionPrefs.resolveSelectedCpuCores(this, cpuTopology)
        val effectiveCpuCores = if (affinityCores.isNotEmpty()) affinityCores.size else cpuTopology.totalCores

        val gpuInfo = GpuInfo.detect()
        val gpuConfig = GpuConfigGenerator.resolve(
            requested = SessionPrefs.gpuMode(this),
            gpu = gpuInfo,
            virglSocketGuestPath = LinuxRuntime.VIRGL_SOCKET_GUEST_PATH,
        )
        val hwSummary = "${effectiveCpuCores}/${cpuTopology.totalCores} CPU Cores • ${gpuConfig.summary}"
        updateBootStep(
            generation = generation,
            step = 2,
            status = "Configuring GPU driver (${gpuConfig.activeBackend.uppercase()})...",
            gpuSummary = hwSummary,
        )
        FileUtils.writeString(File(root, "etc/linuxdroid/gpu.env"), gpuConfig.toEnvFileContent())

        updateBootStep(
            generation = generation,
            step = 3,
            status = "Starting Wayland compositor & audio server...",
        )
        val waylandFirstFrameAtMs = AtomicLong(0L)
        val guestShellReady = AtomicBoolean(false)

        fun completeDesktopReadyIfEligible() {
            if (bootGeneration.get() != generation) return
            val wfMs = waylandFirstFrameAtMs.get()
            val shellReady = guestShellReady.get()
            if (wfMs <= 0L && !shellReady) return
            if (!shellReady && System.currentTimeMillis() - wfMs < 6_000L) return
            SessionState.update {
                if (it.firstFrameReady || it.phase == SessionPhase.FAILED || it.phase == SessionPhase.STOPPING) {
                    it
                } else {
                    it.copy(
                        firstFrameReady = true,
                        phase = SessionPhase.RUNNING,
                        bootStep = 6,
                        statusText = "Running ($username)",
                        errorMessage = null,
                    )
                }
            }
            refreshNotification()
        }

        WaylandCompositor.nativeResetFirstFrame()
        WaylandCompositor.setFirstFrameListener {
            if (bootGeneration.get() == generation) {
                waylandFirstFrameAtMs.compareAndSet(0L, System.currentTimeMillis())
                completeDesktopReadyIfEligible()
            }
        }
        val waylandReady = CompositorHost.ensureStarted(
            context = this,
            xdgRuntimeDir = xdgDir,
            initialWidth = geom.compositorWidth,
            initialHeight = geom.compositorHeight,
            refreshHz = refreshHz,
            desktopScale = geom.compositorScale,
        )
        if (!waylandReady) {
            failBoot(
                generation = generation,
                message = "Android Wayland compositor failed to create socket ${xdgDir.absolutePath}/wayland-0.",
                logDir = logDir,
            )
            return
        }

        stopHelpersOnly()

        if (gpuConfig.requiresVirglServer) {
            virglServer = VirglServerComponent(
                context = this,
                socketFile = LinuxRuntime.virglSocketHostFile(this),
                enableVenus = gpuConfig.enableVenus,
                logFile = File(logDir, "virgl.log"),
            ).also { it.start() }
        }

        pulseAudio = PulseAudioComponent(
            context = this,
            enableMic = SessionPrefs.micEnabled(this),
            logFile = File(logDir, "pulseaudio.log"),
        ).also { it.start() }

        val batterySysfs = File(LinuxRuntime.liveDir(this), "power_supply")
        batteryComponent = BatteryComponent(this, batterySysfs).also { it.start() }
        networkComponent = LinuxNetworkLinkComponent(this, root).also { it.start() }

        val clipboardFile = File(sessionDir, "clipboard")
        val imeStateFile = File(sessionDir, "ime.json")
        FileUtils.writeString(imeStateFile, "{\"active\":false}\n")
        clipboard = SessionClipboard(this, clipboardFile).also { it.start() }

        val binds = LinuxRuntime.buildBinds(
            context = this,
            batterySysfsDir = batterySysfs,
            customMounts = SessionPrefs.customMounts(this),
            effectiveCpuCores = effectiveCpuCores,
        )
        val fastPathKey = if (fastPathPref) ProotFastPath.key(root, binds) else null

        val homePath = "/home/$username"

        val prootCmd = LinuxRuntime.prootPrefix(this, root, homePath, fakeRoot = false)
        for (spec in binds) {
            prootCmd.add("-b")
            prootCmd.add(spec)
        }

        val guestEnv = LinkedHashMap<String, String>().apply {
            put("BL_DESKTOP", "1")
            put("HOME", homePath)
            put("USER", username)
            put("LOGNAME", username)
            put("SHELL", "/bin/bash")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("TZ", TimeZone.getDefault().id)
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("GLIBC_TUNABLES", SessionPrefs.GLIBC_TUNABLES)
            put("XDG_RUNTIME_DIR", LinuxRuntime.GUEST_RUNTIME_DIR)
            put("WAYLAND_DISPLAY", "wayland-0")
            put("PULSE_SERVER", "unix:${pulseAudio!!.socketFile().absolutePath}")
            put("BL_PIXEL_WIDTH", geom.pixelWidth.toString())
            put("BL_PIXEL_HEIGHT", geom.pixelHeight.toString())
            put("BL_WIDTH", geom.compositorWidth.toString())
            put("BL_HEIGHT", geom.compositorHeight.toString())
            put("BL_SCALE", if (SessionPrefs.usesWaylandLogicalScale(this@SessionService)) wlScaleText else scaleText)
            put("BL_UI_SCALE", scaleText)
            put("BL_REFRESH", refreshHz.toInt().toString())
            put("BL_CPU_COUNT", effectiveCpuCores.toString())
            if (affinityCores.isNotEmpty()) {
                put("BL_CPU_CORES", affinityCores.joinToString(","))
            }
            put("BL_ANDROID_CLIPBOARD", clipboardFile.absolutePath)
            put("BL_IME_STATE", imeStateFile.absolutePath)
            put("BL_LOG", sessionLogFile.absolutePath)
            put("BL_BOOT_STATUS", bootStatusFile.absolutePath)
            put("BL_GPU_APPS", if (SessionPrefs.gpuApps(this@SessionService) && gpuConfig.activeBackend != "software") "1" else "0")
            put("BL_GPU_DESKTOP", if (SessionPrefs.gpuDesktop(this@SessionService) && gpuConfig.activeBackend != "software") "1" else "0")
            put("LINUXDROID_DESKTOP", SessionPrefs.desktopEnvironment(this@SessionService))
            put("LINUXDROID_ROOT_SOCKET", RootSpawner.socketName(this@SessionService))
            put("FLATPAK_BWRAP", "/usr/local/bin/bwrap")
            putAll(gpuConfig.envVars)
            if (fastPathKey != null) {
                putAll(ProotFastPath.guestEnv(root, binds, fastPathKey))
            }
        }

        prootCmd.add("/usr/bin/env")
        prootCmd.add("-i")
        for ((k, v) in guestEnv) prootCmd.add("$k=$v")
        prootCmd.add(LinuxRuntime.SESSION_SCRIPT)

        val useNoSeccomp = forceCompatRetry || SessionPrefs.prootNoSeccomp(this)
        val hostEnv = LinkedHashMap<String, String>().apply {
            put("PROOT_LOADER", LinuxRuntime.prootLoader(this@SessionService).absolutePath)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(this@SessionService).absolutePath)
            if (useNoSeccomp) put("PROOT_NO_SECCOMP", "1")
            if (fastPathKey != null) {
                val (k, v) = ProotFastPath.hostEnv(fastPathKey)
                put(k, v)
            }
        }

        suspendController = SessionSuspendController(
            sessionRootPid = { guestPid },
            helperRootPids = { listOfNotNull(virglServer?.pid?.takeIf { it > 1 }) },
            suspendAudio = { pulseAudio?.setSuspended(true) },
            resumeAudio = { pulseAudio?.setSuspended(false) },
            clockControlFile = File(root, "tmp/.bl-clock"),
        )

        updateBootStep(
            generation = generation,
            step = 4,
            status = "Starting Ubuntu ARM64 environment...",
            gpuSummary = hwSummary,
        )

        val pid = try {
            HostProcess.start(
                argv = prootCmd,
                env = hostEnv,
                dir = root,
                onExit = { code ->
                    onGuestProcessExited(
                        code = code,
                        width = width,
                        height = height,
                        refreshHz = refreshHz,
                        forceCompatRetry = forceCompatRetry,
                        usedFastPath = fastPathPref,
                        usedNoSeccomp = useNoSeccomp,
                        waylandPresented = { waylandFirstFrameAtMs.get() > 0L },
                        generation = generation,
                        logDir = logDir,
                    )
                },
                outputFile = prootLogFile,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting PRoot guest process", e)
            failBoot(
                generation = generation,
                message = "Failed to spawn PRoot process: ${e.message ?: e.javaClass.simpleName}",
                logDir = logDir,
            )
            return
        }
        guestPid = pid
        runCatching { Process.setThreadPriority(pid, -4) }

        SessionState.update { snap ->
            if (bootGeneration.get() != generation) snap
            else snap.copy(
                activeGpuSummary = hwSummary,
                guestPid = pid,
            )
        }
        refreshNotification()
        startBootProgressMonitor(
            generation = generation,
            bootStatusFile = bootStatusFile,
            logDir = logDir,
            guestShellReady = guestShellReady,
            onCheckComplete = ::completeDesktopReadyIfEligible,
        )
    }

    private fun startBootProgressMonitor(
        generation: Int,
        bootStatusFile: File,
        logDir: File,
        guestShellReady: AtomicBoolean,
        onCheckComplete: () -> Unit,
    ) {
        Thread({
            while (bootGeneration.get() == generation) {
                val snap = SessionState.state.value
                if (snap.firstFrameReady || snap.phase != SessionPhase.STARTING) break

                val rawStatus = FileUtils.readString(bootStatusFile)?.trim()
                var nextStep = snap.bootStep
                var nextStatus = snap.statusText
                if (!rawStatus.isNullOrEmpty()) {
                    val bar = rawStatus.indexOf('|')
                    if (bar > 0) {
                        val prefix = rawStatus.substring(0, bar).trim()
                        val parsedMsg = rawStatus.substring(bar + 1).trim()
                        if (prefix.equals("READY", ignoreCase = true)) {
                            guestShellReady.set(true)
                            nextStep = 6
                            if (parsedMsg.isNotEmpty()) {
                                nextStatus = parsedMsg
                            }
                        } else {
                            val parsedStep = prefix.toIntOrNull()
                            if (parsedStep != null && parsedStep >= nextStep) {
                                nextStep = parsedStep.coerceIn(1, 6)
                            }
                            if (parsedMsg.isNotEmpty()) {
                                nextStatus = parsedMsg
                            }
                        }
                    }
                }
                val tail = collectDiagnosticLogTail(logDir, maxLines = 18)
                if (nextStep != snap.bootStep || nextStatus != snap.statusText || tail != snap.bootLogTail) {
                    SessionState.update { cur ->
                        if (bootGeneration.get() != generation || cur.firstFrameReady || cur.phase != SessionPhase.STARTING) {
                            cur
                        } else {
                            cur.copy(
                                bootStep = nextStep,
                                statusText = nextStatus,
                                bootLogTail = tail,
                            )
                        }
                    }
                    if (nextStatus != snap.statusText) {
                        refreshNotification()
                    }
                }
                onCheckComplete()
                if (SessionState.state.value.firstFrameReady) break
                try {
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "session-boot-monitor-$generation").apply {
            isDaemon = true
            start()
        }
    }

    private fun onGuestProcessExited(
        code: Int,
        width: Int,
        height: Int,
        refreshHz: Float,
        forceCompatRetry: Boolean,
        usedFastPath: Boolean,
        usedNoSeccomp: Boolean,
        waylandPresented: () -> Boolean,
        generation: Int,
        logDir: File,
    ) {
        if (bootGeneration.get() != generation) return
        val snap = SessionState.state.value
        val hasPresented = snap.firstFrameReady || waylandPresented()
        Log.i(TAG, "Linux session exited (code=$code, phase=${snap.phase}, hasPresented=$hasPresented)")

        if (snap.phase == SessionPhase.STOPPING) return

        if (!hasPresented) {
            if (!forceCompatRetry && (usedFastPath || !usedNoSeccomp)) {
                Log.w(TAG, "Early exit ($code) before first frame; retrying in PRoot compatibility mode")
                val nextGen = bootGeneration.incrementAndGet()
                guestPid = -1
                stopHelpersOnly()
                Thread(
                    { bootWorkstation(width, height, refreshHz, forceCompatRetry = true, generation = nextGen) },
                    "session-boot-compat-$nextGen"
                ).start()
                return
            }

            failBoot(
                generation = generation,
                message = "Linux desktop exited before rendering its first frame (exit code $code).",
                logDir = logDir,
            )
            return
        }

        shutdownWorkstation()
    }

    private fun updateBootStep(
        generation: Int,
        step: Int,
        status: String,
        gpuSummary: String? = null,
    ) {
        if (bootGeneration.get() != generation) return
        SessionState.update { cur ->
            if (cur.firstFrameReady) cur
            else cur.copy(
                bootStep = step.coerceAtLeast(cur.bootStep),
                statusText = status,
                activeGpuSummary = gpuSummary ?: cur.activeGpuSummary,
            )
        }
        refreshNotification()
    }

    private fun failBoot(generation: Int, message: String, logDir: File) {
        if (bootGeneration.get() != generation) return
        Log.e(TAG, "Boot failed: $message")
        val pid = guestPid
        guestPid = -1
        if (pid > 1) killProcessTree(pid)
        suspendController = null
        stopHelpersOnly()
        CompositorHost.detachSurface()
        releaseWakeLock()
        val tail = collectDiagnosticLogTail(logDir, maxLines = 18)
        SessionState.update { cur ->
            cur.copy(
                phase = SessionPhase.FAILED,
                statusText = "Startup failed",
                guestPid = -1,
                errorMessage = message,
                bootLogTail = tail,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopHelpersOnly() {
        runCatching { clipboard?.stop() }
        clipboard = null
        runCatching { networkComponent?.stop() }
        networkComponent = null
        runCatching { batteryComponent?.stop() }
        batteryComponent = null
        runCatching { pulseAudio?.stop() }
        pulseAudio = null
        runCatching { virglServer?.stop() }
        virglServer = null
    }

    private fun rotateFreshBootLog(file: File) {
        try {
            if (file.isFile && file.length() > 0L) {
                val prev = File(file.parentFile, "${file.nameWithoutExtension}.prev.${file.extension}")
                file.copyTo(prev, overwrite = true)
            }
            FileUtils.writeString(file, "")
        } catch (_: Exception) {
        }
    }

    private fun pauseWorkstation() {
        val ctrl = suspendController ?: return
        if (SessionState.isPaused) return
        ctrl.freeze { ok ->
            if (ok) {
                releaseWakeLock()
                SessionState.update { it.copy(phase = SessionPhase.PAUSED, statusText = "Paused") }
                refreshNotification()
            }
        }
    }

    private fun resumeWorkstation() {
        val ctrl = suspendController ?: return
        if (!SessionState.isPaused) return
        acquireWakeLock()
        ctrl.resume { ok ->
            if (ok) {
                val user = SessionPrefs.username(this)
                val firstFrame = SessionState.state.value.firstFrameReady
                SessionState.update {
                    it.copy(
                        phase = if (firstFrame) SessionPhase.RUNNING else SessionPhase.STARTING,
                        statusText = if (firstFrame) "Running ($user)" else "Resuming startup...",
                    )
                }
                refreshNotification()
            }
        }
    }

    private fun shutdownWorkstation() {
        bootGeneration.incrementAndGet()
        SessionState.update { it.copy(phase = SessionPhase.STOPPING, statusText = "Shutting down...") }
        val pid = guestPid
        guestPid = -1

        val cleanup = {
            if (pid > 1) {
                killProcessTree(pid)
            }
            stopHelpersOnly()
            CompositorHost.detachSurface()
            releaseWakeLock()
            SessionState.reset()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        val ctrl = suspendController
        suspendController = null
        if (ctrl != null) {
            ctrl.shutdownAndResume { cleanup() }
        } else {
            Thread(cleanup, "session-stop").start()
        }
    }

    private fun killProcessTree(rootPid: Int) {
        val parents = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val p = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()?.let { parents[p] = it }
        }
        val tree = HashSet<Int>().apply { add(rootPid) }
        var grew = true
        while (grew) {
            grew = false
            for ((p, parent) in parents) {
                if (p !in tree && parent in tree && p != Process.myPid()) {
                    tree.add(p)
                    grew = true
                }
            }
        }
        tree.forEach { runCatching { Process.sendSignal(it, OsConstants.SIGTERM) } }
        try { Thread.sleep(250L) } catch (_: InterruptedException) {}
        tree.forEach { runCatching { Process.killProcess(it) } }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinuxDroid:WorkstationSession").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun refreshNotification() {
        val snap = SessionState.state.value
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val subtitle = buildString {
            if (snap.phase == SessionPhase.STARTING && snap.bootStep > 0) {
                append("Step ${snap.bootStep}/${snap.bootTotalSteps}: ")
            }
            append(snap.statusText)
            if (snap.activeGpuSummary.isNotEmpty() && snap.phase == SessionPhase.RUNNING) {
                append(" • ").append(snap.activeGpuSummary)
            }
        }
        nm.notify(NOTIFICATION_ID, buildNotification(subtitle, snap.phase == SessionPhase.PAUSED))
    }

    private fun buildNotification(subtitle: String, paused: Boolean): Notification {
        val openDesktop = PendingIntent.getActivity(
            this,
            10,
            Intent(this, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val togglePause = PendingIntent.getService(
            this,
            11,
            Intent(this, SessionService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shutdown = PendingIntent.getService(
            this,
            12,
            Intent(this, SessionService::class.java).setAction(ACTION_SHUTDOWN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SESSION)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("LinuxDroid Desktop")
            .setContentText(subtitle)
            .setOngoing(true)
            .setContentIntent(openDesktop)
            .addAction(0, if (paused) "Resume" else "Pause", togglePause)
            .addAction(0, "Shut Down", shutdown)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessionService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.linuxdroid.app.action.START_SESSION"
        const val ACTION_RESTART = "com.linuxdroid.app.action.RESTART_SESSION"
        const val ACTION_PAUSE = "com.linuxdroid.app.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.linuxdroid.app.action.RESUME_SESSION"
        const val ACTION_TOGGLE_PAUSE = "com.linuxdroid.app.action.TOGGLE_PAUSE_SESSION"
        const val ACTION_SHUTDOWN = "com.linuxdroid.app.action.SHUTDOWN_SESSION"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_REFRESH_HZ = "refresh_hz"
        const val EXTRA_FORCE_COMPAT = "force_compat"

        fun startOrResume(
            context: Context,
            width: Int,
            height: Int,
            refreshHz: Float,
            forceCompat: Boolean = false,
        ) {
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_REFRESH_HZ, refreshHz)
                putExtra(EXTRA_FORCE_COMPAT, forceCompat)
            }
            context.startForegroundService(intent)
        }

        fun restart(
            context: Context,
            width: Int = 1920,
            height: Int = 1080,
            refreshHz: Float = 60f,
            forceCompat: Boolean = false,
        ) {
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_RESTART
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_REFRESH_HZ, refreshHz)
                putExtra(EXTRA_FORCE_COMPAT, forceCompat)
            }
            context.startForegroundService(intent)
        }

        fun togglePause(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_TOGGLE_PAUSE))
        }

        fun shutdown(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_SHUTDOWN))
        }

        fun shutdownAndAwait(context: Context, timeoutMs: Long = 4_000L) {
            val cur = SessionState.state.value
            if (cur.phase == SessionPhase.STOPPED && cur.guestPid <= 0) return
            shutdown(context)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val snap = SessionState.state.value
                if (snap.phase == SessionPhase.STOPPED && snap.guestPid <= 0) return
                try {
                    Thread.sleep(80L)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        internal fun collectDiagnosticLogTail(logDir: File, maxLines: Int = 12): List<String> {
            val combined = ArrayList<String>()
            val prootLines = tailNonEmptyLines(File(logDir, "proot-host.log"), 6)
            val sessionLines = tailNonEmptyLines(File(logDir, "session.log"), maxLines)
            val waylandLines = if (sessionLines.isEmpty()) tailNonEmptyLines(File(logDir, "wayland.log"), 5) else emptyList()

            for (l in prootLines) combined.add("[proot] $l")
            for (l in sessionLines) combined.add(l)
            for (l in waylandLines) combined.add("[wayland] $l")

            val filesDir = logDir.parentFile
            if (filesDir != null) {
                val root = File(filesDir, LinuxRuntime.DIR)
                val cacheDirs = buildList {
                    add(File(root, "home/linux/.cache/linuxdroid"))
                    File(root, "home").listFiles()?.forEach { homeChild ->
                        if (homeChild.isDirectory && homeChild.name != "linux") {
                            add(File(homeChild, ".cache/linuxdroid"))
                        }
                    }
                    add(File(root, "root/.cache/linuxdroid"))
                }
                for (cacheDir in cacheDirs) {
                    val diagLines = tailNonEmptyLines(File(cacheDir, "gpu-diag.txt"), 14)
                    for (l in diagLines) combined.add("[gpu-diag] $l")
                    val testLines = tailNonEmptyLines(File(cacheDir, "gpu-test.txt"), 10)
                    for (l in testLines) combined.add("[gpu-test] $l")
                    val kwinGpuLines = tailNonEmptyLines(File(cacheDir, "kwin-gpu.log"), 8)
                    for (l in kwinGpuLines) combined.add("[kwin-gpu] $l")
                    val appGpuLines = tailNonEmptyLines(File(cacheDir, "gpu-apps.log"), 10)
                    for (l in appGpuLines) combined.add("[gpu-app] $l")
                    if (diagLines.isNotEmpty() || kwinGpuLines.isNotEmpty() || appGpuLines.isNotEmpty()) break
                }
            }

            return if (combined.size <= maxLines) combined else combined.takeLast(maxLines)
        }

        fun clearGpuFallbacks(context: Context): Int {
            val root = LinuxRuntime.rootDir(context)
            var cleared = 0
            val homes = buildList {
                File(root, "home").listFiles()?.forEach { if (it.isDirectory) add(it) }
                add(File(root, "root"))
            }
            for (h in homes) {
                val cache = File(h, ".cache/linuxdroid")
                val deskFail = File(cache, "gpu-desktop-failed")
                if (deskFail.exists() && deskFail.delete()) cleared++
                val appFallbackDir = File(cache, "gpu-fallback")
                appFallbackDir.listFiles()?.forEach { f ->
                    if (f.delete()) cleared++
                }
            }
            return cleared
        }

        private fun tailNonEmptyLines(file: File, count: Int): List<String> {
            if (!file.isFile) return emptyList()
            return try {
                val len = file.length()
                val readBytes = minOf(len, 16_384L).toInt()
                if (readBytes <= 0) return emptyList()
                val buf = ByteArray(readBytes)
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(len - readBytes)
                    raf.readFully(buf)
                }
                String(buf, Charsets.UTF_8)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("Warning:          ") }
                    .toList()
                    .takeLast(count)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
