import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

old_else = """                        } else {
                            messageBackoffMap[networkMessage.id] = now + 5000L
                            continue
                        }"""

new_else = """                        } else {
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
                        }"""

content = content.replace(old_else, new_else)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
