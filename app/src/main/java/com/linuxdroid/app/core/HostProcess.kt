package com.linuxdroid.app.core

import android.util.Log
import java.io.File
import java.util.function.Consumer

object HostProcess {
    private const val TAG = "HostProcess"

    @JvmStatic
    fun start(
        argv: List<String>,
        env: Map<String, String>?,
        dir: File?,
        onExit: Consumer<Int>? = null,
        onLine: Consumer<String>? = null,
        outputFile: File? = null,
    ): Int {
        if (argv.isEmpty()) return -1
        return try {
            val builder = ProcessBuilder(argv)
            if (dir != null) builder.directory(dir)
            if (env != null) {
                builder.environment().clear()
                builder.environment().putAll(env)
            }
            builder.redirectErrorStream(true)
            if (onLine == null) {
                if (outputFile == null) {
                    builder.redirectOutput(File("/dev/null"))
                } else {
                    outputFile.parentFile?.mkdirs()
                    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile))
                }
            }
            val process = builder.start()
            val pid = pidOf(process)
            Log.i(TAG, "started pid $pid: ${argv.first()} (${argv.size - 1} args)")
            if (onLine != null) {
                Thread({
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { onLine.accept(it) }
                        }
                    } catch (_: Exception) {
                    }
                }, "proc-out-$pid").apply {
                    priority = Thread.NORM_PRIORITY - 1
                    isDaemon = true
                    start()
                }
            }
            if (onExit != null) {
                Thread({
                    var status = -1
                    try {
                        status = process.waitFor()
                    } catch (_: InterruptedException) {
                    }
                    onExit.accept(status)
                }, "proc-wait-$pid").apply {
                    priority = Thread.NORM_PRIORITY - 1
                    isDaemon = true
                    start()
                }
            }
            pid
        } catch (e: Exception) {
            Log.e(TAG, "could not start ${argv.firstOrNull()}", e)
            -1
        }
    }

    @JvmStatic
    fun pidOf(process: Process): Int = try {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        val pid = field.getInt(process)
        field.isAccessible = false
        pid
    } catch (_: Exception) {
        -1
    }
}
