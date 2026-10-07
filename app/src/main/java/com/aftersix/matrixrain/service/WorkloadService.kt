package com.aftersix.matrixrain.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.aftersix.matrixrain.MatrixRainApp
import com.aftersix.matrixrain.core.Json
import com.aftersix.matrixrain.core.Mode
import com.aftersix.matrixrain.core.RunRequest
import com.aftersix.matrixrain.ui.HudOverlay
import com.aftersix.matrixrain.ui.MainActivity
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Foreground service that owns workload runs. Control (all go through [RunRequest] validation):
 *
 *   adb shell am start-foreground-service -n com.aftersix.matrixrain/.service.WorkloadService \
 *       -a com.aftersix.matrixrain.START --es mode cpu --ei seconds 600 [--ei baselineSeconds 60] [--es commandId X]
 *   adb shell am start-foreground-service -n ... -a com.aftersix.matrixrain.STOP [--es runId <id>]
 *   adb shell am start-foreground-service -n ... -a com.aftersix.matrixrain.FAULT --es fault crash|anr|native|handled
 *   adb shell content read --uri content://com.aftersix.matrixrain.status/status            # status JSON, any time
 *
 * The UI starts runs through [start] with the same validation.
 */
class WorkloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val hud by lazy { HudOverlay(this) }
    private val app get() = MatrixRainApp.from(this)
    private val listener: (Map<String, Any?>) -> Unit = { snapshot -> main.post { onSnapshot(snapshot) } }

    override fun onCreate() {
        super.onCreate()
        app.listeners += listener
        goForeground(app.coordinator.status())
    }

    override fun onDestroy() {
        app.listeners -= listener
        hud.hide()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() requires startForeground() promptly on every start, even for STOP.
        goForeground(app.coordinator.status())
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_ATTACH -> onRunStarted()
            ACTION_FAULT -> {
                val outcome = runCatching { app.faults.raise(com.aftersix.matrixrain.core.Fault.parse(intent.getStringExtra("fault").orEmpty()), "shell") }
                Log.i(TAG, "fault: ${outcome.getOrElse { it.message }}")
            }
            ACTION_TEST_CONFIG -> if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                val extras = intent.extras
                app.managedConfig.testOverride = extras?.keySet().orEmpty().filter { it != "defer" }
                    .associateWith { @Suppress("DEPRECATION") extras?.get(it) }
                // With --ez defer true the override is evaluated by a background exact alarm in 10 s, testing that path.
                if (extras?.getBoolean("defer") == true) {
                    com.aftersix.matrixrain.control.ConfigAlarmReceiver.schedule(this, 10_000, "test (deferred)")
                } else {
                    app.managedConfig.evaluate("test")
                }
            }
            ACTION_STOP -> {
                val outcome = app.coordinator.stop(intent.getStringExtra(EXTRA_RUN_ID))
                Log.i(TAG, "stop requested: $outcome")
            }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val extras = intent.extras
        val commandId = extras?.getString(EXTRA_COMMAND_ID)
        val values = extras?.keySet().orEmpty()
            .filter { it != EXTRA_COMMAND_ID }
            .associateWith { @Suppress("DEPRECATION") extras?.get(it) }
        try {
            check(app.managedConfig.current.enabled) { "Workloads are disabled by managed configuration." }
            val runId = app.coordinator.start(RunRequest.fromMap(values))
            Log.i(TAG, "started run $runId from shell (command ${commandId ?: "-"})")
            onRunStarted()
        } catch (e: Exception) {
            // Errors are kept by command ID so a caller that cannot see logcat can still read why it failed.
            app.store.saveError(commandId ?: "shell", "Start rejected: ${e.message}")
            Log.w(TAG, "start rejected: ${e.message}")
        }
    }

    private fun onRunStarted() {
        val request = app.coordinator.status()
        val mode = request["Mode"] as? String
        // Keep the CPU awake for load phases with the screen off; display-only modes need no wake lock.
        if (mode != Mode.BASELINE.wire && mode != Mode.VISUALIZATION.wire && mode != Mode.WORKDAY.wire) {
            // Network included: probes must keep running with the screen off.
            val planned = (request["PlannedSeconds"] as? Number)?.toLong() ?: RunRequest.MAX_SECONDS.toLong()
            acquireWakeLock((planned + 120) * 1000)
        }
    }

    private fun onSnapshot(snapshot: Map<String, Any?>) {
        notify(snapshot)
        if (snapshot["State"] == "RUNNING" && snapshot["Phase"] == Mode.WORKDAY.wire) {
            hud.show("MATRIX RAIN · ${(snapshot["Detail"] as? String).orEmpty().removePrefix("Workday / ").uppercase()}")
        } else {
            hud.hide()
        }
        if (snapshot["State"] != "RUNNING") {
            releaseWakeLock()
            stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        if (app.coordinator.activeRunId == null) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MatrixRain:workload")
            .apply { acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun goForeground(status: Map<String, Any?>) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(status: Map<String, Any?>) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))

    private fun buildNotification(status: Map<String, Any?>): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workload runs", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Matrix Rain: ${(status["Mode"] as? String)?.uppercase() ?: "IDLE"}")
            .setContentText(summary(status))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** `dumpsys activity service .../.service.WorkloadService` prints status JSON (Get-WorkloadStatus.ps1 analog). */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println(Json.write(app.coordinator.status()))
    }

    companion object {
        private const val TAG = "MatrixRain"
        const val ACTION_START = "com.aftersix.matrixrain.START"
        const val ACTION_STOP = "com.aftersix.matrixrain.STOP"
        const val ACTION_FAULT = "com.aftersix.matrixrain.FAULT"
        const val EXTRA_RUN_ID = "runId"
        const val EXTRA_COMMAND_ID = "commandId"
        private const val CHANNEL = "workload"
        private const val NOTIFICATION_ID = 1

        fun summary(status: Map<String, Any?>): String {
            val state = status["State"] as? String ?: "NONE"
            val phase = status["Phase"] as? String
            val index = status["PhaseIndex"]
            val count = status["PhaseCount"]
            return if (phase != null) "$state / ${phase.uppercase()} $index of $count / ${status["Detail"] ?: ""}" else state
        }

        /**
         * Starts a run from inside the app (UI or managed config). The foreground service is started first, so a
         * refused background start (Android 12+) fails before any run is registered. Throws on rejection.
         */
        fun start(context: Context, request: RunRequest, viaConfig: Boolean = false): String {
            val app = MatrixRainApp.from(context)
            check(app.managedConfig.current.enabled) { "Workloads are disabled by managed configuration." }
            if (!viaConfig) check(app.managedConfig.current.allowLocalControl) { "Local control is disabled by your administrator." }
            context.startForegroundService(Intent(context, WorkloadService::class.java).setAction(ACTION_ATTACH))
            // The service owns the wake lock and notification for the run's lifetime.
            return app.coordinator.start(request)
        }

        fun stop(context: Context, runId: String? = null) = MatrixRainApp.from(context).coordinator.stop(runId)

        private const val ACTION_ATTACH = "com.aftersix.matrixrain.ATTACH"
        private const val ACTION_TEST_CONFIG = "com.aftersix.matrixrain.TEST_CONFIG"
    }
}
