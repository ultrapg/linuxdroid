package com.linuxdroid.app.runtime

import java.io.File
import java.util.Locale

data class CoreDescriptor(
    val index: Int,
    val maxFreqKhz: Long,
)

data class CpuTopology(
    val totalCores: Int,
    val cores: List<CoreDescriptor>,
    val summary: String,
) {
    /**
     * Returns the preferred hardware CPU core indices when limiting the session to [requestedCount] cores.
     * Returns an empty list when all cores are enabled (`requestedCount <= 0` or `>= totalCores`).
     * Prefers higher-frequency performance/prime cores (and higher core indices on ARM64 DynamIQ/big.LITTLE).
     */
    fun selectPreferredCores(
        requestedCount: Int,
        onlineIndices: Set<Int> = CpuInfo.readOnlineCoreSet(),
    ): List<Int> {
        if (requestedCount <= 0 || requestedCount >= totalCores || cores.isEmpty()) {
            return emptyList()
        }
        val onlinePool = cores.filter { it.index in onlineIndices }
        val pool = if (onlinePool.size >= requestedCount) onlinePool else cores
        return pool
            .sortedWith(
                compareByDescending<CoreDescriptor> { it.maxFreqKhz }
                    .thenByDescending { it.index }
            )
            .take(requestedCount)
            .map { it.index }
            .sorted()
    }

    fun presetCoreCounts(): List<Int> {
        val set = linkedSetOf<Int>()
        for (candidate in listOf(1, 2, 4, 6, 8, 10, 12)) {
            if (candidate < totalCores) set.add(candidate)
        }
        set.add(totalCores)
        return set.toList()
    }
}

object CpuInfo {
    fun detect(): CpuTopology {
        val presentIndices = parseCpuListSpec(readSysfsText("/sys/devices/system/cpu/present"))
            .ifEmpty {
                val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                (0 until n).toList()
            }

        val descriptors = presentIndices.map { idx ->
            val maxKhz = readSysfsText("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq")
                ?.toLongOrNull()
                ?: readSysfsText("/sys/devices/system/cpu/cpu$idx/cpufreq/scaling_max_freq")?.toLongOrNull()
                ?: 0L
            CoreDescriptor(index = idx, maxFreqKhz = maxKhz)
        }

        val total = descriptors.size.coerceAtLeast(1)
        val summary = buildSummary(total, descriptors)
        return CpuTopology(
            totalCores = total,
            cores = descriptors,
            summary = summary,
        )
    }

    fun readOnlineCoreSet(): Set<Int> =
        parseCpuListSpec(readSysfsText("/sys/devices/system/cpu/online")).toSet()

    internal fun parseCpuListSpec(spec: String?): List<Int> {
        if (spec.isNullOrBlank()) return emptyList()
        val out = sortedSetOf<Int>()
        for (part in spec.trim().split(',')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val dash = token.indexOf('-')
            if (dash > 0) {
                val start = token.substring(0, dash).toIntOrNull() ?: continue
                val end = token.substring(dash + 1).toIntOrNull() ?: continue
                if (end >= start && (end - start) <= 256) {
                    for (i in start..end) out.add(i)
                }
            } else {
                token.toIntOrNull()?.let { if (it in 0..256) out.add(it) }
            }
        }
        return out.toList()
    }

    internal fun buildSummary(total: Int, descriptors: List<CoreDescriptor>): String {
        val withFreq = descriptors.filter { it.maxFreqKhz > 100_000L }
        if (withFreq.isEmpty()) {
            return "$total ARM64 Hardware Cores"
        }
        val grouped = withFreq
            .groupBy { String.format(Locale.US, "%.2f GHz", it.maxFreqKhz / 1_000_000.0) }
            .entries
            .sortedByDescending { entry -> entry.value.maxOf { it.maxFreqKhz } }
            .joinToString(" + ") { (ghz, list) -> "${list.size}× $ghz" }
        return "$total Cores ($grouped)"
    }

    private fun readSysfsText(path: String): String? = try {
        val f = File(path)
        if (f.isFile && f.canRead()) f.readText(Charsets.UTF_8).trim() else null
    } catch (_: Exception) {
        null
    }
}
