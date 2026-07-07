import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

old_loop = """    private fun startRelayLoop() {
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
                
                val message = selectedMessage ?: continue"""

new_loop = """    private fun startRelayLoop() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e("ROUTE", "Exception in relay loop: ${exception.message}")
        }

        scope.launch(exceptionHandler) {
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
                
                Log.d("ROUTE", "Processing message ${message.id} type=${message.type}")"""

content = content.replace(old_loop, new_loop)

# Fix safeEmitResult in startRelayLoop to actually update DB? No BLinkMeshService updates the DB on TxResult.
# BLinkMeshService receives TxResult.Success and sets STATUS_SENT (2).
# It receives TxResult.Failure and sets STATUS_PENDING (0).

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
