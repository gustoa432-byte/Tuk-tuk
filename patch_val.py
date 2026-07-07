import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

old_enqueue = """    fun enqueueMessage(message: Message) {
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

new_enqueue = """    fun enqueueMessage(message: Message) {
        scope.launch {
            val updatedMsg = message.copy(status = com.blink.dtn.db.Message.STATUS_PENDING)
            val existing = dao.getMessageById(updatedMsg.id)
            if (existing == null) {
                dao.insertMessageWithConversation(updatedMsg)
            } else {
                dao.updateMessageInternal(updatedMsg)
            }
        }
    }"""

content = content.replace(old_enqueue, new_enqueue)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
