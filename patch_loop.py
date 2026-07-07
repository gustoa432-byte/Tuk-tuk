import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

# 1. Add relayTrigger
maps = """    private val connectionLastUsedMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var idleCleanupJob: Job? = null
    private val relayTrigger = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    fun triggerRelay() {
        relayTrigger.trySend(Unit)
    }"""
content = content.replace("    private val connectionLastUsedMap = java.util.concurrent.ConcurrentHashMap<String, Long>()\n    private var idleCleanupJob: Job? = null", maps)

# 2. In enqueueMessage, call triggerRelay()
old_enqueue = """        scope.launch {
            val updatedMsg = message.copy(status = com.blink.dtn.db.Message.STATUS_PENDING)
            val existing = dao.getMessageById(updatedMsg.id)
            if (existing == null) {
                dao.insertMessageWithConversation(updatedMsg)
            } else {
                dao.updateMessageInternal(updatedMsg)
            }
        }"""
new_enqueue = """        scope.launch {
            val updatedMsg = message.copy(status = com.blink.dtn.db.Message.STATUS_PENDING)
            val existing = dao.getMessageById(updatedMsg.id)
            if (existing == null) {
                dao.insertMessageWithConversation(updatedMsg)
            } else {
                dao.updateMessageInternal(updatedMsg)
            }
            android.util.Log.d("QUEUE", "Message enqueued: ${updatedMsg.id}")
            triggerRelay()
        }"""
content = content.replace(old_enqueue, new_enqueue)

# 3. Call triggerRelay when adding to discoveredDevices in onConnectionStateChange and onScanResult
content = content.replace("""                if (discoveredDevices.add(device)) {
                    _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                }""", """                if (discoveredDevices.add(device)) {
                    _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                    triggerRelay()
                }""")

content = content.replace("""            if (discoveredDevices.add(result.device)) {
                _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                // New node detected, handshake profile
                enqueueProfileBroadcast()
            }""", """            if (discoveredDevices.add(result.device)) {
                _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                triggerRelay()
                // New node detected, handshake profile
                enqueueProfileBroadcast()
            }""")


# 4. Modify startRelayLoop
old_loop = """    private fun startRelayLoop() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e(TAG, "Exception in relay loop: ${exception.message}")
        }

        scope.launch(exceptionHandler) {
            while (isActive) {
                delay(3000) // Relay cycle interval
                
                val messages = dao.getQueuedMessages()
                val now = System.currentTimeMillis()
                var selectedMessage: com.blink.dtn.db.Message? = null
                
                for (msg in messages) {
                    if (activeBatches.containsKey(msg.id)) continue // Prevention of race condition / DDoS
                    val backoff = messageBackoffMap[msg.id] ?: 0L
                    if (now >= backoff) {
                        selectedMessage = msg
                        break
                    }
                }
                
                val message = selectedMessage ?: continue"""

new_loop = """    private fun startRelayLoop() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e("ROUTE", "Exception in relay loop: ${exception.message}")
        }

        scope.launch(exceptionHandler) {
            while (isActive) {
                // Wait for a trigger or timeout (15s fallback)
                kotlinx.coroutines.withTimeoutOrNull(15000L) {
                    relayTrigger.receive()
                }
                delay(200) // debounce
                
                val messages = dao.getQueuedMessages()
                if (messages.isNotEmpty()) {
                    Log.d("QUEUE", "Fetched ${messages.size} queued messages")
                }
                
                val now = System.currentTimeMillis()
                var selectedMessage: com.blink.dtn.db.Message? = null
                
                for (msg in messages) {
                    if (activeBatches.containsKey(msg.id)) continue
                    val backoff = messageBackoffMap[msg.id] ?: 0L
                    if (now >= backoff) {
                        selectedMessage = msg
                        break
                    }
                }
                
                val message = selectedMessage ?: continue
                Log.d("ROUTE", "Processing message ${message.id} type=${message.type}")"""

content = content.replace(old_loop, new_loop)


# 5. Fix safeEmitResult in startRelayLoop to actually update DB
# Wait, safeEmitResult emits TxResult. BLinkMeshService handles updating DB?
# BLinkMeshService receives TxResult.Success and sets STATUS_SENT (2).
# It receives TxResult.Failure and sets STATUS_PENDING (0).
# We need to make sure we don't break BLinkMeshService.

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
