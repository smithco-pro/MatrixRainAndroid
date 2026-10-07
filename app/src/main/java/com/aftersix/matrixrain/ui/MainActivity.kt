package com.aftersix.matrixrain.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.aftersix.matrixrain.BuildConfigInfo
import com.aftersix.matrixrain.MatrixRainApp
import com.aftersix.matrixrain.core.Mode
import com.aftersix.matrixrain.core.Rotation
import com.aftersix.matrixrain.core.RunRequest
import com.aftersix.matrixrain.export.LogExporter
import com.aftersix.matrixrain.service.WorkloadService
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * The Matrix Rain display (port of MatrixRain's Display.cs): rain, a header with device readings, the run
 * status block, and Go/Stop/Settings/Last result/Export controls. Keeps the screen on while visible.
 */
class MainActivity : Activity() {
    private val app get() = MatrixRainApp.from(this)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: LocalSettings
    private lateinit var rain: RainView
    private lateinit var header: TextView
    private lateinit var readings: TextView
    private lateinit var state: TextView
    private lateinit var next: TextView
    private lateinit var progress: ProgressBar
    private lateinit var times: TextView
    private lateinit var message: TextView
    private lateinit var last: TextView
    private lateinit var modePicker: Spinner
    private var rainOverride: Boolean? = null
    private var blink = false
    private var nextRandom: Mode? = null
    private var lastSample = 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LocalSettings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun buildLayout(): View {
        rain = RainView(this).apply {
            // A double tap toggles rain, like Space in the Windows display.
            setOnClickListener(object : View.OnClickListener {
                var lastTap = 0L
                override fun onClick(v: View) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTap < 400) rainOverride = !(rainOverride ?: rainEnabled)
                    lastTap = now
                    render()
                }
            })
        }
        header = label(16f, bold = true)
        readings = label(12f)
        state = label(18f, bold = true)
        next = label(12f)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(GREEN)
        }
        times = label(12f)
        message = label(12f)
        last = label(12f)

        modePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, MODES.map { it.first })
            setSelection(MODES.indexOfFirst { it.second == settings.selection }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    settings.selection = MODES[position].second
                    render()
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modePicker)
            addView(button("Go") { go() })
            addView(button("Stop") { stop() })
            addView(button("Settings") { showSettings() })
            addView(button("Last result") { showLastResult() })
            addView(button("Export logs") { exportLogs() })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            listOf(header, readings, state, next, progress, times, message, last).forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            addView(HorizontalScrollView(this@MainActivity).apply { addView(controls) })
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(rain, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun label(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextColor(GREEN)
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // ---- Control ----

    private fun selectedRequest(): RunRequest {
        val mode = when (val s = settings.selection) {
            RANDOM -> nextRandom ?: Rotation(SystemClock.uptimeMillis().toInt(), settings.lastRandom).next()
            else -> Mode.parse(s)
        }
        return settings.request(mode)
    }

    private fun go() {
        if (!settings.allowLocalControl) {
            toast("Local control is disabled by your administrator.")
            return
        }
        try {
            val request = selectedRequest()
            WorkloadService.start(this, request)
            if (settings.selection == RANDOM) {
                settings.lastRandom = request.mode
                nextRandom = null
            }
            rainOverride = null
        } catch (e: Exception) {
            toast(e.message ?: "Could not start the workload.")
        }
        render()
    }

    private fun stop() {
        if (!settings.allowLocalControl) {
            toast("Local control is disabled by your administrator.")
            return
        }
        toast("Stop: ${WorkloadService.stop(this).name.lowercase().replace('_', ' ')}")
    }

    // ---- Rendering ----

    private fun render() {
        val status = app.coordinator.status()
        val now = Instant.now()
        val runState = status["State"] as? String ?: "NONE"
        val running = runState == "RUNNING"
        val stale = status["Stale"] == true
        blink = !blink

        header.text = "${settings.labTitle} · ${MatrixRainApp.deviceName(this)} · v${BuildConfigInfo.versionName(this)}"
        if (SystemClock.elapsedRealtime() - lastSample > settings.sampleSeconds * 1000L || readings.text.isEmpty()) {
            readings.text = sampleReadings()
            lastSample = SystemClock.elapsedRealtime()
        }

        val dot = if (running && blink) "●" else "○"
        state.text = when {
            stale -> "$dot HEARTBEAT STALE"
            running -> "$dot ${phaseLine(status)}"
            runState == "NONE" -> "○ READY"
            else -> "○ $runState / ${(status["Mode"] as? String)?.uppercase()}"
        }
        state.setTextColor(if (stale || runState == "FAILED") Color.rgb(255, 120, 80) else GREEN)

        if (settings.selection == RANDOM && nextRandom == null) {
            nextRandom = Rotation(SystemClock.uptimeMillis().toInt(), settings.lastRandom).next()
        }
        next.text = "NEXT: " + if (settings.selection == RANDOM) "${nextRandom?.wire?.uppercase()} (random)" else settings.selection.uppercase()

        val phaseSeconds = (status["PhaseSeconds"] as? Number)?.toDouble() ?: 0.0
        val planned = (status["PlannedSeconds"] as? Number)?.toDouble() ?: 0.0
        val age = if (running && !stale) ((status["HeartbeatAgeSeconds"] as? Number)?.toDouble() ?: 0.0).coerceAtLeast(0.0) else 0.0
        val phaseElapsed = ((status["PhaseElapsedSeconds"] as? Number)?.toDouble() ?: 0.0) + age
        val elapsed = ((status["ElapsedSeconds"] as? Number)?.toDouble() ?: 0.0) + age
        progress.progress = when {
            runState == "COMPLETED" -> 1000
            phaseSeconds > 0 -> (1000 * min(1.0, phaseElapsed / phaseSeconds)).toInt()
            else -> 0
        }
        times.text = if (running) {
            "PHASE ${clock(phaseElapsed)} · LEFT ${clock(max(0.0, phaseSeconds - phaseElapsed))} · TOTAL LEFT ${clock(max(0.0, planned - elapsed))}"
        } else {
            ""
        }
        message.text = (status["Detail"] as? String).orEmpty()
        last.text = app.store.readLastResult()?.let { "LAST: ${it["Mode"]} / ${it["State"]} / ${it["Detail"]} · ${shortTime(it["UpdatedUtc"], now)}" } ?: "LAST: none"

        rain.rainEnabled = rainOverride ?: if (running) status["RainEnabled"] != false else true
    }

    private fun phaseLine(status: Map<String, Any?>): String {
        val phase = (status["Phase"] as? String)?.uppercase()
        val mode = (status["Mode"] as? String)?.uppercase()
        return "${phase} ${status["PhaseIndex"]}/${status["PhaseCount"]}" + if (phase != mode) " > $mode" else ""
    }

    private fun sampleReadings(): String {
        val mem = app.probe.memory()
        val battery = app.probe.battery()
        val gib = 1024.0 * 1024 * 1024
        val cpuSeconds = Process.getElapsedCpuTime() / 1000.0
        val uptime = Duration.ofMillis(SystemClock.elapsedRealtime())
        return "RAM %.1f/%.1f GiB · BAT %d%%%s%s · THERMAL %s · NET %s\nUPTIME %dd %02dh %02dm · APP CPU %.0fs".format(
            (mem.totalBytes - mem.availableBytes) / gib, mem.totalBytes / gib,
            battery.percent, if (battery.charging) "+" else "", battery.temperatureC?.let { " %.0f°C".format(it) } ?: "",
            app.probe.thermalStatus().uppercase(), network(),
            uptime.toDays(), uptime.toHours() % 24, uptime.toMinutes() % 60, cpuSeconds,
        )
    }

    private fun network(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "OFFLINE"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
            else -> "OTHER"
        }
        val signal = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }?.let { " ${it}dBm" } ?: ""
        return kind + signal
    }

    // ---- Dialogs ----

    private fun showSettings() {
        val fields = listOf(
            Triple("Duration (s)", settings.seconds, "seconds"),
            Triple("Baseline (s)", settings.baselineSeconds, "baselineSeconds"),
            Triple("Intensity (%)", settings.intensity, "intensity"),
            Triple("Threads", settings.threads, "threads"),
            Triple("Memory (MiB)", settings.memoryMiB, "memoryMiB"),
            Triple("Scratch file (MiB)", settings.diskMiB, "diskMiB"),
            Triple("Write budget (MiB)", settings.writeLimitMiB, "writeLimitMiB"),
            Triple("Disk rate ceiling (MiB/s)", settings.diskMiBPerSecond, "diskMiBPerSecond"),
        )
        val inputs = mutableMapOf<String, EditText>()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            fields.forEach { (label, value, key) ->
                addView(TextView(this@MainActivity).apply { text = label })
                addView(EditText(this@MainActivity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(value.toString())
                    inputs[key] = this
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Workload settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                try {
                    val values = inputs.mapValues { it.value.text.toString() } + ("mode" to Mode.CPU.wire)
                    settings.save(RunRequest.fromMap(values))
                    toast("Saved")
                } catch (e: Exception) {
                    toast(e.message ?: "Invalid settings")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLastResult() {
        val result = app.store.readLastResult()
        val text = if (result == null) "No completed runs yet." else LastResultText.format(result)
        AlertDialog.Builder(this)
            .setTitle("Last result")
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    this.text = text
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(48, 24, 48, 24)
                })
            })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun exportLogs() {
        try {
            val file = LogExporter.export(this)
            AlertDialog.Builder(this)
                .setTitle("Logs exported")
                .setMessage("Saved to:\n${file.absolutePath}\n\nPull with:\nadb pull ${file.absolutePath}")
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private val GREEN = Color.rgb(0, 230, 70)
        const val RANDOM = "random"
        val MODES = listOf(
            "Random rotation" to RANDOM, "Baseline" to Mode.BASELINE.wire, "Visualization" to Mode.VISUALIZATION.wire,
            "CPU" to Mode.CPU.wire, "Memory" to Mode.MEMORY.wire, "Disk I/O" to Mode.DISK.wire, "Science / pi" to Mode.SCIENCE.wire,
            "Workday (apps + sites)" to Mode.WORKDAY.wire, "Network probes" to Mode.NETWORK.wire,
        )

        fun clock(seconds: Double): String {
            val s = seconds.toLong()
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        }

        fun shortTime(utc: Any?, now: Instant): String {
            val t = (utc as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
            val ago = Duration.between(t, now)
            return when {
                ago.toMinutes() < 1 -> "just now"
                ago.toHours() < 1 -> "${ago.toMinutes()} min ago"
                ago.toDays() < 1 -> "${ago.toHours()} h ago"
                else -> "${ago.toDays()} d ago"
            }
        }
    }
}

/** UI defaults for local Go, stored per device. MatrixRain's display defaults: 600 s with a 60 s baseline. */
class LocalSettings(private val context: Context) {
    private val managed get() = MatrixRainApp.from(context).managedConfig.current
    private val prefs = context.getSharedPreferences("local-settings", Context.MODE_PRIVATE)

    var selection: String
        get() = prefs.getString("selection", MainActivity.RANDOM)!!
        set(value) = prefs.edit().putString("selection", value).apply()
    var lastRandom: Mode?
        get() = prefs.getString("lastRandom", null)?.let { runCatching { Mode.parse(it) }.getOrNull() }
        set(value) = prefs.edit().putString("lastRandom", value?.wire).apply()

    val seconds get() = prefs.getInt("seconds", 600)
    val baselineSeconds get() = prefs.getInt("baselineSeconds", 60)
    val intensity get() = prefs.getInt("intensity", 50)
    val threads get() = prefs.getInt("threads", 2)
    val memoryMiB get() = prefs.getInt("memoryMiB", 512)
    val diskMiB get() = prefs.getInt("diskMiB", 64)
    val writeLimitMiB get() = prefs.getInt("writeLimitMiB", 1024)
    val diskMiBPerSecond get() = prefs.getInt("diskMiBPerSecond", 10)
    // Workday defaults to 6 hours, like MatrixRain's Office workday.
    val workdaySeconds get() = prefs.getInt("workdaySeconds", com.aftersix.matrixrain.core.WorkdayPlan.DEFAULT_SECONDS)

    // Display settings; managed config will override these.
    val labTitle get() = managed.labTitle ?: prefs.getString("labTitle", "AFTER SIX LAB")!!
    val sampleSeconds get() = prefs.getInt("sampleSeconds", 5)
    val allowLocalControl get() = managed.enabled && managed.allowLocalControl

    fun request(mode: Mode) = RunRequest(
        mode = mode, seconds = if (mode == Mode.WORKDAY) workdaySeconds else seconds, baselineSeconds = baselineSeconds, intensity = intensity, threads = threads,
        memoryMiB = memoryMiB, diskMiB = diskMiB, writeLimitMiB = writeLimitMiB, diskMiBPerSecond = diskMiBPerSecond,
    ).validated()

    fun save(r: RunRequest) = prefs.edit()
        .putInt("seconds", r.seconds).putInt("baselineSeconds", r.baselineSeconds).putInt("intensity", r.intensity)
        .putInt("threads", r.threads).putInt("memoryMiB", r.memoryMiB).putInt("diskMiB", r.diskMiB)
        .putInt("writeLimitMiB", r.writeLimitMiB).putInt("diskMiBPerSecond", r.diskMiBPerSecond)
        .apply()
}

/** Plain-text rendering of a result snapshot for the Last result dialog. */
object LastResultText {
    @Suppress("UNCHECKED_CAST")
    fun format(run: Map<String, Any?>): String = buildString {
        appendLine("Run      ${run["RunId"]}")
        appendLine("Device   ${run["Hostname"]}")
        appendLine("Mode     ${run["Mode"]}")
        appendLine("State    ${run["State"]}")
        appendLine("Detail   ${run["Detail"]}")
        appendLine("Started  ${run["StartedUtc"]}")
        appendLine("Updated  ${run["UpdatedUtc"]}")
        appendLine()
        (run["Phases"] as? List<Map<String, Any?>>).orEmpty().forEach { p ->
            appendLine("Phase ${p["Phase"]}: ${p["StartedUtc"]} → ${p["EndedUtc"]}")
            (p["Result"] as? Map<String, Any?>)?.let { r ->
                appendLine("  reason ${r["Reason"]}, %.1f s".format((r["ElapsedSeconds"] as? Number)?.toDouble() ?: 0.0))
                (r["BytesWritten"] as? Number)?.takeIf { it.toLong() > 0 }?.let { appendLine("  written ${it.toLong() / 1048576} MiB, read ${((r["BytesRead"] as? Number)?.toLong() ?: 0) / 1048576} MiB") }
                (r["AllocatedMiB"] as? Number)?.takeIf { it.toInt() > 0 }?.let { appendLine("  allocated $it MiB") }
                (r["Pi"] as? Number)?.let { appendLine("  pi ≈ %.6f".format(it.toDouble())) }
            }
            p["Error"]?.let { appendLine("  error $it") }
        }
    }
}
