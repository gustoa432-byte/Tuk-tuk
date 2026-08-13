package com.blink.dtn.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-device sequential GATT write queue extracted from [BleMeshManager].
 */
internal class BleGattTxQueue(
    private val scopeProvider: () -> CoroutineScope,
    private val writeBudget: BleWriteBudget,
    private val hooks: Hooks
) {
    data class Op(
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val payload: ByteArray,
        val msgId: Int,
        val messageId: String,
        val isHandled: AtomicBoolean = AtomicBoolean(false)
    )

    interface Hooks {
        fun onWriteStart(messageId: String, address: String, bytes: Int, chunkMsgId: Int)
        fun onWriteDone(messageId: String, address: String, bytes: Int)
        fun onWriteFail(messageId: String, address: String, details: Map<String, Any?>)
        fun onPeerWriteResult(messageId: String, address: String, success: Boolean, softRetry: Boolean = false)
        fun disconnectGatt(gatt: BluetoothGatt)
    }

    private val deviceQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<Op>>()
    private val isOperationInProgress = ConcurrentHashMap<String, AtomicBoolean>()

    fun enqueue(op: Op) {
        val address = op.gatt.device.address
        val queue = deviceQueues.getOrPut(address) { ConcurrentLinkedQueue() }
        queue.offer(op)
        processNext(address)
    }

    fun peek(address: String): Op? = deviceQueues[address]?.peek()

    fun hasMoreOfMessage(address: String, messageId: String): Boolean =
        deviceQueues[address]?.any { it.messageId == messageId } == true

    fun clearDevice(address: String) {
        deviceQueues.remove(address)
        isOperationInProgress.remove(address)
    }

    fun clearAll() {
        deviceQueues.clear()
        isOperationInProgress.clear()
    }

    /** Drop remaining queued chunks that share [chunkMsgId] (cascade after failure). */
    fun purgeChunkMessage(address: String, chunkMsgId: Int) {
        val queue = deviceQueues[address] ?: return
        val it = queue.iterator()
        while (it.hasNext()) {
            if (it.next().msgId == chunkMsgId) it.remove()
        }
    }

    fun forEachQueue(block: (ConcurrentLinkedQueue<Op>) -> Unit) {
        deviceQueues.values.forEach(block)
    }

    fun complete(address: String, op: Op, success: Boolean = true) {
        if (!op.isHandled.compareAndSet(false, true)) return
        val queue = deviceQueues[address] ?: return
        queue.remove(op)

        if (!success) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.msgId == op.msgId) {
                    iterator.remove()
                    Log.d("DTN", "Cascade cancelled chunk for msgId: ${op.msgId}")
                }
            }
        }

        val isWriting = isOperationInProgress.getOrPut(address) { AtomicBoolean(false) }
        isWriting.set(false)
        processNext(address)
    }

    private fun processNext(address: String) {
        val queue = deviceQueues[address] ?: return
        val isWriting = isOperationInProgress.getOrPut(address) { AtomicBoolean(false) }

        if (isWriting.compareAndSet(false, true)) {
            val op = queue.peek()
            if (op == null) {
                isWriting.set(false)
                if (queue.isNotEmpty()) processNext(address)
                return
            }
            executeWrite(op, address)
        }
    }

    private fun executeWrite(op: Op, address: String) {
        try {
            val cap = writeBudget.peerAttrCap(address)
            if (op.payload.size > cap) {
                Log.w(
                    "BLE_TX",
                    "Preflight reject write ${op.payload.size}B > peerCap $cap for $address"
                )
                writeBudget.noteOversizedWrite(address, op.payload.size, "queue_preflight")
                hooks.onWriteFail(
                    op.messageId,
                    address,
                    mapOf("peer" to address, "reason" to "over_peer_cap", "cap" to cap)
                )
                complete(address, op, success = false)
                hooks.onPeerWriteResult(op.messageId, address, false, softRetry = true)
                hooks.disconnectGatt(op.gatt)
                return
            }
            Log.d("BLE_TX", "MessageId=${op.messageId} DeviceMAC=$address PayloadSize=${op.payload.size}")
            hooks.onWriteStart(op.messageId, address, op.payload.size, op.msgId)
            var submitted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = op.gatt.writeCharacteristic(
                    op.characteristic,
                    op.payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (status != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    Log.e("DTN", "writeCharacteristic failed with status: $status. Payload size: ${op.payload.size}")
                    maybeDownshift(address, op.payload.size, "status=$status")
                    hooks.onWriteFail(op.messageId, address, mapOf("peer" to address, "status" to status))
                    complete(address, op, success = false)
                    hooks.onPeerWriteResult(op.messageId, address, false, softRetry = op.payload.size > 185)
                    hooks.disconnectGatt(op.gatt)
                } else {
                    submitted = true
                }
            } else {
                @Suppress("DEPRECATION")
                op.characteristic.value = op.payload
                @Suppress("DEPRECATION")
                op.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val ok = op.gatt.writeCharacteristic(op.characteristic)
                if (!ok) {
                    Log.e("BLE_TX", "writeCharacteristic failed (legacy). Payload size: ${op.payload.size}")
                    maybeDownshift(address, op.payload.size, "legacy_false")
                    hooks.onWriteFail(op.messageId, address, mapOf("peer" to address, "legacy" to true))
                    complete(address, op, success = false)
                    hooks.onPeerWriteResult(op.messageId, address, false, softRetry = op.payload.size > 185)
                    hooks.disconnectGatt(op.gatt)
                } else {
                    submitted = true
                }
            }
            if (submitted) {
                hooks.onWriteDone(op.messageId, address, op.payload.size)
                scopeProvider().launch {
                    delay(3000)
                    if (!op.isHandled.get()) {
                        complete(address, op, success = false)
                        hooks.onPeerWriteResult(op.messageId, address, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BLE_TX", "Exception writing characteristic: ${e.message}")
            val oversized = writeBudget.isOversizedError(e.message)
            if (oversized) {
                val newCap = writeBudget.noteOversizedWrite(address, op.payload.size)
                Log.w("BLE_TX", "Oversized GATT write (${op.payload.size}); peer $address cap → $newCap")
            }
            hooks.onWriteFail(
                op.messageId,
                address,
                mapOf("error" to e.message, "stack" to e.stackTraceToString().take(800))
            )
            complete(address, op, success = false)
            hooks.onPeerWriteResult(op.messageId, address, false, softRetry = oversized)
            hooks.disconnectGatt(op.gatt)
        }
    }

    private fun maybeDownshift(address: String, attempted: Int, reason: String) {
        // Conservative: treat hard submit failures of large payloads as possible length issues.
        if (attempted > 185) {
            val newCap = writeBudget.noteOversizedWrite(address, attempted)
            Log.w("BLE_TX", "Write submit fail ($reason, $attempted B); peer $address cap → $newCap")
        }
    }
}
