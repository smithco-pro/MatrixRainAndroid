package com.aftersix.matrixrain.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkdayTest {
    private class FakeTime {
        var nanos = 0L
        val clock: () -> Long = { nanos }
        val sleep: (Long) -> Unit = { ms -> nanos += ms * 1_000_000 }
    }

    private class RecordingActions(var pause: (Double) -> String? = { null }, val time: FakeTime) : WorkdayActions {
        val performed = mutableListOf<Pair<Double, String>>()
        override fun pauseReason() = pause(time.nanos / 1e9)
        override fun perform(step: WorkdayStep, context: WorkdayContext): String {
            performed += time.nanos / 1e9 to step.action
            return when (step.action) {
                WorkdayPlan.BROWSE -> context.sites.next()
                WorkdayPlan.OPEN_APP -> context.apps?.next() ?: "none"
                else -> "ok"
            }
        }
    }

    private val probe = RunnerTest.FakeProbe()

    @Test
    fun `plan is repeatable from its seed and keeps the Office cadence`() {
        val plan = WorkdayPlan.create(21_600, 7)
        assertEquals(plan, WorkdayPlan.create(21_600, 7))
        assertEquals(WorkdayStep(0, WorkdayPlan.PREPARE), plan.first())
        assertEquals((1..7).map { it * 5 }, plan.drop(1).take(7).map { it.atSeconds })
        val breaks = plan.filter { it.action == WorkdayPlan.BREAK }
        val restarts = plan.filter { it.action == WorkdayPlan.RESTART_APPS }
        assertTrue(breaks.first().atSeconds >= 4_200)
        assertEquals(listOf(5_400, 10_800, 16_200), restarts.map { it.atSeconds }.map { it - it % 5_400 })
        plan.zipWithNext().forEach { (a, b) -> assertTrue(b.atSeconds >= a.atSeconds) }
        assertThrows(IllegalArgumentException::class.java) { WorkdayPlan.create(59, 1) }
        assertThrows(IllegalArgumentException::class.java) { WorkdayPlan.create(28_801, 1) }
    }

    @Test
    fun `driver performs every step that falls inside the duration`() {
        val time = FakeTime()
        val actions = RecordingActions(time = time)
        val driver = WorkdayDriver(actions, { WorkdaySites.DEFAULTS }, { listOf("a", "b") }, time.clock, time.sleep)
        val result = driver.run(RunRequest(mode = Mode.WORKDAY, seconds = 7_200), "run-1", probe, { false }, {})
        val plan = WorkdayPlan.create(7_200, "run-1".hashCode())
        assertEquals(plan.map { it.action }, actions.performed.map { it.second })
        assertEquals("duration reached", result.reason)
        @Suppress("UNCHECKED_CAST")
        val counters = result.extra["Workday"] as Map<String, Any?>
        assertEquals(plan.size, counters["Done"])
        assertEquals(0, counters["Skipped"])
    }

    @Test
    fun `actions due while paused are skipped but the opening burst waits`() {
        val time = FakeTime()
        // Paused from 0 to 20 s (opening burst) and from 600 to 1200 s.
        val actions = RecordingActions(pause = { t -> if (t < 20 || t in 600.0..1200.0) "device locked" else null }, time = time)
        val driver = WorkdayDriver(actions, { WorkdaySites.DEFAULTS }, { emptyList() }, time.clock, time.sleep)
        val result = driver.run(RunRequest(mode = Mode.WORKDAY, seconds = 1_800), "run-2", probe, { false }, {})
        @Suppress("UNCHECKED_CAST")
        val counters = result.extra["Workday"] as Map<String, Any?>
        // All eight opening steps still ran, after the first pause ended.
        assertEquals(WorkdayDriver.OPENING_STEPS, actions.performed.count { it.first >= 20.0 && it.first < 60.0 })
        assertTrue((counters["Skipped"] as Int) > 0)
        assertTrue(actions.performed.none { it.first in 600.0..1200.0 })
        assertTrue((counters["PausedSeconds"] as Int) >= 600)
    }

    @Test
    fun `stop request ends the phase`() {
        val time = FakeTime()
        val actions = RecordingActions(time = time)
        val driver = WorkdayDriver(actions, { WorkdaySites.DEFAULTS }, { emptyList() }, time.clock, time.sleep)
        val result = driver.run(RunRequest(mode = Mode.WORKDAY, seconds = 7_200), "run-3", probe, { time.nanos > 300e9 }, {})
        assertEquals(Engine.STOPPED, result.reason)
        assertTrue(actions.performed.all { it.first <= 300.5 })
    }

    @Test
    fun `missing prerequisite fails the run with its reason`() {
        val dir = Files.createTempDirectory("mr-workday").toFile()
        try {
            val actions = object : WorkdayActions {
                override fun prerequisites() = "needs Display over other apps"
                override fun pauseReason(): String? = null
                override fun perform(step: WorkdayStep, context: WorkdayContext) = "ok"
            }
            val c = RunCoordinator(RunStore(dir), probe, "t", drivers = mapOf(Mode.WORKDAY to WorkdayDriver(actions, { WorkdaySites.DEFAULTS }, { emptyList() })))
            c.start(RunRequest(mode = Mode.WORKDAY, seconds = 60))
            assertTrue(c.awaitIdle(10_000))
            val run = RunStore(dir).readRun()!!
            assertEquals("FAILED", run["State"])
            assertEquals("Workday cannot start: needs Display over other apps", run["Detail"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `workday duration limits follow the Office mode`() {
        RunRequest(mode = Mode.WORKDAY, seconds = 28_800, baselineSeconds = 600).validated()
        assertThrows(IllegalArgumentException::class.java) { RunRequest(mode = Mode.WORKDAY, seconds = 30).validated() }
        assertThrows(IllegalArgumentException::class.java) { RunRequest(mode = Mode.CPU, seconds = 3_601).validated() }
    }

    @Test
    fun `sites are validated like browser-sites json and rotate without repeats`() {
        assertThrows(IllegalArgumentException::class.java) { WorkdaySites.validate(listOf("http://example.com/")) }
        assertThrows(IllegalArgumentException::class.java) { WorkdaySites.validate(listOf("https://user:pw@example.com/")) }
        assertThrows(IllegalArgumentException::class.java) { WorkdaySites.validate(emptyList()) }
        assertEquals(listOf("https://a.example/", "https://b.example/"), WorkdaySites.parse(" https://a.example/ ,\nhttps://b.example/\n"))
        assertEquals(listOf("com.android.chrome", "com.Slack"), WorkdaySites.validatePackages(listOf("com.android.chrome", "com.Slack")))
        assertThrows(IllegalArgumentException::class.java) { WorkdaySites.validatePackages(listOf("chrome; rm -rf")) }
        val cycle = Cycle(WorkdaySites.DEFAULTS, 3)
        repeat(3) { assertEquals(WorkdaySites.DEFAULTS.toSet(), List(WorkdaySites.DEFAULTS.size) { cycle.next() }.toSet()) }
    }
}
