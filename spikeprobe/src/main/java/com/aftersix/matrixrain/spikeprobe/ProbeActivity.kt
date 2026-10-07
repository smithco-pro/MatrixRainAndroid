package com.aftersix.matrixrain.spikeprobe

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/**
 * Spike item (d): an ordinary app with no special permissions tries to reach the spike's
 * DUMP-guarded receiver and service. Both should be refused.
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val results = mutableListOf<String>()

        // Broadcasts to a permission-guarded receiver are dropped silently; the spike log shows whether it arrived.
        sendBroadcast(Intent().setComponent(ComponentName(SPIKE, "$SPIKE.ShellReceiver")).putExtra("cmd", "probe-broadcast"))
        results += "broadcast sent (check spike log for 'cmd=probe-broadcast')"

        results += try {
            startService(Intent().setComponent(ComponentName(SPIKE, "$SPIKE.SpikeService")).putExtra("cmd", "noop"))
            "startService: allowed (unexpected)"
        } catch (e: SecurityException) {
            "startService: SecurityException (expected)"
        } catch (e: Exception) {
            "startService: ${e.javaClass.simpleName} ${e.message}"
        }

        results.forEach { Log.i("MRProbe", it) }
        setContentView(TextView(this).apply { text = results.joinToString("\n"); setPadding(40, 80, 40, 40) })
    }

    companion object {
        const val SPIKE = "com.aftersix.matrixrain.spike"
    }
}
