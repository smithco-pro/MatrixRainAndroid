package com.aftersix.matrixrain.spike

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/**
 * Shell control: `am broadcast -n com.aftersix.matrixrain.spike/.ShellReceiver --es cmd <cmd> ...`.
 * Guarded by android.permission.DUMP, so ordinary apps cannot send to it.
 */
class ShellReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: "noop"
        SpikeLog.event(context, "shell receiver cmd=$cmd")
        val service = SpikeService.instance
        when {
            cmd == "start" -> try {
                context.startForegroundService(Intent(context, SpikeService::class.java))
                SpikeLog.event(context, "startForegroundService from receiver: ok")
            } catch (e: Exception) {
                SpikeLog.event(context, "startForegroundService from receiver: ${e.javaClass.simpleName} ${e.message}")
            }
            service != null -> service.handle(intent)
            else -> SpikeLog.event(context, "cmd=$cmd ignored: service not running")
        }
    }
}

/** Runs in :crashlab. A crash or ANR here must not take down the service process. */
class CrashLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpikeLog.event(context, "crashlab ${intent.action}")
        when (intent.action) {
            "crash" -> throw IllegalStateException("MatrixRain synthetic crash (spike)")
            // Foreground broadcasts time out after ~10 s; blocking the main thread longer produces an ANR.
            "anr" -> Thread.sleep(25_000)
        }
    }
}

class SpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "MatrixRain spike\nControl it from adb."
            textSize = 20f
            setPadding(40, 80, 40, 40)
        })
        SpikeLog.event(this, "activity created")
    }

    override fun onResume() {
        super.onResume()
        SpikeLog.event(this, "activity resumed")
    }
}
