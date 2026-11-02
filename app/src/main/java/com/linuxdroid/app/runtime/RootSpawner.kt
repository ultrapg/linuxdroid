package com.linuxdroid.app.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.linuxdroid.app.core.HostProcess
import com.linuxdroid.app.session.SessionPrefs
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

object RootSpawner {
    private const val TAG = "RootSpawner"
    private val seq = AtomicInteger()

    @Volatile
    private var server: LocalServerSocket? = null

    fun socketName(context: Context): String = "${context.packageName}.root"

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val name = socketName(app)
        val sock = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot listen on @$name", e)
            return
        }
        server = sock
        Thread({
            while (true) {
                val client = try {
                    sock.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "accept stopped", e)
                    break
                }
                Thread({ serve(app, client) }, "root-${seq.incrementAndGet()}").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "root-spawner").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(context: Context, client: LocalSocket) {
        val received = ArrayList<FileDescriptor>()
        client.use {
            val out = DataOutputStream(client.outputStream)
            try {
                if (client.peerCredentials.uid != Process.myUid()) {
                    Log.w(TAG, "Rejected root request from foreign uid ${client.peerCredentials.uid}")
                    return
                }
                val input = DataInputStream(client.inputStream)
                val kind = input.readUnsignedByte()
                client.ancillaryFileDescriptors?.let { received.addAll(it) }
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                client.ancillaryFileDescriptors?.let { received.addAll(it) }
                if (kind != 'R'.code) return
                runRootProcess(context, JSONObject(String(body, Charsets.UTF_8)), received, input, out)
            } catch (e: Exception) {
                runCatching {
                    frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                    frame(out, 'X', "1".toByteArray())
                }
            } finally {
                received.forEach { fd -> runCatching { Os.close(fd) } }
            }
        }
    }

    private class Stdio(
        val redirect: ProcessBuilder.Redirect?,
        val fd: FileDescriptor?,
        val dup: ParcelFileDescriptor?,
    )

    private fun wrapStdio(fd: FileDescriptor?, write: Boolean): Stdio {
        if (fd == null) return Stdio(null, null, null)
        val dup = try {
            ParcelFileDescriptor.dup(fd)
        } catch (_: IOException) {
            return Stdio(null, fd, null)
        }
        val procPath = File("/proc/self/fd/${dup.fd}")
        val canOpen = try {
            if (write) FileOutputStream(procPath, true).close() else FileInputStream(procPath).close()
            true
        } catch (_: IOException) {
            false
        }
        if (!canOpen) return Stdio(null, fd, dup)
        val redirect = if (write) ProcessBuilder.Redirect.appendTo(procPath) else ProcessBuilder.Redirect.from(procPath)
        return Stdio(redirect, fd, dup)
    }

    private fun pump(from: InputStream, to: OutputStream, name: String): Thread =
        Thread({
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: IOException) {
            } finally {
                runCatching { to.close() }
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    private fun runRootProcess(
        context: Context,
        request: JSONObject,
        fds: List<FileDescriptor>,
        input: DataInputStream,
        out: DataOutputStream,
    ) {
        val root = LinuxRuntime.rootDir(context)
        val argvJson = request.getJSONArray("argv")
        val argv = (0 until argvJson.length()).map { argvJson.getString(it) }
        if (argv.isEmpty()) throw IOException("Empty argv")
        val cwd = request.optString("cwd", "/root").ifEmpty { "/root" }

        val cmd = LinuxRuntime.prootPrefix(context, root, cwd, fakeRoot = true)
        cmd.add(1, "--link2symlink")
        for (spec in LinuxRuntime.lastBinds(context)) {
            if (!spec.startsWith("/proc/self/fd/")) {
                cmd.add("-b")
                cmd.add(spec)
            }
        }
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        request.optJSONObject("env")?.let { envObj ->
            for (k in envObj.keys()) {
                cmd.add("$k=${envObj.getString(k)}")
            }
        }
        cmd.addAll(argv)

        val builder = ProcessBuilder(cmd).directory(root)
        builder.environment().apply {
            clear()
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).absolutePath)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).absolutePath)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
        }

        val streams = listOf(
            wrapStdio(fds.getOrNull(0), false),
            wrapStdio(fds.getOrNull(1), true),
            wrapStdio(fds.getOrNull(2), true),
        )
        try {
            builder.redirectInput(streams[0].redirect ?: ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(streams[1].redirect ?: ProcessBuilder.Redirect.PIPE)
            builder.redirectError(streams[2].redirect ?: ProcessBuilder.Redirect.PIPE)
            val proc = builder.start()
            val pid = HostProcess.pidOf(proc)
            synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) }

            val pumps = ArrayList<Thread>()
            if (streams[0].redirect == null) {
                val fd = streams[0].fd
                if (fd != null) pumps.add(pump(FileInputStream(fd), proc.outputStream, "root-in-$pid"))
                else runCatching { proc.outputStream.close() }
            }
            if (streams[1].redirect == null) {
                pumps.add(pump(proc.inputStream, streams[1].fd?.let { FileOutputStream(it) } ?: OutputStream.nullOutputStream(), "root-out-$pid"))
            }
            if (streams[2].redirect == null) {
                pumps.add(pump(proc.errorStream, streams[2].fd?.let { FileOutputStream(it) } ?: OutputStream.nullOutputStream(), "root-err-$pid"))
            }

            Thread({
                try {
                    while (true) {
                        val kind = input.readUnsignedByte()
                        val body = ByteArray(input.readInt()).also { input.readFully(it) }
                        if (kind == 'S'.code) {
                            String(body).trim().toIntOrNull()?.let { sig -> signalTree(pid, sig) }
                        }
                    }
                } catch (_: IOException) {
                }
                if (proc.isAlive) {
                    signalTree(pid, OsConstants.SIGHUP)
                    runCatching { Process.sendSignal(pid, OsConstants.SIGQUIT) }
                }
            }, "root-watch-$pid").apply { isDaemon = true; start() }

            val exitCode = proc.waitFor()
            pumps.filter { it.name.startsWith("root-out") || it.name.startsWith("root-err") }
                .forEach { it.join(1500) }
            runCatching { synchronized(out) { frame(out, 'X', exitCode.toString().toByteArray()) } }
        } finally {
            streams.forEach { s -> runCatching { s.dup?.close() } }
        }
    }

    private fun signalTree(topPid: Int, sig: Int) {
        val parents = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val p = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()?.let { parents[p] = it }
        }
        val tree = HashSet<Int>()
        var grew = true
        while (grew) {
            grew = false
            for ((p, parent) in parents) {
                if (p !in tree && (parent == topPid || parent in tree)) {
                    tree.add(p)
                    grew = true
                }
            }
        }
        tree.forEach { runCatching { Process.sendSignal(it, sig) } }
    }

    private fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }
}
