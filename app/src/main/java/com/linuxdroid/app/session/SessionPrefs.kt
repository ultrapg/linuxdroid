package com.linuxdroid.app.session

import android.content.Context
import android.content.pm.ActivityInfo
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.runtime.UserProvisioner
import org.json.JSONObject

enum class TouchInputMode(val id: String, val label: String) {
    TOUCHSCREEN("touchscreen", "Direct Touchscreen (Laptop Touch)"),
    TRACKPAD("trackpad", "Virtual Multi-Touch Trackpad");

    companion object {
        fun fromId(id: String?): TouchInputMode =
            entries.firstOrNull { it.id == id } ?: TOUCHSCREEN
    }
}

enum class ScreenOrientationPref(val id: String, val label: String, val androidOrientation: Int) {
    LANDSCAPE("landscape", "Landscape (Laptop Default)", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    AUTO("auto", "Auto-Rotate (Sensor)", ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
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
    NATIVE("native", "Native Screen / Window Resolution (Crisp)", 0, 0),
    BALANCED_900P("aspect_900p", "Auto Fullscreen 900p (Balanced Speed)", 0, 0, shortEdgeTarget = 900),
    PERFORMANCE_720P("aspect_720p", "Auto Fullscreen 720p (Max FPS)", 0, 0, shortEdgeTarget = 720),
    FHD_PLUS("1920x1200", "1920 × 1200 (16:10 Workstation)", 1920, 1200),
    FHD("1920x1080", "1920 × 1080 (1080p)", 1920, 1080),
    QHD("2560x1600", "2560 × 1600 (HiDPI Tablet)", 2560, 1600),
    HD_PLUS("1600x900", "1600 × 900 (Compact)", 1600, 900);

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

object SessionPrefs {
    private const val PREFS_NAME = "linuxdroid_prefs"
    const val GLIBC_TUNABLES = "glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun micEnabled(context: Context): Boolean =
        prefs(context).getBoolean("mic_enabled", false)

    fun setMicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("mic_enabled", enabled).apply()
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

    fun computeEffectiveScale(context: Context, pixelWidth: Int, pixelHeight: Int): Float {
        val explicit = displayScale(context)
        if (explicit >= 1f) return explicit
        val maxDim = maxOf(pixelWidth, pixelHeight)
        val minDim = minOf(pixelWidth, pixelHeight)
        return when {
            minDim >= 1400 || maxDim >= 2600 -> 1.75f
            minDim >= 1150 || maxDim >= 2200 -> 1.5f
            minDim >= 960 || maxDim >= 1850 -> 1.25f
            else -> 1.0f
        }
    }
}
