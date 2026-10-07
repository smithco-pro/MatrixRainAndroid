package com.aftersix.matrixrain.core

import java.net.URI
import kotlin.random.Random

data class WorkdayStep(val atSeconds: Int, val action: String)

/**
 * Seeded Workday schedule, ported from MatrixRain's OfficePlan.Create (shared/OfficeModel.cs). Timing is repeatable
 * from the run's seed; there are no wall-clock sleeps in the planner.
 */
object WorkdayPlan {
    const val DEFAULT_SECONDS = 21_600
    const val MAX_SECONDS = 28_800

    const val PREPARE = "prepare"
    const val BROWSE = "browse"
    const val OPEN_APP = "open-app"
    const val CREATE_TEXT = "create-text"
    const val CREATE_CSV = "create-csv"
    const val EDIT_FILE = "edit-file"
    const val OPEN_FILE = "open-file"
    const val RETURN_HOME = "return-home"
    const val BREAK = "break"
    const val RESTART_APPS = "restart-apps"

    private val START = listOf(CREATE_TEXT, CREATE_CSV, BROWSE, OPEN_APP, OPEN_FILE, BROWSE, RETURN_HOME)

    // Weighted like the Office list: the most frequent actions are the ones that move the foreground.
    private val ACTIONS = listOf(
        BROWSE, BROWSE, BROWSE, OPEN_APP, OPEN_APP, OPEN_APP, OPEN_FILE, OPEN_FILE,
        CREATE_TEXT, CREATE_CSV, EDIT_FILE, EDIT_FILE, RETURN_HOME,
    )

    fun create(seconds: Int, seed: Int): List<WorkdayStep> {
        require(seconds in 60..MAX_SECONDS) { "Workday duration must be 60..$MAX_SECONDS seconds." }
        val random = Random(seed)
        val steps = mutableListOf(WorkdayStep(0, PREPARE))
        START.forEachIndexed { i, action -> if ((i + 1) * 5 < seconds) steps += WorkdayStep((i + 1) * 5, action) }
        var next = 60
        var restart = 5_400
        var pause = 4_200
        while (next < seconds) {
            when {
                next >= restart -> { steps += WorkdayStep(next, RESTART_APPS); restart += 5_400; next += 60 }
                next >= pause -> { steps += WorkdayStep(next, BREAK); next += random.nextInt(600, 1_201); pause += 5_400 }
                else -> { steps += WorkdayStep(next, ACTIONS[random.nextInt(ACTIONS.size)]); next += random.nextInt(30, 121) }
            }
        }
        return steps
    }
}

/** Endless shuffled rotation without repeats until the list is used up (port of BrowserSites.Select). */
class Cycle<T>(items: List<T>, seed: Int) {
    private val items = items.distinct()
    private val random = Random(seed)
    private val remaining = mutableListOf<T>()

    init {
        require(this.items.isNotEmpty()) { "Rotation needs at least one item." }
    }

    fun next(): T {
        if (remaining.isEmpty()) remaining += items
        return remaining.removeAt(random.nextInt(remaining.size))
    }
}

object WorkdaySites {
    val DEFAULTS = listOf(
        "https://www.foxnews.com/", "https://www.cnn.com/", "https://gizmodo.com/", "https://aftersixcomputers.com/",
        "https://apnews.com/", "https://www.theverge.com/", "https://arstechnica.com/", "https://www.bbc.com/news",
    )

    /** Same rules as MatrixRain's browser-sites.json: 1..64 absolute HTTPS URLs, no credentials or whitespace. */
    fun validate(sites: List<String>): List<String> {
        require(sites.size in 1..64) { "Sites must contain 1..64 HTTPS URLs." }
        return sites.map { site ->
            val uri = runCatching { URI(site) }.getOrNull()
            require(
                site.isNotBlank() && site.none { it in "\r\n\t\" " } && uri != null && uri.isAbsolute &&
                    uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && site.length <= 2048,
            ) { "Sites require absolute HTTPS URLs without credentials or whitespace: $site" }
            uri!!.toString()
        }.distinct()
    }

    /** Parses a managed-config list: one entry per line, or comma-separated. */
    fun parse(text: String?): List<String> =
        text?.split('\n', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun validatePackages(packages: List<String>): List<String> {
        require(packages.size <= 32) { "Apps must list at most 32 packages." }
        packages.forEach { require(PACKAGE.matches(it)) { "Not an Android package name: $it" } }
        return packages.distinct()
    }
}

/** What the Workday driver asks the platform to do. Android implements this with intents and files. */
interface WorkdayActions {
    /** A reason the Workday cannot run at all (missing permission, no apps), or null. Checked once before the plan starts. */
    fun prerequisites(): String? = null

    /** A reason to pause right now (device locked, screen off, a person is using the device), or null. */
    fun pauseReason(): String?

    /** Performs one action and returns a short description for the log, or throws. */
    fun perform(step: WorkdayStep, context: WorkdayContext): String

    /** Called once when the phase ends, however it ends. */
    fun finish(context: WorkdayContext) = Unit
}

class WorkdayContext(val runId: String, val seed: Int, val sites: Cycle<String>, val apps: Cycle<String>?) {
    val log = ArrayDeque<String>()

    fun record(line: String) {
        log.addLast(line)
        while (log.size > 200) log.removeFirst()
    }
}

/**
 * Walks the plan in real time. Actions that come due while paused are skipped rather than replayed (except the
 * opening burst), and the deadline keeps running during pauses, as in MatrixRain's Office actor.
 */
class WorkdayDriver(
    private val actions: WorkdayActions,
    /** Read at the start of each Workday phase, so managed-config changes apply to the next run. */
    private val sites: () -> List<String>,
    private val apps: () -> List<String>,
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
        val seed = runId.hashCode()
        val result = EngineResult()
        val validSites = WorkdaySites.validate(sites())
        val context = WorkdayContext(runId, seed, Cycle(validSites, seed xor 0x5175), apps().takeIf { it.isNotEmpty() }?.let { Cycle(it, seed xor 0xA995) })
        actions.prerequisites()?.let { throw IllegalStateException("Workday cannot start: $it") }
        val plan = WorkdayPlan.create(request.seconds, seed)
        val start = clock()
        fun elapsed() = (clock() - start) / 1e9
        var index = 0
        var done = 0
        var skipped = 0
        var failed = 0
        var pausedSeconds = 0.0
        var nextPulse = 0.0
        var activity = "Workday / starting"
        var reason = "duration reached"
        fun pulse(now: Double) {
            result.elapsedSeconds = now
            result.extra["Activity"] = activity
            result.extra["Workday"] = linkedMapOf(
                "Planned" to plan.size, "Done" to done, "Skipped" to skipped, "Failed" to failed,
                "PausedSeconds" to pausedSeconds.toInt(), "Next" to plan.getOrNull(index)?.action,
                "Recent" to context.log.toList().takeLast(10),
            )
            heartbeat(result)
        }
        try {
            while (true) {
                val now = elapsed()
                if (stopRequested()) { reason = Engine.STOPPED; break }
                if (now >= request.seconds) break
                probe.safetyStop()?.let { reason = "safety stop: $it" }
                if (reason.startsWith("safety stop")) break
                val paused = actions.pauseReason()
                if (paused != null) {
                    pausedSeconds += TICK_MS / 1000.0
                    activity = "Workday / paused: $paused"
                }
                while (index < plan.size && plan[index].atSeconds <= now) {
                    val step = plan[index++]
                    if (paused != null && index > OPENING_STEPS) {
                        skipped++
                        context.record("${clock(now)} SKIP ${step.action} ($paused)")
                        continue
                    }
                    if (paused != null) { index--; break } // the opening burst waits for the device instead of being lost
                    try {
                        val detail = actions.perform(step, context)
                        done++
                        activity = "Workday / ${step.action}"
                        context.record("${clock(now)} ${step.action}: $detail")
                    } catch (e: Exception) {
                        failed++
                        context.record("${clock(now)} FAIL ${step.action}: ${e.message}")
                    }
                }
                if (now >= nextPulse) { pulse(now); nextPulse = now + 2 }
                sleep(TICK_MS)
            }
        } finally {
            runCatching { actions.finish(context) }
        }
        result.reason = reason
        pulse(elapsed())
        return result
    }

    private fun clock(seconds: Double): String {
        val s = seconds.toLong()
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    companion object {
        const val TICK_MS = 250L
        /** prepare + the seven opening actions, which wait out a pause instead of being skipped. */
        const val OPENING_STEPS = 8
    }
}
