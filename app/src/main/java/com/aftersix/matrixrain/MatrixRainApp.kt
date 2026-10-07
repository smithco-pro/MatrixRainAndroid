package com.aftersix.matrixrain

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.aftersix.matrixrain.control.ManagedConfigController
import com.aftersix.matrixrain.core.DeviceProbe
import com.aftersix.matrixrain.core.Mode
import com.aftersix.matrixrain.core.NetworkDriver
import com.aftersix.matrixrain.fault.FaultController
import com.aftersix.matrixrain.telemetry.AndroidHttp
import com.aftersix.matrixrain.telemetry.Intel
import com.aftersix.matrixrain.telemetry.NoopIntel
import com.aftersix.matrixrain.telemetry.PrefsDataBudget
import com.aftersix.matrixrain.core.WorkdayDriver
import com.aftersix.matrixrain.core.WorkdaySites
import com.aftersix.matrixrain.workday.AndroidWorkdayActions
import com.aftersix.matrixrain.core.MemInfo
import com.aftersix.matrixrain.core.RunCoordinator
import com.aftersix.matrixrain.core.RunStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide lab state: one run store and coordinator shared by the service, UI and control paths. */
class MatrixRainApp : Application() {
    lateinit var store: RunStore
        private set
    lateinit var coordinator: RunCoordinator
        private set
    lateinit var probe: AndroidProbe
        private set
    lateinit var managedConfig: ManagedConfigController
        private set
    lateinit var faults: FaultController
        private set
    /** Intelligence SDK seam; [NoopIntel] until the SDK is added. */
    val intel: Intel = NoopIntel

    private val ready = java.util.concurrent.CountDownLatch(1)

    /**
     * Content providers can receive calls on binder threads before onCreate finishes (seen on a cold start from
     * `adb shell content read`), so they wait for initialization here.
     */
    fun awaitReady(): MatrixRainApp {
        check(ready.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Matrix Rain did not finish starting" }
        return this
    }

    /** Listeners for run.json snapshots (UI, notification). Called on the runner thread. */
    val listeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        store = RunStore(File(filesDir, "workloads"))
        probe = AndroidProbe(this)
        val workday = AndroidWorkdayActions(this)
        val drivers = mapOf(
            Mode.WORKDAY to WorkdayDriver(
                workday,
                sites = { managedConfig.current.sites.ifEmpty { WorkdaySites.DEFAULTS } },
                apps = { managedConfig.current.apps.ifEmpty { workday.defaultApps() } },
            ),
            Mode.NETWORK to NetworkDriver(
                AndroidHttp(this),
                PrefsDataBudget(this) { managedConfig.current.dailyDataBudgetMiB },
                sites = { managedConfig.current.sites.ifEmpty { WorkdaySites.DEFAULTS } },
                downloadUrl = { managedConfig.current.downloadUrl },
                allowMetered = { managedConfig.current.allowMeteredData },
            ),
        )
        coordinator = RunCoordinator(
            store, probe, deviceName(this), android.os.Process.myPid(),
            onPublish = { snapshot -> listeners.forEach { it(snapshot) } },
            allocator = SharedMemoryAllocator(),
            drivers = drivers,
            onFinished = { snapshot ->
                intel.breadcrumb("MatrixRain run ${snapshot["RunId"]} ${snapshot["Mode"]} ${snapshot["State"]}: ${snapshot["Detail"]}")
                listeners.forEach { it(snapshot) }
            },
        )
        coordinator.recover()
        faults = FaultController(this)
        managedConfig = ManagedConfigController(this)
        managedConfig.register()
        ready.countDown()
    }

    companion object {
        fun from(context: Context) = context.applicationContext as MatrixRainApp

        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}

/** Android readings for the engine's reserves and safety stops. */
class AndroidProbe(private val context: Context) : DeviceProbe {
    /** Stop when unplugged below this battery level. Managed config can change it. */
    @Volatile var minBatteryPercent = 30

    override fun memory(): MemInfo {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return MemInfo(info.totalMem, info.availMem, info.threshold)
    }

    override fun freeDiskBytes(directory: File): Long {
        val dir = generateSequence(directory) { it.parentFile }.first { it.exists() }
        return StatFs(dir.path).availableBytes
    }

    override fun safetyStop(): String? {
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return "thermal status ${thermalName(thermal)}"
        val battery = battery()
        if (!battery.charging && battery.percent in 0 until minBatteryPercent) {
            return "battery ${battery.percent}% below $minBatteryPercent% while unplugged"
        }
        return null
    }

    fun battery(): Battery {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return Battery(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = plugged,
            temperatureC = if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10.0,
        )
    }

    fun thermalStatus(): String = thermalName(context.getSystemService(PowerManager::class.java).currentThermalStatus)

    data class Battery(val percent: Int, val charging: Boolean, val temperatureC: Double?)

    private fun thermalName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }
}
