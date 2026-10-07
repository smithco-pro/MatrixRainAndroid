package com.aftersix.matrixrain.export

import android.content.Context
import android.os.Build
import com.aftersix.matrixrain.BuildConfigInfo
import com.aftersix.matrixrain.MatrixRainApp
import com.aftersix.matrixrain.core.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local troubleshooting ZIP (port of MatrixRain's Troubleshooting.cs): REPORT.txt plus run.json, results and
 * errors. Nothing is uploaded. Written to app-specific external storage so `adb pull` can collect it.
 * Same limits as the Windows exporter: 2 MiB per file, 24 MiB total.
 */
object LogExporter {
    private const val PER_FILE = 2L * 1024 * 1024
    private const val TOTAL = 24L * 1024 * 1024
    private const val KEEP_EXPORTS = 5

    fun export(context: Context): File {
        val app = MatrixRainApp.from(context)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val zip = File(dir, "matrixrain-logs-$stamp.zip")
        var total = 0L
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, bytes: ByteArray) {
                if (bytes.size > PER_FILE || total + bytes.size > TOTAL) return
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
                total += bytes.size
            }
            put("REPORT.txt", report(context).toByteArray())
            app.store.directory.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".txt")) && !it.name.endsWith(".tmp") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { put("workloads/${it.name}", it.readBytes()) }
        }
        dir.listFiles()?.filter { it.name.startsWith("matrixrain-logs-") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_EXPORTS)?.forEach { it.delete() }
        return zip
    }

    private fun report(context: Context): String {
        val app = MatrixRainApp.from(context)
        val battery = app.probe.battery()
        val mem = app.probe.memory()
        return buildString {
            appendLine("Matrix Rain for Android ${BuildConfigInfo.versionName(context)} (${BuildConfigInfo.versionCode(context)})")
            appendLine("Generated  ${Instant.now()}")
            appendLine("Device     ${MatrixRainApp.deviceName(context)}")
            appendLine("Model      ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android    ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
            appendLine("Battery    ${battery.percent}% charging=${battery.charging} temp=${battery.temperatureC}")
            appendLine("Thermal    ${app.probe.thermalStatus()}")
            appendLine("Memory     total=${mem.totalBytes} available=${mem.availableBytes} threshold=${mem.lowMemoryThresholdBytes}")
            appendLine("Data       ${app.store.directory.absolutePath}")
            appendLine()
            appendLine("Status")
            appendLine(Json.write(app.coordinator.status()))
        }
    }
}
