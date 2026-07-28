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
    }

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

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                var currentMtu = 20

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            Log.e("BLE_TX", "SecurityException requesting MTU: ${e.message}")
                            deps.onWriteResult(messageId, gatt.device.address, false)
                            deps.disconnectGatt(gatt)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val address = gatt.device.address
                        deps.activeGatt().remove(address)
                        deps.activeMtu().remove(address)
                        deps.connectionLastUsed().remove(address)
                        deps.onPeerDisconnected(address)
                        deps.clearPendingOps(address)
                        deps.disconnectGatt(gatt)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        currentMtu = mtu
                    }
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
                        if (status == 0x0D && op != null) {
                            writeBudget.noteOversizedWrite(address, op.payload.size)
                        }
                        if (op != null) {
                            txQueue.complete(address, op, success = false)
                            deps.onWriteResult(op.messageId, address, false)
                        }
                        deps.disconnectGatt(gatt)
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
            val encodeMtu = writeBudget.encodeMtu(address, mtu)
            val chunkStart = System.currentTimeMillis()
            val chunks = BleChunkCodec.encode(payload, encodeMtu, chunkMessageId)
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
