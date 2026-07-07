import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

old_handle = """                if (s > 0) {
                    safeEmitResult(TxResult.Success(messageId))
                } else {
                    safeEmitResult(TxResult.Failure(messageId, batch.failedMacs.toList()))
                }"""

new_handle = """                if (s > 0) {
                    safeEmitResult(TxResult.Success(messageId))
                } else {
                    messageBackoffMap[messageId] = System.currentTimeMillis() + 10_000L
                    safeEmitResult(TxResult.Failure(messageId, batch.failedMacs.toList()))
                }"""

content = content.replace(old_handle, new_handle)

# Also handle the batch == null case
old_null_batch = """        if (batch == null) {
            if (success) {
                safeEmitResult(TxResult.Success(messageId))
            } else {
                safeEmitResult(TxResult.Failure(messageId, if (mac != null) listOf(mac) else emptyList()))
            }
            return
        }"""
        
new_null_batch = """        if (batch == null) {
            if (success) {
                safeEmitResult(TxResult.Success(messageId))
            } else {
                messageBackoffMap[messageId] = System.currentTimeMillis() + 10_000L
                safeEmitResult(TxResult.Failure(messageId, if (mac != null) listOf(mac) else emptyList()))
            }
            return
        }"""
content = content.replace(old_null_batch, new_null_batch)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
