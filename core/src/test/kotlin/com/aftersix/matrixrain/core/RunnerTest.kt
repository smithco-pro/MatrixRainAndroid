package com.aftersix.matrixrain.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Ports of MatrixRain's tests/portable/check.py and sequence.py scenarios to the Kotlin runner. */
class RunnerTest {
    private lateinit var dir: File
    private lateinit var store: RunStore

    class FakeProbe(
        var available: Long = 6L shl 30,
        var freeDisk: Long = 64L shl 30,
        var safety: String? = null,
    ) : DeviceProbe {
        override fun memory() = MemInfo(8L shl 30, available, 256L shl 20)
        override fun freeDiskBytes(directory: File) = freeDisk
        override fun safetyStop() = safety
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mr-runner").toFile()
        store = RunStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun coordinator(probe: DeviceProbe = FakeProbe()) = RunCoordinator(store, probe, "test-device")

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within $timeoutMs ms")
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun phases(run: Map<String, Any?>) = run["Phases"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun result(run: Map<String, Any?>) = run["Result"] as Map<String, Any?>?

    @Test
    fun `two-phase run completes under one run ID with baseline rain off then workload rain on`() {
        val rainSeen = mutableListOf<Pair<String, Boolean>>()
        val tracking = RunCoordinator(store, FakeProbe(), "test-device", onPublish = {
            synchronized(rainSeen) { rainSeen += (it["Phase"] as String) to (it["RainEnabled"] as Boolean) }
        })
        val id = tracking.start(RunRequest(mode = Mode.CPU, seconds = 1, baselineSeconds = 1, threads = 1))
        assertTrue(tracking.awaitIdle(10_000))
        val run = store.readRun()!!
        assertEquals("Both phases complete under one run ID", id, run["RunId"])
        assertEquals(RunState.COMPLETED.name, run["State"])
        assertEquals(2L, run["PhaseCount"])
        assertEquals(2L, run["PlannedSeconds"])
        assertEquals(listOf("baseline", "cpu"), phases(run).map { it["Phase"] })
        synchronized(rainSeen) {
            assertTrue("Baseline requests rain off", rainSeen.any { it == ("baseline" to false) })
            assertTrue("Workload restores rain", rainSeen.any { it == ("cpu" to true) })
        }
        assertEquals("duration reached", result(run)!!["Reason"])
        assertNull(tracking.activeRunId)
    }

    @Test
    fun `second run rejected while one is active and does not overwrite its state`() {
        val c = coordinator()
        val id = c.start(RunRequest(mode = Mode.BASELINE, seconds = 3))
        waitFor { store.readRun()?.get("RunId") == id }
        try {
            c.start(RunRequest(mode = Mode.CPU, seconds = 1))
            fail("second run should be rejected")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(id, store.readRun()!!["RunId"])
        c.stop()
        assertTrue(c.awaitIdle(10_000))
    }

    @Test
    fun `operator stop ends the run as IDLE and persists the result`() {
        val c = coordinator()
        val id = c.start(RunRequest(mode = Mode.CPU, seconds = 30, threads = 1))
        waitFor { store.readRun()?.get("State") == RunState.RUNNING.name }
        assertEquals(StopOutcome.REQUESTED, c.stop(id))
        assertTrue("Stop exits worker cleanly", c.awaitIdle(10_000))
        val last = store.readLastResult()!!
        assertEquals(id, last["RunId"])
        assertEquals(RunState.IDLE.name, last["State"])
        assertEquals(Engine.STOPPED, result(last)!!["Reason"])
        assertTrue(File(dir, "result-$id.json").exists())
    }

    @Test
    fun `stale stop target cannot stop the current run`() {
        val c = coordinator()
        c.start(RunRequest(mode = Mode.BASELINE, seconds = 2))
        assertEquals(StopOutcome.STALE, c.stop("0".repeat(32)))
        assertTrue(c.awaitIdle(10_000))
        assertEquals(RunState.COMPLETED.name, store.readRun()!!["State"])
        assertEquals(StopOutcome.NOT_RUNNING, c.stop())
    }

    @Test
    fun `stop during baseline cancels the whole run and never starts the workload`() {
        val c = coordinator()
        val id = c.start(RunRequest(mode = Mode.MEMORY, seconds = 5, baselineSeconds = 5))
        waitFor { store.readRun()?.get("Phase") == "baseline" && store.readRun()?.get("State") == "RUNNING" }
        c.stop(id)
        assertTrue(c.awaitIdle(10_000))
        val run = store.readRun()!!
        assertEquals(RunState.IDLE.name, run["State"])
        assertEquals(listOf("baseline"), phases(run).map { it["Phase"] })
    }

    @Test
    fun `stop during workload keeps the completed baseline`() {
        val c = coordinator()
        val id = c.start(RunRequest(mode = Mode.CPU, seconds = 30, baselineSeconds = 1, threads = 1))
        waitFor { store.readRun()?.get("Phase") == "cpu" }
        c.stop(id)
        assertTrue(c.awaitIdle(10_000))
        val run = store.readRun()!!
        assertEquals(RunState.IDLE.name, run["State"])
        assertEquals(listOf("baseline", "cpu"), phases(run).map { it["Phase"] })
        assertEquals("duration reached", (phases(run)[0]["Result"] as Map<*, *>)["Reason"])
    }

    @Test
    fun `science result survives the JSON boundary`() {
        val c = coordinator()
        c.start(RunRequest(mode = Mode.SCIENCE, seconds = 1, threads = 1))
        assertTrue(c.awaitIdle(10_000))
        val pi = result(store.readLastResult()!!)!!["Pi"] as Double
        assertTrue("pi estimate $pi", pi in 2.9..3.4)
        assertTrue((store.readRun()!!["Detail"] as String).contains("pi ~ "))
    }

    @Test
    fun `write-budget completion reports its real reason and cleans its scratch file`() {
        val c = coordinator()
        c.start(RunRequest(mode = Mode.DISK, seconds = 60, diskMiB = 4, writeLimitMiB = 8, diskMiBPerSecond = 256, intensity = 95))
        assertTrue(c.awaitIdle(30_000))
        val last = store.readLastResult()!!
        assertEquals(RunState.COMPLETED.name, last["State"])
        assertEquals("write budget reached", result(last)!!["Reason"])
        assertEquals(8L * Engine.MIB, result(last)!!["BytesWritten"])
        assertTrue(store.scratchDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `workload failure is published after a successful baseline and keeps prior phases`() {
        val probe = FakeProbe(available = 1L shl 30) // below the 20%-of-8-GiB reserve once memory is requested
        val c = coordinator(probe)
        val id = c.start(RunRequest(mode = Mode.MEMORY, seconds = 5, baselineSeconds = 1, memoryMiB = 64))
        assertTrue(c.awaitIdle(10_000))
        val run = store.readRun()!!
        assertEquals(RunState.FAILED.name, run["State"])
        assertTrue((run["Detail"] as String).startsWith("Memory reserve reached"))
        val p = phases(run)
        assertEquals(listOf("baseline", "memory"), p.map { it["Phase"] })
        assertNotNull(p[1]["Error"])
        assertTrue(File(dir, "error-$id.txt").exists())
    }

    @Test
    fun `memory workload allocates the requested amount`() {
        val c = coordinator()
        c.start(RunRequest(mode = Mode.MEMORY, seconds = 1, memoryMiB = 16))
        assertTrue(c.awaitIdle(10_000))
        assertEquals(16L, result(store.readLastResult()!!)!!["AllocatedMiB"])
    }

    @Test
    fun `allocation failure becomes a FAILED run instead of crashing the process`() {
        val exhausted = object : BlockAllocator {
            var count = 0
            override fun allocate(bytes: Int): java.nio.ByteBuffer {
                if (++count > 2) throw OutOfMemoryError("heap exhausted")
                return java.nio.ByteBuffer.allocate(bytes)
            }
        }
        val c = RunCoordinator(store, FakeProbe(), "test-device", allocator = exhausted)
        c.start(RunRequest(mode = Mode.MEMORY, seconds = 5, memoryMiB = 64))
        assertTrue(c.awaitIdle(10_000))
        val run = store.readRun()!!
        assertEquals(RunState.FAILED.name, run["State"])
        assertTrue(run["Detail"] as String, (run["Detail"] as String).startsWith("Memory allocation failed after 16 MiB"))
    }

    @Test
    fun `safety stop ends the run early as IDLE with its reason`() {
        val probe = FakeProbe(safety = "battery below 30% while unplugged")
        val c = coordinator(probe)
        c.start(RunRequest(mode = Mode.CPU, seconds = 30, threads = 1))
        assertTrue(c.awaitIdle(10_000))
        val run = store.readRun()!!
        assertEquals(RunState.IDLE.name, run["State"])
        assertTrue((run["Detail"] as String).startsWith("safety stop: battery below 30%"))
    }

    @Test
    fun `recovery marks an orphaned RUNNING record FAILED`() {
        store.write(store.runFile, Json.write(mapOf("RunId" to "a".repeat(32), "State" to "RUNNING", "UpdatedUtc" to "2026-01-01T00:00:00Z")))
        val c = coordinator()
        assertEquals(true, c.status()["Stale"])
        c.recover()
        assertEquals(RunState.FAILED.name, store.readRun()!!["State"])
        assertEquals(false, c.status()["Stale"])
        // The lease is free for the next run.
        c.start(RunRequest(mode = Mode.BASELINE, seconds = 1))
        assertTrue(c.awaitIdle(10_000))
    }

    @Test
    fun `recovery cleans named orphan scratch files only`() {
        store.scratchDirectory.mkdirs()
        val orphan = File(store.scratchDirectory, "io-" + "0123456789abcdef".repeat(2) + ".tmp").apply { writeText("x") }
        val keep = File(store.scratchDirectory, "io-notours.tmp").apply { writeText("x") }
        store.cleanScratch()
        assertFalse(orphan.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `result retention is bounded at 20 files and last result survives the next run`() {
        repeat(23) { i ->
            store.saveResult("%032d".format(i), mapOf("RunId" to "%032d".format(i)))
            File(dir, "result-%032d.json".format(i)).setLastModified(1_000_000L + i * 1000)
        }
        store.saveResult("f".repeat(32), mapOf("RunId" to "f".repeat(32)))
        assertEquals(RunStore.KEEP, store.results().size)
        assertEquals("f".repeat(32), store.readLastResult()!!["RunId"])
    }
}
