package com.aftersix.matrixrain.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import java.time.Instant

/**
 * Phase 0 spike service. Answers:
 *  (a) does a specialUse foreground service keep running (heartbeat gaps show throttling)?
 *  (b) can it launch other apps' activities from the background, with and without an overlay?
 *  (e) do crashes/ANRs in the :crashlab process land in dropbox while this process survives?
 */
class SpikeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var startedElapsed = 0L
    private var beats = 0
    private var maxBeatGapMs = 0L
    private var lastBeatElapsed = 0L
    private var overlay: View? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (lastBeatElapsed != 0L) maxBeatGapMs = maxOf(maxBeatGapMs, now - lastBeatElapsed)
            lastBeatElapsed = now
            beats++
            if (beats % 6 == 0) SpikeLog.event(this@SpikeService, "beat $beats maxGapMs=$maxBeatGapMs")
            (overlay as? TextView)?.text = "MR spike ${beats}"
            handler.postDelayed(this, BEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startedElapsed = SystemClock.elapsedRealtime()
        goForeground()
        SpikeLog.event(this, "service created sdk=${Build.VERSION.SDK_INT}")
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handle(it) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        wakeLock?.takeIf { it.isHeld }?.release()
        SpikeLog.event(this, "service destroyed beats=$beats")
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun handle(intent: Intent) {
        when (val cmd = intent.getStringExtra("cmd") ?: "noop") {
            "noop" -> Unit
            "stop" -> stopSelf()
            "overlay" -> if (intent.getStringExtra("state") == "off") removeOverlay() else showOverlay()
            "wakelock" -> setWakeLock(intent.getStringExtra("state") != "off")
            "rotate" -> rotate(
                intent.getStringExtra("steps").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                intent.getIntExtra("dwell", 30).coerceIn(5, 600) * 1000L,
            )
            "crash", "anr" -> sendBroadcast(Intent(this, CrashLabReceiver::class.java).setAction(cmd).apply {
                if (cmd == "anr") addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            else -> SpikeLog.event(this, "unknown cmd $cmd")
        }
    }

    /** Steps: "self", "pkg:<package>", "url:<https url>". Each runs after the previous dwell. */
    private fun rotate(steps: List<String>, dwellMs: Long) {
        steps.forEachIndexed { i, step ->
            handler.postDelayed({ launch(step) }, i * dwellMs)
        }
        SpikeLog.event(this, "rotate scheduled ${steps.size} steps dwellMs=$dwellMs overlay=${overlay != null}")
    }

    private fun launch(step: String) {
        val intent = when {
            step == "self" -> Intent(this, SpikeActivity::class.java)
            step.startsWith("pkg:") -> packageManager.getLaunchIntentForPackage(step.removePrefix("pkg:"))
            step.startsWith("url:") -> Intent(Intent.ACTION_VIEW, Uri.parse(step.removePrefix("url:")))
            else -> null
        }
        if (intent == null) {
            SpikeLog.event(this, "launch $step: no intent")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            SpikeLog.event(this, "launch $step: startActivity returned (BAL result only visible in system log)")
        } catch (e: Exception) {
            SpikeLog.event(this, "launch $step: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            SpikeLog.event(this, "overlay refused: no SYSTEM_ALERT_WINDOW")
            return
        }
        val view = TextView(this).apply {
            text = "MR spike"
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(12, 6, 12, 6)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            alpha = 0.8f
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
        SpikeLog.event(this, "overlay shown")
    }

    private fun removeOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        if (overlay != null) SpikeLog.event(this, "overlay removed")
        overlay = null
    }

    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MatrixRainSpike:cpu").apply { acquire(2 * 60 * 60 * 1000L) }
        } else if (!on) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
        SpikeLog.event(this, "wakelock ${if (on) "on" else "off"}")
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Spike", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("MatrixRain spike running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    /** `adb shell dumpsys activity service com.aftersix.matrixrain.spike/.SpikeService` */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        val upMs = SystemClock.elapsedRealtime() - startedElapsed
        writer.println("""{"now":"${Instant.now()}","uptimeSec":${upMs / 1000},"beats":$beats,"expectedBeats":${upMs / BEAT_MS},"maxBeatGapMs":$maxBeatGapMs,"overlay":${overlay != null},"canDrawOverlays":${Settings.canDrawOverlays(this)},"wakeLock":${wakeLock?.isHeld == true}}""")
        SpikeLog.tail(this, 40).forEach { writer.println(it) }
    }

    companion object {
        const val CHANNEL = "spike"
        const val BEAT_MS = 10_000L
        @Volatile var instance: SpikeService? = null

        fun component(context: Context) = ComponentName(context, SpikeService::class.java)
    }
}

object SpikeLog {
    private const val TAG = "MRSpike"

    fun event(context: Context, message: String) {
        val line = "${Instant.now()} pid=${android.os.Process.myPid()} $message"
        Log.i(TAG, line)
        synchronized(this) { File(context.filesDir, "spike-events.log").appendText(line + "\n") }
    }

    fun tail(context: Context, n: Int): List<String> =
        File(context.filesDir, "spike-events.log").takeIf { it.exists() }?.readLines()?.takeLast(n).orEmpty()
}
