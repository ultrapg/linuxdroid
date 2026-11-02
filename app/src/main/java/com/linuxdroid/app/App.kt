package com.linuxdroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.linuxdroid.app.runtime.RootSpawner
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        createNotificationChannels()
        RootSpawner.start(this)
    }

    private fun installCrashLogger() {
        val logDir = File(filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(logDir, "crash-$stamp.log").writeText(
                    "Thread: ${thread.name}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n\n$sw"
                )
            }.onFailure { Log.e(TAG, "Failed writing crash log", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val sessionChannel = NotificationChannel(
            CHANNEL_SESSION,
            getString(R.string.notification_channel_session),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Linux desktop running in the background with quick pause and shutdown actions."
            setShowBadge(false)
        }
        val installChannel = NotificationChannel(
            CHANNEL_INSTALL,
            getString(R.string.notification_channel_install),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows download and extraction progress when installing a Linux desktop rootfs."
            setShowBadge(false)
        }
        nm.createNotificationChannel(sessionChannel)
        nm.createNotificationChannel(installChannel)
    }

    companion object {
        private const val TAG = "LinuxDroidApp"
        const val CHANNEL_SESSION = "linuxdroid_session"
        const val CHANNEL_INSTALL = "linuxdroid_install"

        @Volatile
        var instance: App? = null
            private set
    }
}
