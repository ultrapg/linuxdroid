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
import com.linuxdroid.app.session.SessionState
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

import android.net.LocalSocketAddress

object RootSpawner {
    private const val TAG = "RootSpawner"
    const val FS_SOCKET_FILE_NAME = "root.sock"
    private val seq = AtomicInteger()

    @Volatile
    private var server: LocalServerSocket? = null

    @Volatile
    private var legacyAtServer: LocalServerSocket? = null

    @Volatile
    private var fsServerBoundSocket: LocalSocket? = null

    @Volatile
    private var fsServer: LocalServerSocket? = null

    fun socketName(context: Context): String = "${context.packageName}.root"

    fun fsSocketFile(context: Context): File = File(LinuxRuntime.runtimeDir(context), FS_SOCKET_FILE_NAME)

    fun normalizeSocketName(raw: String?, defaultName: String = "com.linuxdroid.app.root"): String {
        val cleaned = raw?.trim()?.trimStart('@', '\u0000')?.trim().orEmpty()
        return cleaned.ifEmpty { defaultName }
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        val name = socketName(app)
        if (server == null) {
            server = bindAndAccept(app, name)
        }
        if (legacyAtServer == null) {
            legacyAtServer = bindAndAccept(app, "@$name")
        }
        val sockFile = fsSocketFile(app)
        if (fsServer == null || !sockFile.exists()) {
            runCatching { fsServer?.close() }
            runCatching { fsServerBoundSocket?.close() }
            fsServer = null
            fsServerBoundSocket = null
            bindFilesystemSocket(app, sockFile)
        }
    }

    private fun bindFilesystemSocket(app: Context, sockFile: File) {
        try {
            sockFile.parentFile?.mkdirs()
            sockFile.delete()
            val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
            bound.bind(LocalSocketAddress(sockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val serverSock = LocalServerSocket(bound.fileDescriptor)
            fsServerBoundSocket = bound
            fsServer = serverSock
            startAcceptThread(app, serverSock, sockFile.absolutePath)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot listen on filesystem socket ${sockFile.absolutePath}", e)
        }
    }

    private fun bindAndAccept(app: Context, listenName: String): LocalServerSocket? {
        val sock = try {
            LocalServerSocket(listenName)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot listen on abstract socket $listenName", e)
            return null
        }
        startAcceptThread(app, sock, listenName)
        return sock
    }

    private fun startAcceptThread(app: Context, sock: LocalServerSocket, listenName: String) {
        Thread({
            var consecutiveErrors = 0
            while (true) {
                val client = try {
                    sock.accept().also { consecutiveErrors = 0 }
                } catch (e: IOException) {
                    consecutiveErrors++
                    if (consecutiveErrors > 10) {
                        Log.e(TAG, "accept loop on $listenName stopping after repeated errors", e)
                        synchronized(this@RootSpawner) {
                            if (server === sock) server = null
                            if (legacyAtServer === sock) legacyAtServer = null
                            if (fsServer === sock) {
                                fsServer = null
                                runCatching { fsServerBoundSocket?.close() }
                                fsServerBoundSocket = null
                            }
                        }
                        runCatching { sock.close() }
                        break
                    }
                    try {
                        Thread.sleep(50L * consecutiveErrors)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                Thread({ serve(app, client) }, "root-${seq.incrementAndGet()}").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "root-spawner-$listenName").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(context: Context, client: LocalSocket) {
        val received = ArrayList<FileDescriptor>()
        val closedFds = Collections.synchronizedSet(HashSet<FileDescriptor>())
        fun closeOnce(fd: FileDescriptor?) {
            if (fd != null && fd.valid() && closedFds.add(fd)) {
                runCatching { Os.close(fd) }
            }
        }
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
                runRootProcess(
                    context = context,
                    request = JSONObject(String(body, Charsets.UTF_8)),
                    fds = received,
                    closeFdOnce = ::closeOnce,
                    markFdOwnedByStream = { fd -> if (fd != null) closedFds.add(fd) },
                    input = input,
                    out = out,
                )
            } catch (e: Exception) {
                runCatching {
                    synchronized(out) {
                        frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                        frame(out, 'X', "1".toByteArray())
                    }
                }
            } finally {
                received.forEach { fd -> closeOnce(fd) }
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
        if (!canOpen) {
            runCatching { dup.close() }
            return Stdio(null, fd, null)
        }
        val redirect = if (write) ProcessBuilder.Redirect.appendTo(procPath) else ProcessBuilder.Redirect.from(procPath)
        return Stdio(redirect, fd, dup)
    }

    private fun detectControllingPts(request: JSONObject, streams: List<Stdio>): String? {
        for (s in streams) {
            val dupFd = s.dup?.fd ?: continue
            val target = runCatching { Os.readlink("/proc/self/fd/$dupFd") }.getOrNull() ?: continue
            if (target.startsWith("/dev/pts/") && File(target).exists()) {
                return target
            }
        }
        val reqTty = request.optString("tty", "").trim()
        if (reqTty.startsWith("/dev/pts/") && File(reqTty).exists()) {
            return reqTty
        }
        return null
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
                runCatching { from.close() }
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    private fun runRootProcess(
        context: Context,
        request: JSONObject,
        fds: List<FileDescriptor>,
        closeFdOnce: (FileDescriptor?) -> Unit,
        markFdOwnedByStream: (FileDescriptor?) -> Unit,
        input: DataInputStream,
        out: DataOutputStream,
    ) {
        LinuxRuntime.ensureBundledOverlayCurrent(context)
        val root = LinuxRuntime.rootDir(context)
        val argvJson = request.getJSONArray("argv")
        val argv = (0 until argvJson.length()).map { argvJson.getString(it) }
        if (argv.isEmpty()) throw IOException("Empty argv")
        val requestedCwd = request.optString("cwd", "/root").ifEmpty { "/root" }
        val cwd = if (File(root, requestedCwd.removePrefix("/")).isDirectory) requestedCwd else "/"

        val streams = listOf(
            wrapStdio(fds.getOrNull(0), false),
            wrapStdio(fds.getOrNull(1), true),
            wrapStdio(fds.getOrNull(2), true),
        )

        try {
            val cmd = LinuxRuntime.prootPrefix(context, root, cwd, fakeRoot = true)
            for (spec in LinuxRuntime.lastBinds(context)) {
                if (!spec.startsWith("/proc/self/fd/")) {
                    cmd.add("-b")
                    cmd.add(spec)
                }
            }
            val ptsPath = detectControllingPts(request, streams)
            if (ptsPath != null) {
                cmd.add("-b")
                cmd.add("$ptsPath:/dev/tty")
            }
            cmd.add("/usr/bin/env")
            cmd.add("-i")
            request.optJSONObject("env")?.let { envObj ->
                for (k in envObj.keys()) {
                    cmd.add("$k=${envObj.getString(k)}")
                }
            }
            cmd.addAll(argv)

            val useNoSeccomp = SessionPrefs.prootNoSeccomp(context) ||
                SessionState.state.value.compatibilityRetryUsed
            val builder = ProcessBuilder(cmd).directory(root)
            builder.environment().apply {
                clear()
                put("PROOT_LOADER", LinuxRuntime.prootLoader(context).absolutePath)
                put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).absolutePath)
                if (useNoSeccomp) put("PROOT_NO_SECCOMP", "1")
            }

            builder.redirectInput(streams[0].redirect ?: ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(streams[1].redirect ?: ProcessBuilder.Redirect.PIPE)
            builder.redirectError(streams[2].redirect ?: ProcessBuilder.Redirect.PIPE)
            val proc = builder.start()

            // Close parent-side duplicate descriptors now that child has inherited them
            for (s in streams) {
                runCatching { s.dup?.close() }
                if (s.redirect != null) {
                    closeFdOnce(s.fd)
                }
            }

            val pid = HostProcess.pidOf(proc)
            synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) }

            val pumps = ArrayList<Thread>()
            var fallbackInStream: FileInputStream? = null
            if (streams[0].redirect == null) {
                val fd = streams[0].fd
                if (fd != null) {
                    markFdOwnedByStream(fd)
                    val fis = FileInputStream(fd)
                    fallbackInStream = fis
                    pumps.add(pump(fis, proc.outputStream, "root-in-$pid"))
                } else {
                    runCatching { proc.outputStream.close() }
                }
            }
            if (streams[1].redirect == null) {
                val fd = streams[1].fd
                val outStream = if (fd != null) {
                    markFdOwnedByStream(fd)
                    FileOutputStream(fd)
                } else {
                    OutputStream.nullOutputStream()
                }
                pumps.add(pump(proc.inputStream, outStream, "root-out-$pid"))
            }
            if (streams[2].redirect == null) {
                val fd = streams[2].fd
                val errStream = if (fd != null) {
                    markFdOwnedByStream(fd)
                    FileOutputStream(fd)
                } else {
                    OutputStream.nullOutputStream()
                }
                pumps.add(pump(proc.errorStream, errStream, "root-err-$pid"))
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
            runCatching { proc.outputStream.close() }
            runCatching { fallbackInStream?.close() }
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
        if (tree.isEmpty() && topPid > 1) {
            runCatching { Process.sendSignal(topPid, sig) }
        } else {
            tree.forEach { runCatching { Process.sendSignal(it, sig) } }
        }
    }

    private fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }
}
