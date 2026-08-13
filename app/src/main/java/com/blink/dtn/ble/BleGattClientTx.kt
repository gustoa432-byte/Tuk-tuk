package com.blink.dtn.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * GATT client TX path: connect / MTU / discover / chunk enqueue.
 * Extracted from [BleMeshManager].
 */
@SuppressLint("MissingPermission")
internal class BleGattClientTx(
    private val context: Context,
    private val writeBudget: BleWriteBudget,
    private val txQueue: BleGattTxQueue,
    private val deps: Deps
) {
    interface Deps {
        fun activeGatt(): ConcurrentHashMap<String, BluetoothGatt>
        fun activeMtu(): ConcurrentHashMap<String, Int>
        fun connectionLastUsed(): ConcurrentHashMap<String, Long>
        fun onPeerDisconnected(address: String)
        fun clearPendingOps(address: String)
        fun disconnectGatt(gatt: BluetoothGatt)
        fun onWriteResult(messageId: String, address: String, success: Boolean, softRetry: Boolean = false)
        fun trace(messageId: String, stage: String, details: Map<String, String> = emptyMap(), visual: String? = null)
        fun serviceUuid(): UUID
        fun characteristicUuid(): UUID
        /** Global GATT concurrency gate before a new connectGatt. */
        fun tryAcquireGattSlot(address: String): Boolean
    }

    private val connectAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val connectBlockedUntil = ConcurrentHashMap<String, Long>()

    fun send(device: BluetoothDevice, payload: ByteArray, messageId: String) {
        val chunkMsgId = BleChunkCodec.newChunkMessageId()
        val existingGatt = deps.activeGatt()[device.address]

        if (existingGatt != null) {
            Log.d("BLE_TX", "Reusing existing GATT connection for ${device.address}")
            deps.connectionLastUsed()[device.address] = System.currentTimeMillis()
            deps.trace(
                messageId,
                com.blink.dtn.telemetry.TraceStages.GATT_READY,
                com.blink.dtn.telemetry.detailsOf("peer" to device.address, "reuseGatt" to true),
                visual = "🚶 Несёт ${device.name ?: device.address}"
            )
            val service = existingGatt.getService(deps.serviceUuid())
            val characteristic = service?.getCharacteristic(deps.characteristicUuid())
            if (characteristic != null) {
                val currentMtu = deps.activeMtu()[device.address] ?: 20
                val enqueued = enqueueChunks(existingGatt, characteristic, currentMtu, payload, chunkMsgId, messageId)
                if (!enqueued) {
                    deps.onWriteResult(messageId, device.address, false)
                    deps.disconnectGatt(existingGatt)
                }
            } else {
                deps.disconnectGatt(existingGatt)
                deps.onWriteResult(messageId, device.address, false)
            }
            return
        }

        val now = System.currentTimeMillis()
        val blockedUntil = connectBlockedUntil[device.address] ?: 0L
        if (now < blockedUntil) {
            Log.w("BLE_TX", "Connect backoff ${device.address} for ${blockedUntil - now}ms")
            deps.onWriteResult(messageId, device.address, false, softRetry = true)
            return
        }

        if (!deps.tryAcquireGattSlot(device.address)) {
            Log.w("BLE_TX", "GATT slot denied for ${device.address}")
            deps.trace(
                messageId,
                com.blink.dtn.telemetry.TraceStages.GATT_CONNECT_FAIL,
                com.blink.dtn.telemetry.detailsOf("peer" to device.address, "reason" to "gatt_slot_full")
            )
            deps.onWriteResult(messageId, device.address, false, softRetry = true)
            return
        }

        try {
            com.blink.dtn.telemetry.MeshDutyTelemetry.noteGattConnectStart()
            deps.trace(
                messageId,
                com.blink.dtn.telemetry.TraceStages.GATT_CONNECT_START,
                com.blink.dtn.telemetry.detailsOf("peer" to device.address)
            )
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                var currentMtu = 23

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectAttempts.remove(gatt.device.address)
                        connectBlockedUntil.remove(gatt.device.address)
                        com.blink.dtn.telemetry.MeshDutyTelemetry.noteGattConnectOk()
                        deps.trace(
                            messageId,
                            com.blink.dtn.telemetry.TraceStages.GATT_CONNECT_OK,
                            com.blink.dtn.telemetry.detailsOf("peer" to gatt.device.address, "status" to status)
                        )
                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            Log.e("BLE_TX", "SecurityException requesting MTU: ${e.message}")
                            deps.onWriteResult(messageId, gatt.device.address, false)
                            deps.disconnectGatt(gatt)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            com.blink.dtn.telemetry.MeshDutyTelemetry.noteGattConnectFail()
                            val n = connectAttempts.getOrPut(gatt.device.address) { AtomicInteger(0) }
                                .getAndIncrement()
                            val wait = BleRadioBackoff.gattDelayMs(n, status)
                            connectBlockedUntil[gatt.device.address] = System.currentTimeMillis() + wait
                            Log.w("BLE_TX", "GATT disconnect status=$status backoff=${wait}ms peer=${gatt.device.address}")
                        }
                        val address = gatt.device.address
                        deps.activeGatt().remove(address)
                        deps.activeMtu().remove(address)
                        deps.connectionLastUsed().remove(address)
                        deps.onPeerDisconnected(address)
                        deps.clearPendingOps(address)
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        deps.onWriteResult(messageId, gatt.device.address, false)
                        deps.disconnectGatt(gatt)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val address = gatt.device.address
                        deps.activeGatt()[address] = gatt
                        deps.activeMtu()[address] = currentMtu
                        deps.connectionLastUsed()[address] = System.currentTimeMillis()

                        val service = gatt.getService(deps.serviceUuid())
                        val characteristic = service?.getCharacteristic(deps.characteristicUuid())
                        if (characteristic != null) {
                            val enqueued = enqueueChunks(gatt, characteristic, currentMtu, payload, chunkMsgId, messageId)
                            if (!enqueued) {
                                deps.onWriteResult(messageId, address, false)
                                deps.disconnectGatt(gatt)
                            }
                        } else {
                            deps.onWriteResult(messageId, address, false)
                            deps.disconnectGatt(gatt)
                        }
                    } else {
                        deps.onWriteResult(messageId, gatt.device.address, false)
                        deps.disconnectGatt(gatt)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    val address = gatt.device.address
                    val op = txQueue.peek(address)

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLE_WRITE_FAIL", "MessageId=${op?.messageId} status=false gattStatus=$status")
                        // 0x0D = ATT_INVALID_ATTRIBUTE_VALUE_LENGTH
                        val attrLenFail = status == 0x0D
                        if (attrLenFail && op != null) {
                            writeBudget.noteOversizedWrite(address, op.payload.size)
                        }
                        if (op != null) {
                            txQueue.complete(address, op, success = false)
                            deps.onWriteResult(op.messageId, address, false, softRetry = attrLenFail)
                        }
                        if (!attrLenFail) {
                            deps.disconnectGatt(gatt)
                        }
                    } else if (op != null) {
                        Log.d("BLE_WRITE_OK", "MessageId=${op.messageId} DeviceMAC=$address")
                        txQueue.complete(address, op, success = true)
                        if (!txQueue.hasMoreOfMessage(address, op.messageId)) {
                            deps.onWriteResult(op.messageId, address, true)
                        }
                    }
                }
            })
            Log.d("BLE_TX", "Attempting to send to ${device.address} messageId=$messageId")
        } catch (e: Exception) {
            Log.e("BLE_TX", "Exception connecting GATT client: ${e.message}")
            deps.onWriteResult(messageId, device.address, false)
        }
    }

    private fun enqueueChunks(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        mtu: Int,
        payload: ByteArray,
        chunkMessageId: Int,
        messageId: String
    ): Boolean {
        return try {
            val address = gatt.device.address
            var encodeMtu = writeBudget.encodeMtu(address, mtu)
            val chunkStart = System.currentTimeMillis()
            var chunks = BleChunkCodec.encode(payload, encodeMtu, chunkMessageId)
            val maxWrite = writeBudget.maxWriteBytes(address, mtu)
            val oversized = chunks.firstOrNull { it.size > maxWrite }
            if (oversized != null) {
                // Defensive re-encode after learning a tighter peer cap (OEM attr length).
                writeBudget.noteOversizedWrite(address, oversized.size, "preflight")
                encodeMtu = writeBudget.encodeMtu(address, mtu)
                chunks = BleChunkCodec.encode(payload, encodeMtu, chunkMessageId)
                val stillBad = chunks.any { it.size > writeBudget.maxWriteBytes(address, mtu) }
                if (stillBad) {
                    Log.e("BLE_TX", "Chunks still exceed write budget after downshift msg=$messageId")
                    return false
                }
            }
            deps.trace(
                messageId,
                com.blink.dtn.telemetry.TraceStages.CHUNK_ENCODE,
                com.blink.dtn.telemetry.detailsOf(
                    "originalPayloadBytes" to payload.size,
                    "chunksCount" to chunks.size,
                    "chunkSizes" to chunks.joinToString(",") { it.size.toString() },
                    "mtu" to mtu,
                    "encodeMtu" to encodeMtu,
                    "maxWrite" to writeBudget.maxWriteBytes(address, mtu),
                    "chunkCreationDurationMs" to (System.currentTimeMillis() - chunkStart),
                    "peer" to address
                ),
                visual = "📚 Разделено на ${chunks.size} чанков"
            )
            for (chunkBytes in chunks) {
                txQueue.enqueue(
                    BleGattTxQueue.Op(gatt, characteristic, chunkBytes, chunkMessageId, messageId)
                )
            }
            true
        } catch (e: IllegalArgumentException) {
            Log.e("BLE_TX", "Cannot chunk message $messageId: ${e.message}")
            deps.trace(
                messageId,
                com.blink.dtn.telemetry.TraceStages.CHUNK_ENCODE,
                com.blink.dtn.telemetry.detailsOf("error" to e.message)
            )
            false
        }
    }
}
