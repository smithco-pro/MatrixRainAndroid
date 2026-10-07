package com.aftersix.matrixrain.core

/**
 * UEM managed configuration (Android app restrictions), the admin channel that replaces MatrixRain's UAC gate and
 * Freestyle Node scripts. A run is triggered by changing `commandId` (a nonce) with `command=start` or `stop`;
 * the same commandId is never acted on twice, so re-pushing an unchanged profile is harmless.
 */
data class ManagedConfig(
    val enabled: Boolean = true,
    val allowLocalControl: Boolean = true,
    val labTitle: String? = null,
    val minBatteryPercent: Int = 30,
    val command: String = "none",
    val commandId: String = "",
    val request: Map<String, Any?> = emptyMap(),
    /** Workday rotation. Empty means the built-in sites / the installed apps from the default list. */
    val sites: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    /** Network mode: bulk-download source (HTTPS, admin-owned), daily allowance, and whether metered networks may be used. */
    val downloadUrl: String? = null,
    val dailyDataBudgetMiB: Int = 512,
    val allowMeteredData: Boolean = false,
    /** Fault to raise when command=fault. */
    val fault: Fault? = null,
) {
    companion object {
        val COMMANDS = setOf("none", "start", "stop", "fault")
        private val SETTINGS = setOf(
            "enabled", "allowLocalControl", "labTitle", "minBatteryPercent", "command", "commandId", "sites", "apps",
            "downloadUrl", "dailyDataBudgetMiB", "allowMeteredData", "fault",
        )

        /**
         * Parses restrictions. Run keys are passed through to [RunRequest.fromMap] when a start is executed;
         * unset run keys (empty strings or -1 integers, the "not configured" values UEM consoles commonly send) are dropped.
         */
        fun parse(values: Map<String, Any?>): ManagedConfig {
            val unknown = values.keys - SETTINGS - RunRequest.KEYS
            require(unknown.isEmpty()) { "Unknown managed config key: ${unknown.sorted().joinToString()}" }
            val command = (values["command"] as? String)?.trim()?.lowercase()?.ifEmpty { null } ?: "none"
            require(command in COMMANDS) { "command must be one of ${COMMANDS.joinToString()}" }
            val title = (values["labTitle"] as? String)?.trim()?.ifEmpty { null }
            require(title == null || title.length <= 48) { "labTitle must be at most 48 characters." }
            val battery = (values["minBatteryPercent"] as? Number)?.toInt() ?: 30
            require(battery in 0..90) { "minBatteryPercent must be 0..90." }
            val commandId = (values["commandId"] as? String)?.trim().orEmpty()
            require(commandId.length <= 64 && commandId.all { it.isLetterOrDigit() || it in "-_." }) {
                "commandId must be up to 64 letters, digits, '-', '_' or '.'."
            }
            val request = values.filterKeys { it in RunRequest.KEYS }.filterValues { v ->
                !(v == null || (v is String && v.isBlank()) || (v is Number && v.toInt() < 0))
            }
            val sites = WorkdaySites.parse(values["sites"] as? String).let { if (it.isEmpty()) it else WorkdaySites.validate(it) }
            val apps = WorkdaySites.validatePackages(WorkdaySites.parse(values["apps"] as? String))
            val downloadUrl = (values["downloadUrl"] as? String)?.trim()?.ifEmpty { null }?.also { NetworkDriver.validateDownload(it) }
            val budget = (values["dailyDataBudgetMiB"] as? Number)?.toInt()?.takeIf { it >= 0 } ?: 512
            require(budget <= 10_240) { "dailyDataBudgetMiB must be 0..10240." }
            val fault = (values["fault"] as? String)?.trim()?.ifEmpty { null }?.let { Fault.parse(it) }
            return ManagedConfig(
                enabled = values["enabled"] as? Boolean ?: true,
                allowLocalControl = values["allowLocalControl"] as? Boolean ?: true,
                labTitle = title,
                minBatteryPercent = battery,
                command = command,
                commandId = commandId,
                request = request,
                sites = sites,
                apps = apps,
                downloadUrl = downloadUrl,
                dailyDataBudgetMiB = budget,
                allowMeteredData = values["allowMeteredData"] as? Boolean ?: false,
                fault = fault,
            )
        }
    }
}

sealed interface ConfigAction {
    data object None : ConfigAction
    data class Start(val request: RunRequest, val commandId: String) : ConfigAction
    data class Stop(val commandId: String?) : ConfigAction
    data class RaiseFault(val fault: Fault, val commandId: String) : ConfigAction
    data class Reject(val commandId: String, val reason: String) : ConfigAction
}

/** Decides what a configuration means given the last commandId already handled. Pure, so it is unit-tested. */
object ConfigPolicy {
    fun decide(config: ManagedConfig, lastHandledCommandId: String?, running: Boolean): ConfigAction {
        // The kill switch always wins: stop whatever is running, whatever the command.
        if (!config.enabled) return if (running) ConfigAction.Stop(null) else ConfigAction.None
        if (config.commandId.isEmpty() || config.commandId == lastHandledCommandId) return ConfigAction.None
        return when (config.command) {
            "start" -> try {
                ConfigAction.Start(RunRequest.fromMap(config.request), config.commandId)
            } catch (e: IllegalArgumentException) {
                ConfigAction.Reject(config.commandId, e.message ?: "invalid request")
            }
            "stop" -> ConfigAction.Stop(config.commandId)
            "fault" -> config.fault?.let { ConfigAction.RaiseFault(it, config.commandId) }
                ?: ConfigAction.Reject(config.commandId, "command=fault needs fault set to crash, anr, native or handled")
            else -> ConfigAction.None
        }
    }
}
