package com.linuxdroid.app.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import com.linuxdroid.app.App
import com.linuxdroid.app.core.ArchiveExtractor
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.session.SessionPrefs
import com.linuxdroid.app.session.SessionService
import com.linuxdroid.app.session.SessionState
import com.linuxdroid.app.ui.FrontEndActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class InstallProgress(
    val active: Boolean = false,
    val deleting: Boolean = false,
    val stage: String = "",
    val fraction: Float = -1f,
    val recentEntries: List<String> = emptyList(),
    val error: String? = null,
)

class RuntimeInstallService : Service() {
    private val logEntries = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                val editionId = intent.getStringExtra(EXTRA_EDITION_ID) ?: "ubuntu-arm64"
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Ubuntu ARM64 Desktop"
                val desktop = intent.getStringExtra(EXTRA_DESKTOP) ?: "auto"
                val sha256 = intent.getStringExtra(EXTRA_SHA256).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification("Preparing $title...", -1f, allowCancel = true))
                runWorker(isDelete = false) {
                    installFromDownload(editionId, url, title, desktop, sha256)
                }
            }
            ACTION_IMPORT_URI -> {
                val uri = intent.data ?: return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, buildNotification("Importing Linux rootfs archive...", -1f, allowCancel = true))
                runWorker(isDelete = false) {
                    installFromUri(uri)
                }
            }
            ACTION_DELETE -> {
                val label = LinuxRuntime.detectInstalledDesktopLabel(this)
                startForeground(NOTIFICATION_ID, buildNotification("Deleting $label...", -1f, allowCancel = false))
                runWorker(isDelete = true) {
                    deleteInstalledEdition(label)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runWorker(isDelete: Boolean, block: () -> Unit) {
        if (progressFlow.value.active) return
        cancelled = false
        synchronized(logEntries) { logEntries.clear() }
        Thread({
            try {
                block()
                if (cancelled && !isDelete) {
                    appendLog("Installation cancelled by user.")
                    updateProgress(active = false, deleting = false, stage = "Installation cancelled", fraction = -1f)
                } else if (isDelete) {
                    appendLog("Edition removed cleanly.")
                    updateProgress(active = false, deleting = false, stage = "Edition deleted", fraction = -1f)
                } else {
                    appendLog("Workstation ready to launch.")
                    updateProgress(active = false, deleting = false, stage = "Desktop installed and ready", fraction = 1f)
                }
            } catch (e: Exception) {
                Log.e(TAG, if (isDelete) "Rootfs delete failed" else "Rootfs install failed", e)
                appendLog("Error: ${e.message ?: e.toString()}")
                updateProgress(
                    active = false,
                    deleting = false,
                    stage = if (isDelete) "Deletion failed" else "Installation failed",
                    fraction = -1f,
                    error = e.message ?: e.toString(),
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, if (isDelete) "rootfs-deleter" else "rootfs-installer").start()
    }

    private fun ensureNoEditionInstalled() {
        if (LinuxRuntime.hasRootfs(this)) {
            throw IOException("An edition is already installed. Delete the current edition first before installing another.")
        }
    }

    private fun installFromDownload(
        editionId: String,
        url: String,
        title: String,
        desktop: String,
        expectedSha256: String,
    ) {
        ensureNoEditionInstalled()
        val archiveFile = File(cacheDir, "download-rootfs.tar.zst")
        archiveFile.delete()
        try {
            appendLog("Connecting to release server for $title...")
            updateProgress(true, false, "Downloading $title...", 0f)
            val conn = followRedirects(url)
            val totalBytes = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                FileOutputStream(archiveFile).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var downloaded = 0L
                    var lastNotify = 0L
                    var lastLoggedTenPct = -1
                    while (!cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastNotify >= 200L) {
                                lastNotify = now
                                val frac = if (totalBytes > 0) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
                                val mb = downloaded / (1024 * 1024)
                                val totalMb = if (totalBytes > 0) " / ${totalBytes / (1024 * 1024)} MB" else " MB"
                                if (frac >= 0f) {
                                    val bucket = (frac * 10f).toInt()
                                    if (bucket > lastLoggedTenPct) {
                                        lastLoggedTenPct = bucket
                                        appendLog("Downloaded ${bucket * 10}% ($mb$totalMb)")
                                    }
                                }
                                updateProgress(true, false, "Downloading $title ($mb$totalMb)", frac)
                            }
                        }
                    }
                }
            }
            if (cancelled) return
            if (expectedSha256.isNotBlank()) {
                appendLog("Verifying SHA-256 archive integrity...")
                updateProgress(true, false, "Verifying SHA-256 checksum...", 1f)
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("SHA-256 mismatch (expected ${expectedSha256.take(12)}..., got ${actualSha.take(12)}...)")
                }
                appendLog("SHA-256 verified (${actualSha.take(12)}...).")
            }
            unpackIntoRootfs(archiveFile, editionId, title, desktop)
        } finally {
            archiveFile.delete()
        }
    }

    private fun installFromUri(uri: Uri) {
        ensureNoEditionInstalled()
        val fileName = queryDisplayName(uri) ?: "custom-rootfs.tar.zst"
        appendLog("Importing archive: $fileName")
        updateProgress(true, false, "Extracting $fileName...", -1f)
        val stagingRoot = File(filesDir, "linuxfs-staging")
        FileUtils.deleteRecursively(stagingRoot)
        stagingRoot.mkdirs()

        var lastNotify = 0L
        contentResolver.openInputStream(uri)?.use { stream ->
            ArchiveExtractor.extractStream(stream, fileName, stagingRoot) { count, entry ->
                if (cancelled) throw IOException("Cancelled")
                val now = System.currentTimeMillis()
                if (now - lastNotify >= 150L) {
                    lastNotify = now
                    appendLog("[$count files] $entry")
                    updateProgress(true, false, "Extracting $fileName ($count files)...", -1f)
                }
            }
        } ?: throw IOException("Cannot open selected archive URI")

        finalizeStaging(stagingRoot, "custom-rootfs", "Custom Rootfs ($fileName)", "auto")
    }

    private fun unpackIntoRootfs(archiveFile: File, editionId: String, editionLabel: String, desktop: String) {
        val stagingRoot = File(filesDir, "linuxfs-staging")
        FileUtils.deleteRecursively(stagingRoot)
        stagingRoot.mkdirs()
        appendLog("Extracting root filesystem...")
        updateProgress(true, false, "Extracting $editionLabel...", -1f)
        var lastNotify = 0L
        ArchiveExtractor.extractFile(archiveFile, stagingRoot) { count, entry ->
            if (cancelled) throw IOException("Cancelled")
            val now = System.currentTimeMillis()
            if (now - lastNotify >= 150L) {
                lastNotify = now
                appendLog("[$count files] $entry")
                updateProgress(true, false, "Extracting $editionLabel ($count files)...", -1f)
            }
        }
        finalizeStaging(stagingRoot, editionId, editionLabel, desktop)
    }

    private fun finalizeStaging(stagingRoot: File, editionId: String, editionLabel: String, desktop: String) {
        val actualRoot = unwrapSingleSubdirIfNeeded(stagingRoot)
        if (!File(actualRoot, "bin").exists() && !File(actualRoot, "usr/bin").exists()) {
            FileUtils.deleteRecursively(stagingRoot)
            throw IOException("Archive does not contain a valid Linux root filesystem (/usr/bin missing)")
        }

        val targetRoot = LinuxRuntime.rootDir(this)
        appendLog("Provisioning runtime scripts & user account...")
        updateProgress(true, false, "Finalizing Linux workstation...", -1f)
        FileUtils.deleteRecursively(targetRoot)
        if (!actualRoot.renameTo(targetRoot)) {
            throw IOException("Failed moving staged rootfs into place")
        }
        FileUtils.deleteRecursively(stagingRoot)

        FileUtils.writeString(File(targetRoot, "etc/linuxdroid/edition"), "$editionLabel\n")
        FileUtils.writeString(File(targetRoot, "etc/linuxdroid/edition-id"), "$editionId\n")
        SessionPrefs.setInstalledEditionId(this, editionId)
        if (desktop != "auto") {
            SessionPrefs.setDesktopEnvironment(this, desktop)
        }
        LinuxRuntime.clearCachedBinds()
        val enableFastPath = !SessionPrefs.prootNoSeccomp(this) && SessionPrefs.prootFastPath(this)
        LinuxRuntime.stageBundledOverlay(this, enableFastPath)
        UserProvisioner.provisionRootfs(targetRoot, SessionPrefs.username(this), Process.myUid())
    }

    private fun deleteInstalledEdition(label: String) {
        appendLog("Stopping any running Linux session...")
        updateProgress(true, true, "Stopping active Linux session...", -1f)
        SessionService.shutdownAndAwait(this, timeoutMs = 4_000L)

        appendLog("Deleting $label root filesystem...")
        updateProgress(true, true, "Deleting $label...", -1f)
        FileUtils.deleteRecursively(LinuxRuntime.rootDir(this))
        FileUtils.deleteRecursively(File(filesDir, "linuxfs-staging"))
        FileUtils.deleteRecursively(File(LinuxRuntime.liveDir(this), "shm"))
        FileUtils.deleteRecursively(File(LinuxRuntime.liveDir(this), "proot"))
        File(LinuxRuntime.sessionDir(this), "boot-status.txt").delete()

        LinuxRuntime.clearCachedBinds()
        SessionPrefs.setInstalledEditionId(this, null)
        SessionPrefs.setDesktopEnvironment(this, "auto")
        SessionState.reset()
    }

    private fun unwrapSingleSubdirIfNeeded(dir: File): File {
        if (File(dir, "usr").isDirectory || File(dir, "etc").isDirectory) return dir
        val children = dir.listFiles() ?: return dir
        if (children.size == 1 && children[0].isDirectory) {
            val nested = children[0]
            if (File(nested, "usr").isDirectory || File(nested, "etc").isDirectory) {
                return nested
            }
        }
        return dir
    }

    private fun followRedirects(initialUrl: String): HttpURLConnection {
        var current = initialUrl
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "LinuxDroid/1.0")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect missing Location")
                current = URL(URL(current), loc).toString()
                conn.disconnect()
            } else if (code in 200..299) {
                return conn
            } else {
                throw IOException("HTTP $code when downloading rootfs ($current)")
            }
        }
        throw IOException("Too many redirects for $initialUrl")
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun appendLog(line: String) {
        synchronized(logEntries) {
            if (logEntries.size >= 24) logEntries.removeFirst()
            logEntries.addLast(line)
        }
    }

    private fun snapshotLogs(): List<String> = synchronized(logEntries) { logEntries.toList() }

    private fun updateProgress(
        active: Boolean,
        deleting: Boolean,
        stage: String,
        fraction: Float,
        error: String? = null,
    ) {
        progressFlow.value = InstallProgress(
            active = active,
            deleting = deleting,
            stage = stage,
            fraction = fraction,
            recentEntries = snapshotLogs(),
            error = error,
        )
        if (active) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(stage, fraction, allowCancel = !deleting))
        }
    }

    private fun buildNotification(stage: String, fraction: Float, allowCancel: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, FrontEndActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, App.CHANNEL_INSTALL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LinuxDroid Rootfs Manager")
            .setContentText(stage)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setProgress(100, if (fraction >= 0f) (fraction * 100).toInt() else 0, fraction < 0f)

        if (allowCancel) {
            val cancelIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, RuntimeInstallService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Cancel", cancelIntent)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "RuntimeInstallService"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_DOWNLOAD = "com.linuxdroid.app.action.DOWNLOAD_ROOTFS"
        const val ACTION_IMPORT_URI = "com.linuxdroid.app.action.IMPORT_URI_ROOTFS"
        const val ACTION_DELETE = "com.linuxdroid.app.action.DELETE_ROOTFS"
        const val ACTION_CANCEL = "com.linuxdroid.app.action.CANCEL_INSTALL"
        const val EXTRA_EDITION_ID = "edition_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESKTOP = "desktop"
        const val EXTRA_SHA256 = "sha256"

        @Volatile
        private var cancelled = false

        private val progressFlow = MutableStateFlow(InstallProgress())
        val state: StateFlow<InstallProgress> = progressFlow

        fun startDownload(context: Context, edition: DesktopEdition) {
            val intent = Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_EDITION_ID, edition.id)
                putExtra(EXTRA_URL, edition.downloadUrl)
                putExtra(EXTRA_TITLE, edition.title)
                putExtra(EXTRA_DESKTOP, edition.desktop)
                putExtra(EXTRA_SHA256, edition.sha256)
            }
            context.startForegroundService(intent)
        }

        fun startImport(context: Context, uri: Uri) {
            val intent = Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_IMPORT_URI
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        }

        fun startDelete(context: Context) {
            val intent = Intent(context, RuntimeInstallService::class.java).setAction(ACTION_DELETE)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            cancelled = true
            context.startService(Intent(context, RuntimeInstallService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
