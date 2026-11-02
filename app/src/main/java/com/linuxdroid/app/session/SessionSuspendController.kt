package com.linuxdroid.app.session

import android.os.Process
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Freezes (SIGSTOP) and resumes (SIGCONT) the entire Linux guest process tree so the user can
 * pause the Linux desktop at zero CPU/battery drain and instantly unfreeze without losing open windows.
 */
class SessionSuspendController(
    private val sessionRootPid: () -> Int,
    private val helperRootPids: () -> List<Int>,
    private val suspendAudio: () -> Unit,
    private val resumeAudio: () -> Unit,
    private val clockControlFile: File? = null,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private data class ProcessRecord(val pid: Int, val started: Long, val isRoot: Boolean)
    private data class ProcStat(val pid: Int, val parent: Int, val started: Long, val state: Char, val tracer: Int)

    private var frozen = emptyList<ProcessRecord>()
    private var frozenSinceNs: Long? = null

    fun freeze(onComplete: (Boolean) -> Unit) {
        executor.execute {
            val rootPid = sessionRootPid()
            val rootStat = readProc(rootPid)
            if (rootPid <= 1 || rootStat == null) {
                onComplete(false)
                return@execute
            }
            runCatching { suspendAudio() }
            val helpers = helperRootPids().filter { it > 1 && it != rootPid }.distinct()
            val targets = collectTree(rootPid, helpers)
            try {
                for (rec in targets.filter { it.isRoot }) {
                    runCatching { Process.sendSignal(rec.pid, SIGSTOP) }
                }
                for (rec in targets.filter { !it.isRoot }) {
                    stopAllThreads(rec)
                }
                Thread.sleep(60L)
                val secondPass = collectTree(rootPid, helpers)
                for (rec in secondPass.filter { !it.isRoot }) {
                    stopAllThreads(rec)
                }
                frozen = secondPass
                if (frozenSinceNs == null) frozenSinceNs = System.nanoTime()
                onComplete(true)
            } catch (_: Exception) {
                restoreInternal(targets)
                runCatching { resumeAudio() }
                onComplete(false)
            }
        }
    }

    fun resume(onComplete: (Boolean) -> Unit) {
        executor.execute {
            advanceClockOffset()
            runCatching { resumeAudio() }
            val ok = restoreInternal(frozen)
            if (ok) frozen = emptyList()
            onComplete(ok)
        }
    }

    fun shutdownAndResume(onDone: () -> Unit) {
        executor.execute {
            advanceClockOffset()
            restoreInternal(frozen)
            frozen = emptyList()
            onDone()
            executor.shutdown()
        }
    }

    private fun advanceClockOffset() {
        val since = frozenSinceNs ?: return
        frozenSinceNs = null
        val ctrl = clockControlFile?.takeIf { it.isFile && it.length() >= Long.SIZE_BYTES } ?: return
        runCatching {
            RandomAccessFile(ctrl, "rw").use { raf ->
                val bytes = ByteArray(Long.SIZE_BYTES)
                raf.readFully(bytes)
                val current = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long.coerceAtLeast(0L)
                val delta = (System.nanoTime() - since).coerceAtLeast(0L)
                raf.seek(0L)
                raf.write(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(current + delta).array())
            }
        }
    }

    private fun restoreInternal(records: List<ProcessRecord>): Boolean {
        for (rec in records.sortedByDescending { it.isRoot }) {
            val cur = readProc(rec.pid)
            if (cur != null && cur.started == rec.started) {
                runCatching { Process.sendSignal(rec.pid, SIGCONT) }
            }
        }
        return true
    }

    private fun stopAllThreads(record: ProcessRecord) {
        val cur = readProc(record.pid) ?: return
        if (cur.started != record.started) return
        File("/proc/${record.pid}/task").listFiles()?.forEach { taskDir ->
            val tid = taskDir.name.toIntOrNull() ?: return@forEach
            runCatching { ThreadSignals.tgkill(record.pid, tid, SIGSTOP) }
        }
    }

    private fun collectTree(rootPid: Int, helpers: List<Int>): List<ProcessRecord> {
        val all = File("/proc").listFiles()?.mapNotNull { entry ->
            entry.name.toIntOrNull()?.let { readProc(it) }
        }.orEmpty()
        val byPid = all.associateBy { it.pid }
        val children = HashMap<Int, MutableList<ProcStat>>()
        for (p in all) children.getOrPut(p.parent) { ArrayList() }.add(p)

        val myPid = Process.myPid()
        val result = LinkedHashMap<Int, ProcessRecord>()

        fun walk(seed: Int) {
            val seedStat = byPid[seed] ?: return
            result[seed] = ProcessRecord(seed, seedStat.started, isRoot = true)
            val queue = ArrayDeque<Int>().apply { add(seed) }
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (child in children[cur].orEmpty()) {
                    if (child.pid <= 1 || child.pid == myPid || child.pid in result) continue
                    result[child.pid] = ProcessRecord(child.pid, child.started, isRoot = false)
                    queue.add(child.pid)
                }
            }
        }

        walk(rootPid)
        for (p in all) {
            if (p.tracer == rootPid && p.pid != myPid && p.pid !in result) {
                result[p.pid] = ProcessRecord(p.pid, p.started, isRoot = false)
            }
        }
        for (h in helpers) walk(h)
        return result.values.toList()
    }

    private fun readProc(pid: Int): ProcStat? = try {
        val statText = File("/proc/$pid/stat").readText()
        val close = statText.lastIndexOf(')')
        if (close < 0 || close + 2 >= statText.length) null
        else {
            val fields = statText.substring(close + 2).trim().split(Regex("\\s+"))
            if (fields.size < 20) null
            else {
                val tracer = runCatching {
                    File("/proc/$pid/status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    }
                }.getOrDefault(0)
                ProcStat(pid, fields[1].toInt(), fields[19].toLong(), fields[0].first(), tracer)
            }
        }
    } catch (_: IOException) {
        null
    }

    companion object {
        private const val SIGSTOP = 19
        private const val SIGCONT = 18
    }
}
