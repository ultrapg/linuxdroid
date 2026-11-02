package com.linuxdroid.app.gpu

object VulkanInfo {
    init {
        runCatching { System.loadLibrary("deviceinfo") }
    }

    @JvmStatic
    external fun nativeQuery(): String

    fun querySafe(): Map<String, String> {
        val raw = runCatching { nativeQuery() }.getOrDefault("")
        return parse(raw)
    }

    fun parse(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
        return out
    }
}
