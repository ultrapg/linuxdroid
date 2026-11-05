package com.linuxdroid.app.runtime

import java.io.File
import java.util.Locale

enum class CpuCoreTier(val label: String, val shortBadge: String) {
    PRIME("Prime Core", "Prime"),
    PERFORMANCE("Performance Core", "Perf"),
    EFFICIENCY("Efficiency Core", "Eff"),
}

data class CoreDescriptor(
    val index: Int,
    val maxFreqKhz: Long,
    val tier: CpuCoreTier = CpuCoreTier.PERFORMANCE,
) {
    fun formattedGhz(): String =
        if (maxFreqKhz > 100_000L) String.format(Locale.US, "%.2f GHz", maxFreqKhz / 1_000_000.0)
        else "ARM64 Core"

    val isPerformanceOrPrime: Boolean
        get() = tier == CpuCoreTier.PRIME || tier == CpuCoreTier.PERFORMANCE
}

data class CpuTopology(
    val totalCores: Int,
    val cores: List<CoreDescriptor>,
    val summary: String,
) {
    fun allCoreIndices(): List<Int> =
        if (cores.isNotEmpty()) cores.map { it.index }.sorted() else (0 until totalCores).toList()

    fun performanceCoreIndices(): List<Int> {
        val perf = cores.filter { it.isPerformanceOrPrime }.map { it.index }.sorted()
        if (perf.isNotEmpty() && perf.size < totalCores) return perf
        val all = allCoreIndices()
        return if (all.size >= 2) all.drop(all.size / 2) else all
    }

    fun primeCoreIndices(): List<Int> =
        cores.filter { it.tier == CpuCoreTier.PRIME }.map { it.index }.sorted()

    fun efficiencyCoreIndices(): List<Int> {
        val eff = cores.filter { it.tier == CpuCoreTier.EFFICIENCY }.map { it.index }.sorted()
        if (eff.isNotEmpty() && eff.size < totalCores) return eff
        val all = allCoreIndices()
        return if (all.size >= 2) all.take(all.size / 2) else all
    }

    /**
     * Resolves an explicit comma/range core specification (or legacy core count limit) into a sorted
     * list of pinned core indices. Returns an empty list when all cores are selected.
     */
    fun resolveSelectedCores(
        selectedSpec: String?,
        legacyLimit: Int = 0,
        onlineIndices: Set<Int> = CpuInfo.readOnlineCoreSet(),
    ): List<Int> {
        val validSet = allCoreIndices().toSet()
        if (!selectedSpec.isNullOrBlank()) {
            val parsed = CpuInfo.parseCpuListSpec(selectedSpec).filter { it in validSet }.sorted()
            if (parsed.isNotEmpty()) {
                return if (parsed.size >= totalCores) emptyList() else parsed
            }
        }
        return selectPreferredCores(legacyLimit, onlineIndices)
    }

    /**
     * Returns the full non-empty set of active core indices for UI display and core count calculation.
     */
    fun effectiveSelectedSet(selectedSpec: String?, legacyLimit: Int = 0): Set<Int> {
        val pinned = resolveSelectedCores(selectedSpec, legacyLimit)
        return if (pinned.isEmpty()) allCoreIndices().toSet() else pinned.toSet()
    }

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

        val rawDescriptors = presentIndices.map { idx ->
            val maxKhz = readSysfsText("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq")
                ?.toLongOrNull()
                ?: readSysfsText("/sys/devices/system/cpu/cpu$idx/cpufreq/scaling_max_freq")?.toLongOrNull()
                ?: 0L
            CoreDescriptor(index = idx, maxFreqKhz = maxKhz)
        }

        val descriptors = assignCoreTiers(rawDescriptors)
        val total = descriptors.size.coerceAtLeast(1)
        val summary = buildSummary(total, descriptors)
        return CpuTopology(
            totalCores = total,
            cores = descriptors,
            summary = summary,
        )
    }

    internal fun assignCoreTiers(rawCores: List<CoreDescriptor>): List<CoreDescriptor> {
        if (rawCores.isEmpty()) return emptyList()
        val withFreq = rawCores.filter { it.maxFreqKhz > 100_000L }
        val buckets = withFreq.map { (it.maxFreqKhz + 25_000L) / 50_000L }.distinct().sorted()
        if (buckets.size <= 1) {
            val splitIdx = rawCores.size / 2
            return rawCores.mapIndexed { pos, c ->
                val tier = if (rawCores.size >= 4 && pos < splitIdx) {
                    CpuCoreTier.EFFICIENCY
                } else {
                    CpuCoreTier.PERFORMANCE
                }
                c.copy(tier = tier)
            }
        }
        val minBucket = buckets.first()
        val maxBucket = buckets.last()
        val hasThreeOrMore = buckets.size >= 3
        return rawCores.map { c ->
            val b = (c.maxFreqKhz + 25_000L) / 50_000L
            val tier = when {
                c.maxFreqKhz <= 100_000L -> CpuCoreTier.PERFORMANCE
                b == minBucket -> CpuCoreTier.EFFICIENCY
                b == maxBucket && hasThreeOrMore -> CpuCoreTier.PRIME
                else -> CpuCoreTier.PERFORMANCE
            }
            c.copy(tier = tier)
        }
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
