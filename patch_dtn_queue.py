import re

with open('app/src/main/java/com/blink/dtn/db/BLinkDao.kt', 'r') as f:
    dao_content = f.read()

if "abstract suspend fun updateMessageInternal" not in dao_content:
    dao_content = dao_content.replace(
        "abstract suspend fun updateMessageStatusAndRetryCount(msgId: String, status: Int, retryCount: Int)",
        "abstract suspend fun updateMessageStatusAndRetryCount(msgId: String, status: Int, retryCount: Int)\n    @androidx.room.Update\n    abstract suspend fun updateMessageInternal(message: Message)"
    )
    with open('app/src/main/java/com/blink/dtn/db/BLinkDao.kt', 'w') as f:
        f.write(dao_content)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    ble_content = f.read()

# Fix 1: Race condition (in-flight skip)
old_loop_for = """                for (msg in messages) {
                    val backoff = messageBackoffMap[msg.id] ?: 0L
                    if (now >= backoff) {
                        selectedMessage = msg
                        break
                    }
                }"""

new_loop_for = """                for (msg in messages) {
                    if (activeBatches.containsKey(msg.id)) continue // Prevention of race condition / DDoS
                    val backoff = messageBackoffMap[msg.id] ?: 0L
                    if (now >= backoff) {
                        selectedMessage = msg
                        break
                    }
                }"""
ble_content = ble_content.replace(old_loop_for, new_loop_for)

# Fix 2: Restore enqueueMessage logic
old_enqueue = """    fun enqueueMessage(message: Message) {
        // Obsolete: queue is now fully managed via DB and queried in startRelayLoop().
        // Kept for API compatibility with BLinkMeshService.
    }"""

new_enqueue = """    fun enqueueMessage(message: Message) {
        scope.launch {
            message.status = com.blink.dtn.db.Message.STATUS_PENDING
            val existing = dao.getMessageById(message.id)
            if (existing == null) {
                dao.insertMessageWithConversation(message)
            } else {
                dao.updateMessageInternal(message)
            }
        }
    }"""
ble_content = ble_content.replace(old_enqueue, new_enqueue)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(ble_content)
