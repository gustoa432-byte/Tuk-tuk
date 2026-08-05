package com.blink.dtn.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime view of nearby BLE peers and per-MAC TX backoff.
 * Peers expire after [peerTtlMs] without a fresh scan/GATT touch (crowd control).
 */
internal class BlePeerTable {
    private val discovered = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val connectedClients = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val txBackoffUntil = ConcurrentHashMap<String, Long>()
    /** GATT MAC → mesh nodeId after IDENTITY handshake. */
    private val nodeIdByMac = ConcurrentHashMap<String, String>()
    private val macByNodeId = ConcurrentHashMap<String, String>()
    /** Last scan hit or GATT activity per MAC. */
    private val lastSeenAt = ConcurrentHashMap<String, Long>()

    private val _peerCount = MutableStateFlow(0)
    private val _activePeers = MutableStateFlow<List<String>>(emptyList())
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    val activePeers: StateFlow<List<String>> = _activePeers.asStateFlow()

    fun snapshot(): List<BluetoothDevice> = discovered.toList()

    fun connectedClients(): Set<BluetoothDevice> = connectedClients.toSet()

    /** @return true if this device was newly added to the discovery set. */
    fun noteDiscovered(device: BluetoothDevice): Boolean {
        touchSeen(device.address)
        val added = discovered.add(device)
        if (added) publish()
        return added
    }

    /** @return true if this device was newly added to the discovery set. */
    fun noteGattClientConnected(device: BluetoothDevice): Boolean {
        connectedClients.add(device)
        touchSeen(device.address)
        val added = discovered.add(device)
        if (added) publish()
        return added
    }

    /** Refresh freshness without requiring a new discovery entry. */
    fun touchSeen(address: String) {
        if (address.isBlank()) return
        lastSeenAt[address] = System.currentTimeMillis()
    }

    fun noteDisconnected(address: String) {
        discovered.removeIf { it.address == address }
        connectedClients.removeIf { it.address == address }
        lastSeenAt.remove(address)
        nodeIdByMac.remove(address)?.let { macByNodeId.remove(it, address) }
        publish()
    }

    /**
     * Drop peers with no scan/GATT activity for [ttlMs].
     * Active GATT server clients and [protectAddresses] (outbound GATT) are kept.
     * @return number of peers removed
     */
    fun pruneStale(ttlMs: Long, protectAddresses: Set<String> = emptySet()): Int {
        val ttl = ttlMs.coerceAtLeast(30_000L)
        val now = System.currentTimeMillis()
        val connectedAddrs = connectedClients.map { it.address }.toHashSet()
        var removed = 0
        val stale = discovered.filter { device ->
            val addr = device.address
            if (addr in connectedAddrs || addr in protectAddresses) return@filter false
            val seen = lastSeenAt[addr] ?: 0L
            now - seen > ttl
        }
        for (device in stale) {
            val addr = device.address
            if (discovered.remove(device)) removed++
            lastSeenAt.remove(addr)
            txBackoffUntil.remove(addr)
            nodeIdByMac.remove(addr)?.let { macByNodeId.remove(it, addr) }
        }
        if (removed > 0) publish()
        return removed
    }

    fun bindNodeId(address: String, nodeId: String) {
        val id = nodeId.trim()
        if (address.isBlank() || id.isEmpty()) return
        nodeIdByMac[address] = id
        macByNodeId[id] = address
        touchSeen(address)
    }

    fun nodeIdFor(address: String): String? = nodeIdByMac[address]

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
        nodeIdByMac.clear()
        macByNodeId.clear()
        lastSeenAt.clear()
        publish()
    }

    private fun publish() {
        _peerCount.value = discovered.size
        _activePeers.value = discovered.map { it.address }
    }
}
