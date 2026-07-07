import re

with open("app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt", "r") as f:
    content = f.read()

# 1. SeenPacket move
old_seen_packet = """            // Drop expired packets to prevent resurrection of dead data across the mesh
            val messageTtlMs = 48 * 60 * 60 * 1000L
            if (System.currentTimeMillis() - packet.timestamp > messageTtlMs) {
                return@launch
            }
            
            // Save to SeenPackets immediately to prevent DoS from invalid signatures
            dao.insertSeenPacket(SeenPacket(packet.id, System.currentTimeMillis()))

            if (packet.type == "SYSTEM_ANNOUNCEMENT") {
                val isValid = withContext(Dispatchers.Default) {
                    SecurityConfig.verifySignature(packet.text, packet.authorSignature)
                }
                if (!isValid) {
                    dao.blockUser(BlockedUser(packet.senderNick, System.currentTimeMillis()))
                    return@launch
                }
            }"""

new_seen_packet = """            // Drop expired packets to prevent resurrection of dead data across the mesh
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
            dao.insertSeenPacket(SeenPacket(packet.id, System.currentTimeMillis()))"""

content = content.replace(old_seen_packet, new_seen_packet)

# 2. Fix PendingKey bug (call enqueueMessage)
old_pending = """                    if (pubKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            val updatedMsg = msg.copy(status = com.blink.dtn.db.Message.STATUS_PENDING)
                            dao.updateMessageInternal(updatedMsg)
                        }
                    }"""

new_pending = """                    if (pubKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            enqueueMessage(msg)
                        }
                    }"""

content = content.replace(old_pending, new_pending)

with open("app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt", "w") as f:
    f.write(content)
