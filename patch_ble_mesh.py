import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

# 1. Remove outgoingQueue
content = re.sub(r'private val outgoingQueue\s*=\s*ConcurrentLinkedQueue<Message>\(\)\n', '', content)

# 2. Update enqueueMessage
old_enqueue = """    fun enqueueMessage(message: Message) {
        val messageTtlMs = 48 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - message.timestamp > messageTtlMs) {
            return
        }

        if (message.ttl > 0) {
            Log.d(TAG, "Enqueued message ${message.id} to outgoingQueue. Current queue size: ${outgoingQueue.size + 1}")
            outgoingQueue.add(message)
        }
    }"""
new_enqueue = """    fun enqueueMessage(message: Message) {
        // Obsolete: queue is now fully managed via DB and queried in startRelayLoop().
        // Kept for API compatibility with BLinkMeshService.
    }"""
content = content.replace(old_enqueue, new_enqueue)

# 3. Update startRelayLoop
old_loop = """    private fun startRelayLoop() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e(TAG, "Exception in relay loop: ${exception.message}")
        }

        scope.launch(exceptionHandler) {
            while (isActive) {
                delay(3000) // Relay cycle interval
                
                val message = outgoingQueue.peek()
                if (message != null) {
                    if (discoveredDevices.isEmpty()) {
                        safeEmitResult(TxResult.Failure(message.id, emptyList()))
                        outgoingQueue.poll()
                        continue
                    }
                    
                    val jsonPayload = Json.encodeToString(message)
                    val bytes = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)
                    
                    // Copy list to avoid concurrent modification
                    val targetDevices = discoveredDevices.toList()
                    val validDevices = mutableListOf<android.bluetooth.BluetoothDevice>()
                    val now = System.currentTimeMillis()
                    for (device in targetDevices) {
                        val retryTime = txBackoffMap[device.address] ?: 0L
                        if (now >= retryTime) {
                            validDevices.add(device)
                        }
                    }
                    Log.d(TAG, "Relay: Processing message ${message.id} type=${message.type} ttl=${message.ttl} for ${validDevices.size} devices")
                    
                    if (validDevices.isEmpty()) {
                        safeEmitResult(TxResult.Failure(message.id, emptyList()))
                        outgoingQueue.poll()
                        continue
                    } else {
                        val batch = TxBatch(validDevices.size)
                        activeBatches[message.id] = batch
                        
                        // Watchdog timer to prevent hanging batches if BLE stack swallows callbacks
                        batch.watchdogJob = scope.launch {
                            kotlinx.coroutines.delay(45_000L) // 45 seconds absolute TTL
                            if (batch.isResolved.compareAndSet(false, true)) {
                                activeBatches.remove(message.id)
                                safeEmitResult(TxResult.Failure(message.id, batch.failedMacs.toList()))
                            }
                        }

                        for (device in validDevices) {
                            sendPayloadToDevice(device, bytes, message.id)
                        }
                    }
                    
                    outgoingQueue.poll()

                }
            }
        }
    }"""

new_loop = """    private fun startRelayLoop() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e(TAG, "Exception in relay loop: ${exception.message}")
        }

        scope.launch(exceptionHandler) {
            while (isActive) {
                delay(3000) // Relay cycle interval
                
                val message = dao.getQueuedMessages().firstOrNull()
                if (message != null) {
                    val messageTtlMs = 48 * 60 * 60 * 1000L
                    if (System.currentTimeMillis() - message.timestamp > messageTtlMs || message.ttl <= 0) {
                        Log.w("BleTx", "Message ${message.id} expired or TTL <= 0")
                        safeEmitResult(TxResult.Failure(message.id, emptyList()))
                        continue
                    }
                    
                    if (discoveredDevices.isEmpty()) {
                        Log.d("BleTx", "DTN: No discovered devices, failing message ${message.id}")
                        safeEmitResult(TxResult.Failure(message.id, emptyList()))
                        continue
                    }
                    
                    val jsonPayload = Json.encodeToString(message)
                    val bytes = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)
                    
                    val targetDevices = discoveredDevices.toList()
                    val validDevices = mutableListOf<android.bluetooth.BluetoothDevice>()
                    val now = System.currentTimeMillis()
                    for (device in targetDevices) {
                        val retryTime = txBackoffMap[device.address] ?: 0L
                        if (now >= retryTime) {
                            validDevices.add(device)
                        }
                    }
                    
                    Log.i("BleTx", "DTN Relay: Processing message ${message.id} for ${validDevices.size} valid devices")
                    
                    if (validDevices.isEmpty()) {
                        safeEmitResult(TxResult.Failure(message.id, emptyList()))
                        continue
                    } else {
                        val batch = TxBatch(validDevices.size)
                        activeBatches[message.id] = batch
                        
                        batch.watchdogJob = scope.launch {
                            kotlinx.coroutines.delay(45_000L)
                            if (batch.isResolved.compareAndSet(false, true)) {
                                activeBatches.remove(message.id)
                                Log.w("BleTx", "DTN Watchdog: timeout for message ${message.id}")
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
    }"""
content = content.replace(old_loop, new_loop)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
