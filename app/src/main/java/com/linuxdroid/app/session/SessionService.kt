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

class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var pulseAudio: PulseAudioComponent? = null
    private var virglServer: VirglServerComponent? = null
    private var batteryComponent: BatteryComponent? = null
    private var networkComponent: LinuxNetworkLinkComponent? = null
    private var clipboard: SessionClipboard? = null
    private var suspendController: SessionSuspendController? = null

    @Volatile
    private var guestPid: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, 1920).coerceAtLeast(640)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1080).coerceAtLeast(480)
                val refreshHz = intent.getFloatExtra(EXTRA_REFRESH_HZ, 60f).coerceIn(30f, 240f)
                startForeground(NOTIFICATION_ID, buildNotification("Starting Linux workstation...", paused = false))
                if (!SessionState.isRunningOrPaused) {
                    Thread({ bootWorkstation(width, height, refreshHz) }, "session-boot").start()
                } else if (SessionState.isPaused) {
                    resumeWorkstation()
                }
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

    private fun bootWorkstation(width: Int, height: Int, refreshHz: Float) {
        if (!LinuxRuntime.isReady(this)) {
            SessionState.reset()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        acquireWakeLock()
        RootSpawner.start(this)

        val root = LinuxRuntime.rootDir(this)
        val sessionDir = LinuxRuntime.sessionDir(this)
        val xdgDir = LinuxRuntime.runtimeDir(this)
        val logDir = LinuxRuntime.logDir(this)

        SessionState.update {
            SessionSnapshot(
                phase = SessionPhase.STARTING,
                statusText = "Configuring GPU & Linux environment...",
                resolutionText = "${width}×${height} @ ${refreshHz.toInt()}Hz",
            )
        }

        val fastPathPref = SessionPrefs.prootFastPath(this) && !SessionPrefs.prootNoSeccomp(this)
        LinuxRuntime.stageBundledOverlay(this, enableFastPath = fastPathPref)
        val username = UserProvisioner.provisionRootfs(root, SessionPrefs.username(this), Process.myUid())

        val gpuInfo = GpuInfo.detect()
        val gpuConfig = GpuConfigGenerator.resolve(
            requested = SessionPrefs.gpuMode(this),
            gpu = gpuInfo,
            virglSocketGuestPath = LinuxRuntime.VIRGL_SOCKET_GUEST_PATH,
        )
        FileUtils.writeString(File(root, "etc/linuxdroid/gpu.env"), gpuConfig.toEnvFileContent())

        WaylandCompositor.nativeResetFirstFrame()
        WaylandCompositor.setFirstFrameListener {
            SessionState.update { it.copy(firstFrameReady = true, phase = SessionPhase.RUNNING, statusText = "Running ($username)") }
            refreshNotification()
        }
        CompositorHost.ensureStarted(this, xdgDir, width, height, refreshHz)

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
        )
        val fastPathKey = if (fastPathPref) ProotFastPath.key(root, binds) else null

        val homePath = "/home/$username"
        val scale = SessionPrefs.computeEffectiveScale(this, width, height)
        val scaleText = if (scale % 1.0f == 0f) scale.toInt().toString() else "%.2f".format(java.util.Locale.US, scale).trimEnd('0').trimEnd('.')

        val prootCmd = LinuxRuntime.prootPrefix(this, root, homePath, fakeRoot = false)
        for (spec in binds) {
            prootCmd.add("-b")
            prootCmd.add(spec)
        }

        val guestEnv = LinkedHashMap<String, String>().apply {
            put("HOME", homePath)
            put("USER", username)
            put("LOGNAME", username)
            put("SHELL", "/bin/bash")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("TZ", TimeZone.getDefault().id)
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("XDG_RUNTIME_DIR", LinuxRuntime.GUEST_RUNTIME_DIR)
            put("WAYLAND_DISPLAY", "wayland-0")
            put("PULSE_SERVER", "unix:${pulseAudio!!.socketFile().absolutePath}")
            put("BL_WIDTH", width.toString())
            put("BL_HEIGHT", height.toString())
            put("BL_SCALE", scaleText)
            put("BL_REFRESH", refreshHz.toInt().toString())
            put("BL_ANDROID_CLIPBOARD", clipboardFile.absolutePath)
            put("BL_IME_STATE", imeStateFile.absolutePath)
            put("BL_LOG", File(logDir, "session.log").absolutePath)
            put("LINUXDROID_DESKTOP", SessionPrefs.desktopEnvironment(this@SessionService))
            putAll(gpuConfig.envVars)
            if (fastPathKey != null) {
                putAll(ProotFastPath.guestEnv(root, binds, fastPathKey))
            }
        }

        prootCmd.add("/usr/bin/env")
        prootCmd.add("-i")
        for ((k, v) in guestEnv) prootCmd.add("$k=$v")
        prootCmd.add(LinuxRuntime.SESSION_SCRIPT)

        val hostEnv = LinkedHashMap<String, String>().apply {
            put("PROOT_LOADER", LinuxRuntime.prootLoader(this@SessionService).absolutePath)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(this@SessionService).absolutePath)
            if (SessionPrefs.prootNoSeccomp(this@SessionService)) put("PROOT_NO_SECCOMP", "1")
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

        val pid = HostProcess.start(
            argv = prootCmd,
            env = hostEnv,
            dir = root,
            onExit = { code ->
                Log.i(TAG, "Linux session exited ($code)")
                if (SessionState.state.value.phase != SessionPhase.STOPPING) {
                    shutdownWorkstation()
                }
            },
            outputFile = File(logDir, "proot-host.log"),
        )
        guestPid = pid

        SessionState.update {
            it.copy(
                phase = SessionPhase.RUNNING,
                statusText = "Running ($username)",
                activeGpuSummary = gpuConfig.summary,
                guestPid = pid,
            )
        }
        refreshNotification()
    }

    private fun pauseWorkstation() {
        val ctrl = suspendController ?: return
        if (SessionState.isPaused) return
        ctrl.freeze { ok ->
            if (ok) {
                releaseWakeLock()
                SessionState.update { it.copy(phase = SessionPhase.PAUSED, statusText = "Paused (0% CPU Freeze)") }
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
                SessionState.update { it.copy(phase = SessionPhase.RUNNING, statusText = "Running ($user)") }
                refreshNotification()
            }
        }
    }

    private fun shutdownWorkstation() {
        SessionState.update { it.copy(phase = SessionPhase.STOPPING, statusText = "Shutting down...") }
        val pid = guestPid
        guestPid = -1

        val cleanup = {
            if (pid > 1) {
                killProcessTree(pid)
            }
            clipboard?.stop()
            clipboard = null
            networkComponent?.stop()
            networkComponent = null
            batteryComponent?.stop()
            batteryComponent = null
            pulseAudio?.stop()
            pulseAudio = null
            virglServer?.stop()
            virglServer = null
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
            append(snap.statusText)
            if (snap.activeGpuSummary.isNotEmpty()) append(" • ").append(snap.activeGpuSummary)
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
        const val ACTION_PAUSE = "com.linuxdroid.app.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.linuxdroid.app.action.RESUME_SESSION"
        const val ACTION_TOGGLE_PAUSE = "com.linuxdroid.app.action.TOGGLE_PAUSE_SESSION"
        const val ACTION_SHUTDOWN = "com.linuxdroid.app.action.SHUTDOWN_SESSION"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_REFRESH_HZ = "refresh_hz"

        fun startOrResume(context: Context, width: Int, height: Int, refreshHz: Float) {
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_REFRESH_HZ, refreshHz)
            }
            context.startForegroundService(intent)
        }

        fun togglePause(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_TOGGLE_PAUSE))
        }

        fun shutdown(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_SHUTDOWN))
        }
    }
}
