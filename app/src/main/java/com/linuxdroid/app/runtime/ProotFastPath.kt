package com.linuxdroid.app.runtime

import android.os.Build
import java.io.File
import java.security.MessageDigest

object ProotFastPath {
    const val LIBRARY = "/usr/local/lib/libblfastpath.so"
    private const val MAX_BINDS = 256

    fun key(root: File, binds: List<String>): String? {
        if (binds.size > MAX_BINDS || binds.any { it.contains('|') } || root.path.contains('|')) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((root.path + "\n" + binds.joinToString("\n")).toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun hostEnv(key: String): Pair<String, String> = "PROOT_FASTPATH" to key

    fun guestEnv(root: File, binds: List<String>, key: String, sdk: Int = Build.VERSION.SDK_INT): Map<String, String> =
        mapOf(
            "PROOT_FP_ROOT" to root.path,
            "PROOT_FP_BINDS" to binds.joinToString("|"),
            "PROOT_FP_KEY" to key,
            "PROOT_FP_ANDROID_API" to sdk.toString(),
        )
}
