package com.aftersix.matrixrain.workday

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import com.aftersix.matrixrain.core.WorkdayActions
import com.aftersix.matrixrain.core.WorkdayContext
import com.aftersix.matrixrain.core.WorkdayPlan
import com.aftersix.matrixrain.core.WorkdayStep
import com.aftersix.matrixrain.ui.MainActivity
import java.io.File
import java.io.FileNotFoundException
import kotlin.random.Random

/**
 * Workday actions on Android: launch-and-dwell only. Apps and pages are brought to the foreground with intents and
 * left there until the next action; nothing is typed or tapped inside other apps.
 *
 * Bringing another app forward from the background needs "Display over other apps" (verified on Android 14 and 17:
 * BAL_ALLOW_SAW_PERMISSION). A person taking over is detected with usage access when granted; without it, only
 * screen-off and keyguard pauses apply.
 */
class AndroidWorkdayActions(private val context: Context) : WorkdayActions {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    @Volatile private var expectedPackage: String? = null
    @Volatile private var lastLaunchAt = 0L
    @Volatile private var pausedUntil = 0L

    override fun prerequisites(): String? {
        if (!Settings.canDrawOverlays(context)) {
            return "allow 'Display over other apps' for Matrix Rain so it can bring apps to the foreground " +
                "(adb: appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow)"
        }
        return null
    }

    override fun pauseReason(): String? {
        if (!power.isInteractive) return "screen off"
        if (keyguard.isKeyguardLocked) return "device locked"
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return "person using the device"
        takeover()?.let {
            pausedUntil = now + TAKEOVER_PAUSE_MS
            return "person using the device ($it)"
        }
        return null
    }

    /** The newest resumed app, if it is neither the one we brought forward nor ours. Needs usage access. */
    private fun takeover(): String? {
        val expected = expectedPackage ?: return null
        if (SystemClock.elapsedRealtime() - lastLaunchAt < 5_000 || !hasUsageAccess()) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - 30_000, end)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.getNextEvent(event)) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        return latest?.takeIf { it != expected && it != context.packageName && it !in IGNORED_FOREGROUND }
    }

    @Suppress("DEPRECATION") // unsafeCheckOpNoThrow is the API 29+ call; its replacement needs API 36.
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    override fun perform(step: WorkdayStep, context: WorkdayContext): String {
        val files = Files(this.context, context.runId, context.seed)
        return when (step.action) {
            WorkdayPlan.PREPARE -> "${files.create("txt").name}, ${files.create("csv").name}; apps: ${context.apps?.let { "configured" } ?: "none"}"
            WorkdayPlan.BROWSE -> browse(context.sites.next())
            WorkdayPlan.OPEN_APP -> openApp(context.apps?.next() ?: throw IllegalStateException("no apps to rotate"))
            WorkdayPlan.CREATE_TEXT -> files.createOrEdit("txt")
            WorkdayPlan.CREATE_CSV -> files.createOrEdit("csv")
            WorkdayPlan.EDIT_FILE -> files.edit()
            WorkdayPlan.OPEN_FILE -> openFile(files.pick(), context.seed)
            WorkdayPlan.RETURN_HOME, WorkdayPlan.BREAK -> returnHome()
            // Closing other apps needs the shell; the adb harness can do it with `am force-stop`.
            WorkdayPlan.RESTART_APPS -> "skipped on device (needs adb)"
            else -> throw IllegalArgumentException("unknown action ${step.action}")
        }
    }

    override fun finish(context: WorkdayContext) {
        runCatching { returnHome() }
    }

    private fun browse(url: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val chrome = CHROME.takeIf { launchable(it) }
        if (chrome != null) intent.setPackage(chrome)
        return "${launch(intent)} $url"
    }

    private fun openApp(pkg: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("$pkg is not installed or not launchable")
        return launch(intent)
    }

    private fun openFile(file: File, seed: Int): String {
        val uri = WorkdayFileProvider.uri(context, file)
        val type = if (file.extension == "csv") "text/csv" else "text/plain"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Pick a viewer ourselves: with several and no default, Android would show its chooser instead of the file.
        val viewers = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct().sorted()
        if (viewers.isEmpty()) throw IllegalStateException("no installed app opens $type")
        intent.setPackage(viewers[Math.floorMod(seed + file.name.hashCode(), viewers.size)])
        return "${launch(intent)} ${file.name}"
    }

    private fun returnHome(): String =
        launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    private fun launch(intent: Intent): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = intent.resolveActivity(context.packageManager)?.packageName ?: throw IllegalStateException("nothing handles ${intent.action}")
        context.startActivity(intent)
        expectedPackage = target
        lastLaunchAt = SystemClock.elapsedRealtime()
        return target
    }

    private fun launchable(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Default rotation when managed config lists no apps: the installed, launchable entries from [DEFAULT_APPS]. */
    fun defaultApps(): List<String> = DEFAULT_APPS.filter { it != context.packageName && launchable(it) }

    /** Run-owned TXT/CSV files: at most 12 per type and 64 MiB in total, smaller than MatrixRain's desktop files. */
    private class Files(context: Context, runId: String, seed: Int) {
        private val dir = WorkdayFileProvider.root(context).resolve(runId).apply { mkdirs() }
        private val random = Random(seed xor System.nanoTime().toInt())

        fun create(ext: String): File {
            val existing = list(ext)
            check(existing.size < MAX_PER_TYPE) { "file limit reached" }
            check(totalBytes() < BUDGET_BYTES) { "storage budget reached" }
            val file = dir.resolve("workday-%02d.$ext".format(existing.size + 1))
            file.writeText(content(ext, SIZES_KIB.random(random) * 1024))
            return file
        }

        fun createOrEdit(ext: String): String =
            if (list(ext).size < MAX_PER_TYPE && totalBytes() < BUDGET_BYTES) "created ${create(ext).name}" else edit(ext)

        fun edit(ext: String? = null): String {
            val file = (if (ext != null) list(ext) else list("txt") + list("csv")).randomOrNull(random) ?: return "created ${create(ext ?: "txt").name}"
            check(totalBytes() < BUDGET_BYTES) { "storage budget reached" }
            file.appendText(content(file.extension, 4 * 1024))
            return "edited ${file.name} (${file.length() / 1024} KiB)"
        }

        fun pick(): File = (list("txt") + list("csv")).randomOrNull(random) ?: create("txt")

        private fun list(ext: String) = dir.listFiles { f -> f.extension == ext }.orEmpty().toList()
        private fun totalBytes() = dir.listFiles().orEmpty().sumOf { it.length() }

        private fun content(ext: String, bytes: Int): String = buildString {
            if (ext == "csv") {
                appendLine("date,region,item,units,price,total")
                // Plain concatenation: String.format per row took seconds for 512 KiB on a TC26.
                while (length < bytes) {
                    val units = random.nextInt(1, 500)
                    val cents = random.nextInt(100, 10_000)
                    val month = random.nextInt(1, 13)
                    val day = random.nextInt(1, 29)
                    append("2026-").append(if (month < 10) "0" else "").append(month).append('-').append(if (day < 10) "0" else "").append(day)
                    append(',').append(REGIONS.random(random)).append(',').append(ITEMS.random(random)).append(',').append(units)
                    append(',').append(cents / 100).append('.').append(cents % 100 / 10).append(cents % 10)
                    val total = units.toLong() * cents
                    append(',').append(total / 100).append('.').append(total % 100 / 10).append(total % 10).append('\n')
                }
            } else {
                appendLine("WORKING NOTES")
                while (length < bytes) appendLine(PARAGRAPHS.random(random))
            }
        }

        companion object {
            const val MAX_PER_TYPE = 12
            const val BUDGET_BYTES = 64L * 1024 * 1024
            val SIZES_KIB = listOf(4, 32, 128, 512)
            val REGIONS = listOf("North", "South", "East", "West", "Central")
            val ITEMS = listOf("Scanner", "Labels", "Batteries", "Gloves", "Cartons", "Tablets")
            // Same tone as MatrixRain's Outlook draft text.
            val PARAGRAPHS = listOf(
                "I reviewed the latest project materials and noted the items that need follow-up before the next check-in.",
                "The working files have been updated with the current assumptions, dates, and open decisions for the team.",
                "Please use this draft as a reference while we compare the schedule with the remaining work and available resources.",
                "I will consolidate the requested changes after the next review and save the revised documents in the project folder.",
            )
        }
    }

    companion object {
        private const val TAKEOVER_PAUSE_MS = 15 * 60 * 1000L
        private const val CHROME = "com.android.chrome"
        private val IGNORED_FOREGROUND = setOf("com.android.systemui", "android")

        /** Productivity-style apps commonly found on lab devices. Agents, security and settings apps are left out. */
        val DEFAULT_APPS = listOf(
            "com.android.chrome", "com.microsoft.teams", "com.Slack", "com.microsoft.office.outlook",
            "com.microsoft.office.officehubrow", "com.google.android.gm", "com.google.android.calendar",
            "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
            "com.google.android.keep", "com.google.android.apps.translate", "com.google.android.apps.magazines",
            "com.google.android.youtube", "org.videolan.vlc", "com.omnissa.horizon.client.android",
        )
    }
}

/**
 * Serves Workday files to the viewer app opened for them (a minimal FileProvider without AndroidX).
 * Not exported; access is only through per-intent read grants.
 */
class WorkdayFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val root = root(context!!).canonicalFile
        val file = File(root, uri.path.orEmpty().removePrefix("/")).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(file.name, file.length())) }
    }

    override fun getType(uri: Uri) = if (uri.path.orEmpty().endsWith(".csv")) "text/csv" else "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun root(context: Context) = File(context.filesDir, "workday")

        fun uri(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.files")
                .path(file.canonicalPath.removePrefix(root(context).canonicalPath)).build()
    }
}
