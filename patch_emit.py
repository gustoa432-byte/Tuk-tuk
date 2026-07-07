import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

old_emit = """    private fun safeEmitResult(result: TxResult) {
        val channelResult = _txResults.trySend(result)
        if (channelResult.isClosed) {
            val exception = channelResult.exceptionOrNull()

            Log.w("DTN", "Channel closed, dropped result: $result, exception: ${exception?.message}")
        }
    }"""

new_emit = """    private fun safeEmitResult(result: TxResult) {
        if (result is TxResult.Success) {
            android.util.Log.i("ROUTE", "Message ${result.msgId} TxResult: Success")
        } else if (result is TxResult.Failure) {
            android.util.Log.e("ROUTE", "Message ${result.msgId} TxResult: Failure. Failed MACs: ${result.failedMacs}")
        }
        val channelResult = _txResults.trySend(result)
        if (channelResult.isClosed) {
            val exception = channelResult.exceptionOrNull()

            Log.w("DTN", "Channel closed, dropped result: $result, exception: ${exception?.message}")
        }
    }"""

content = content.replace(old_emit, new_emit)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
