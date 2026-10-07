package com.aftersix.matrixrain

import android.os.SharedMemory
import com.aftersix.matrixrain.core.BlockAllocator
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * Memory-workload blocks backed by anonymous shared memory. On ART, direct ByteBuffers come out of the Java
 * heap (256 MiB on a Pixel 8a), so they cannot model large allocations; shared memory is mapped outside the
 * heap but is still charged to this app's memory footprint.
 */
class SharedMemoryAllocator : BlockAllocator {
    private val regions = IdentityHashMap<ByteBuffer, SharedMemory>()

    override fun allocate(bytes: Int): ByteBuffer {
        val region = try {
            SharedMemory.create("matrixrain-block", bytes)
        } catch (e: Exception) {
            throw OutOfMemoryError("shared memory: ${e.message}")
        }
        val buffer = region.mapReadWrite()
        synchronized(regions) { regions[buffer] = region }
        return buffer
    }

    override fun release(block: ByteBuffer) {
        val region = synchronized(regions) { regions.remove(block) } ?: return
        SharedMemory.unmap(block)
        region.close()
    }
}
