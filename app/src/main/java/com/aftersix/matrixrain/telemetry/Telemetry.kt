package com.aftersix.matrixrain.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.aftersix.matrixrain.core.DataBudget
import com.aftersix.matrixrain.core.HttpProbe
import com.aftersix.matrixrain.core.ProbeResult
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Seam for the Omnissa Intelligence SDK. This build ships [NoopIntel]; an SDK-backed implementation maps these calls
 * to the SDK's breadcrumb, handled-exception and user-flow APIs once the SDK artifact and an app ID are available.
 * Run IDs go into breadcrumbs so Intelligence events can be matched to run.json phases.
 */
interface Intel {
    fun breadcrumb(text: String)
    fun handledException(error: Throwable)
    fun beginFlow(name: String) = Unit
    fun endFlow(name: String, succeeded: Boolean) = Unit
}

object NoopIntel : Intel {
    override fun breadcrumb(text: String) {
        Log.i("MatrixRain", "breadcrumb: $text")
    }

    override fun handledException(error: Throwable) = Unit
}

/** HttpURLConnection probes. Stock HttpURLConnection is what network-instrumenting SDKs hook. */
class AndroidHttp(private val context: Context) : HttpProbe {
    override fun get(url: String, maxBytes: Long): ProbeResult {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MatrixRain-Android/0.1 (DEX lab workload)")
            }
            val status = connection.responseCode
            var read = 0L
            (if (status < 400) connection.inputStream else connection.errorStream)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (read < maxBytes) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - read).toInt())
                    if (n < 0) break
                    read += n
                }
            }
            ProbeResult(status in 200..399, (System.nanoTime() - started) / 1_000_000, read, status)
        } catch (e: Exception) {
            ProbeResult(false, (System.nanoTime() - started) / 1_000_000, 0, error = e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    override fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
}

/** Daily download allowance, reset at local midnight, shared by all network runs. */
class PrefsDataBudget(context: Context, private val dailyMiB: () -> Int) : DataBudget {
    private val prefs = context.getSharedPreferences("data-budget", Context.MODE_PRIVATE)

    private fun spentToday(): Long =
        if (prefs.getString("day", null) == LocalDate.now().toString()) prefs.getLong("spent", 0) else 0

    @Synchronized
    override fun remainingBytes() = maxOf(0L, dailyMiB() * 1_048_576L - spentToday())

    @Synchronized
    override fun spend(bytes: Long) {
        prefs.edit().putString("day", LocalDate.now().toString()).putLong("spent", spentToday() + bytes).apply()
    }
}
