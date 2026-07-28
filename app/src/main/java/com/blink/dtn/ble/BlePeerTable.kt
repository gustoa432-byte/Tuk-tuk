package com.blink.dtn.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime view of nearby BLE peers and per-MAC TX backoff.
 * (Distinct from telemetry [com.blink.dtn.telemetry.PeerDirectory].)
 */
internal class BlePeerTable {
    private val discovered = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val connectedClients = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val txBackoffUntil = ConcurrentHashMap<String, Long>()

    private val _peerCount = MutableStateFlow(0)
    private val _activePeers = MutableStateFlow<List<String>>(emptyList())
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    val activePeers: StateFlow<List<String>> = _activePeers.asStateFlow()

    fun snapshot(): List<BluetoothDevice> = discovered.toList()

    fun connectedClients(): Set<BluetoothDevice> = connectedClients.toSet()

    /** @return true if this device was newly added to the discovery set. */
    fun noteDiscovered(device: BluetoothDevice): Boolean {
        val added = discovered.add(device)
        if (added) publish()
        return added
    }

    /** @return true if this device was newly added to the discovery set. */
    fun noteGattClientConnected(device: BluetoothDevice): Boolean {
        connectedClients.add(device)
        val added = discovered.add(device)
        if (added) publish()
        return added
    }

    fun noteDisconnected(address: String) {
        discovered.removeIf { it.address == address }
        connectedClients.removeIf { it.address == address }
        publish()
    }

    fun setBackoff(mac: String, durationMs: Long) {
        txBackoffUntil[mac] = System.currentTimeMillis() + durationMs
    }

    fun backoffUntil(mac: String): Long = txBackoffUntil[mac] ?: 0L

    fun isBackedOff(mac: String): Boolean =
        System.currentTimeMillis() < backoffUntil(mac)

    fun clear() {
        discovered.clear()
        connectedClients.clear()
        txBackoffUntil.clear()
        publish()
    }

    private fun publish() {
        _peerCount.value = discovered.size
        _activePeers.value = discovered.map { it.address }
    }
}
