package com.aftersix.matrixrain.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class MemInfo(val totalBytes: Long, val availableBytes: Long, val lowMemoryThresholdBytes: Long = 0)

/** Allocates the memory workload's blocks. Android supplies off-heap shared memory; the JVM default uses direct buffers. */
interface BlockAllocator {
    fun allocate(bytes: Int): ByteBuffer
    fun release(block: ByteBuffer) = Unit

    object Direct : BlockAllocator {
        override fun allocate(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes)
    }
}

/** Device readings the engine needs. Android supplies ActivityManager/StatFs/thermal/battery; tests supply fakes. */
interface DeviceProbe {
    fun memory(): MemInfo
    fun freeDiskBytes(directory: File): Long

    /** A reason to stop early for device protection (thermal, battery), or null. */
    fun safetyStop(): String? = null
}

class EngineResult {
    @Volatile var reason: String = ""
    var isScience = false
    @Volatile var elapsedSeconds = 0.0
    val iterations = AtomicLong()
    val hits = AtomicLong()
    @Volatile var bytesWritten = 0L
    @Volatile var bytesRead = 0L
    @Volatile var allocatedMiB = 0

    /** Mode-specific progress (for example Workday counters), published under its own key. */
    val extra = java.util.concurrent.ConcurrentHashMap<String, Any>()

    val pi: Double? get() = if (!isScience || iterations.get() == 0L) null else 4.0 * hits.get() / iterations.get()

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "Reason" to reason, "ElapsedSeconds" to elapsedSeconds, "Iterations" to iterations.get(), "Hits" to hits.get(),
        "BytesWritten" to bytesWritten, "BytesRead" to bytesRead, "AllocatedMiB" to allocatedMiB, "Pi" to pi,
    ) + extra.toSortedMap()
}

/**
 * Port of MatrixRain's workload engine (workloads/src/Engine.cs): duty-cycled CPU/science threads, page-touched
 * memory, and paced scratch-file disk I/O, with the coordinator loop enforcing deadline, stop and heartbeat.
 */
class Engine(
    private val request: RunRequest,
    private val scratchDirectory: File,
    private val probe: DeviceProbe,
    private val stopRequested: () -> Boolean,
    private val heartbeat: (EngineResult) -> Unit,
    private val allocator: BlockAllocator = BlockAllocator.Direct,
) {
    private val result = EngineResult().also { it.isScience = request.mode == Mode.SCIENCE }
    private val cancel = CountDownLatch(1)
    private val workers = mutableListOf<Thread>()
    private val blocks = mutableListOf<ByteBuffer>()
    @Volatile private var workerError: Throwable? = null
    @Volatile private var reason = "duration reached"
    private val startNanos = System.nanoTime()

    private val elapsedSeconds get() = (System.nanoTime() - startNanos) / 1e9
    private val cancelled get() = cancel.count == 0L
    private fun waitCancel(ms: Long) = cancel.await(ms, TimeUnit.MILLISECONDS)

    private fun memoryGuard(additional: Long) {
        val info = probe.memory()
        // Android counts reclaimable cache as available; keep at least 512 MiB, 20% of RAM, or twice the kernel's low-memory threshold.
        val reserve = maxOf(512 * MIB, info.totalBytes / 5, info.lowMemoryThresholdBytes * 2)
        if (info.totalBytes <= 0 || info.availableBytes - additional < reserve) {
            throw IllegalStateException("Memory reserve reached: leaving at least 512 MiB or 20% of RAM available.")
        }
    }

    private fun diskGuard() {
        if (probe.freeDiskBytes(scratchDirectory) < (2L * 1024 + 1) * MIB) throw IOException("Disk reserve reached: less than 2 GiB free.")
    }

    private fun ended(): Boolean {
        if (cancelled) return true
        if (stopRequested()) {
            reason = STOPPED
            cancel.countDown()
            return true
        }
        if (elapsedSeconds >= request.seconds) {
            cancel.countDown()
            return true
        }
        return false
    }

    private fun addWorker(name: String, action: () -> Unit) {
        val t = Thread({
            try {
                action()
            } catch (e: Throwable) {
                workerError = e
                cancel.countDown()
            }
        }, "mr-$name")
        t.isDaemon = true
        workers += t
        t.start()
    }

    private fun calculate(id: Int) {
        var seed = 123456789 + id * 7919
        fun next(): Double {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            return (seed.toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
        var sink = 0.0
        val science = request.mode == Mode.SCIENCE
        while (!cancelled) {
            val sliceStart = System.nanoTime()
            var count = 0L
            var hits = 0L
            while ((System.nanoTime() - sliceStart) / 1_000_000 < request.intensity && !cancelled) {
                repeat(256) {
                    val x = next()
                    if (science) {
                        val y = next()
                        if (x * x + y * y <= 1) hits++
                    } else {
                        sink += sqrt(x + 0.01)
                    }
                    count++
                }
            }
            result.iterations.addAndGet(count)
            result.hits.addAndGet(hits)
            waitCancel(max(1L, 100 - (System.nanoTime() - sliceStart) / 1_000_000))
        }
        if (sink == -1.0) println(sink) // keeps the CPU work observable to the optimizer
    }

    private fun allocate() {
        memoryGuard(request.memoryMiB * MIB)
        var remaining = request.memoryMiB
        while (remaining > 0 && !ended()) {
            val size = min(8, remaining)
            memoryGuard(size * MIB)
            val block = try {
                allocator.allocate((size * MIB).toInt())
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("Memory allocation failed after ${result.allocatedMiB} MiB: ${e.message}")
            }
            var i = 0
            while (i < block.capacity()) { block.put(i, 1); i += PAGE }
            blocks += block
            remaining -= size
            result.allocatedMiB += size
        }
    }

    private fun touchMemory() {
        while (!cancelled) {
            val sliceStart = System.nanoTime()
            for (block in blocks) {
                var i = 0
                while (i < block.capacity()) { block.put(i, (block.get(i) + 1).toByte()); i += PAGE }
                if (cancelled) return
            }
            // Intensity controls page-touch frequency, while the allocated amount remains fixed.
            waitCancel(max(20L, (100L - request.intensity) * 10 - (System.nanoTime() - sliceStart) / 1_000_000))
        }
    }

    private fun diskLoop() {
        diskGuard()
        scratchDirectory.mkdirs()
        val file = File(scratchDirectory, "io-" + UUID.randomUUID().toString().replace("-", "") + ".tmp")
        val buffer = ByteArray(MIB.toInt()).also { Random(741).nextBytes(it) }
        val pacingMs = 1000.0 / (request.diskMiBPerSecond * request.intensity / 100.0)
        try {
            // "rwd" writes content synchronously, the closest match to FileOptions.WriteThrough.
            RandomAccessFile(file, "rwd").use { stream ->
                while (!ended()) {
                    stream.seek(0)
                    var i = 0
                    while (i < request.diskMiB && !ended()) {
                        if (result.bytesWritten >= request.writeLimitMiB * MIB) {
                            reason = "write budget reached"
                            return
                        }
                        diskGuard()
                        val sliceStart = System.nanoTime()
                        stream.write(buffer)
                        result.bytesWritten += buffer.size
                        waitCancel(max(1L, (pacingMs - (System.nanoTime() - sliceStart) / 1_000_000.0).toLong()))
                        i++
                    }
                    stream.fd.sync()
                    stream.seek(0)
                    while (!ended()) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        result.bytesRead += read
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    fun run(): EngineResult {
        request.validated()
        try {
            when (request.mode) {
                Mode.MEMORY -> {
                    allocate()
                    if (!ended()) addWorker("memory") { touchMemory() }
                }
                Mode.CPU, Mode.SCIENCE -> repeat(min(request.threads, Runtime.getRuntime().availableProcessors())) { id ->
                    addWorker("${request.mode.wire}-$id") { calculate(id) }
                }
                // Disk work runs on a worker too, so this loop can enforce deadline/stop and emit heartbeats.
                Mode.DISK -> addWorker("disk") { diskLoop(); cancel.countDown() }
                Mode.BASELINE, Mode.VISUALIZATION -> Unit
                Mode.WORKDAY, Mode.NETWORK -> throw IllegalArgumentException("${request.mode.wire} runs through its ModeDriver, not the load engine.")
            }
            var nextPulse = 0.0
            var nextSafetyCheck = 0.0
            while (!ended()) {
                if (request.mode == Mode.MEMORY) memoryGuard(0)
                val now = elapsedSeconds
                if (now >= nextSafetyCheck) {
                    probe.safetyStop()?.let {
                        reason = "safety stop: $it"
                        cancel.countDown()
                    }
                    nextSafetyCheck = now + 1
                }
                if (now >= nextPulse) {
                    result.elapsedSeconds = now
                    heartbeat(result)
                    nextPulse += 2
                }
                waitCancel(100)
            }
        } finally {
            cancel.countDown()
            var joined = true
            for (t in workers) {
                t.join(5000)
                if (t.isAlive) joined = false
            }
            if (joined) {
                blocks.forEach { allocator.release(it) }
                blocks.clear()
            } else workerError = IllegalStateException("Worker did not stop within five seconds.")
            result.elapsedSeconds = elapsedSeconds
        }
        workerError?.let { throw IllegalStateException("Workload failed: ${it.message}", it) }
        result.reason = reason
        return result
    }

    companion object {
        const val MIB = 1_048_576L
        const val PAGE = 4096
        const val STOPPED = "stopped by operator"
    }
}
