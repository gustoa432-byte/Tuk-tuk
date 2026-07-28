package com.blink.dtn.ble

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.serialization.Serializable

/**
 * Per-peer write budget for GATT characteristic values.
 *
 * Strategy: use the largest *safe* size after MTU negotiation, then step down
 * only when a peer rejects an oversized write (Android attribute-length cap /
 * OEM quirks). Never start at the BLE minimum — that burns battery on chunking.
 */
class BleWriteBudget {
    companion object {
        /** Hard ceiling observed on Android 13+ stacks (see feedback ZIP 2026-07-28). */
        const val ANDROID_MAX_ATTR_BYTES = 512
        private val DOWNSTEPS = intArrayOf(512, 256, 185, 23)
    }

    /** Optional peer-specific max attribute value length (bytes), learned from failures. */
    private val peerMaxWrite = ConcurrentHashMap<String, Int>()
    private val downshiftCount = AtomicInteger(0)

    fun maxWriteBytes(address: String?, negotiatedMtu: Int): Int {
        val fromMtu = (negotiatedMtu - 3).coerceAtLeast(20)
        val peerCap = address?.let { peerMaxWrite[it] } ?: Int.MAX_VALUE
        return min(fromMtu, min(ANDROID_MAX_ATTR_BYTES, peerCap))
    }

    /**
     * MTU value to pass into [BleChunkCodec.encode] so that
     * header + payload ≤ [maxWriteBytes].
     */
    fun encodeMtu(address: String?, negotiatedMtu: Int): Int =
        maxWriteBytes(address, negotiatedMtu) + 3

    /**
     * After an oversized-write rejection, lower the peer ceiling to the next
     * step strictly below the failed attempt (or 23 as floor).
     */
    fun noteOversizedWrite(address: String, attemptedBytes: Int, reason: String = "oversized"): Int {
        val prev = peerMaxWrite[address] ?: ANDROID_MAX_ATTR_BYTES
        val next = DOWNSTEPS.firstOrNull { it < attemptedBytes } ?: 23
        peerMaxWrite.merge(address, next) { a, b -> min(a, b) }
        val applied = peerMaxWrite[address] ?: next
        if (applied < prev) {
            downshiftCount.incrementAndGet()
            com.blink.dtn.telemetry.MeshDutyTelemetry.noteBudgetDownshift(
                address = address,
                fromBytes = prev,
                toBytes = applied,
                reason = reason
            )
        }
        return applied
    }

    fun isOversizedError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return m.contains("longer than max length") ||
            m.contains("max length of an attribute") ||
            m.contains("invalid attribute length")
    }

    fun clear(address: String) {
        peerMaxWrite.remove(address)
    }

    fun clearAll() {
        peerMaxWrite.clear()
    }

    fun snapshot(): WriteBudgetSnapshot =
        WriteBudgetSnapshot(
            downshiftCount = downshiftCount.get(),
            peerCaps = peerMaxWrite.mapValues { it.value }
        )
}

@Serializable
data class WriteBudgetSnapshot(
    val downshiftCount: Int,
    val peerCaps: Map<String, Int>
)
