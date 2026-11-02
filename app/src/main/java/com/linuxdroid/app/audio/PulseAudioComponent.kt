package com.linuxdroid.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.system.Os
import android.util.Log
import com.linuxdroid.app.core.ArchiveExtractor
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.core.HostProcess
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class PulseAudioComponent(
    private val context: Context,
    private val enableMic: Boolean,
    private val logFile: File? = null,
) {
    private val workingDir = File(context.filesDir, "pulseaudio")
    private val micFifoFile = File(workingDir, "mic.fifo")

    @Volatile
    var pid: Int = -1
        private set

    @Volatile
    private var micActive = false
    private var micThread: Thread? = null

    fun socketFile(): File = File(workingDir, SOCKET_NAME)

    fun start() {
        stop()
        if (!workingDir.isDirectory) {
            workingDir.mkdirs()
            FileUtils.chmod(workingDir, 0b111_111_001)
        }

        val modulesDir = File(workingDir, "modules")
        val stampFile = File(modulesDir, ".bundle")
        val wantStamp = "$BUNDLE_STAMP-${bundleChecksum()}"
        if (!File(modulesDir, "arm64/module-aaudio-sink.so").isFile ||
            !File(workingDir, "pactl").isFile ||
            FileUtils.readString(stampFile)?.trim() != wantStamp
        ) {
            FileUtils.deleteRecursively(modulesDir)
            if (ArchiveExtractor.extractAsset(context, "pulseaudio.tzst", workingDir)) {
                FileUtils.writeString(stampFile, wantStamp)
            } else {
                Log.e(TAG, "Failed extracting pulseaudio.tzst")
            }
        }

        val pactl = File(workingDir, "pactl")
        if (pactl.isFile) FileUtils.chmod(pactl, 0b111_101_101)

        socketFile().delete()
        micFifoFile.delete()

        val canRecord = enableMic &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (canRecord) {
            runCatching { Os.mkfifo(micFifoFile.absolutePath, 0b110_110_000) }
        }

        val paLines = buildList {
            add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\"${socketFile().absolutePath}\"")
            add(".nofail")
            add("load-module module-aaudio-sink sink_name=AAudioSink performance_mode=1 adaptive=1 volume=1.0")
            add("set-default-sink AAudioSink")
            if (canRecord && micFifoFile.exists()) {
                add("load-module module-pipe-source source_name=$MIC_SOURCE file=\"${micFifoFile.absolutePath}\" format=s16le rate=48000 channels=1")
                add("set-default-source $MIC_SOURCE")
            }
            add(".fail")
        }
        FileUtils.writeString(File(workingDir, "default.pa"), paLines.joinToString("\n") + "\n")

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val pulseBin = File(nativeLibDir, "libpulseaudio.so")
        val modulesArm64 = File(workingDir, "modules/arm64")
        val ldPath = "/system/lib64:${modulesArm64.absolutePath}:${nativeLibDir.absolutePath}:${workingDir.absolutePath}"
        val env = mapOf(
            "LD_LIBRARY_PATH" to ldPath,
            "HOME" to workingDir.absolutePath,
            "TMPDIR" to workingDir.absolutePath,
        )
        val argv = listOf(
            pulseBin.absolutePath,
            "--system=false",
            "--disable-shm=true",
            "--fail=false",
            "-n",
            "--file=default.pa",
            "--daemonize=false",
            "--use-pid-file=false",
            "--exit-idle-time=-1",
            "--log-level=info",
        )
        val writer = logFile?.let { runCatching { PrintWriter(FileWriter(it, true)) }.getOrNull() }
        pid = HostProcess.start(
            argv = argv,
            env = env,
            dir = workingDir,
            onLine = { line ->
                Log.i(TAG, line)
                writer?.let { synchronized(it) { it.println(line); it.flush() } }
            },
        )

        if (canRecord && micFifoFile.exists()) {
            startMicBridge()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicBridge() {
        micActive = true
        micThread = Thread({
            val sampleRate = 48_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@Thread
                FileOutputStream(micFifoFile).use { out ->
                    recorder.startRecording()
                    val buf = ByteArray(1920)
                    while (micActive && pid > 1) {
                        val read = recorder.read(buf, 0, buf.size)
                        if (read > 0) {
                            out.write(buf, 0, read)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }, "pa-mic-bridge").apply {
            isDaemon = true
            start()
        }
    }

    fun setSuspended(suspended: Boolean) {
        pactl("suspend-sink", "@DEFAULT_SINK@", suspended.toString())
        if (enableMic) {
            pactl("suspend-source", MIC_SOURCE, suspended.toString())
        }
    }

    private fun pactl(vararg args: String): Boolean {
        val pactlBin = File(workingDir, "pactl")
        if (!pactlBin.isFile || pid <= 1) return false
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val modulesArm64 = File(workingDir, "modules/arm64")
        var proc: java.lang.Process? = null
        return try {
            val pb = ProcessBuilder(listOf(pactlBin.absolutePath) + args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
            pb.environment().apply {
                put("LD_LIBRARY_PATH", "/system/lib64:${modulesArm64.absolutePath}:${nativeLibDir.absolutePath}:${workingDir.absolutePath}")
                put("HOME", workingDir.absolutePath)
                put("TMPDIR", workingDir.absolutePath)
                put("PULSE_SERVER", "unix:${socketFile().absolutePath}")
            }
            proc = pb.start()
            proc.waitFor(4, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        } finally {
            if (proc != null && proc.isAlive) proc.destroyForcibly()
        }
    }

    fun stop() {
        micActive = false
        micThread?.interrupt()
        micThread = null
        val current = pid
        if (current > 1) {
            runCatching { Process.killProcess(current) }
            pid = -1
        }
        micFifoFile.delete()
    }

    private fun bundleChecksum(): String {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        return try {
            context.assets.open("pulseaudio.tzst").use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    crc.update(buf, 0, n)
                }
            }
            java.lang.Long.toHexString(crc.value)
        } catch (_: Exception) {
            "0"
        }
    }

    companion object {
        private const val TAG = "PulseAudio"
        const val SOCKET_NAME = "PS0"
        private const val MIC_SOURCE = "LinuxDroidMic"
        private const val BUNDLE_STAMP = "linuxdroid-aaudio-v1"
    }
}
