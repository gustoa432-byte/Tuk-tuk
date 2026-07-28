package com.blink.dtn.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.util.Log
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message
import com.blink.dtn.db.SeenPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Facade over the BLE mesh transport stack.
 *
 * Layers (bottom → top):
 * 1. Radio / discovery — [BleRadioFront] (GATT server, advertise, scan)
 * 2. Peer table + GATT pool — [BlePeerTable], [BleConnectionPool]
 * 3. Wire framing — [BleChunkCodec], [BleChunkReassembler], [BleWriteBudget], [BleGattTxQueue]
 * 4. Client TX — [BleGattClientTx]
 * 5. Relay / store-and-forward — [BleRelayEngine]
 * 6. Ingress / policy — [BleIngressHandler] (dedup, ACK flood vs consume, identity)
 * 7. Maintenance — [BleKeyExchangeMaintenance]
 *
 * This class owns lifecycle, UI toasts/notifications, DB enqueue, and wiring only.
 */
@SuppressLint("MissingPermission") // Permissions are checked before calling into the mesh
class BleMeshManager private constructor(
    private val context: Context,
    private val dao: BLinkDao,
    private val myUniqueNodeId: String
) {
    init {
        com.blink.dtn.utils.MeshIdGenerator.init(context)
        com.blink.dtn.telemetry.TraceStore.init(context)
    }

    // Fast in-memory hot-path de-dup filter, in addition to the durable DB
    // seen_packets table. During a broadcast storm this avoids a DB round-trip
    // for the common duplicate case. Bounded LRU (access-order LinkedHashMap)
    // capped at 2048 entries; guarded by its own monitor since LinkedHashMap is
    // not thread-safe.
    private val recentSeenIds: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
            object : java.util.LinkedHashMap<String, Boolean>(512, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 2048
            }
        )
    )

    private val _txResults = kotlinx.coroutines.channels.Channel<TxResult>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )
    val txResults = _txResults.receiveAsFlow()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMeshRunning = AtomicBoolean(false)

    private val peers = BlePeerTable()
    val peerCount: StateFlow<Int> = peers.peerCount
    val activePeers: StateFlow<List<String>> = peers.activePeers

    private val pool = BleConnectionPool(scopeProvider = { scope })

    private var currentNick: String = "User"
    private var currentIsVip: Boolean = false
    private val notificationAdapter = MeshNotificationAdapter(context)

    private val writeBudget = BleWriteBudget()
    private val txQueue = BleGattTxQueue(
        scopeProvider = { scope },
        writeBudget = writeBudget,
        hooks = object : BleGattTxQueue.Hooks {
            override fun onWriteStart(messageId: String, address: String, bytes: Int, chunkMsgId: Int) {
                com.blink.dtn.telemetry.MeshDutyTelemetry.noteWriteAttempt(bytes)
                trace(
                    messageId,
                    com.blink.dtn.telemetry.TraceStages.GATT_WRITE_START,
                    com.blink.dtn.telemetry.detailsOf(
                        "peer" to address,
                        "bytes" to bytes,
                        "chunkMsgId" to chunkMsgId
                    )
                )
            }

            override fun onWriteDone(messageId: String, address: String, bytes: Int) {
                com.blink.dtn.telemetry.MeshDutyTelemetry.noteWriteSuccess(bytes)
                trace(
                    messageId,
                    com.blink.dtn.telemetry.TraceStages.GATT_WRITE_DONE,
                    com.blink.dtn.telemetry.detailsOf("peer" to address, "bytes" to bytes)
                )
            }

            override fun onWriteFail(messageId: String, address: String, details: Map<String, Any?>) {
                val b = (details["bytes"] as? Number)?.toInt()
                    ?: details["bytes"]?.toString()?.toIntOrNull()
                    ?: 0
                com.blink.dtn.telemetry.MeshDutyTelemetry.noteWriteFailure(b)
                val pairs = details.entries.map { it.key to (it.value?.toString() ?: "") }.toTypedArray()
                trace(
                    messageId,
                    com.blink.dtn.telemetry.TraceStages.GATT_WRITE_FAIL,
                    com.blink.dtn.telemetry.detailsOf(*pairs)
                )
                com.blink.dtn.telemetry.PeerDirectory.noteError(address)
            }

            override fun onPeerWriteResult(messageId: String, address: String, success: Boolean, softRetry: Boolean) {
                relayEngine.onPeerWriteResult(messageId, address, success, softRetry)
            }

            override fun disconnectGatt(gatt: BluetoothGatt) {
                pool.disconnect(gatt)
            }
        }
    )

    private val reassembler = BleChunkReassembler(scopeProvider = { scope })

    private val gattClientTx: BleGattClientTx by lazy {
        BleGattClientTx(
            context = context,
            writeBudget = writeBudget,
            txQueue = txQueue,
            deps = object : BleGattClientTx.Deps {
                override fun activeGatt() = pool.connections
                override fun activeMtu() = pool.mtuByAddress
                override fun connectionLastUsed() = pool.lastUsedAt
                override fun onPeerDisconnected(address: String) {
                    peers.noteDisconnected(address)
                }
                override fun clearPendingOps(address: String) = txQueue.clearDevice(address)
                override fun disconnectGatt(gatt: BluetoothGatt) = pool.disconnect(gatt)
                override fun onWriteResult(messageId: String, address: String, success: Boolean, softRetry: Boolean) {
                    relayEngine.onPeerWriteResult(messageId, address, success, softRetry)
                }
                override fun trace(messageId: String, stage: String, details: Map<String, String>, visual: String?) {
                    this@BleMeshManager.trace(messageId, stage, details, visual)
                }
                override fun serviceUuid() = SERVICE_UUID
                override fun characteristicUuid() = CHARACTERISTIC_UUID
            }
        )
    }

    private val relayEngine: BleRelayEngine by lazy {
        BleRelayEngine(
            scopeProvider = { scope },
            deps = object : BleRelayEngine.Deps {
                override suspend fun queuedMessages() = dao.getQueuedMessages()
                override fun peerDevices() = peers.snapshot()
                override fun peerBackoffUntil(mac: String) = peers.backoffUntil(mac)
                override fun myNodeId() = myUniqueNodeId
                override fun currentNick() = currentNick
                override suspend fun profile(targetId: String) = dao.getProfileById(targetId)
                override suspend fun updateMessage(msg: Message) { dao.updateMessageInternal(msg) }
                override fun enqueueMessage(msg: Message) { this@BleMeshManager.enqueueMessage(msg) }
                override fun sendPayload(device: BluetoothDevice, bytes: ByteArray, messageId: String) {
                    gattClientTx.send(device, bytes, messageId)
                }
                override fun setPeerBackoff(mac: String, durationMs: Long) {
                    peers.setBackoff(mac, durationMs)
                }
                override fun trace(messageId: String, stage: String, details: Map<String, String>, visual: String?) {
                    this@BleMeshManager.trace(messageId, stage, details, visual)
                }
                override fun emitTxResult(result: TxResult) {
                    when (result) {
                        is TxResult.Success ->
                            Log.i("ROUTE", "Message ${result.msgId} TxResult: Success")
                        is TxResult.Failure ->
                            Log.e("ROUTE", "Message ${result.msgId} TxResult: Failure. Failed MACs: ${result.failedMacs}")
                    }
                    val channelResult = _txResults.trySend(result)
                    if (channelResult.isClosed) {
                        Log.w("DTN", "Channel closed, dropped result: $result, exception: ${channelResult.exceptionOrNull()?.message}")
                    }
                }
                override fun defaultTtl() = DEFAULT_TTL
                override suspend fun tryAlternateTransport(bytes: ByteArray, messageId: String): Boolean {
                    val wifi = transportRegistry?.byId("wifi_direct") as? com.blink.dtn.transport.WifiDirectTransport
                        ?: return false
                    if (!wifi.isGroupReady()) return false
                    return wifi.send(bytes, messageId = messageId)
                }
                override fun maxPeersPerBatch(): Int = MeshDutyPrefs.cadence().maxPeersPerBatch
            }
        )
    }

    private val ingress: BleIngressHandler by lazy {
        BleIngressHandler(
            dao = dao,
            myNodeId = myUniqueNodeId,
            scopeProvider = { scope },
            deps = object : BleIngressHandler.Deps {
                override fun currentNick() = currentNick
                override fun enqueueMessage(msg: Message) = this@BleMeshManager.enqueueMessage(msg)
                override fun enqueueProfileBroadcast() = this@BleMeshManager.enqueueProfileBroadcast()
                override fun notifyIncoming(packet: Message) = triggerNotification(packet)
                override fun ensureTrace(messageId: String, type: String?, senderId: String?, targetId: String?) {
                    this@BleMeshManager.ensureTrace(messageId, type, senderId, targetId)
                }
                override fun trace(messageId: String, stage: String, details: Map<String, String>, visual: String?) {
                    this@BleMeshManager.trace(messageId, stage, details, visual)
                }
                override fun markSeen(dedupKey: String): Boolean = recentSeenIds.add(dedupKey)
            }
        )
    }

    private val radio = BleRadioFront(
        context = context,
        scopeProvider = { scope },
        deps = object : BleRadioFront.Deps {
            override fun serviceUuid() = SERVICE_UUID
            override fun characteristicUuid() = CHARACTERISTIC_UUID
            override fun noteDiscovered(device: BluetoothDevice) = peers.noteDiscovered(device)
            override fun noteGattClientConnected(device: BluetoothDevice) =
                peers.noteGattClientConnected(device)
            override fun noteGattClientDisconnected(device: BluetoothDevice) {
                peers.noteDisconnected(device.address)
            }
            override fun onNewPeerFromScan(device: BluetoothDevice) {
                triggerRelay()
                enqueueProfileBroadcast()
            }
            override fun onNewPeerFromGatt(device: BluetoothDevice) {
                triggerRelay()
            }
            override fun onWriteValue(device: BluetoothDevice, value: ByteArray) {
                handleIncomingWrite(device, value)
            }
            override fun showToast(msg: String) = this@BleMeshManager.showToast(msg)
        }
    )

    private val keyExchange = BleKeyExchangeMaintenance(
        dao = dao,
        myNodeId = myUniqueNodeId,
        scopeProvider = { scope },
        deps = object : BleKeyExchangeMaintenance.Deps {
            override fun currentNick() = currentNick
            override fun defaultTtl() = DEFAULT_TTL
            override fun enqueueMessage(msg: Message) = this@BleMeshManager.enqueueMessage(msg)
        }
    )

    fun triggerRelay() {
        relayEngine.trigger()
    }

    fun setPeerBackoff(mac: String, durationMs: Long) {
        peers.setBackoff(mac, durationMs)
    }

    fun isPeerBackedOff(mac: String): Boolean = peers.isBackedOff(mac)

    private fun showToast(msg: String) {
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun resetTransportState() {
        relayEngine.clear()
        pool.clear()
        txQueue.clearAll()
        writeBudget.clearAll()
        reassembler.clear()
        peers.clear()
    }

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
                id = com.blink.dtn.utils.MeshIdGenerator.next(myUniqueNodeId),
                type = "IDENTITY_ANNOUNCEMENT",
                senderId = myUniqueNodeId,
                senderNick = currentNick,
                targetId = null,
                text = payload,
                room = "system",
                timestamp = System.currentTimeMillis(),
                ttl = DEFAULT_TTL
            )
            enqueueMessage(msg)
        } catch (e: Exception) {
            showToast("Profile Broadcast Error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BleMeshManager"
        // Hop budget for flooded messages. Key-exchange (IDENTITY_*) packets use
        // the same value as data (PRIVATE/PUBLIC) so keys can propagate as far as
        // the data they unlock; otherwise peers >3 hops away could never get keys.
        const val DEFAULT_TTL = 7
        val SERVICE_UUID: UUID = BleMeshUuids.SERVICE
        val CHARACTERISTIC_UUID: UUID = BleMeshUuids.CHARACTERISTIC

        @Volatile
        private var INSTANCE: BleMeshManager? = null

        fun getInstance(context: Context, dao: BLinkDao, myUniqueNodeId: String): BleMeshManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleMeshManager(context.applicationContext, dao, myUniqueNodeId).also { INSTANCE = it }
            }
        }
    }

    private fun ensureTrace(
        messageId: String,
        type: String? = null,
        senderId: String? = null,
        targetId: String? = null
    ) {
        if (com.blink.dtn.telemetry.TraceStore.getByMessageId(messageId) != null) return
        com.blink.dtn.telemetry.TraceStore.begin(
            messageId = messageId,
            targetId = targetId,
            senderId = senderId,
            messageType = type
        )
    }

    private fun trace(
        messageId: String,
        stage: String,
        details: Map<String, String> = emptyMap(),
        visual: String? = null
    ) {
        ensureTrace(messageId)
        com.blink.dtn.telemetry.TraceStore.stage(messageId, stage, details, visual)
    }

    fun enqueueMessage(message: Message) {
        scope.launch {
            ensureTrace(message.id, message.type, message.senderId, message.targetId)
            // Mark messages we originate as already "seen" so that when the mesh
            // floods them back to us we drop the echo instead of re-inserting a
            // duplicate UI row, inflating unread counts, or clobbering our own
            // locally-stored copy. TX is driven from the DB queue, not the
            // seen-set, so this never suppresses our own outgoing transmissions.
            if (message.senderId == myUniqueNodeId) {
                dao.insertSeenPacket(SeenPacket(message.id, System.currentTimeMillis()))
            }
            val updatedMsg = message.copy(status = Message.STATUS_PENDING)
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
                            status = Message.STATUS_PENDING
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
            val queued = dao.getQueuedMessages()
            val position = queued.indexOfFirst { it.id == updatedMsg.id }.let { if (it < 0) queued.size else it }
            trace(
                updatedMsg.id,
                com.blink.dtn.telemetry.TraceStages.QUEUE_ADDED,
                com.blink.dtn.telemetry.detailsOf(
                    "queuePosition" to position,
                    "queueSize" to queued.size,
                    "retryCounter" to updatedMsg.retryCount,
                    "priority" to "normal",
                    "type" to updatedMsg.type,
                    "asRelay" to shouldStoreAsRelayPacket(updatedMsg)
                ),
                visual = if (updatedMsg.senderId == myUniqueNodeId) "📤 В очереди на отправку" else "🌫 В relay-очереди"
            )
            Log.d("BLE_QUEUE", "MessageId=${updatedMsg.id} Type=${updatedMsg.type} Receiver=${updatedMsg.targetId ?: "null"} RetryCount=${updatedMsg.retryCount}")
            triggerRelay()
        }
    }

    /**
     * Abort an outgoing message that has not finished delivery: drop BLE TX ops,
     * remove the active batch, and delete the local row. Already SENT/DELIVERED
     * messages are left alone (use local delete instead).
     */
    suspend fun cancelOutgoing(messageId: String): Boolean {
        val msg = dao.getMessageById(messageId) ?: return false
        if (msg.senderId != myUniqueNodeId) return false
        val cancellable = msg.status == Message.STATUS_PENDING ||
            msg.status == Message.STATUS_IN_FLIGHT ||
            msg.status == Message.STATUS_PENDING_KEY ||
            msg.status == Message.STATUS_FAILED
        if (!cancellable) return false

        relayEngine.dropBatch(messageId)

        txQueue.forEachQueue { queue ->
            val it = queue.iterator()
            while (it.hasNext()) {
                if (it.next().messageId == messageId) it.remove()
            }
        }

        dao.deleteMessageLocally(messageId)
        com.blink.dtn.telemetry.TraceStore.finish(
            messageId,
            "Cancelled",
            com.blink.dtn.telemetry.detailsOf("reason" to "user_cancel_send")
        )
        Log.d("BLE_QUEUE", "Cancelled outgoing MessageId=$messageId")
        return true
    }

    private fun shouldStoreAsRelayPacket(message: Message): Boolean {
        if (message.isAck || message.type == "ACK") return true
        if (message.type == "IDENTITY_ANNOUNCEMENT" ||
            message.type == "IDENTITY_REQUEST" ||
            message.type == "SYSTEM_PROFILE"
        ) {
            return true
        }
        return message.type == "PRIVATE" &&
            message.senderId != myUniqueNodeId &&
            message.targetId != myUniqueNodeId
    }

    fun startMesh() {
        try {
            MeshDutyPrefs.init(context)
            if (!isMeshRunning.compareAndSet(false, true)) {
                triggerRelay()
                return
            }
            if (!scope.isActive) {
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            }
            val cadence = MeshDutyPrefs.cadence()
            pool.setIdleTimeoutMs(cadence.gattIdleTimeoutMs)
            keyExchange.setIntervalMs(cadence.keyExchangeIntervalMs)
            pool.startIdleCleanup()
            keyExchange.start()
            radio.start()
            radio.applyCadence(cadence)
            relayEngine.start()
            enqueueProfileBroadcast()
        } catch (e: SecurityException) {
            isMeshRunning.set(false)
            Log.e("DTN", "SecurityException starting mesh: ${e.message}")
        } catch (e: Exception) {
            isMeshRunning.set(false)
            Log.e("DTN", "Exception starting mesh: ${e.message}")
        }
    }

    fun writeBudgetSnapshot(): WriteBudgetSnapshot = writeBudget.snapshot()

    /** Optional multi-transport registry (BLE + experimental Wi‑Fi Direct). */
    @Volatile
    var transportRegistry: com.blink.dtn.transport.MeshTransportRegistry? = null
        private set

    fun attachTransportRegistry(registry: com.blink.dtn.transport.MeshTransportRegistry) {
        transportRegistry = registry
        val wifi = registry.byId("wifi_direct") as? com.blink.dtn.transport.WifiDirectTransport
        wifi?.onMeshPayload = { bytes ->
            if (bytes.isNotEmpty()) {
                try {
                    injectEncryptedPayload(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Wi‑Fi Direct ingress failed: ${e.message}")
                }
            }
        }
    }

    /** Apply Economy / Norm / Max radio + pool cadence (hot). */
    fun applyDutyPreset(preset: MeshDutyPreset) {
        MeshDutyPrefs.set(context, preset)
        val cadence = MeshDutyCadence.forPreset(preset)
        radio.applyCadence(cadence)
        pool.setIdleTimeoutMs(cadence.gattIdleTimeoutMs)
        keyExchange.setIntervalMs(cadence.keyExchangeIntervalMs)
        Log.i(TAG, "Duty preset → ${preset.labelRu}")
    }

    fun currentDutyPreset(): MeshDutyPreset = MeshDutyPrefs.current()

    fun stopMesh() {
        try {
            isMeshRunning.set(false)
            radio.stop()
            relayEngine.stop()
            pool.stopIdleCleanup()
            keyExchange.stop()
            resetTransportState()
            scope.cancel()
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException stopping mesh: ${e.message}")
        }
    }

    private fun handleIncomingWrite(device: BluetoothDevice, value: ByteArray) {
        val assembledValue = reassembler.ingest(value) ?: return
        val jsonString = com.blink.dtn.crypto.CryptoUtils.decrypt(assembledValue)
            ?: throw Exception("Decryption returned null")
        val decoded = ingress.decodeWirePacket(jsonString)
        val message = decoded.message
        Log.d("BLE_RX_RAW", "MessageId=${message.id} Size=${assembledValue.size} SenderMAC=${device.address}")
        Log.d("BLE_PACKET", "Type=${message.type} SenderId=${message.senderId} ReceiverId=${message.targetId ?: "null"} TTL=${message.ttl}")
        Log.d("BLE_PROCESS", "MessageId=${message.id} Type=${message.type}")
        ingress.handle(message, decoded.dedupKey)
    }

    fun injectEncryptedPayload(value: ByteArray) {
        if (value.isEmpty()) return
        try {
            val jsonString = com.blink.dtn.crypto.CryptoUtils.decrypt(value) ?: return
            val decoded = ingress.decodeWirePacket(jsonString)
            ingress.handle(decoded.message, decoded.dedupKey)
        } catch (e: Exception) {
            e.printStackTrace()
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
}
