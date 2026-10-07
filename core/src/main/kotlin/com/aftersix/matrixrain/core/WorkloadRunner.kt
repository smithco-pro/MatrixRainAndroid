package com.aftersix.matrixrain.core

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one request's phases (baseline, then workload) under one run ID and publishes run.json snapshots.
 * Port of MatrixRain's worker Run() (workloads/src/Program.cs); the snapshot keeps its field names so
 * status tooling can read both platforms the same way.
 */
class WorkloadRunner(
    request: RunRequest,
    val runId: String,
    private val store: RunStore,
    private val probe: DeviceProbe,
    private val device: String,
    private val processId: Int = 0,
    private val onPublish: (Map<String, Any?>) -> Unit = {},
    private val allocator: BlockAllocator = BlockAllocator.Direct,
    private val drivers: Map<Mode, ModeDriver> = emptyMap(),
) {
    private val request = request.validated()
    private val stop = AtomicBoolean(false)
    private val phases = mutableListOf<Map<String, Any?>>()
    private val started = Instant.now().toString()
    private val startNanos = System.nanoTime()
    private val phaseCount = if (this.request.baselineSeconds > 0) 2 else 1
    private var index = 0
    private var current = this.request
    private var phaseStarted = started
    private var phaseStartNanos = startNanos
    @Volatile var latest: Map<String, Any?> = emptyMap()
        private set

    fun requestStop() = stop.set(true)

    val stopRequested: Boolean get() = stop.get()

    private fun publish(state: RunState, detail: String, result: EngineResult?) {
        val now = System.nanoTime()
        val snapshot = linkedMapOf<String, Any?>(
            "RunId" to runId, "Hostname" to device, "Pid" to processId,
            "State" to state.name, "Mode" to request.mode.wire, "Phase" to current.mode.wire,
            "PhaseIndex" to minOf(index + 1, phaseCount), "PhaseCount" to phaseCount,
            "StartedUtc" to started, "UpdatedUtc" to Instant.now().toString(), "PhaseStartedUtc" to phaseStarted,
            "PhaseSeconds" to current.seconds, "PlannedSeconds" to request.seconds + request.baselineSeconds,
            "ElapsedSeconds" to (now - startNanos) / 1e9, "PhaseElapsedSeconds" to (now - phaseStartNanos) / 1e9,
            "Detail" to detail, "Options" to request.toMap(), "BaselineSeconds" to request.baselineSeconds,
            "Result" to result?.toMap(), "Phases" to phases.toList(),
            // The display turns rain off for baseline, as MatrixRain's state.json RainEnabled does.
            "RainEnabled" to (current.mode != Mode.BASELINE),
        )
        store.write(store.runFile, Json.write(snapshot))
        latest = snapshot
        onPublish(snapshot)
    }

    /** Runs to completion on the calling thread and returns the final snapshot. */
    fun run(): Map<String, Any?> {
        store.cleanScratch()
        try {
            index = 0
            while (index < phaseCount) {
                current = if (request.baselineSeconds > 0 && index == 0) {
                    RunRequest(mode = Mode.BASELINE, seconds = request.baselineSeconds)
                } else {
                    request
                }
                phaseStarted = Instant.now().toString()
                phaseStartNanos = System.nanoTime()
                // Checked between phases as well as inside the engine. A stop during baseline cancels the whole run.
                if (stop.get()) {
                    publish(RunState.IDLE, "${Engine.STOPPED} before ${current.mode.wire}", null)
                    break
                }
                publish(RunState.RUNNING, "Starting ${current.mode.wire}", null)
                val pulse: (EngineResult) -> Unit = { r ->
                    publish(RunState.RUNNING, (r.extra["Activity"] as? String) ?: "Active / ${current.mode.wire}", r)
                }
                val driver = drivers[current.mode]
                val final = if (driver != null) {
                    driver.run(current, runId, probe, stop::get, pulse)
                } else {
                    if (current.mode == Mode.WORKDAY || current.mode == Mode.NETWORK) {
                        throw IllegalStateException("${current.mode.wire} is not available on this device.")
                    }
                    Engine(current, store.scratchDirectory, probe, stop::get, pulse, allocator).run()
                }
                phases += linkedMapOf("Phase" to current.mode.wire, "StartedUtc" to phaseStarted, "EndedUtc" to Instant.now().toString(), "Result" to final.toMap())
                if (final.reason == Engine.STOPPED || stop.get()) {
                    publish(RunState.IDLE, "${Engine.STOPPED} during ${current.mode.wire}", final)
                    break
                }
                if (final.reason.startsWith("safety stop")) {
                    publish(RunState.IDLE, "${final.reason} during ${current.mode.wire}", final)
                    break
                }
                if (index == phaseCount - 1) {
                    val detail = final.reason + (final.pi?.let { " / pi ~ %.6f".format(java.util.Locale.ROOT, it) } ?: "")
                    publish(RunState.COMPLETED, detail, final)
                }
                index++
            }
        } catch (e: Throwable) {
            // Includes OutOfMemoryError: an uncaught error on this thread would kill the whole app process.
            val message = e.message ?: e.javaClass.simpleName
            phases += linkedMapOf("Phase" to current.mode.wire, "StartedUtc" to phaseStarted, "EndedUtc" to Instant.now().toString(), "Error" to message)
            publish(RunState.FAILED, message, null)
            store.saveError(runId, "Phase: ${current.mode.wire}\n${e.stackTraceToString()}")
        }
        store.saveResult(runId, latest)
        return latest
    }

    companion object {
        fun newRunId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

enum class RunState { RUNNING, IDLE, COMPLETED, FAILED }

/** Runs a phase for modes that are not synthetic load (Workday), like MatrixRain's OfficeBridge.Run. */
fun interface ModeDriver {
    fun run(request: RunRequest, runId: String, probe: DeviceProbe, stopRequested: () -> Boolean, heartbeat: (EngineResult) -> Unit): EngineResult
}

/**
 * One run at a time per device (MatrixRain's run.lock lease). Stop requests are scoped to a run ID, so a
 * stale stop cannot end a newer run.
 */
class RunCoordinator(
    private val store: RunStore,
    private val probe: DeviceProbe,
    private val device: String,
    private val processId: Int = 0,
    private val onPublish: (Map<String, Any?>) -> Unit = {},
    private val allocator: BlockAllocator = BlockAllocator.Direct,
    private val drivers: Map<Mode, ModeDriver> = emptyMap(),
    private val onFinished: (Map<String, Any?>) -> Unit = {},
) {
    private var active: WorkloadRunner? = null
    private var thread: Thread? = null

    val activeRunId: String? @Synchronized get() = active?.runId

    /** Starts a run on a background thread and returns its ID. Throws IllegalStateException if one is active. */
    @Synchronized
    fun start(request: RunRequest, runId: String = WorkloadRunner.newRunId()): String {
        active?.let { throw IllegalStateException("A workload is already running: ${it.runId}") }
        val runner = WorkloadRunner(request, runId, store, probe, device, processId, onPublish, allocator, drivers)
        active = runner
        thread = Thread({
            val final = try {
                runner.run()
            } finally {
                synchronized(this) { if (active === runner) { active = null; thread = null } }
            }
            onFinished(final)
        }, "mr-run-${runId.take(8)}").apply { start() }
        return runId
    }

    /** Requests a stop. With a run ID, only that run is stopped. */
    @Synchronized
    fun stop(runId: String? = null): StopOutcome {
        val runner = active ?: return StopOutcome.NOT_RUNNING
        if (runId != null && runId != runner.runId) return StopOutcome.STALE
        runner.requestStop()
        return StopOutcome.REQUESTED
    }

    /** Waits for the active run (if any) to finish. Returns true if idle within the timeout. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val t = synchronized(this) { thread } ?: return true
        t.join(timeoutMs)
        return !t.isAlive
    }

    /**
     * After a process restart, run.json may still say RUNNING for a run this process does not own.
     * Mark it FAILED so status is truthful (MatrixRain relies on the OS releasing run.lock for the same effect).
     */
    @Synchronized
    fun recover() {
        if (active != null) return
        val run = store.readRun() ?: return
        if (run["State"] != RunState.RUNNING.name) return
        val failed = LinkedHashMap(run).apply {
            put("State", RunState.FAILED.name)
            put("Detail", "Run ended unexpectedly (process restarted).")
            put("UpdatedUtc", Instant.now().toString())
        }
        store.write(store.runFile, Json.write(failed))
        (run["RunId"] as? String)?.let { store.saveResult(it, failed) }
    }

    /** run.json plus heartbeat age and staleness, like Get-WorkloadStatus.ps1. */
    fun status(now: Instant = Instant.now()): Map<String, Any?> {
        val run = synchronized(this) { active?.latest }?.takeIf { it.isNotEmpty() } ?: store.readRun() ?: return mapOf("State" to "NONE")
        val updated = (run["UpdatedUtc"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val age = updated?.let { (now.toEpochMilli() - it.toEpochMilli()) / 1000.0 }
        return LinkedHashMap(run).apply {
            put("HeartbeatAgeSeconds", age)
            put("Stale", run["State"] == RunState.RUNNING.name && (age == null || age > STALE_SECONDS))
        }
    }

    companion object {
        const val STALE_SECONDS = 20
    }
}

enum class StopOutcome { REQUESTED, NOT_RUNNING, STALE }
