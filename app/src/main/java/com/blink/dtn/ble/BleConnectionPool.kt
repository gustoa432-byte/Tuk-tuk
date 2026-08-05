package com.blink.dtn.ble

import android.bluetooth.BluetoothGatt
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Pooled GATT client connections with idle timeout and a hard concurrent cap.
 * New connects fail-fast (or evict oldest idle) when [maxConcurrent] is reached.
 */
internal class BleConnectionPool(
    private val scopeProvider: () -> CoroutineScope,
    idleTimeoutMs: Long = 60_000L,
    private val sweepIntervalMs: Long = 10_000L,
    maxConcurrent: Int = 3
) {
    val connections = ConcurrentHashMap<String, BluetoothGatt>()
    val mtuByAddress = ConcurrentHashMap<String, Int>()
    val lastUsedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    var idleTimeoutMs: Long = idleTimeoutMs
        private set

    @Volatile
    var maxConcurrent: Int = maxConcurrent.coerceIn(1, 8)
        private set

    private var idleJob: Job? = null

    /** Optional hook: prune peer table each sweep. */
    @Volatile
    var onSweep: (() -> Unit)? = null

    fun setIdleTimeoutMs(ms: Long) {
        idleTimeoutMs = ms.coerceAtLeast(5_000L)
    }

    fun setMaxConcurrent(n: Int) {
        maxConcurrent = n.coerceIn(1, 8)
    }

    fun touch(address: String) {
        lastUsedAt[address] = System.currentTimeMillis()
    }

    /**
     * Reserve a slot before [BluetoothDevice.connectGatt].
     * Reuses existing connection; otherwise evicts oldest idle peer if at cap.
     */
    fun tryAcquireSlot(address: String): Boolean {
        if (connections.containsKey(address)) {
            touch(address)
            return true
        }
        if (connections.size < maxConcurrent) return true
        val victim = lastUsedAt.entries
            .asSequence()
            .filter { it.key != address && connections.containsKey(it.key) }
            .minByOrNull { it.value }
            ?.key
        if (victim != null) {
            Log.i("BLE_TX", "GATT slot full (${connections.size}/$maxConcurrent) — evict idle $victim")
            connections[victim]?.let { disconnect(it) }
        }
        return connections.size < maxConcurrent
    }

    fun put(address: String, gatt: BluetoothGatt, mtu: Int) {
        connections[address] = gatt
        mtuByAddress[address] = mtu
        touch(address)
    }

    fun disconnect(gatt: BluetoothGatt) {
        try {
            val address = gatt.device.address
            connections.remove(address)
            mtuByAddress.remove(address)
            lastUsedAt.remove(address)
            gatt.disconnect()
            gatt.close()
        } catch (_: Exception) {
            // ignore
        }
    }

    fun startIdleCleanup() {
        idleJob?.cancel()
        idleJob = scopeProvider().launch {
            while (isActive) {
                delay(sweepIntervalMs)
                val now = System.currentTimeMillis()
                for ((address, lastUsed) in lastUsedAt.entries.toList()) {
                    if (now - lastUsed > idleTimeoutMs) {
                        Log.d("BLE_TX", "GATT Idle timeout for $address")
                        connections[address]?.let { disconnect(it) }
                        lastUsedAt.remove(address)
                    }
                }
                try {
                    onSweep?.invoke()
                } catch (e: Exception) {
                    Log.w("BLE_TX", "peer sweep failed: ${e.message}")
                }
            }
        }
    }

    fun stopIdleCleanup() {
        idleJob?.cancel()
        idleJob = null
    }

    fun clear() {
        stopIdleCleanup()
        connections.values.toList().forEach { disconnect(it) }
        connections.clear()
        mtuByAddress.clear()
        lastUsedAt.clear()
    }
}
