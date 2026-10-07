package com.aftersix.matrixrain.control

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.util.Log
import com.aftersix.matrixrain.MatrixRainApp
import com.aftersix.matrixrain.core.ConfigAction
import com.aftersix.matrixrain.core.ConfigPolicy
import com.aftersix.matrixrain.core.Json
import com.aftersix.matrixrain.core.ManagedConfig
import com.aftersix.matrixrain.service.WorkloadService
import java.time.Instant

/**
 * Applies UEM managed configuration. Evaluated at boot and app update, on restriction changes, and every 15 minutes,
 * so a command pushed while the app was not running is still picked up.
 *
 * Android 12+ refuses to start a foreground service from the background, and on Android 15+ that includes jobs even
 * with the overlay permission (verified on Android 17). Delivery of an exact alarm is an exemption, so every
 * evaluation that may start a run happens inside [ConfigAlarmReceiver]; other triggers just schedule one.
 */
class ManagedConfigController(private val context: Context) {
    private val prefs = context.getSharedPreferences("managed-config", Context.MODE_PRIVATE)

    @Volatile var current: ManagedConfig = ManagedConfig()
        private set

    fun register() {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = ConfigAlarmReceiver.schedule(c, delayMs = 1_000, reason = "restrictions changed")
        }, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED))
        ConfigAlarmReceiver.schedule(context, delayMs = 1_000, reason = "app start")
        // Versions before 0.1.0 polled with a persisted job; this app no longer uses JobScheduler.
        context.getSystemService(android.app.job.JobScheduler::class.java).cancelAll()
    }

    /** Debug builds only: restrictions injected over adb in place of the DPC's, to exercise this path without UEM. */
    @Volatile var testOverride: Map<String, Any?>? = null

    @Synchronized
    fun evaluate(trigger: String) {
        val app = MatrixRainApp.from(context)
        val values = testOverride ?: context.getSystemService(RestrictionsManager::class.java).applicationRestrictions.let { b ->
            b.keySet().associateWith { @Suppress("DEPRECATION") b.get(it) }
        }
        val config = try {
            ManagedConfig.parse(values)
        } catch (e: IllegalArgumentException) {
            record(trigger, "invalid configuration: ${e.message}")
            return
        }
        current = config
        app.probe.minBatteryPercent = config.minBatteryPercent

        val action = ConfigPolicy.decide(config, prefs.getString(LAST_COMMAND, null), app.coordinator.activeRunId != null)
        val outcome = when (action) {
            ConfigAction.None -> null
            is ConfigAction.Start -> try {
                "started run ${WorkloadService.start(context, action.request, viaConfig = true)}"
            } catch (e: Exception) {
                app.store.saveError(action.commandId, "Start rejected: ${e.message}")
                "start rejected: ${e.message}"
            }
            is ConfigAction.Stop -> "stop: ${app.coordinator.stop().name.lowercase()}"
            is ConfigAction.RaiseFault -> "fault: ${app.faults.raise(action.fault, "managed config")}"
            is ConfigAction.Reject -> {
                app.store.saveError(action.commandId, "Start rejected: ${action.reason}")
                "start rejected: ${action.reason}"
            }
        }
        val commandId = when (action) {
            is ConfigAction.Start -> action.commandId
            is ConfigAction.Stop -> action.commandId
            is ConfigAction.RaiseFault -> action.commandId
            is ConfigAction.Reject -> action.commandId
            ConfigAction.None -> null
        }
        // Only mark a command handled once it was acted on, so a start refused for a transient reason is not retried forever
        // but is recorded with its reason under error-<commandId>.txt.
        if (commandId != null) prefs.edit().putString(LAST_COMMAND, commandId).apply()
        if (outcome != null) record(trigger, outcome, commandId)
    }

    private fun record(trigger: String, outcome: String, commandId: String? = null) {
        Log.i(TAG, "managed config ($trigger): $outcome")
        val app = MatrixRainApp.from(context)
        app.store.write(
            app.store.directory.resolve("managed-config.json"),
            Json.write(mapOf("UpdatedUtc" to Instant.now().toString(), "Trigger" to trigger, "CommandId" to commandId, "Outcome" to outcome)),
        )
    }

    companion object {
        private const val TAG = "MatrixRain"
        private const val LAST_COMMAND = "lastHandledCommandId"
    }
}

/**
 * Exact-alarm chain: evaluates managed config, then schedules the next poll in 15 minutes. Receiving an exact alarm
 * lets the app start its foreground service from the background.
 */
class ConfigAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MatrixRainApp.from(context).managedConfig.evaluate(intent.getStringExtra(EXTRA_REASON) ?: "poll")
        schedule(context, POLL_MS, "poll")
    }

    companion object {
        private const val EXTRA_REASON = "reason"
        private const val POLL_MS = 15 * 60 * 1000L

        fun schedule(context: Context, delayMs: Long, reason: String) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(context, ConfigAlarmReceiver::class.java).putExtra(EXTRA_REASON, reason),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val at = System.currentTimeMillis() + delayMs
            if (android.os.Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                // Without exact-alarm access the poll still runs, but a start from it may be refused in the background.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }
}

/** Boot and app-update entry point. Both broadcasts are background-start exemptions, so evaluate directly. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MatrixRainApp.from(context).managedConfig.evaluate(intent.action!!.substringAfterLast('.').lowercase())
        }
    }
}
