package com.blink.dtn.transport

import com.blink.dtn.ble.BleMeshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Production BLE path behind [MeshTransport]. Sending still goes through the
 * existing DTN queue ([BleMeshManager.enqueueMessage]) — this adapter exposes
 * peer discovery for the registry UI and documents the contract.
 *
 * Direct [send] of raw bytes is intentionally limited: the mesh stack expects
 * Message entities. Callers should keep using BleMeshManager for message TX;
 * this method returns false so higher layers fall back cleanly.
 */
class BleMeshTransport(
    private val ble: BleMeshManager
) : MeshTransport {
    override val id: String = "ble"
    override val displayNameRu: String = "Bluetooth LE"
    override val isExperimental: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _available = MutableStateFlow(true)
    override val isAvailable: StateFlow<Boolean> = _available.asStateFlow()

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    override fun start() {
        _available.value = true
        scope.launch {
            ble.activePeers.collect { macs ->
                _peers.value = macs.map { mac ->
                    MeshPeer(id = mac, displayName = mac, transportId = id)
                }
            }
        }
    }

    override fun stop() {
        // BLE lifecycle owned by BleMeshManager / service — do not stop mesh here.
    }

    override suspend fun send(payload: ByteArray, peerId: String?, messageId: String?): Boolean {
        // Opaque byte send would bypass DTN/crypto framing; refuse and let BLE
        // message path remain authoritative.
        return false
    }
}
