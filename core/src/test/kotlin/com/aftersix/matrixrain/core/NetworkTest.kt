package com.aftersix.matrixrain.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    private class FakeTime {
        var nanos = 0L
        val clock: () -> Long = { nanos }
        val sleep: (Long) -> Unit = { ms -> nanos += ms * 1_000_000 }
    }

    private class FakeHttp(var metered: Boolean = false) : HttpProbe {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun get(url: String, maxBytes: Long): ProbeResult {
            calls += url to maxBytes
            return if (url.contains("down")) ProbeResult(false, 0, 0, error = "timeout") else ProbeResult(true, 120, maxBytes, 200)
        }
        override fun metered() = metered
    }

    private class Budget(var left: Long) : DataBudget {
        override fun remainingBytes() = left
        override fun spend(bytes: Long) { left -= bytes }
    }

    private val probe = RunnerTest.FakeProbe()

    @Suppress("UNCHECKED_CAST")
    private fun counters(r: EngineResult) = r.extra["Network"] as Map<String, Any?>

    @Test
    fun `probes follow the intensity cadence and bulk respects the budget`() {
        val time = FakeTime()
        val http = FakeHttp()
        val budget = Budget(48 * Engine.MIB)
        val driver = NetworkDriver(http, budget, { listOf("https://a.example/", "https://down.example/") }, { "https://files.example/blob" },
            clock = time.clock, sleep = time.sleep)
        val r = driver.run(RunRequest(mode = Mode.NETWORK, seconds = 300, intensity = 50, downloadMiB = 16), "n1", probe, { false }, {})
        val probes = http.calls.count { it.second == NetworkDriver.PROBE_BYTES }
        assertEquals(60, probes) // every 5 s for 300 s
        assertEquals(3, http.calls.count { it.first.contains("files.example") }) // 48 MiB budget / 16 MiB
        assertEquals(48 * Engine.MIB, r.bytesRead)
        assertEquals("daily data budget reached", counters(r)["BulkSkipped"])
        assertEquals(30, counters(r)["Failures"])
        assertEquals(120L, counters(r)["AverageMs"])
    }

    @Test
    fun `bulk downloads skip metered networks and need a configured URL`() {
        val time = FakeTime()
        val http = FakeHttp(metered = true)
        val r = NetworkDriver(http, Budget(Long.MAX_VALUE), { WorkdaySites.DEFAULTS }, { "https://files.example/blob" }, clock = time.clock, sleep = time.sleep)
            .run(RunRequest(mode = Mode.NETWORK, seconds = 120, downloadMiB = 8), "n2", probe, { false }, {})
        assertEquals("metered network", counters(r)["BulkSkipped"])
        assertFalse(http.calls.any { it.first.contains("files.example") })

        val r2 = NetworkDriver(FakeHttp(), Budget(Long.MAX_VALUE), { WorkdaySites.DEFAULTS }, { null }, clock = time.clock, sleep = time.sleep)
            .run(RunRequest(mode = Mode.NETWORK, seconds = 30, downloadMiB = 8), "n3", probe, { false }, {})
        assertEquals("no downloadUrl configured", counters(r2)["BulkSkipped"])
        assertThrows(IllegalArgumentException::class.java) { NetworkDriver.validateDownload("http://files.example/blob") }
    }

    @Test
    fun `probe interval scales with intensity`() {
        assertEquals(9_900L, NetworkDriver.probeIntervalMs(1))
        assertEquals(5_000L, NetworkDriver.probeIntervalMs(50))
        assertEquals(1_000L, NetworkDriver.probeIntervalMs(95))
    }

    @Test
    fun `fault limiter allows six per rolling hour`() {
        var t = 0L
        val limiter = FaultLimiter(6) { t }
        repeat(6) { assertTrue(limiter.tryAcquire()); t += 60_000 }
        assertFalse(limiter.tryAcquire())
        t = 3_600_001
        assertTrue(limiter.tryAcquire())
        assertEquals(Fault.NATIVE, Fault.parse("Native"))
        assertThrows(IllegalArgumentException::class.java) { Fault.parse("reboot") }
    }
}
