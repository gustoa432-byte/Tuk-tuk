package com.blink.dtn.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Pluggable mesh hop transport. BLE is production; Wi‑Fi Direct is experimental.
 * Keep payloads opaque (already encrypted NetworkPacket bytes) so crypto stays shared.
 */
interface MeshTransport {
    val id: String
    /** Russian UI label; may include «экспериментально». */
    val displayNameRu: String
    val isExperimental: Boolean

    val isAvailable: StateFlow<Boolean>
    val discoveredPeers: StateFlow<List<MeshPeer>>

    fun start()
    fun stop()

    /**
     * Best-effort send of already-framed mesh bytes to [peerId] or broadcast/group.
     * Returns false if this transport cannot send right now (caller may fall back to BLE).
     */
    suspend fun send(payload: ByteArray, peerId: String? = null, messageId: String? = null): Boolean
}

data class MeshPeer(
    val id: String,
    val displayName: String,
    val transportId: String,
    val lastSeenAt: Long = System.currentTimeMillis()
)

/**
 * Registry: prefer non-experimental transports that are available; BLE remains default.
 */
class MeshTransportRegistry(
    private val transports: List<MeshTransport>
) {
    fun all(): List<MeshTransport> = transports

    fun byId(id: String): MeshTransport? = transports.find { it.id == id }

    /** Active non-experimental first, then experimental available ones. */
    fun preferredAvailable(): List<MeshTransport> =
        transports.filter { it.isAvailable.value }
            .sortedBy { if (it.isExperimental) 1 else 0 }

    fun startAll() = transports.forEach { it.start() }
    fun stopAll() = transports.forEach { it.stop() }
}
