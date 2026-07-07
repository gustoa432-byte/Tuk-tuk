import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

old_block = """                    if (pubKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            val encryptedText = com.blink.dtn.crypto.RsaUtils.encryptAsymmetric(msg.text, pubKey)
                            dao.updateMessageStatus(msg.id, com.blink.dtn.db.Message.STATUS_PENDING)
                            val networkMsg = msg.copy(text = encryptedText, status = com.blink.dtn.db.Message.STATUS_PENDING)
                            enqueueMessage(networkMsg)
                        }
                    }"""

new_block = """                    if (pubKey.isNotEmpty()) {
                        val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
                        for (msg in pendingMsgs) {
                            val encryptedText = com.blink.dtn.crypto.RsaUtils.encryptAsymmetric(msg.text, pubKey)
                            val updatedMsg = msg.copy(text = encryptedText, status = com.blink.dtn.db.Message.STATUS_PENDING)
                            dao.updateMessageInternal(updatedMsg)
                        }
                    }"""

content = content.replace(old_block, new_block)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
