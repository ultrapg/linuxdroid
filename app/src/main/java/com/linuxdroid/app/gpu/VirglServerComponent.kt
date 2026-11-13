package com.linuxdroid.app.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.core.HostProcess
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

class VirglServerComponent(
    private val context: Context,
    private val socketFile: File,
    private val enableVenus: Boolean,
    private val logFile: File? = null,
) {
    @Volatile
    var pid: Int = -1
        private set

    @Volatile
    private var stopped: Boolean = true

    @Volatile
    private var restartCount: Int = 0

    fun start() {
        stop()
        stopped = false
        restartCount = 0
        launchServer(allowVenus = enableVenus)
    }

    private fun launchServer(allowVenus: Boolean) {
        if (stopped) return
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val serverBin = File(nativeDir, "libvirgl_test_server.so")
        val renderServerBin = File(nativeDir, "libvirgl_render_server.so")
        if (!serverBin.isFile) {
            Log.e(TAG, "libvirgl_test_server.so missing in ${nativeDir.path}")
            return
        }

        socketFile.parentFile?.mkdirs()
        socketFile.delete()

        val useVenus = allowVenus && renderServerBin.isFile
        val argv = buildList {
            add(serverBin.absolutePath)
            add("--multi-clients")
            add("--use-egl-surfaceless")
            add("--use-gles")
            if (useVenus) {
                add("--venus")
            }
            add("--socket-path")
            add(socketFile.absolutePath)
        }

        val env = LinkedHashMap<String, String>().apply {
            put("LD_LIBRARY_PATH", "${nativeDir.absolutePath}:/system/lib64")
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            if (renderServerBin.isFile) {
                put("RENDER_SERVER_EXEC_PATH", renderServerBin.absolutePath)
            }
        }

        val startedAt = System.currentTimeMillis()
        val writer = logFile?.let { runCatching { PrintWriter(FileWriter(it, true)) }.getOrNull() }
        pid = HostProcess.start(
            argv = argv,
            env = env,
            dir = context.filesDir,
            onExit = { code ->
                Log.i(TAG, "virgl_test_server exited ($code)")
                writer?.let { runCatching { synchronized(it) { it.close() } } }
                if (!stopped && restartCount < MAX_RESTARTS) {
                    val ranMs = System.currentTimeMillis() - startedAt
                    restartCount += 1
                    val nextVenus = if (useVenus && ranMs < 5_000L) false else useVenus
                    Log.w(TAG, "Restarting virgl_test_server (#$restartCount, venus=$nextVenus)")
                    Thread {
                        try {
                            Thread.sleep(250L)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        if (!stopped) {
                            launchServer(allowVenus = nextVenus)
                        }
                    }.start()
                }
            },
            onLine = { line ->
                Log.i(TAG, line)
                writer?.let { synchronized(it) { it.println(line); it.flush() } }
            },
        )

        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline && pid > 1 && !stopped) {
            if (socketFile.exists()) {
                FileUtils.chmod(socketFile, 0b111_111_111)
                break
            }
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.i(TAG, "Started SoC GPU server (venus=$useVenus, pid=$pid, socket=${socketFile.path})")
    }

    fun stop() {
        stopped = true
        val current = pid
        if (current > 1) {
            runCatching { Process.killProcess(current) }
            pid = -1
        }
        socketFile.delete()
    }

    companion object {
        private const val TAG = "VirglServer"
        private const val MAX_RESTARTS = 5
    }
}
