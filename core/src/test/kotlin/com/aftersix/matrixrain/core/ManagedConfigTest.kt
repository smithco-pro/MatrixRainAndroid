package com.aftersix.matrixrain.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedConfigTest {
    private fun config(vararg pairs: Pair<String, Any?>) = ManagedConfig.parse(mapOf(*pairs))

    @Test
    fun `new commandId with start runs the configured profile once`() {
        val c = config("command" to "start", "commandId" to "run-1", "profile" to "node03", "seconds" to 120)
        val action = ConfigPolicy.decide(c, lastHandledCommandId = null, running = false)
        assertTrue(action is ConfigAction.Start)
        assertEquals(Mode.CPU, (action as ConfigAction.Start).request.mode)
        assertEquals(120, action.request.seconds)
        assertEquals(ConfigAction.None, ConfigPolicy.decide(c, lastHandledCommandId = "run-1", running = false))
    }

    @Test
    fun `unset values from the console are ignored`() {
        val c = config("command" to "start", "commandId" to "x", "mode" to "disk", "seconds" to -1, "intensity" to "", "threads" to null)
        val action = ConfigPolicy.decide(c, null, false) as ConfigAction.Start
        assertEquals(600, action.request.seconds)
        assertEquals(50, action.request.intensity)
    }

    @Test
    fun `kill switch stops a running workload regardless of command`() {
        val c = config("enabled" to false, "command" to "start", "commandId" to "new")
        assertEquals(ConfigAction.Stop(null), ConfigPolicy.decide(c, null, running = true))
        assertEquals(ConfigAction.None, ConfigPolicy.decide(c, null, running = false))
    }

    @Test
    fun `stop command and invalid start are reported`() {
        assertEquals(ConfigAction.Stop("s1"), ConfigPolicy.decide(config("command" to "stop", "commandId" to "s1"), null, true))
        val bad = ConfigPolicy.decide(config("command" to "start", "commandId" to "b1", "mode" to "office"), null, false)
        assertTrue(bad is ConfigAction.Reject)
    }

    @Test
    fun `workday sites and apps are parsed and validated`() {
        val c = config("sites" to "https://a.example/\nhttps://b.example/", "apps" to "com.android.chrome, com.Slack")
        assertEquals(listOf("https://a.example/", "https://b.example/"), c.sites)
        assertEquals(listOf("com.android.chrome", "com.Slack"), c.apps)
        assertThrows(IllegalArgumentException::class.java) { config("sites" to "http://insecure.example/") }
    }

    @Test
    fun `fault command raises the configured fault once`() {
        val c = config("command" to "fault", "commandId" to "f1", "fault" to "anr")
        assertEquals(ConfigAction.RaiseFault(Fault.ANR, "f1"), ConfigPolicy.decide(c, null, false))
        assertTrue(ConfigPolicy.decide(config("command" to "fault", "commandId" to "f2"), null, false) is ConfigAction.Reject)
        assertThrows(IllegalArgumentException::class.java) { config("downloadUrl" to "http://files.example/x") }
    }

    @Test
    fun `unknown keys and malformed values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { config("nodeId" to "01") }
        assertThrows(IllegalArgumentException::class.java) { config("command" to "reboot") }
        assertThrows(IllegalArgumentException::class.java) { config("commandId" to "has space") }
        assertThrows(IllegalArgumentException::class.java) { config("minBatteryPercent" to 95) }
    }
}
