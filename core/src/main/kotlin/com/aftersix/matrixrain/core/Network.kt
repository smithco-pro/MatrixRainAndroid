package com.aftersix.matrixrain.core

import java.net.URI
import kotlin.math.max
import kotlin.math.roundToLong

class ProbeResult(val ok: Boolean, val millis: Long, val bytes: Long, val status: Int = 0, val error: String? = null)

/** HTTPS access for the network workload. Android uses HttpURLConnection, which telemetry SDKs can instrument. */
interface HttpProbe {
    /** GETs the URL, reading at most [maxBytes] of the body, and reports time to complete. */
    fun get(url: String, maxBytes: Long): ProbeResult

    /** True when the active network is metered (cellular, hotspot). */
    fun metered(): Boolean
}

/** Per-day download allowance shared by all runs. */
interface DataBudget {
    fun remainingBytes(): Long
    fun spend(bytes: Long)
}

/**
 * Network workload: latency probes across the sites list on a cadence set by intensity (every 10 s at 1 %, 5 s at
 * 50 %, 1 s at 90 %+), plus an optional bulk download every 60 s from an admin-configured HTTPS URL. Bulk transfers
 * respect the daily data budget and run only on unmetered networks unless allowed.
 */
class NetworkDriver(
    private val http: HttpProbe,
    private val budget: DataBudget,
    private val sites: () -> List<String>,
    private val downloadUrl: () -> String?,
    private val allowMetered: () -> Boolean = { false },
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : ModeDriver {
    override fun run(
        request: RunRequest,
        runId: String,
        probe: DeviceProbe,
        stopRequested: () -> Boolean,
        heartbeat: (EngineResult) -> Unit,
    ): EngineResult {
        val result = EngineResult()
        val targets = Cycle(WorkdaySites.validate(sites()), runId.hashCode())
        val bulkUrl = downloadUrl()?.takeIf { it.isNotBlank() }?.also { validateDownload(it) }
        val interval = probeIntervalMs(request.intensity)
        val start = clock()
        fun elapsed() = (clock() - start) / 1e9
        var probes = 0
        var failures = 0
        var totalMs = 0L
        var worstMs = 0L
        var downloaded = 0L
        var bulkSkipped: String? = if (request.downloadMiB == 0) "not requested" else if (bulkUrl == null) "no downloadUrl configured" else null
        var nextProbe = 0.0
        var nextBulk = 10.0
        var nextPulse = 0.0
        var reason = "duration reached"
        val recent = ArrayDeque<String>()
        while (true) {
            val now = elapsed()
            if (stopRequested()) { reason = Engine.STOPPED; break }
            if (now >= request.seconds) break
            probe.safetyStop()?.let { reason = "safety stop: $it" }
            if (reason.startsWith("safety stop")) break
            if (now >= nextProbe) {
                val url = targets.next()
                val r = http.get(url, PROBE_BYTES)
                probes++
                if (r.ok) {
                    totalMs += r.millis
                    worstMs = max(worstMs, r.millis)
                } else {
                    failures++
                }
                recent.addLast("${host(url)} ${if (r.ok) "${r.millis} ms" else "failed: ${r.error ?: r.status}"}")
                while (recent.size > 10) recent.removeFirst()
                nextProbe = now + interval / 1000.0
            }
            if (bulkUrl != null && request.downloadMiB > 0 && now >= nextBulk) {
                val want = request.downloadMiB * Engine.MIB
                bulkSkipped = when {
                    http.metered() && !allowMetered() -> "metered network"
                    budget.remainingBytes() < want -> "daily data budget reached"
                    else -> {
                        val r = http.get(bulkUrl, want)
                        budget.spend(r.bytes)
                        downloaded += r.bytes
                        if (!r.ok) failures++
                        null
                    }
                }
                nextBulk = now + BULK_EVERY_SECONDS
            }
            if (now >= nextPulse) {
                result.elapsedSeconds = now
                result.bytesRead = downloaded
                result.extra["Activity"] = "Network / ${probes} probes"
                result.extra["Network"] = linkedMapOf(
                    "Probes" to probes, "Failures" to failures,
                    "AverageMs" to if (probes - failures > 0) totalMs / (probes - failures) else null, "WorstMs" to worstMs,
                    "DownloadedBytes" to downloaded, "BulkSkipped" to bulkSkipped, "Recent" to recent.toList(),
                )
                heartbeat(result)
                nextPulse = now + 2
            }
            sleep(TICK_MS)
        }
        result.reason = reason
        result.elapsedSeconds = elapsed()
        result.bytesRead = downloaded
        return result
    }

    companion object {
        const val TICK_MS = 100L
        const val PROBE_BYTES = 256L * 1024
        const val BULK_EVERY_SECONDS = 60

        fun probeIntervalMs(intensity: Int): Long = max(1_000L, (10_000.0 * (100 - intensity) / 100).roundToLong())

        fun validateDownload(url: String) {
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null) {
                "downloadUrl must be an absolute HTTPS URL without credentials."
            }
        }

        private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url
    }
}

/** One-shot fault events in this app, for crash/ANR/exception telemetry. */
enum class Fault(val wire: String) {
    CRASH("crash"),
    ANR("anr"),
    NATIVE("native"),
    HANDLED("handled");

    companion object {
        fun parse(value: String): Fault =
            entries.firstOrNull { it.wire == value.trim().lowercase() } ?: throw IllegalArgumentException("Unknown fault: $value (crash, anr, native, handled)")
    }
}

/** Limits faults to [maxPerHour] in any rolling hour, so a misconfigured schedule cannot flood the tenant. */
class FaultLimiter(private val maxPerHour: Int = 6, private val now: () -> Long = System::currentTimeMillis) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(history: List<Long> = emptyList()): Boolean {
        if (times.isEmpty()) times += history
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= 3_600_000) times.removeFirst()
        if (times.size >= maxPerHour) return false
        times.addLast(t)
        return true
    }

    @Synchronized
    fun history(): List<Long> = times.toList()
}
