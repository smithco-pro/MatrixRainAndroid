package com.aftersix.matrixrain.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.aftersix.matrixrain.MatrixRainApp
import com.aftersix.matrixrain.core.Json
import java.io.FileNotFoundException

/**
 * Read-only status for shell tooling, available even when no run (and so no service) is active:
 *
 *   adb shell content read --uri content://com.aftersix.matrixrain.status/status        # run.json + heartbeat age
 *   adb shell content read --uri content://com.aftersix.matrixrain.status/last-result
 *   adb shell content read --uri content://com.aftersix.matrixrain.status/error/<commandId>
 *
 * Guarded by android.permission.DUMP like the service, so only shell/system can read it.
 */
class StatusProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val app = MatrixRainApp.from(context!!).awaitReady()
        val segments = uri.pathSegments
        val text = when (segments.firstOrNull()) {
            "status" -> Json.write(app.coordinator.status())
            "last-result" -> app.store.readLastResult()?.let { Json.write(it) } ?: "{}"
            "error" -> segments.getOrNull(1)?.let { app.store.errorFile(it).takeIf { f -> f.exists() }?.readText() }
                ?: throw FileNotFoundException("no such error")
            else -> throw FileNotFoundException(uri.toString())
        }
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write((text + "\n").toByteArray()) }
        }.start()
        return read
    }

    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
