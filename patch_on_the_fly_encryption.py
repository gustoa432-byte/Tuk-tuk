import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

# Fix 1: Do not overwrite plain text in handleIncomingPacket
old_pending = """                    if (pubKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            val encryptedText = com.blink.dtn.crypto.RsaUtils.encryptAsymmetric(msg.text, pubKey)
                            val updatedMsg = msg.copy(text = encryptedText, status = com.blink.dtn.db.Message.STATUS_PENDING)
                            dao.updateMessageInternal(updatedMsg)
                        }
                    }"""

new_pending = """                    if (pubKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            val updatedMsg = msg.copy(status = com.blink.dtn.db.Message.STATUS_PENDING)
                            dao.updateMessageInternal(updatedMsg)
                        }
                    }"""
content = content.replace(old_pending, new_pending)

# Fix 2: On-the-fly encryption in startRelayLoop
old_relay = """                if (discoveredDevices.isEmpty()) {
                    // DTN: Just wait for devices, do not fail
                    continue
                }
                
                val jsonPayload = Json.encodeToString(message)
                val bytes = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)"""

new_relay = """                if (discoveredDevices.isEmpty()) {
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
                            networkMessage = networkMessage.copy(text = encryptedText)
                        } else {
                            messageBackoffMap[networkMessage.id] = now + 5000L
                            continue
                        }
                    }
                }
                
                val jsonPayload = Json.encodeToString(networkMessage)
                val bytes = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)"""
content = content.replace(old_relay, new_relay)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
