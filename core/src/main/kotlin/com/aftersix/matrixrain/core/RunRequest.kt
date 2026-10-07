package com.aftersix.matrixrain.core

import kotlin.random.Random

/** Workload modes. Wire names match MatrixRain's `--mode` values. */
enum class Mode(val wire: String) {
    BASELINE("baseline"),
    VISUALIZATION("visualization"),
    CPU("cpu"),
    MEMORY("memory"),
    DISK("disk"),
    SCIENCE("science"),
    WORKDAY("workday"),
    NETWORK("network");

    companion object {
        fun parse(value: String): Mode =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
                ?: throw IllegalArgumentException("Unknown workload mode: $value")
    }
}

/**
 * One run: an optional baseline phase followed by the workload. Ports MatrixRain's
 * WorkloadRequest/Options validation (src/WorkloadRequest.cs, workloads/src/Engine.cs).
 */
data class RunRequest(
    val mode: Mode = Mode.CPU,
    val seconds: Int = 600,
    val baselineSeconds: Int = 0,
    val intensity: Int = 50,
    val threads: Int = 2,
    val memoryMiB: Int = 512,
    val diskMiB: Int = 64,
    val writeLimitMiB: Int = 1024,
    val diskMiBPerSecond: Int = 10,
    /** Network mode: size of each bulk download (0 = latency probes only). */
    val downloadMiB: Int = 0,
) {
    /** Returns the request with baseline cleared for baseline mode, or throws IllegalArgumentException. */
    fun validated(): RunRequest {
        // Workday, like MatrixRain's Office mode, runs 60 s to 8 h; other modes 1 s to 1 h.
        val (low, high) = if (mode == Mode.WORKDAY) 60 to WorkdayPlan.MAX_SECONDS else 1 to MAX_SECONDS
        range(seconds, low, high, "Duration")
        range(baselineSeconds, 0, MAX_BASELINE_SECONDS, "Baseline")
        val baseline = if (mode == Mode.BASELINE) 0 else baselineSeconds
        val total = if (mode == Mode.WORKDAY) WorkdayPlan.MAX_SECONDS + MAX_BASELINE_SECONDS else MAX_SECONDS
        require(seconds + baseline <= total) { "Baseline plus workload must be at most $total seconds." }
        range(intensity, 1, 95, "Intensity")
        range(threads, 1, 32, "Threads")
        range(memoryMiB, 8, 4096, "Memory")
        range(diskMiB, 4, 256, "Scratch size")
        range(writeLimitMiB, diskMiB, 4096, "Write budget")
        range(diskMiBPerSecond, 1, 256, "Disk rate")
        range(downloadMiB, 0, 256, "Download size")
        return copy(baselineSeconds = baseline)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "Mode" to mode.wire, "Seconds" to seconds, "Intensity" to intensity, "Threads" to threads,
        "MemoryMiB" to memoryMiB, "DiskMiB" to diskMiB, "WriteLimitMiB" to writeLimitMiB, "DiskMiBPerSecond" to diskMiBPerSecond,
        "DownloadMiB" to downloadMiB,
    )

    companion object {
        const val MAX_SECONDS = 3600
        const val MAX_BASELINE_SECONDS = 600

        /** Keys accepted from shell extras and managed config. Unknown keys are rejected, as in MatrixRain's config. */
        val KEYS = setOf(
            "profile", "mode", "seconds", "baselineSeconds", "intensity", "threads",
            "memoryMiB", "diskMiB", "writeLimitMiB", "diskMiBPerSecond", "downloadMiB",
        )

        /** Builds a request from loosely typed values. `profile` (node01..node06) supplies defaults that other keys override. */
        fun fromMap(values: Map<String, Any?>): RunRequest {
            val unknown = values.keys - KEYS
            require(unknown.isEmpty()) { "Unknown option: ${unknown.sorted().joinToString()}" }
            var r = (values["profile"] as? String)?.let { Profiles.request(it) } ?: RunRequest()
            fun int(key: String): Int? = when (val v = values[key]) {
                null -> null
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: throw IllegalArgumentException("$key must be a whole number.")
                else -> throw IllegalArgumentException("$key must be a whole number.")
            }
            (values["mode"] as? String)?.let { r = r.copy(mode = Mode.parse(it)) }
            int("seconds")?.let { r = r.copy(seconds = it) }
            int("baselineSeconds")?.let { r = r.copy(baselineSeconds = it) }
            int("intensity")?.let { r = r.copy(intensity = it) }
            int("threads")?.let { r = r.copy(threads = it) }
            int("memoryMiB")?.let { r = r.copy(memoryMiB = it) }
            int("diskMiB")?.let { r = r.copy(diskMiB = it) }
            int("writeLimitMiB")?.let { r = r.copy(writeLimitMiB = it) }
            int("diskMiBPerSecond")?.let { r = r.copy(diskMiBPerSecond = it) }
            int("downloadMiB")?.let { r = r.copy(downloadMiB = it) }
            return r.validated()
        }

        private fun range(value: Int, low: Int, high: Int, name: String) =
            require(value in low..high) { "$name must be $low..$high." }
    }
}

/**
 * MatrixRain's legacy Node01..Node06 aliases (uem/Run-Node0X.ps1 → Start-Workload.ps1): fixed test presets,
 * not device identities. Each is 600 s with no baseline and the script defaults.
 */
object Profiles {
    private val modes = listOf(Mode.BASELINE, Mode.VISUALIZATION, Mode.CPU, Mode.MEMORY, Mode.DISK, Mode.SCIENCE)
    val names: List<String> = modes.indices.map { "node%02d".format(it + 1) }

    fun request(name: String): RunRequest {
        val index = names.indexOf(name.trim().lowercase())
        require(index >= 0) { "Unknown profile: $name (expected node01..node06)" }
        return RunRequest(mode = modes[index], seconds = 600, baselineSeconds = 0)
    }
}

/**
 * Random rotation (port of TestRotation): a shuffled bag of the five load modes, never repeating
 * the previous mode, refilled when empty.
 */
class Rotation(seed: Int, private var previous: Mode? = null) {
    private val random = Random(seed)
    private val remaining = mutableListOf<Mode>()

    fun next(): Mode {
        if (remaining.isEmpty()) remaining += BAG
        // A refilled bag holds five distinct modes, so at least four candidates always differ from the previous one.
        val candidates = remaining.filter { it != previous }
        val next = candidates[random.nextInt(candidates.size)]
        remaining.remove(next)
        previous = next
        return next
    }

    companion object {
        val BAG = listOf(Mode.VISUALIZATION, Mode.CPU, Mode.MEMORY, Mode.DISK, Mode.SCIENCE)
    }
}
