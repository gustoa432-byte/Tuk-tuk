package com.blink.dtn.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Compact stadium wire (not full signed JSON [NetworkPacket]).
 *
 * Binary layout (little-endian):
 *   magic 0x54 0x4B ('TK') | ver=1 | kind | ttl | flags | idHash u32 | room u8 | textLen u8 | utf8 text
 * Max text 72 bytes → total ≤ ~84 bytes (fits many ADV / short GATT writes).
 */
object CrowdFrame {
    const val MAGIC0: Byte = 0x54
    const val MAGIC1: Byte = 0x4B
    const val VERSION: Byte = 1
    const val MAX_TEXT = 72

    const val KIND_PRESENCE: Byte = 1
    const val KIND_PUBLIC: Byte = 2
    const val KIND_SOS: Byte = 3

    data class Decoded(
        val kind: Byte,
        val ttl: Int,
        val idHash: Int,
        val room: Int,
        val text: String,
        val messageKey: String
    )

    fun encode(
        kind: Byte,
        text: String,
        idHash: Int,
        room: Int = 0,
        ttl: Int = 3
    ): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8).take(MAX_TEXT).toByteArray()
        val buf = ByteBuffer.allocate(2 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + body.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC0)
        buf.put(MAGIC1)
        buf.put(VERSION)
        buf.put(kind)
        buf.put(ttl.coerceIn(0, 7).toByte())
        buf.put(0) // flags
        buf.putInt(idHash)
        buf.put(room.coerceIn(0, 255).toByte())
        buf.put(body.size.toByte())
        buf.put(body)
        return buf.array()
    }

    fun looksLike(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[0] == MAGIC0 && bytes[1] == MAGIC1 && bytes[2] == VERSION

    fun decode(bytes: ByteArray): Decoded? {
        if (!looksLike(bytes)) return null
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.get(); buf.get(); buf.get() // magic + ver
            val kind = buf.get()
            val ttl = buf.get().toInt() and 0xFF
            buf.get() // flags
            val idHash = buf.int
            val room = buf.get().toInt() and 0xFF
            val len = buf.get().toInt() and 0xFF
            val textBytes = ByteArray(len.coerceAtMost(buf.remaining()))
            buf.get(textBytes)
            val text = String(textBytes, Charsets.UTF_8)
            val key = "$idHash|$kind|$room|${text.hashCode()}"
            Decoded(kind, ttl, idHash, room, text, key)
        } catch (_: Exception) {
            null
        }
    }

    fun idHashFromNodeId(nodeId: String): Int = nodeId.hashCode()
}

/**
 * Probabilistic gossip + seen-set for [CrowdFrame] in dense air.
 */
object CrowdGossip {
    private const val SEEN_CAP = 4096
    private val seen = object : LinkedHashMap<String, Long>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > SEEN_CAP
    }
    private val lock = Any()

    /** @return false if already seen (drop). */
    fun markSeen(key: String): Boolean = synchronized(lock) {
        if (seen.containsKey(key)) return false
        seen[key] = System.currentTimeMillis()
        true
    }

    /**
     * Forward probability by kind — SOS almost always, presence rarely.
     * Extra decay when TTL low.
     */
    fun shouldForward(kind: Byte, ttl: Int): Boolean {
        if (ttl <= 0) return false
        val base = when (kind) {
            CrowdFrame.KIND_SOS -> 0.92f
            CrowdFrame.KIND_PUBLIC -> 0.55f
            CrowdFrame.KIND_PRESENCE -> 0.18f
            else -> 0.3f
        }
        val ttlBoost = if (ttl >= 3) 1f else 0.7f
        return Random.nextFloat() < (base * ttlBoost)
    }

    /** Jitter ms before TX so N phones don't sync. */
    fun forwardJitterMs(kind: Byte): Long {
        val max = when (kind) {
            CrowdFrame.KIND_SOS -> 80L
            CrowdFrame.KIND_PUBLIC -> 220L
            else -> 400L
        }
        return Random.nextLong(0L, max + 1)
    }

    fun qosRank(kind: Byte): Int = when (kind) {
        CrowdFrame.KIND_SOS -> 0
        CrowdFrame.KIND_PUBLIC -> 1
        CrowdFrame.KIND_PRESENCE -> 2
        else -> 3
    }
}
