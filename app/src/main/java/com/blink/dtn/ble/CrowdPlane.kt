package com.blink.dtn.ble

import android.util.Log
import com.blink.dtn.crowd.CrowdFeed
import com.blink.dtn.crowd.EventRoomStore
import com.blink.dtn.telemetry.MeshDutyTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stadium plane: short [CrowdFrame] over GATT writes + gossip forward.
 * Full [NetworkPacket] path stays for DTN PRIVATE / courier.
 */
class CrowdPlane(
    private val myNodeId: String,
    private val scope: () -> CoroutineScope,
    private val broadcastRaw: suspend (ByteArray) -> Unit
) {
    fun onRawIngress(bytes: ByteArray) {
        if (!CrowdFrame.looksLike(bytes)) return
        val decoded = CrowdFrame.decode(bytes) ?: return
        if (!CrowdGossip.markSeen(decoded.messageKey)) return
        MeshDutyTelemetry.noteCrowdFrameRx()
        val room = EventRoomStore.current()?.id
        CrowdFeed.add(
            kind = decoded.kind,
            text = decoded.text,
            fromHash = decoded.idHash,
            roomId = room,
            mine = false
        )
        if (CrowdGossip.shouldForward(decoded.kind, decoded.ttl)) {
            val nextTtl = decoded.ttl - 1
            if (nextTtl <= 0) return
            val forwarded = CrowdFrame.encode(
                kind = decoded.kind,
                text = decoded.text,
                idHash = decoded.idHash,
                room = decoded.room,
                ttl = nextTtl
            )
            scope().launch(Dispatchers.IO) {
                delay(CrowdGossip.forwardJitterMs(decoded.kind))
                runCatching {
                    broadcastRaw(forwarded)
                    MeshDutyTelemetry.noteCrowdFrameForward()
                }.onFailure {
                    Log.w(TAG, "crowd forward: ${it.message}")
                }
            }
        }
    }

    fun send(kind: Byte, text: String) {
        val trimmed = text.trim().take(CrowdFrame.MAX_TEXT)
        if (trimmed.isEmpty() && kind != CrowdFrame.KIND_PRESENCE) return
        val hash = CrowdFrame.idHashFromNodeId(myNodeId)
        val roomCode = EventRoomStore.current()?.id?.hashCode()?.and(0xFF) ?: 0
        val frame = CrowdFrame.encode(
            kind = kind,
            text = trimmed,
            idHash = hash,
            room = roomCode,
            ttl = if (kind == CrowdFrame.KIND_SOS) 5 else 3
        )
        val key = CrowdFrame.decode(frame)?.messageKey ?: return
        CrowdGossip.markSeen(key)
        CrowdFeed.add(kind, trimmed, hash, EventRoomStore.current()?.id, mine = true)
        emit(frame)
    }

    /**
     * PWA / external ingress already wrote [CrowdFeed] — only push frame to BLE.
     */
    fun bridgeExternal(kind: Byte, text: String, fromHash: Int) {
        val trimmed = text.trim().take(CrowdFrame.MAX_TEXT)
        if (trimmed.isEmpty()) return
        val roomCode = EventRoomStore.current()?.id?.hashCode()?.and(0xFF) ?: 0
        val frame = CrowdFrame.encode(
            kind = kind,
            text = trimmed,
            idHash = fromHash,
            room = roomCode,
            ttl = 3
        )
        val key = CrowdFrame.decode(frame)?.messageKey ?: return
        CrowdGossip.markSeen(key)
        emit(frame)
    }

    private fun emit(frame: ByteArray) {
        scope().launch(Dispatchers.IO) {
            runCatching {
                broadcastRaw(frame)
                MeshDutyTelemetry.noteCrowdFrameTx()
            }.onFailure {
                Log.w(TAG, "crowd send: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "CrowdPlane"
    }
}
