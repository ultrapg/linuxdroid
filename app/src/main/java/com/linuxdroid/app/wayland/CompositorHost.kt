package com.linuxdroid.app.wayland

import android.content.Context
import android.system.Os
import android.util.Log
import android.view.Surface
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.gpu.GpuFamily
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.gpu.TurnipDriver
import com.linuxdroid.app.runtime.LinuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object CompositorHost {
    private const val TAG = "CompositorHost"
    private val started = AtomicBoolean(false)

    @Synchronized
    fun ensureStarted(
        context: Context,
        xdgRuntimeDir: File,
        initialWidth: Int,
        initialHeight: Int,
        refreshHz: Float,
        desktopScale: Float = 1.0f,
        surface: Surface? = null,
        surfaceWidth: Int = initialWidth,
        surfaceHeight: Int = initialHeight,
    ): Boolean {
        xdgRuntimeDir.mkdirs()
        FileUtils.chmod(xdgRuntimeDir, 0b111_000_000)
        ensureKeymapExtracted(context, xdgRuntimeDir)

        val waylandLog = File(LinuxRuntime.logDir(context), "wayland.log")
        runCatching { Os.setenv("BL_WAYLAND_LOG", waylandLog.absolutePath, true) }

        val gpu = GpuInfo.detect()
        WaylandCompositor.nativeSetUbwc(gpu.family == GpuFamily.ADRENO)
        WaylandCompositor.nativeSetCursorComposite(true)
        WaylandCompositor.nativeSetRenderOnCommit(true)
        WaylandCompositor.nativeSetScaleMode(1, 0)
        WaylandCompositor.nativeSetDesktopScale(desktopScale)
        WaylandCompositor.nativeSetOutputSize(initialWidth, initialHeight)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)

        if (started.compareAndSet(false, true)) {
            val hostDriver = TurnipDriver.prepareHostPresenterDriver(context, gpu)
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            Log.i(
                TAG,
                "Starting Wayland compositor (${initialWidth}x${initialHeight}@${refreshHz}Hz, scale=${desktopScale}, gpu=${gpu.deviceName}, hostDriver=${hostDriver?.libraryName ?: "system"})"
            )
            WaylandCompositor.nativeStartWithSurface(
                surface,
                xdgRuntimeDir.absolutePath,
                hostDriver?.driverDir,
                hostDriver?.libraryName,
                nativeLibDir,
            )
            if (surface != null && surfaceWidth > 0 && surfaceHeight > 0) {
                WaylandCompositor.nativeSetSurfaceWithSize(surface, surfaceWidth, surfaceHeight)
            }
        } else {
            if (initialWidth > 0 && initialHeight > 0) {
                WaylandCompositor.nativeResizeOutput(initialWidth, initialHeight)
            }
            if (surface != null) {
                attachSurface(surface, initialWidth, initialHeight, refreshHz, desktopScale, surfaceWidth, surfaceHeight)
            }
        }

        val socketFile = File(xdgRuntimeDir, "wayland-0")
        for (i in 0 until 60) {
            if (socketFile.exists()) return true
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return socketFile.exists()
    }

    private fun ensureKeymapExtracted(context: Context, xdgRuntimeDir: File) {
        val keymapOut = File(xdgRuntimeDir, "keymap.xkb")
        if (keymapOut.isFile && keymapOut.length() > 0L) return
        try {
            val tmp = File(xdgRuntimeDir, ".keymap.xkb.tmp")
            context.assets.open("wayland/keymap.xkb").use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            FileUtils.chmod(tmp, 0b110_100_100)
            if (!tmp.renameTo(keymapOut)) {
                tmp.copyTo(keymapOut, overwrite = true)
                tmp.delete()
                FileUtils.chmod(keymapOut, 0b110_100_100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting assets/wayland/keymap.xkb", e)
        }
    }

    fun attachSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        refreshHz: Float,
        desktopScale: Float = 1.0f,
        surfaceWidth: Int = width,
        surfaceHeight: Int = height,
    ) {
        WaylandCompositor.nativeSetDesktopScale(desktopScale)
        if (width > 0 && height > 0) {
            WaylandCompositor.nativeResizeOutput(width, height)
        }
        if (refreshHz > 0f) {
            WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
            WaylandCompositor.nativeSetLayerFrameRate(refreshHz)
        }
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            WaylandCompositor.nativeSetSurfaceWithSize(surface, surfaceWidth, surfaceHeight)
        } else {
            WaylandCompositor.nativeSetSurface(surface)
        }
    }

    fun detachSurface() {
        WaylandCompositor.nativeSetSurface(null)
    }
}
