package com.aftersix.matrixrain.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTest {
    @Test
    fun `invalid mode fails before running`() {
        val e = assertThrows(IllegalArgumentException::class.java) { RunRequest.fromMap(mapOf("mode" to "office")) }
        assertTrue(e.message!!.contains("Unknown workload mode"))
    }

    @Test
    fun `unknown keys are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RunRequest.fromMap(mapOf("mode" to "cpu", "turbo" to "1")) }
    }

    @Test
    fun `combined duration above one hour is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RunRequest(seconds = 3500, baselineSeconds = 101).validated() }
        RunRequest(seconds = 3000, baselineSeconds = 600).validated()
    }

    @Test
    fun `explicit standalone baseline is not duplicated`() {
        assertEquals(0, RunRequest(mode = Mode.BASELINE, seconds = 60, baselineSeconds = 60).validated().baselineSeconds)
    }

    @Test
    fun `ranges match MatrixRain`() {
        listOf(
            RunRequest(intensity = 96), RunRequest(intensity = 0), RunRequest(threads = 33), RunRequest(memoryMiB = 4097),
            RunRequest(diskMiB = 3), RunRequest(diskMiB = 64, writeLimitMiB = 63), RunRequest(diskMiBPerSecond = 257),
            RunRequest(seconds = 0), RunRequest(baselineSeconds = 601),
        ).forEach { r -> assertThrows(r.toString(), IllegalArgumentException::class.java) { r.validated() } }
    }

    @Test
    fun `string values from shell extras are parsed`() {
        val r = RunRequest.fromMap(mapOf("mode" to "DISK", "seconds" to "120", "diskMiB" to "32", "writeLimitMiB" to "64"))
        assertEquals(Mode.DISK, r.mode)
        assertEquals(120, r.seconds)
        assertEquals(64, r.writeLimitMiB)
        assertThrows(IllegalArgumentException::class.java) { RunRequest.fromMap(mapOf("seconds" to "ten")) }
    }

    @Test
    fun `node profiles map to the legacy script modes and can be overridden`() {
        assertEquals(
            listOf(Mode.BASELINE, Mode.VISUALIZATION, Mode.CPU, Mode.MEMORY, Mode.DISK, Mode.SCIENCE),
            Profiles.names.map { Profiles.request(it).mode },
        )
        val r = RunRequest.fromMap(mapOf("profile" to "node03", "seconds" to 120))
        assertEquals(Mode.CPU, r.mode)
        assertEquals(120, r.seconds)
        assertEquals(0, r.baselineSeconds)
        assertThrows(IllegalArgumentException::class.java) { Profiles.request("node07") }
    }

    @Test
    fun `rotation never repeats back to back and covers every mode in each bag`() {
        val rotation = Rotation(seed = 42, previous = Mode.CPU)
        var previous: Mode? = Mode.CPU
        val seen = (1..50).map { rotation.next().also { m -> assertNotEquals(previous, m); previous = m } }
        seen.chunked(5).forEach { bag -> assertEquals(Rotation.BAG.toSet(), bag.toSet()) }
    }

    @Test
    fun `json round trip`() {
        val value = mapOf("a" to "q\"uote\n", "b" to listOf(1L, 2.5, true, null), "c" to mapOf("d" to -3L))
        assertEquals(value, Json.parse(Json.write(value)))
    }
}
