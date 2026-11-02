package com.linuxdroid.app.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.wayland.WaylandCompositor
import java.io.File

/**
 * Bidirectional clipboard bridge between Android ClipboardManager, Wayland wl_data_device,
 * and X11 CLIPBOARD (via linuxdroid-clipboard watching clipboardBridgeFile).
 */
class SessionClipboard(
    context: Context,
    val bridgeFile: File,
) {
    private val appContext = context.applicationContext
    private val clipboardManager = appContext.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastSyncedText: String = ""
    private var lastFileStamp: Long = -1L

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        pushAndroidClipboardToLinux()
    }

    private val filePollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val mod = bridgeFile.lastModified()
            if (mod != 0L && mod != lastFileStamp) {
                lastFileStamp = mod
                val text = FileUtils.readString(bridgeFile).orEmpty()
                if (text.isNotEmpty() && text != lastSyncedText) {
                    lastSyncedText = text
                    setAndroidClipQuietly(text)
                    WaylandCompositor.setClipboardText(text)
                }
            }
            handler.postDelayed(this, 400L)
        }
    }

    fun start() {
        bridgeFile.parentFile?.mkdirs()
        if (!bridgeFile.exists()) FileUtils.writeString(bridgeFile, "")
        lastFileStamp = bridgeFile.lastModified()
        running = true

        WaylandCompositor.setClipboardListener { guestText ->
            if (guestText.isNotEmpty() && guestText != lastSyncedText) {
                lastSyncedText = guestText
                FileUtils.writeString(bridgeFile, guestText)
                lastFileStamp = bridgeFile.lastModified()
                handler.post { setAndroidClipQuietly(guestText) }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(primaryClipListener)
        pushAndroidClipboardToLinux()
        handler.post(filePollRunnable)
    }

    fun pushAndroidClipboardToLinux() {
        val text = runCatching {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(appContext)?.toString() else null
        }.getOrNull().orEmpty()
        if (text.isNotEmpty() && text != lastSyncedText) {
            lastSyncedText = text
            FileUtils.writeString(bridgeFile, text)
            lastFileStamp = bridgeFile.lastModified()
            WaylandCompositor.setClipboardText(text)
        }
    }

    private fun setAndroidClipQuietly(text: String) {
        runCatching {
            clipboardManager?.setPrimaryClip(ClipData.newPlainText("LinuxDroid", text))
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(filePollRunnable)
        runCatching { clipboardManager?.removePrimaryClipChangedListener(primaryClipListener) }
        WaylandCompositor.setClipboardListener(null)
    }
}
