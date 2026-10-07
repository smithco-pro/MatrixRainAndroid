package com.aftersix.matrixrain.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Run files, laid out like MatrixRain's `workloads/` directory: run.json, last-result.json,
 * result-<id>.json and error-<id>.txt (newest 20 kept), and scratch/ for disk I/O.
 * Writes are atomic (temp file then rename), so readers never see a partial snapshot.
 */
class RunStore(val directory: File) {
    val runFile = File(directory, "run.json")
    val lastResultFile = File(directory, "last-result.json")
    val scratchDirectory = File(directory, "scratch")

    init {
        directory.mkdirs()
    }

    fun write(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID().toString().replace("-", "")}.tmp")
        try {
            temp.writeText(text)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    fun readRun(): Map<String, Any?>? = read(runFile)

    fun readLastResult(): Map<String, Any?>? = read(lastResultFile)

    private fun read(file: File): Map<String, Any?>? =
        file.takeIf { it.exists() }?.let { runCatching { Json.parseObject(it.readText()) }.getOrNull() }

    fun saveResult(runId: String, snapshot: Map<String, Any?>) {
        val text = Json.write(snapshot)
        write(File(directory, "result-$runId.json"), text)
        write(lastResultFile, text)
        prune("result-", ".json", KEEP)
    }

    /** Errors are kept by run ID or command ID, so a caller that cannot see logcat can read why a request failed. */
    fun saveError(id: String, text: String) {
        write(errorFile(id), text)
        prune("error-", ".txt", KEEP)
    }

    fun errorFile(id: String) = File(directory, "error-${safeId(id)}.txt")

    fun results(): List<File> = matching("result-", ".json").sortedByDescending { it.lastModified() }

    /** Removes leftover scratch files from interrupted runs, matched by exact name only. */
    fun cleanScratch() {
        scratchDirectory.listFiles()?.filter { SCRATCH.matches(it.name) }?.forEach { it.delete() }
    }

    private fun matching(prefix: String, suffix: String) =
        directory.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(suffix) }.orEmpty()

    private fun prune(prefix: String, suffix: String, keep: Int) {
        matching(prefix, suffix).sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(keep).forEach { it.delete() }
    }

    companion object {
        const val KEEP = 20

        /** Letters, digits, '-' and '_' only (max 64), so IDs from shell or UEM cannot escape the directory. */
        fun safeId(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "unnamed" }
        private val SCRATCH = Regex("^io-[0-9a-f]{32}\\.tmp$")
    }
}
