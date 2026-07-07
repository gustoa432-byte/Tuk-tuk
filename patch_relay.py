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
            Log.d("QUEUE", "Message enqueued: ${updatedMsg.id}")
            triggerRelay()
        }"""
content = content.replace(old_enqueue, new_enqueue)

# 3. Call triggerRelay when discovering devices
content = content.replace("enqueueProfileBroadcast()", "enqueueProfileBroadcast()\n                triggerRelay()")
content = content.replace("                        _activePeers.value = discoveredDevices.map { it.address }\n                }\n            } else if", "                        _activePeers.value = discoveredDevices.map { it.address }\n                    triggerRelay()\n                }\n            } else if")

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
