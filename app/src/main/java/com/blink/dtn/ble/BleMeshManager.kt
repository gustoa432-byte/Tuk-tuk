package com.blink.dtn.ble

import kotlinx.coroutines.flow.receiveAsFlow
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message
import com.blink.dtn.db.SeenPacket
import com.blink.dtn.db.BlockedUser
import com.blink.dtn.security.SecurityConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

sealed class TxResult {
    data class Success(val msgId: String) : TxResult()
    data class Failure(val msgId: String, val failedMacs: List<String> = emptyList()) : TxResult()
}

@SuppressLint("MissingPermission") // Suppressed because permissions are checked before calling
class BleMeshManager private constructor(
    private val context: Context,
    private val dao: BLinkDao,
    private val myUniqueNodeId: String
) {

    private class TxBatch(val totalAttempts: Int) {
        val successes = java.util.concurrent.atomic.AtomicInteger(0)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val failedMacs = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val isResolved = java.util.concurrent.atomic.AtomicBoolean(false)
        var watchdogJob: kotlinx.coroutines.Job? = null
    }
    private val messageBackoffMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val activeGattConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private val activeMtuMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val connectionLastUsedMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var idleCleanupJob: Job? = null
    private val relayTrigger = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    fun triggerRelay() {
        relayTrigger.trySend(Unit)
    }
    private val activeBatches = ConcurrentHashMap<String, TxBatch>()


    private fun safeEmitResult(result: TxResult) {
        if (result is TxResult.Success) {
            android.util.Log.i("ROUTE", "Message ${result.msgId} TxResult: Success")
        } else if (result is TxResult.Failure) {
            android.util.Log.e("ROUTE", "Message ${result.msgId} TxResult: Failure. Failed MACs: ${result.failedMacs}")
        }
        val channelResult = _txResults.trySend(result)
        if (channelResult.isClosed) {
            val exception = channelResult.exceptionOrNull()

            Log.w("DTN", "Channel closed, dropped result: $result, exception: ${exception?.message}")
        }
    }

    private fun handleOperationResult(messageId: String, mac: String?, success: Boolean) {
        if (mac != null && !success) {
            setPeerBackoff(mac, 10_000L)
        }
        val batch = activeBatches[messageId]
        if (batch == null) {
            if (success) {
                safeEmitResult(TxResult.Success(messageId))
            } else {
                messageBackoffMap[messageId] = System.currentTimeMillis() + 10_000L
                safeEmitResult(TxResult.Failure(messageId, if (mac != null) listOf(mac) else emptyList()))
            }
            return
        }
        
        if (success) {
            batch.successes.incrementAndGet()
        } else {
            batch.failures.incrementAndGet()
            if (mac != null) {
                batch.failedMacs.add(mac)
            }
        }
        
        val s = batch.successes.get()
        val f = batch.failures.get()
        if (s + f >= batch.totalAttempts) {
            if (batch.isResolved.compareAndSet(false, true)) {
                batch.watchdogJob?.cancel()
                activeBatches.remove(messageId)
                if (s > 0) {
                    safeEmitResult(TxResult.Success(messageId))
                } else {
                    messageBackoffMap[messageId] = System.currentTimeMillis() + 10_000L
                    safeEmitResult(TxResult.Failure(messageId, batch.failedMacs.toList()))
                }
            }
        }
    }

    private val _txResults = kotlinx.coroutines.channels.Channel<TxResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val txResults = _txResults.receiveAsFlow()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var relayJob: Job? = null
    private val isMeshRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    
    // The relay queue
        private val txBackoffMap = ConcurrentHashMap<String, Long>()
    private val connectedGattClients = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val discoveredDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    private data class ChunkBufferEntry(
        val timestamp: Long,
        val chunks: MutableMap<Int, ByteArray>,
        var watchdogJob: kotlinx.coroutines.Job? = null,
        val isReassembled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    )
    private val chunkBuffers = ConcurrentHashMap<Int, ChunkBufferEntry>()
    private val activeRxBuffers = java.util.concurrent.atomic.AtomicInteger(0)
    private val evictionQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()



    private fun cleanupEvictionQueue() {
        while (true) {
            val peekedId = evictionQueue.peek() ?: break
            if (!chunkBuffers.containsKey(peekedId)) {
                evictionQueue.remove(peekedId)
            } else {
                break
            }
        }
    }

    private fun showToast(msg: String) {
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private data class BleOperation(
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val payload: ByteArray,
        val msgId: Int,
        val messageId: String,
        val isHandled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    )
    private val deviceQueues = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<BleOperation>>()
    private val isOperationInProgress = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    private fun enqueueOperation(op: BleOperation) {
        val address = op.gatt.device.address
        val queue = deviceQueues.getOrPut(address) { java.util.concurrent.ConcurrentLinkedQueue() }
        queue.offer(op)
        processNextOperation(address)
    }

    private fun processNextOperation(address: String) {
        val queue = deviceQueues[address] ?: return
        val isWriting = isOperationInProgress.getOrPut(address) { java.util.concurrent.atomic.AtomicBoolean(false) }
        
        if (isWriting.compareAndSet(false, true)) {
            val op = queue.peek()
            if (op == null) {
                isWriting.set(false)
                if (queue.isNotEmpty()) {
                    processNextOperation(address)
                }
                return
            }
            executeWrite(op, address)
        }
    }

    private fun completeOperation(address: String, op: BleOperation, success: Boolean = true) {
        if (op.isHandled.compareAndSet(false, true)) {
            val queue = deviceQueues[address] ?: return
            queue.remove(op)
            
            if (!success) {
                // Cascade Cancellation: Purge all remaining operations with the same msgId
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val pendingOp = iterator.next()
                    if (pendingOp.msgId == op.msgId) {
                        iterator.remove()
                        Log.d("DTN", "Cascade cancelled chunk for msgId: ${op.msgId}")
                    }
                }
            }
            
            val isWriting = isOperationInProgress.getOrPut(address) { java.util.concurrent.atomic.AtomicBoolean(false) }
            isWriting.set(false)
            processNextOperation(address)
        }
    }

    private fun clearPendingOperationsForDevice(address: String) {
        deviceQueues.remove(address)
        isOperationInProgress.remove(address)
    }

    private fun executeWrite(op: BleOperation, address: String) {
        try {
            android.util.Log.d("BLE_TX", "MessageId=${op.messageId} DeviceMAC=${address} PayloadSize=${op.payload.size}")
            var successFlag = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val success = op.gatt.writeCharacteristic(op.characteristic, op.payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (success != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    Log.e("DTN", "writeCharacteristic failed with status: $success. Payload size: ${op.payload.size}")
                    completeOperation(address, op, success = false)
                    handleOperationResult(op.messageId, address, false)
                    disconnectGatt(op.gatt)
                } else {
                    successFlag = true
                }
            } else {
                @Suppress("DEPRECATION")
                op.characteristic.value = op.payload
                @Suppress("DEPRECATION")
                op.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val success = op.gatt.writeCharacteristic(op.characteristic)
                if (!success) {
                    Log.e("BLE_TX", "writeCharacteristic failed (legacy). Payload size: ${op.payload.size}")
                    completeOperation(address, op, success = false)
                    handleOperationResult(op.messageId, address, false)
                    disconnectGatt(op.gatt)
                } else {
                    successFlag = true
                }
            }
            if (successFlag) {
                scope.launch {
                    delay(3000)
                    // Finding #3 hardening: only treat as a failure if the write callback
                    // hasn't already resolved this op. Prevents spurious TxResult.Failure and
                    // cascade-cancelling the remaining chunks of an already-delivered message.
                    if (!op.isHandled.get()) {
                        completeOperation(address, op, success = false)
                        handleOperationResult(op.messageId, address, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BLE_TX", "Exception writing characteristic: ${e.message}")
            completeOperation(address, op, success = false)
            handleOperationResult(op.messageId, address, false)
            disconnectGatt(op.gatt)
        }
    }


    private fun disconnectGatt(gatt: BluetoothGatt) {
        try {
            val address = gatt.device.address
            activeGattConnections.remove(address)
            activeMtuMap.remove(address)
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private val _peerCount = MutableStateFlow(0)
    fun setPeerBackoff(mac: String, durationMs: Long) {
        txBackoffMap[mac] = System.currentTimeMillis() + durationMs
    }

    fun isPeerBackedOff(mac: String): Boolean {
        val retryTime = txBackoffMap[mac] ?: 0L
        return System.currentTimeMillis() < retryTime
    }

    private val _activePeers = MutableStateFlow<List<String>>(emptyList())
    val activePeers: StateFlow<List<String>> = _activePeers.asStateFlow()
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    private var currentNick: String = "User"
    private var currentIsVip: Boolean = false
    private val notificationAdapter = MeshNotificationAdapter(context)

    fun updateMyProfile(nick: String, isVip: Boolean) {
        currentNick = nick
        currentIsVip = isVip
        enqueueProfileBroadcast()
    }

    private fun enqueueProfileBroadcast() {
        try {
            val pubKey = com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64()
            val payload = "$currentNick|$currentIsVip|$pubKey"
            val msg = Message(
                id = UUID.randomUUID().toString(),
                type = "IDENTITY_ANNOUNCEMENT",
                senderId = myUniqueNodeId,
                senderNick = currentNick,
                targetId = null,
                text = payload,
                room = "system",
                timestamp = System.currentTimeMillis(),
                ttl = 3 
            )
            enqueueMessage(msg)
        } catch (e: Exception) {
            showToast("Profile Broadcast Error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BleMeshManager"
        val SERVICE_UUID: UUID = UUID.fromString("0000b111-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000b112-0000-1000-8000-00805f9b34fb")
        
        @Volatile
        private var INSTANCE: BleMeshManager? = null
        
        fun getInstance(context: Context, dao: BLinkDao, myUniqueNodeId: String): BleMeshManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleMeshManager(context.applicationContext, dao, myUniqueNodeId).also { INSTANCE = it }
            }
        }
    }
    
    fun enqueueMessage(message: Message) {
        scope.launch {
            // Mark messages we originate as already "seen" so that when the mesh
            // floods them back to us we drop the echo instead of re-inserting a
            // duplicate UI row, inflating unread counts, or clobbering our own
            // locally-stored copy. TX is driven from the DB queue, not the
            // seen-set, so this never suppresses our own outgoing transmissions.
            if (message.senderId == myUniqueNodeId) {
                dao.insertSeenPacket(SeenPacket(message.id, System.currentTimeMillis()))
            }
            val updatedMsg = message.copy(status = com.blink.dtn.db.Message.STATUS_PENDING)
            val existing = dao.getMessageById(updatedMsg.id)
            if (existing == null) {
                if (shouldStoreAsRelayPacket(updatedMsg)) {
                    dao.insertRelayPacket(updatedMsg)
                } else {
                    dao.insertMessageWithConversation(updatedMsg)
                }
            } else {
                val userVisible = existing.conversationId != BLinkDao.RELAY_CONVERSATION_ID &&
                    existing.conversationId.isNotEmpty()
                if (userVisible && shouldStoreAsRelayPacket(updatedMsg)) {
                    dao.updateMessageInternal(
                        existing.copy(
                            ttl = updatedMsg.ttl,
                            status = com.blink.dtn.db.Message.STATUS_PENDING
                        )
                    )
                } else if (userVisible &&
                    updatedMsg.type == "PRIVATE" &&
                    updatedMsg.senderId != myUniqueNodeId
                ) {
                    dao.updateMessageInternal(
                        existing.copy(
                            ttl = updatedMsg.ttl,
                            status = existing.status
                        )
                    )
                } else {
                    dao.updateMessageInternal(updatedMsg)
                }
            }
            android.util.Log.d("BLE_QUEUE", "MessageId=${updatedMsg.id} Type=${updatedMsg.type} Receiver=${updatedMsg.targetId ?: "null"} RetryCount=${updatedMsg.retryCount}")
            triggerRelay()
        }
    }

    private fun shouldStoreAsRelayPacket(message: Message): Boolean {
        if (message.isAck || message.type == "ACK") return true
        if (message.type == "IDENTITY_ANNOUNCEMENT" || message.type == "IDENTITY_REQUEST" || message.type == "SYSTEM_PROFILE") return true
        return message.type == "PRIVATE" &&
            message.senderId != myUniqueNodeId &&
            message.targetId != myUniqueNodeId
    }

    private fun startIdleCleanupLoop() {
        idleCleanupJob?.cancel()
        idleCleanupJob = scope.launch {
            while (isActive) {
                delay(10000)
                val now = System.currentTimeMillis()
                val timeoutMs = 60_000L
                for ((address, lastUsed) in connectionLastUsedMap.entries) {
                    if (now - lastUsed > timeoutMs) {
                        Log.d("BLE_TX", "GATT Idle timeout for $address")
                        val gatt = activeGattConnections[address]
                        if (gatt != null) {
                            disconnectGatt(gatt)
                        }
                        connectionLastUsedMap.remove(address)
                    }
                }
            }
        }
    }

    fun startMesh() {
        try {
            if (!isMeshRunning.compareAndSet(false, true)) {
                triggerRelay()
                return
            }
            if (!scope.isActive) {
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            }
            startIdleCleanupLoop()
            startGattServer()
            startAdvertising()
            startScanningCycle()
            startRelayLoop()
            enqueueProfileBroadcast()
        } catch (e: SecurityException) {
            isMeshRunning.set(false)
            Log.e("DTN", "SecurityException starting mesh: ${e.message}")
        } catch (e: Exception) {
            isMeshRunning.set(false)
            Log.e("DTN", "Exception starting mesh: ${e.message}")
        }
    }
    
    fun stopMesh() {
        try {
            isMeshRunning.set(false)
            scanJob?.cancel()
            relayJob?.cancel()
            idleCleanupJob?.cancel()
            scanner?.stopScan(scanCallback)
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
            scope.cancel()
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException stopping mesh: ${e.message}")
        }
    }

    private fun startGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException in startGattServer: ${e.message}")
        }
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
            
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
            
        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException in startAdvertising: ${e.message}")
        }
    }

    private fun startScanningCycle() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        scanJob?.cancel()
        scanJob = scope.launch {
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            
            while (isActive) {
                try {
                    scanner?.startScan(filters, settings, scanCallback)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in startScanning: ${e.message}")
                }
                
                delay(10000) // Scan for 10 seconds
                
                try {
                    scanner?.stopScan(scanCallback)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in stopScanning: ${e.message}")
                }
                
                // Optional: clear stale devices occasionally
                // discoveredDevices.clear()
                
                delay(20000) // Sleep for 20 seconds
            }
        }
    }

    private fun startRelayLoop() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e("ROUTE", "Exception in relay loop: ${exception.message}")
        }

        relayJob?.cancel()
        relayJob = scope.launch(exceptionHandler) {
            while (isActive) {
                val messages = dao.getQueuedMessages()
                val now = System.currentTimeMillis()
                var selectedMessage: com.blink.dtn.db.Message? = null
                var nextWakeTime = Long.MAX_VALUE
                
                for (msg in messages) {
                    if (activeBatches.containsKey(msg.id)) continue
                    val backoff = messageBackoffMap[msg.id] ?: 0L
                    if (now >= backoff) {
                        selectedMessage = msg
                        break
                    } else if (backoff < nextWakeTime) {
                        nextWakeTime = backoff
                    }
                }
                
                val message = selectedMessage
                if (message == null) {
                    // Calculate wait time
                    var waitTime = 15000L // default fallback
                    if (nextWakeTime != Long.MAX_VALUE) {
                        waitTime = (nextWakeTime - now).coerceIn(100L, 15000L)
                    }
                    kotlinx.coroutines.withTimeoutOrNull(waitTime) {
                        relayTrigger.receive()
                    }
                    delay(200) // debounce
                    continue
                }
                
                Log.d("ROUTE", "Processing message ${message.id} type=${message.type}")
                Log.d("ROUTE", "Processing message ${message.id} type=${message.type}")

                val messageTtlMs = 48 * 60 * 60 * 1000L
                if (System.currentTimeMillis() - message.timestamp > messageTtlMs || message.ttl <= 0) {
                    Log.w("ROUTE", "Message ${message.id} expired or TTL <= 0")
                    safeEmitResult(TxResult.Failure(message.id, emptyList()))
                    continue
                }
                
                if (discoveredDevices.isEmpty()) {
                    // DTN: Just wait for devices, do not fail
                    continue
                }
                
                var networkMessage = message
                if (networkMessage.type == "PRIVATE" && networkMessage.senderId == myUniqueNodeId && networkMessage.text.isNotEmpty()) {
                    val targetId = networkMessage.targetId
                    if (targetId != null) {
                        val profile = dao.getProfileById(targetId)
                        if (profile != null && profile.publicKey.isNotEmpty()) {
                            val encryptedText = com.blink.dtn.crypto.RsaUtils.encryptAsymmetric(networkMessage.text, profile.publicKey)
                            if (encryptedText.isEmpty()) {
                                Log.e("ROUTE", "Private encryption failed for ${networkMessage.id}; backing off")
                                messageBackoffMap[networkMessage.id] = System.currentTimeMillis() + calculateBackoff(networkMessage.retryCount)
                                continue
                            }
                            networkMessage = networkMessage.copy(text = encryptedText)
                        } else {
                            val updatedMsg = networkMessage.copy(status = com.blink.dtn.db.Message.STATUS_PENDING_KEY)
                            dao.updateMessageInternal(updatedMsg)
                            
                            val req = com.blink.dtn.db.Message(
                                id = java.util.UUID.randomUUID().toString(),
                                type = "IDENTITY_REQUEST",
                                senderId = myUniqueNodeId,
                                senderNick = currentNick,
                                targetId = targetId,
                                text = "",
                                room = "system",
                                timestamp = System.currentTimeMillis(),
                                ttl = 3
                            )
                            enqueueMessage(req)
                            
                            continue
                        }
                    }
                }
                
                val bytes: ByteArray
                try {
                    val wirePacket = NetworkPacket.fromMessage(networkMessage)
                    val jsonPayload = Json.encodeToString(wirePacket)
                    bytes = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)
                } catch (e: Exception) {
                    // Finding #2 hardening: a serialization/encryption failure on one message
                    // must not escape the while-loop and permanently kill the relay engine.
                    Log.e("ROUTE", "Relay encode/encrypt failed for ${message.id}: ${e.message}")
                    messageBackoffMap[message.id] = System.currentTimeMillis() + calculateBackoff(message.retryCount)
                    continue
                }
                
                val targetDevices = discoveredDevices.toList()
                val validDevices = mutableListOf<android.bluetooth.BluetoothDevice>()
                
                for (device in targetDevices) {
                    val retryTime = txBackoffMap[device.address] ?: 0L
                    if (now >= retryTime) {
                        validDevices.add(device)
                    }
                }
                
                Log.i("ROUTE", "Processing message ${message.id} attempt=${message.retryCount} to ${validDevices.size} valid devices")
                
                if (validDevices.isEmpty()) {
                    // Peers are backed off. Do not fail the message, just back off the message itself slightly
                    messageBackoffMap[message.id] = now + 5000L
                    continue
                } else {
                    val batch = TxBatch(validDevices.size)
                    activeBatches[message.id] = batch
                    
                    batch.watchdogJob = scope.launch {
                        kotlinx.coroutines.delay(45_000L)
                        if (batch.isResolved.compareAndSet(false, true)) {
                            activeBatches.remove(message.id)
                            Log.w("BLE_TX", "Watchdog timeout for message ${message.id}")
                            messageBackoffMap[message.id] = System.currentTimeMillis() + calculateBackoff(message.retryCount)
                            safeEmitResult(TxResult.Failure(message.id, batch.failedMacs.toList()))
                        }
                    }

                    for (device in validDevices) {
                        sendPayloadToDevice(device, bytes, message.id)
                    }
                }
            }
        }
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val baseMs = 5000L
        return baseMs * (1 shl minOf(retryCount, 6)) // max backoff ~ 320s
    }

    private fun enqueuePayloadChunks(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        mtu: Int,
        payload: ByteArray,
        chunkMessageId: Int,
        messageId: String
    ): Boolean {
        return try {
            for (chunkBytes in BleChunkCodec.encode(payload, mtu, chunkMessageId)) {
                enqueueOperation(BleOperation(gatt, characteristic, chunkBytes, chunkMessageId, messageId))
            }
            true
        } catch (e: IllegalArgumentException) {
            Log.e("BLE_TX", "Cannot chunk message $messageId: ${e.message}")
            false
        }
    }

    private fun sendPayloadToDevice(device: BluetoothDevice, payload: ByteArray, messageId: String) {
        val msgId = BleChunkCodec.newChunkMessageId()
        val existingGatt = activeGattConnections[device.address]
        
        if (existingGatt != null) {
            Log.d("BLE_TX", "Reusing existing GATT connection for ${device.address}")
            connectionLastUsedMap[device.address] = System.currentTimeMillis()
            val service = existingGatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                val currentMtu = activeMtuMap[device.address] ?: 20
                val enqueued = enqueuePayloadChunks(existingGatt, characteristic, currentMtu, payload, msgId, messageId)
                if (!enqueued) {
                    handleOperationResult(messageId, device.address, false)
                    disconnectGatt(existingGatt)
                }
            } else {
                disconnectGatt(existingGatt)
                handleOperationResult(messageId, device.address, false)
                // Let the next iteration retry by establishing a new connection
            }
            return
        }

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                var currentMtu = 20 // Default BLE MTU

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            Log.e("BLE_TX", "SecurityException requesting MTU: ${e.message}")
                            handleOperationResult(messageId, gatt.device.address, false)
                            disconnectGatt(gatt)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val address = gatt.device.address
                        activeGattConnections.remove(address)
                        activeMtuMap.remove(address)
                        connectionLastUsedMap.remove(address)
                        discoveredDevices.removeIf { it.address == address }
                        connectedGattClients.removeIf { it.address == address }
                        _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                        clearPendingOperationsForDevice(address)
                        disconnectGatt(gatt)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        currentMtu = mtu
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            handleOperationResult(messageId, gatt.device.address, false)
                            disconnectGatt(gatt)
                        }
                    } else {
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            handleOperationResult(messageId, gatt.device.address, false)
                            disconnectGatt(gatt)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val address = gatt.device.address
                        activeGattConnections[address] = gatt
                        activeMtuMap[address] = currentMtu
                        connectionLastUsedMap[address] = System.currentTimeMillis()
                        
                        val service = gatt.getService(SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            val enqueued = enqueuePayloadChunks(gatt, characteristic, currentMtu, payload, msgId, messageId)
                            if (!enqueued) {
                                handleOperationResult(messageId, gatt.device.address, false)
                                disconnectGatt(gatt)
                            }
                        } else {
                            handleOperationResult(messageId, gatt.device.address, false)
                            disconnectGatt(gatt)
                        }
                    } else {
                        handleOperationResult(messageId, gatt.device.address, false)
                        disconnectGatt(gatt)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    val address = gatt.device.address
                    val queue = deviceQueues[address]
                    val op = queue?.peek()
                    
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLE_WRITE_FAIL", "MessageId=${op?.messageId} status=false gattStatus=$status")
                        if (op != null) {
                            completeOperation(address, op, success = false)
                            handleOperationResult(op.messageId, address, false)
                        }
                        disconnectGatt(gatt)
                    } else {
                        if (op != null) {
                            Log.d("BLE_WRITE_OK", "MessageId=${op.messageId} DeviceMAC=$address")
                            completeOperation(address, op, success = true)
                            val queueAfter = deviceQueues[address]
                            val hasMoreOfSameMessage = queueAfter?.any { it.messageId == op.messageId } == true
                            if (!hasMoreOfSameMessage) {
                                handleOperationResult(op.messageId, address, true)
                            }
                        }
                        // Pool modification: do not disconnect GATT here, connection stays alive!
                    }
                }
            })
            Log.d("BLE_TX", "Attempting to send to ${device.address} messageId=$messageId")
        } catch (e: Exception) {
            Log.e("BLE_TX", "Exception connecting GATT client: ${e.message}")
            handleOperationResult(messageId, device.address, false)
        }
    }

    // GATT Server Callback for handling incoming packets
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedGattClients.add(device)
                if (discoveredDevices.add(device)) {
                    _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                    triggerRelay()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedGattClients.remove(device)
                discoveredDevices.remove(device)
                _peerCount.value = discoveredDevices.size
                _activePeers.value = discoveredDevices.map { it.address }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }

                    var assembledValue = value
                    val chunk = BleChunkCodec.decode(value)
                    if (chunk != null) {
                        val msgId = chunk.messageId
                        val index = chunk.index
                        val total = chunk.total
                        val chunkData = chunk.payload
                        
                        var entry = chunkBuffers[msgId]
                        if (entry == null) {
                            // Global RX Concurrency Limit / High-Water Mark to prevent OOM DDoS
                            if (activeRxBuffers.incrementAndGet() > 30) {
                                // Preemptive Eviction Strategy - O(1)
                                var evicted = false
                                while (true) {
                                    val oldestMsgId = evictionQueue.poll() ?: break
                                    val removedEntry = chunkBuffers.remove(oldestMsgId)
                                    if (removedEntry != null) {
                                        removedEntry.watchdogJob?.cancel()
                                        activeRxBuffers.decrementAndGet()
                                        evicted = true
                                        break
                                    }
                                }
                                
                                if (!evicted && activeRxBuffers.get() > 30) {
                                    activeRxBuffers.decrementAndGet()
                                    return
                                }
                            }
                            
                            val newEntry = ChunkBufferEntry(System.currentTimeMillis(), java.util.concurrent.ConcurrentHashMap())
                            val existing = chunkBuffers.putIfAbsent(msgId, newEntry)
                            if (existing == null) {
                                entry = newEntry
                                evictionQueue.offer(msgId)
                                entry.watchdogJob = scope.launch {
                                    kotlinx.coroutines.delay(60_000L) // 60 seconds absolute TTL from first chunk
                                    if (chunkBuffers.remove(msgId) != null) {
                                        activeRxBuffers.decrementAndGet()
                                        cleanupEvictionQueue()
                                    }
                                }
                            } else {
                                activeRxBuffers.decrementAndGet() // It was already put by another thread, revert increment
                                entry = existing
                            }
                        }
                        
                        val safeEntry = entry
                        
                        // Immutable First-Arrival: Do not overwrite clean chunks with corrupted duplicates
                        safeEntry.chunks.putIfAbsent(index, chunkData)
                        
                        // Completion Check
                        if (safeEntry.chunks.size == total) {
                            // Atomic lock to prevent duplicate reassembly if multiple threads finish simultaneously
                            if (safeEntry.isReassembled.compareAndSet(false, true)) {
                                val stream = java.io.ByteArrayOutputStream()
                                // Strict index ordering 0 to total - 1
                                var isValid = true
                                for (i in 0 until total) {
                                    val bufferedChunk = safeEntry.chunks[i]
                                    if (bufferedChunk == null) {
                                        isValid = false
                                        break
                                    }
                                    stream.write(bufferedChunk)
                                }
                                
                                if (!isValid) {
                                    // Safety fallback if map state is somehow corrupted
                                    safeEntry.isReassembled.set(false)
                                    return
                                }
                                
                                safeEntry.watchdogJob?.cancel()
                                if (chunkBuffers.remove(msgId) != null) {
                                    activeRxBuffers.decrementAndGet()
                                    cleanupEvictionQueue()
                                }
                                assembledValue = stream.toByteArray()
                            } else {
                                return
                            }
                        } else {
                            return
                        }
                    }

                    val jsonString = com.blink.dtn.crypto.CryptoUtils.decrypt(assembledValue) ?: throw Exception("Decryption returned null")
                    val message = decodeWirePacket(jsonString)
                    Log.d("BLE_RX_RAW", "MessageId=${message.id} Size=${assembledValue.size} SenderMAC=${device.address}")
                    Log.d("BLE_PACKET", "Type=${message.type} SenderId=${message.senderId} ReceiverId=${message.targetId ?: "null"} TTL=${message.ttl}")
                    Log.d("BLE_PROCESS", "MessageId=${message.id} Type=${message.type}")
                    handleIncomingPacket(message)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in write request: ${e.message}")
                    showToast("Security Exception: ${e.message}")
                } catch (e: Exception) {
                    Log.e("DTN", "Error decoding message: ${e.message}")
                    showToast("BLE Rx Error: ${e.message}")
                }
            }
        }
    }

    fun injectEncryptedPayload(value: ByteArray) {
        try {
            val jsonString = com.blink.dtn.crypto.CryptoUtils.decrypt(value) ?: return
            val message = decodeWirePacket(jsonString)
            handleIncomingPacket(message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Decode an on-wire JSON payload into a local [Message]. Prefer the explicit
     * [NetworkPacket] format; fall back to legacy direct-[Message] JSON for peers
     * that have not upgraded yet.
     */
    private fun decodeWirePacket(jsonString: String): Message {
        return try {
            Json.decodeFromString<NetworkPacket>(jsonString).toMessage()
        } catch (_: Exception) {
            Json.decodeFromString<Message>(jsonString)
        }
    }

    private fun handleIncomingPacket(packet: Message) {
        Log.d("DTN", "Received packet: id=${packet.id} type=${packet.type} from=${packet.senderNick} ttl=${packet.ttl}")
        scope.launch {
            val isSeen = dao.hasSeenPacket(packet.id)
            if (isSeen) {
                // DROP packet immediately
                return@launch
            }
            
            // Drop expired packets to prevent resurrection of dead data across the mesh
            val messageTtlMs = 48 * 60 * 60 * 1000L
            if (System.currentTimeMillis() - packet.timestamp > messageTtlMs) {
                return@launch
            }

            if (packet.type == "SYSTEM_ANNOUNCEMENT") {
                val isValid = withContext(Dispatchers.Default) {
                    SecurityConfig.verifySignature(packet.text, packet.authorSignature)
                }
                if (!isValid) {
                    dao.blockUser(BlockedUser(packet.senderNick, System.currentTimeMillis()))
                    return@launch
                }
            }
            
            // Save to SeenPackets after validation
            dao.insertSeenPacket(SeenPacket(packet.id, System.currentTimeMillis()))
            
            if (packet.isAck) {
                if (packet.targetId == myUniqueNodeId) {
                    val ackedMessageId = packet.originalMessageId?.takeIf { it.isNotEmpty() }
                        ?: packet.text.takeIf { it.isNotEmpty() }
                        ?: return@launch
                    val ackedMsg = dao.getMessageById(ackedMessageId)
                    val status = if (ackedMsg?.type == "PRIVATE") {
                        com.blink.dtn.db.Message.STATUS_DELIVERED
                    } else {
                        com.blink.dtn.db.Message.STATUS_SENT
                    }
                    dao.updateMessageStatus(ackedMessageId, status)
                    return@launch
                }
            } else if (packet.type == "IDENTITY_ANNOUNCEMENT" || packet.type == "SYSTEM_PROFILE") {
                val parts = packet.text.split("|")
                if (parts.size >= 2) {
                    val nick = parts[0]
                    val isVip = parts[1].toBoolean()
                    val pubKey = if (parts.size >= 3) parts[2] else ""
                    
                    val existingProfile = dao.getProfileById(packet.senderId)
                    val trustedPublicKey = when {
                        pubKey.isEmpty() -> existingProfile?.publicKey ?: ""
                        existingProfile?.publicKey.isNullOrEmpty() -> pubKey
                        existingProfile?.publicKey == pubKey -> pubKey
                        else -> {
                            Log.w("DTN", "Ignoring public key change for Node: ${packet.senderId}")
                            existingProfile?.publicKey ?: ""
                        }
                    }
                    dao.insertOrUpdateProfile(com.blink.dtn.db.UserProfile(packet.senderId, nick, System.currentTimeMillis(), isVip, trustedPublicKey))
                    Log.i("DTN", "Successfully saved public key for Node: ${packet.senderId}")
                    
                    if (existingProfile == null || existingProfile.publicKey.isEmpty()) {
                        enqueueProfileBroadcast()
                    }
                    
                    if (trustedPublicKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            enqueueMessage(msg)
                        }
                    }
                }
            } else if (packet.type == "IDENTITY_REQUEST") {
                if (packet.targetId == myUniqueNodeId) {
                    enqueueProfileBroadcast()
                } else {
                    dao.deleteTransitMessage(packet.id, myUniqueNodeId)
                    dao.insertRelayPacket(packet)
                }
            } else if (packet.type == "PUBLIC" || packet.type == "SYSTEM_ANNOUNCEMENT") {
                dao.insertMessageWithConversation(packet)
                triggerNotification(packet)
            } else if (packet.type == "PRIVATE") {
                if (packet.targetId == myUniqueNodeId) {
                    val plainText = com.blink.dtn.crypto.RsaUtils.decryptAsymmetric(packet.text)
                    if (plainText.isEmpty()) {
                        Log.e("DTN", "Private decrypt failed for message ${packet.id}; dropping without ACK")
                        return@launch
                    }
                    val finalMsg = packet.copy(text = plainText)
                    dao.insertMessageWithConversation(finalMsg)
                    triggerNotification(finalMsg)
                    
                    // Generate and enqueue ACK
                    val ack = Message(
                        id = java.util.UUID.randomUUID().toString(),
                        type = "ACK",
                        senderId = myUniqueNodeId,
                        senderNick = currentNick,
                        targetId = packet.senderId,
                        text = "",
                        originalMessageId = packet.id,
                        timestamp = System.currentTimeMillis(),
                        ttl = 7,
                        isAck = true
                    )
                    enqueueMessage(ack)
                    return@launch
                } else {
                    dao.insertRelayPacket(packet) // Save silently as relay payload
                }
            }
            
            packet.ttl = packet.ttl - 1
            if (packet.ttl > 0) {
                enqueueMessage(packet)
            }
        }
    }

    private fun triggerNotification(packet: Message) {
        notificationAdapter.notifyIncoming(
            id = packet.id,
            isPrivate = packet.type == "PRIVATE",
            senderNick = packet.senderNick,
            body = packet.text
        )
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("DTN", "Advertise started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("DTN", "Advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Cache discovered device
            if (discoveredDevices.add(result.device)) {
                _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                triggerRelay()
                // New node detected, handshake profile
                enqueueProfileBroadcast()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("DTN", "Scan failed: $errorCode")
        }
    }
}
