package com.aftersix.matrixrain.fault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.aftersix.matrixrain.MatrixRainApp
import com.aftersix.matrixrain.core.Fault
import com.aftersix.matrixrain.core.FaultLimiter
import com.aftersix.matrixrain.core.Json
import java.time.Instant

/**
 * Raises one-shot faults in this app so crash, ANR and exception telemetry has something to report. Crashes and ANRs
 * happen in the separate :crashlab process (verified: the main process and any running workload survive).
 * At most six per rolling hour, across restarts.
 */
class FaultController(private val context: Context) {
    private val prefs = context.getSharedPreferences("faults", Context.MODE_PRIVATE)
    private val limiter = FaultLimiter(MAX_PER_HOUR)

    @Synchronized
    fun raise(fault: Fault, source: String): String {
        // An ANR keeps :crashlab blocked until the system kills it; a fault sent meanwhile would queue and be lost.
        val sinceAnr = System.currentTimeMillis() - prefs.getLong(LAST_ANR, 0)
        if (fault != Fault.HANDLED && sinceAnr < ANR_BUSY_MS) {
            return "refused: the previous ANR is still in progress (retry in ${(ANR_BUSY_MS - sinceAnr) / 1000 + 1} s)"
        }
        val history = prefs.getString(HISTORY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        if (!limiter.tryAcquire(history)) return "refused: more than $MAX_PER_HOUR faults in the last hour"
        prefs.edit().putString(HISTORY, limiter.history().joinToString(",")).apply()
        if (fault == Fault.ANR) prefs.edit().putLong(LAST_ANR, System.currentTimeMillis()).apply()

        val app = MatrixRainApp.from(context)
        val runId = app.coordinator.activeRunId
        app.intel.breadcrumb("MatrixRain fault ${fault.wire} from $source run=${runId ?: "-"}")
        record(fault, source, runId)
        when (fault) {
            Fault.HANDLED -> {
                val e = IllegalStateException("MatrixRain synthetic handled exception (fault test)")
                app.intel.handledException(e)
                Log.w(TAG, "handled exception raised", e)
            }
            else -> context.sendBroadcast(
                Intent(context, CrashLabReceiver::class.java).setAction(fault.wire).apply {
                    // Foreground broadcasts time out after about 10 s, which is what turns the blocked receiver into an ANR.
                    if (fault == Fault.ANR) addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                },
            )
        }
        return "raised ${fault.wire}"
    }

    private fun record(fault: Fault, source: String, runId: String?) {
        val store = MatrixRainApp.from(context).store
        val file = store.directory.resolve("faults.json")
        @Suppress("UNCHECKED_CAST")
        val previous = runCatching { Json.parse(file.readText()) as List<Any?> }.getOrNull().orEmpty()
        val entry = mapOf("Utc" to Instant.now().toString(), "Fault" to fault.wire, "Source" to source, "RunId" to runId)
        store.write(file, Json.write((previous + entry).takeLast(50)))
    }

    companion object {
        private const val TAG = "MatrixRain"
        private const val HISTORY = "history"
        private const val LAST_ANR = "lastAnr"
        private const val ANR_BUSY_MS = 35_000L
        const val MAX_PER_HOUR = 6
    }
}

/** Runs in :crashlab. Each action ends or stalls that process only. */
class CrashLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("MatrixRain", "crashlab ${intent.action}")
        when (intent.action) {
            Fault.CRASH.wire -> throw IllegalStateException("MatrixRain synthetic crash (fault test)")
            Fault.ANR.wire -> Thread.sleep(25_000)
            // A real signal, so the platform writes a native-crash tombstone; no NDK needed.
            Fault.NATIVE.wire -> Os.kill(Os.getpid(), OsConstants.SIGSEGV)
        }
    }
}
