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
 * Pooled GATT client connections with idle timeout.
 */
internal class BleConnectionPool(
    private val scopeProvider: () -> CoroutineScope,
    idleTimeoutMs: Long = 60_000L,
    private val sweepIntervalMs: Long = 10_000L
) {
    val connections = ConcurrentHashMap<String, BluetoothGatt>()
    val mtuByAddress = ConcurrentHashMap<String, Int>()
    val lastUsedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    var idleTimeoutMs: Long = idleTimeoutMs
        private set

    private var idleJob: Job? = null

    fun setIdleTimeoutMs(ms: Long) {
        idleTimeoutMs = ms.coerceAtLeast(5_000L)
    }

    fun touch(address: String) {
        lastUsedAt[address] = System.currentTimeMillis()
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
            // MTU map cleared; write-budget caps intentionally survive in BleWriteBudget.
            mtuByAddress.remove(address)
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
