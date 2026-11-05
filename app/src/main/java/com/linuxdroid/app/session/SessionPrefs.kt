package com.linuxdroid.app.session

import android.content.Context
import android.content.pm.ActivityInfo
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.runtime.UserProvisioner
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

enum class TouchInputMode(val id: String, val label: String) {
    TOUCHSCREEN("touchscreen", "Direct Touch"),
    TRACKPAD("trackpad", "Virtual Trackpad");

    companion object {
        fun fromId(id: String?): TouchInputMode =
            entries.firstOrNull { it.id == id } ?: TOUCHSCREEN
    }
}

enum class ExternalDisplayMode(
    val id: String,
    val title: String,
    val subtitle: String,
) {
    AUTO_EXTERNAL(
        id = "auto_external",
        title = "Auto-Switch on Connection",
        subtitle = "Uses an external USB-C / HDMI monitor or wireless display when connected, and switches back to the phone screen when unplugged.",
    ),
    EXTERNAL_ONLY(
        id = "external_only",
        title = "External Display Only",
        subtitle = "Outputs the desktop exclusively to a USB-C / HDMI monitor or wireless display while your phone works as a trackpad and keyboard.",
    ),
    PHONE_ONLY(
        id = "phone_only",
        title = "Phone Screen / App Window Only",
        subtitle = "Always renders on the device screen or inside the current Samsung DeX / multi-window.",
    );

    companion object {
        fun fromId(id: String?): ExternalDisplayMode =
            entries.firstOrNull { it.id == id } ?: AUTO_EXTERNAL
    }
}

enum class ScreenOrientationPref(val id: String, val label: String, val androidOrientation: Int) {
    LANDSCAPE("landscape", "Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    AUTO("auto", "Auto-Rotate", ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
    PORTRAIT("portrait", "Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);

    fun next(): ScreenOrientationPref = when (this) {
        LANDSCAPE -> AUTO
        AUTO -> PORTRAIT
        PORTRAIT -> LANDSCAPE
    }

    companion object {
        fun fromId(id: String?): ScreenOrientationPref =
            entries.firstOrNull { it.id == id } ?: LANDSCAPE
    }
}

enum class ResolutionPreset(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val shortEdgeTarget: Int = 0,
) {
    NATIVE("native", "Native Display", 0, 0),
    BALANCED_900P("aspect_900p", "Balanced 900p", 0, 0, shortEdgeTarget = 900),
    PERFORMANCE_720P("aspect_720p", "Fast 720p", 0, 0, shortEdgeTarget = 720),
    FHD_PLUS("1920x1200", "1920 × 1200 (16:10)", 1920, 1200),
    FHD("1920x1080", "1920 × 1080 (1080p)", 1920, 1080),
    QHD("2560x1600", "2560 × 1600 (QHD+)", 2560, 1600),
    HD_PLUS("1600x900", "1600 × 900", 1600, 900);

    fun resolveForSurface(surfaceW: Int, surfaceH: Int): Pair<Int, Int> {
        val safeW = (surfaceW and 1.inv()).coerceAtLeast(640)
        val safeH = (surfaceH and 1.inv()).coerceAtLeast(480)
        if (this == NATIVE) return safeW to safeH
        if (shortEdgeTarget > 0) {
            val landscape = safeW >= safeH
            val shortDim = if (landscape) safeH else safeW
            val longDim = if (landscape) safeW else safeH
            if (shortDim <= shortEdgeTarget) return safeW to safeH
            val scaledShort = (shortEdgeTarget and 1.inv()).coerceAtLeast(480)
            val scaledLong = ((kotlin.math.round(longDim.toDouble() * scaledShort / shortDim.toDouble()).toInt()) and 1.inv())
                .coerceAtLeast(640)
            return if (landscape) scaledLong to scaledShort else scaledShort to scaledLong
        }
        return if (safeW >= safeH) {
            width to height
        } else {
            height to width
        }
    }

    companion object {
        fun fromId(id: String?): ResolutionPreset =
            entries.firstOrNull { it.id == id } ?: NATIVE
    }
}

data class CompositorGeometry(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val compositorWidth: Int,
    val compositorHeight: Int,
    val compositorScale: Float,
    val effectiveScale: Float,
)

data class SessionConfigSignature(
    val editionId: String,
    val resolutionPresetId: String,
    val displayScale: Float,
    val targetRefreshHz: Int,
    val cpuCoreLimit: Int,
    val cpuSelectedCores: String,
    val gpuModeId: String,
    val fastPath: Boolean,
    val noSeccomp: Boolean,
    val username: String,
    val micEnabled: Boolean,
    val gpuApps: Boolean,
    val gpuDesktop: Boolean,
    val customMountsHash: Int,
)

enum class BackgroundMode(val id: String, val label: String, val description: String) {
    KEEP_RUNNING("keep", "Keep Running", "Linux keeps running while the app is in the background"),
    PAUSE("pause", "Pause", "Freeze Linux (0% CPU) after the delay and resume when you return"),
    SHUT_DOWN("shutdown", "Shut Down", "Stop Linux after the delay");

    companion object {
        fun fromId(id: String?): BackgroundMode = entries.firstOrNull { it.id == id } ?: KEEP_RUNNING
    }
}

object SessionPrefs {
    private const val PREFS_NAME = "linuxdroid_prefs"
    const val GLIBC_TUNABLES = "glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun appTheme(context: Context): com.linuxdroid.app.ui.AppTheme =
        com.linuxdroid.app.ui.AppTheme.fromId(prefs(context).getString("app_theme", "dynamic"))

    fun setAppTheme(context: Context, theme: com.linuxdroid.app.ui.AppTheme) {
        prefs(context).edit().putString("app_theme", theme.id).apply()
    }

    fun backgroundMode(context: Context): BackgroundMode =
        BackgroundMode.fromId(prefs(context).getString("background_mode", "keep"))

    fun setBackgroundMode(context: Context, mode: BackgroundMode) {
        prefs(context).edit().putString("background_mode", mode.id).apply()
    }

    /** Seconds the app may stay in the background before the chosen background action runs. */
    fun backgroundDelaySec(context: Context): Int =
        prefs(context).getInt("background_delay_sec", 30).coerceIn(0, 3600)

    fun setBackgroundDelaySec(context: Context, sec: Int) {
        prefs(context).edit().putInt("background_delay_sec", sec.coerceIn(0, 3600)).apply()
    }

    fun autoBootOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean("auto_boot_on_launch", false)

    fun setAutoBootOnLaunch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_boot_on_launch", enabled).apply()
    }

    fun gpuMode(context: Context): GpuDriverMode =
        GpuDriverMode.fromId(prefs(context).getString("gpu_mode", GpuDriverMode.AUTO.id))

    fun setGpuMode(context: Context, mode: GpuDriverMode) {
        prefs(context).edit().putString("gpu_mode", mode.id).apply()
    }

    /** 0 means All Hardware CPU Cores (maximum performance); 1..totalCores pins to N cores. */
    fun cpuCoreLimit(context: Context): Int =
        prefs(context).getInt("cpu_core_limit", 0).coerceAtLeast(0)

    fun setCpuCoreLimit(context: Context, limit: Int) {
        val safeLimit = limit.coerceAtLeast(0)
        val editor = prefs(context).edit().putInt("cpu_core_limit", safeLimit)
        if (safeLimit == 0) {
            editor.putString("cpu_selected_cores", "")
        }
        editor.apply()
    }

    /**
     * Explicit comma-separated core list (e.g. "4,5,6,7" or "6,7"); empty string means all cores (or legacy limit).
     */
    fun selectedCpuCoresSpec(context: Context): String =
        prefs(context).getString("cpu_selected_cores", "")?.trim().orEmpty()

    fun setSelectedCpuCores(context: Context, coreIndices: Collection<Int>, totalCores: Int) {
        val sorted = coreIndices.filter { it >= 0 }.distinct().sorted()
        val isAll = sorted.isEmpty() || (totalCores > 0 && sorted.size >= totalCores)
        prefs(context).edit()
            .putString("cpu_selected_cores", if (isAll) "" else sorted.joinToString(","))
            .putInt("cpu_core_limit", if (isAll) 0 else sorted.size)
            .apply()
    }

    fun resolveSelectedCpuCores(context: Context, topology: com.linuxdroid.app.runtime.CpuTopology): List<Int> =
        topology.resolveSelectedCores(selectedCpuCoresSpec(context), cpuCoreLimit(context))

    fun effectiveSelectedCpuCoreSet(context: Context, topology: com.linuxdroid.app.runtime.CpuTopology): Set<Int> =
        topology.effectiveSelectedSet(selectedCpuCoresSpec(context), cpuCoreLimit(context))

    fun effectiveCpuCores(context: Context, totalCores: Int): Int {
        val safeTotal = totalCores.coerceAtLeast(1)
        val spec = selectedCpuCoresSpec(context)
        if (spec.isNotEmpty()) {
            val count = com.linuxdroid.app.runtime.CpuInfo.parseCpuListSpec(spec)
                .count { it in 0 until safeTotal }
            if (count in 1 until safeTotal) return count
        }
        val limit = cpuCoreLimit(context)
        return if (limit in 1 until safeTotal) limit else safeTotal
    }

    fun externalDisplayMode(context: Context): ExternalDisplayMode =
        ExternalDisplayMode.fromId(prefs(context).getString("external_display_mode", ExternalDisplayMode.AUTO_EXTERNAL.id))

    fun setExternalDisplayMode(context: Context, mode: ExternalDisplayMode) {
        prefs(context).edit().putString("external_display_mode", mode.id).apply()
    }

    fun installedEditionId(context: Context): String? =
        prefs(context).getString("installed_edition_id", null)?.takeIf { it.isNotBlank() }

    fun setInstalledEditionId(context: Context, id: String?) {
        val editor = prefs(context).edit()
        if (id.isNullOrBlank()) editor.remove("installed_edition_id") else editor.putString("installed_edition_id", id)
        editor.apply()
    }

    fun desktopEnvironment(context: Context): String =
        prefs(context).getString("desktop_env", "auto") ?: "auto"

    fun setDesktopEnvironment(context: Context, de: String) {
        prefs(context).edit().putString("desktop_env", de).apply()
    }

    fun username(context: Context): String =
        UserProvisioner.sanitizeUsername(prefs(context).getString("linux_username", "linux"))

    fun setUsername(context: Context, raw: String) {
        prefs(context).edit().putString("linux_username", UserProvisioner.sanitizeUsername(raw)).apply()
    }

    fun resolutionPreset(context: Context): ResolutionPreset =
        ResolutionPreset.fromId(prefs(context).getString("resolution_preset", ResolutionPreset.NATIVE.id))

    fun setResolutionPreset(context: Context, preset: ResolutionPreset) {
        prefs(context).edit().putString("resolution_preset", preset.id).apply()
    }

    /** 0f means Auto Smart HiDPI based on screen density & pixel dimensions. */
    fun displayScale(context: Context): Float =
        prefs(context).getFloat("display_scale", 0f)

    fun setDisplayScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat("display_scale", scale).apply()
    }

    /** 0 means match display's highest native refresh rate (60/90/120Hz). */
    fun targetRefreshHz(context: Context): Int =
        prefs(context).getInt("target_refresh_hz", 0)

    fun setTargetRefreshHz(context: Context, hz: Int) {
        prefs(context).edit().putInt("target_refresh_hz", hz).apply()
    }

    fun orientation(context: Context): ScreenOrientationPref =
        ScreenOrientationPref.fromId(prefs(context).getString("screen_orientation", ScreenOrientationPref.LANDSCAPE.id))

    fun setOrientation(context: Context, pref: ScreenOrientationPref) {
        prefs(context).edit().putString("screen_orientation", pref.id).apply()
    }

    fun touchInputMode(context: Context): TouchInputMode =
        TouchInputMode.fromId(prefs(context).getString("touch_input_mode", TouchInputMode.TOUCHSCREEN.id))

    fun setTouchInputMode(context: Context, mode: TouchInputMode) {
        prefs(context).edit().putString("touch_input_mode", mode.id).apply()
    }

    fun showFloatingControls(context: Context): Boolean =
        prefs(context).getBoolean("show_floating_controls", false)

    fun setShowFloatingControls(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("show_floating_controls", enabled).apply()
    }

    fun micEnabled(context: Context): Boolean =
        prefs(context).getBoolean("mic_enabled", false)

    fun setMicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("mic_enabled", enabled).apply()
    }

    /** GPU acceleration for apps that support it (browser, video, creative tools). The desktop shell stays software-composited. */
    fun gpuApps(context: Context): Boolean =
        prefs(context).getBoolean("gpu_apps", true)

    fun setGpuApps(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("gpu_apps", enabled).apply()
    }

    /** GPU compositing for the KDE desktop itself (KWin OpenGL + GPU Qt Quick). Falls back to software on failure. */
    fun gpuDesktop(context: Context): Boolean =
        prefs(context).getBoolean("gpu_desktop", false)

    fun setGpuDesktop(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("gpu_desktop", enabled).apply()
        // Re-enabling clears a previous automatic software fallback so the GPU desktop is tried again.
        if (enabled) {
            runCatching {
                File(LinuxRuntime.rootDir(context), "home/${username(context)}/.cache/linuxdroid/gpu-desktop-failed").delete()
            }
        }
    }

    fun prootFastPath(context: Context): Boolean =
        prefs(context).getBoolean("proot_fast_path", true)

    fun setProotFastPath(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("proot_fast_path", enabled).apply()
    }

    fun prootNoSeccomp(context: Context): Boolean =
        prefs(context).getBoolean("proot_no_seccomp", false)

    fun setProotNoSeccomp(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("proot_no_seccomp", enabled).apply()
    }

    fun customMounts(context: Context): Map<String, String> {
        val raw = prefs(context).getString("custom_mounts_json", "{}") ?: "{}"
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, String>()
            for (k in obj.keys()) {
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun addCustomMount(context: Context, name: String, hostPath: String) {
        val updated = LinkedHashMap(customMounts(context))
        updated[UserProvisioner.sanitizeMountName(name)] = hostPath
        saveCustomMounts(context, updated)
    }

    fun removeCustomMount(context: Context, name: String) {
        val updated = LinkedHashMap(customMounts(context))
        updated.remove(name)
        saveCustomMounts(context, updated)
    }

    private fun saveCustomMounts(context: Context, mounts: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in mounts) obj.put(k, v)
        prefs(context).edit().putString("custom_mounts_json", obj.toString()).apply()
    }

    fun computeEffectiveScale(context: Context, pixelWidth: Int, pixelHeight: Int): Float =
        computeEffectiveScaleForExplicit(displayScale(context), pixelWidth, pixelHeight)

    fun computeEffectiveScaleForExplicit(explicitScale: Float, pixelWidth: Int, pixelHeight: Int): Float {
        if (explicitScale >= 1f) return explicitScale.coerceIn(1.0f, 3.0f)
        val maxDim = maxOf(pixelWidth, pixelHeight)
        val minDim = minOf(pixelWidth, pixelHeight)
        return when {
            minDim >= 1400 || maxDim >= 2600 -> 1.75f
            minDim >= 1150 || maxDim >= 2200 -> 1.5f
            minDim >= 960 || maxDim >= 1850 -> 1.25f
            else -> 1.0f
        }
    }

    fun usesWaylandLogicalScale(context: Context): Boolean {
        val de = desktopEnvironment(context)
        if (de.equals("kde", ignoreCase = true)) return true
        if (!de.equals("auto", ignoreCase = true)) return false
        val editionId = LinuxRuntime.detectInstalledEditionId(context)
        if (editionId == "ubuntu-kde-arm64") return true
        if (editionId == "ubuntu-xfce-arm64") return false
        return File(LinuxRuntime.rootDir(context), "usr/bin/startplasma-wayland").exists()
    }

    fun computeCompositorGeometry(
        pixelWidth: Int,
        pixelHeight: Int,
        explicitScale: Float,
        usesWaylandLogicalScale: Boolean,
    ): CompositorGeometry {
        val safeW = (pixelWidth and 1.inv()).coerceAtLeast(640)
        val safeH = (pixelHeight and 1.inv()).coerceAtLeast(480)
        val scale = computeEffectiveScaleForExplicit(explicitScale, safeW, safeH)
        return if (usesWaylandLogicalScale && scale > 1.01f) {
            // Nested KWin 5.27 mishandles fractional output scales (wl_output scale is ceil'd while its
            // buffer is multiplied by the fractional value), which draws a second scaled desktop. Use a
            // whole-number Wayland scale and compensate the remainder with font DPI in the session.
            val wlScale = scale.roundToInt().coerceAtLeast(1).toFloat()
            val logicalW = ((safeW / wlScale).roundToInt() and 1.inv()).coerceAtLeast(480)
            val logicalH = ((safeH / wlScale).roundToInt() and 1.inv()).coerceAtLeast(320)
            CompositorGeometry(
                pixelWidth = safeW,
                pixelHeight = safeH,
                compositorWidth = logicalW,
                compositorHeight = logicalH,
                compositorScale = wlScale,
                effectiveScale = scale,
            )
        } else {
            CompositorGeometry(
                pixelWidth = safeW,
                pixelHeight = safeH,
                compositorWidth = safeW,
                compositorHeight = safeH,
                compositorScale = 1.0f,
                effectiveScale = scale,
            )
        }
    }

    fun computeCompositorGeometry(context: Context, pixelWidth: Int, pixelHeight: Int): CompositorGeometry =
        computeCompositorGeometry(
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            explicitScale = displayScale(context),
            usesWaylandLogicalScale = usesWaylandLogicalScale(context),
        )

    fun currentConfigSignature(context: Context): SessionConfigSignature = SessionConfigSignature(
        editionId = LinuxRuntime.detectInstalledEditionId(context).orEmpty(),
        resolutionPresetId = resolutionPreset(context).id,
        displayScale = displayScale(context),
        targetRefreshHz = targetRefreshHz(context),
        cpuCoreLimit = cpuCoreLimit(context),
        cpuSelectedCores = selectedCpuCoresSpec(context),
        gpuModeId = gpuMode(context).id,
        fastPath = prootFastPath(context),
        noSeccomp = prootNoSeccomp(context),
        username = username(context),
        micEnabled = micEnabled(context),
        gpuApps = gpuApps(context),
        gpuDesktop = gpuDesktop(context),
        customMountsHash = customMounts(context).hashCode(),
    )
}
