package com.linuxdroid.app.wayland

import android.content.Context
import android.util.Log
import android.view.Surface
import com.linuxdroid.app.gpu.GpuFamily
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.gpu.TurnipDriver
import java.io.File
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
        surface: Surface? = null,
    ) {
        xdgRuntimeDir.mkdirs()
        val gpu = GpuInfo.detect()
        WaylandCompositor.nativeSetUbwc(gpu.family == GpuFamily.ADRENO)
        WaylandCompositor.nativeSetCursorComposite(true)
        WaylandCompositor.nativeSetRenderOnCommit(true)
        WaylandCompositor.nativeSetOutputSize(initialWidth, initialHeight)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)

        if (started.compareAndSet(false, true)) {
            val hostDriver = TurnipDriver.prepareHostPresenterDriver(context, gpu)
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            Log.i(
                TAG,
                "Starting Wayland compositor (${initialWidth}x${initialHeight}@${refreshHz}Hz, gpu=${gpu.deviceName}, hostDriver=${hostDriver?.libraryName ?: "system"})"
            )
            WaylandCompositor.nativeStartWithSurface(
                surface,
                xdgRuntimeDir.absolutePath,
                hostDriver?.driverDir,
                hostDriver?.libraryName,
                nativeLibDir,
            )
        } else if (surface != null) {
            attachSurface(surface, initialWidth, initialHeight, refreshHz)
        }
    }

    fun attachSurface(surface: Surface?, width: Int, height: Int, refreshHz: Float) {
        if (width > 0 && height > 0) {
            WaylandCompositor.nativeResizeOutput(width, height)
        }
        if (refreshHz > 0f) {
            WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
            WaylandCompositor.nativeSetLayerFrameRate(refreshHz)
        }
        WaylandCompositor.nativeSetSurface(surface)
    }

    fun detachSurface() {
        WaylandCompositor.nativeSetSurface(null)
    }
}
