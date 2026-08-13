package com.blink.dtn.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * RX chunk reassembly extracted from [BleMeshManager].
 *
 * Accepts raw GATT write values. Non-chunked payloads pass through as-is.
 * Chunked frames are buffered until complete, then returned as one byte array.
 * Incomplete frames return null (caller must wait for more writes).
 */
internal class BleChunkReassembler(
    private val scopeProvider: () -> CoroutineScope,
    private val maxConcurrentBuffers: Int = 30,
    private val bufferTtlMs: Long = 60_000L
) {
    private data class Entry(
        val timestamp: Long,
        val chunks: MutableMap<Int, ByteArray>,
        val expectedTotal: Int,
        var watchdogJob: kotlinx.coroutines.Job? = null,
        val isReassembled: AtomicBoolean = AtomicBoolean(false)
    )

    private val buffers = ConcurrentHashMap<Int, Entry>()
    private val activeBuffers = AtomicInteger(0)
    private val evictionQueue = ConcurrentLinkedQueue<Int>()

    /**
     * @return assembled payload ready for decrypt, or null if more chunks are needed / dropped.
     */
    fun ingest(value: ByteArray): ByteArray? {
        val chunk = BleChunkCodec.decode(value) ?: return value
        val msgId = chunk.messageId
        val index = chunk.index
        val total = chunk.total
        val chunkData = chunk.payload

        var entry = buffers[msgId]
        if (entry == null) {
            if (activeBuffers.incrementAndGet() > maxConcurrentBuffers) {
                var evicted = false
                while (true) {
                    val oldestMsgId = evictionQueue.poll() ?: break
                    val removed = buffers.remove(oldestMsgId)
                    if (removed != null) {
                        removed.watchdogJob?.cancel()
                        activeBuffers.decrementAndGet()
                        evicted = true
                        break
                    }
                }
                if (!evicted && activeBuffers.get() > maxConcurrentBuffers) {
                    activeBuffers.decrementAndGet()
                    Log.w("BLE_RX", "Dropping new chunk buffer $msgId — RX high-water mark")
                    return null
                }
            }

            val newEntry = Entry(System.currentTimeMillis(), ConcurrentHashMap(), total)
            val existing = buffers.putIfAbsent(msgId, newEntry)
            if (existing == null) {
                entry = newEntry
                evictionQueue.offer(msgId)
                entry.watchdogJob = scopeProvider().launch {
                    delay(bufferTtlMs)
                    if (buffers.remove(msgId) != null) {
                        activeBuffers.decrementAndGet()
                        cleanupEvictionQueue()
                    }
                }
            } else {
                activeBuffers.decrementAndGet()
                entry = existing
            }
        }

        val ready = entry ?: return null
        if (total != ready.expectedTotal) {
            Log.w("BLE_RX", "Rejecting chunk $msgId index=$index total=$total expected=${ready.expectedTotal}")
            return null
        }

        // Immutable first-arrival: do not overwrite a clean chunk with a duplicate.
        ready.chunks.putIfAbsent(index, chunkData)

        if (ready.chunks.size != ready.expectedTotal) return null

        if (!ready.isReassembled.compareAndSet(false, true)) return null

        val stream = ByteArrayOutputStream()
        for (i in 0 until ready.expectedTotal) {
            val part = ready.chunks[i]
            if (part == null) {
                ready.isReassembled.set(false)
                return null
            }
            stream.write(part)
        }

        ready.watchdogJob?.cancel()
        if (buffers.remove(msgId) != null) {
            activeBuffers.decrementAndGet()
            cleanupEvictionQueue()
        }
        return stream.toByteArray()
    }

    fun clear() {
        buffers.values.forEach { it.watchdogJob?.cancel() }
        buffers.clear()
        activeBuffers.set(0)
        evictionQueue.clear()
    }

    private fun cleanupEvictionQueue() {
        while (true) {
            val peekedId = evictionQueue.peek() ?: break
            if (!buffers.containsKey(peekedId)) {
                evictionQueue.remove(peekedId)
            } else {
                break
            }
        }
    }
}
