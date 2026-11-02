package com.linuxdroid.app.session

object ThreadSignals {
    init {
        runCatching { System.loadLibrary("sdnative") }
    }

    @JvmStatic
    external fun tgkill(tgid: Int, tid: Int, signal: Int): Int
}
