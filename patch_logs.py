import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

# Replace TAG with literal strings or update usages.
content = content.replace('Log.e(TAG, "SecurityException requesting MTU: ${e.message}")', 'Log.e("BLE_TX", "SecurityException requesting MTU: ${e.message}")')
content = content.replace('Log.d(TAG, "Attempting to send to ${device.address} messageId=$messageId")', 'Log.d("BLE_TX", "Attempting to send to ${device.address} messageId=$messageId")')
content = content.replace('Log.e(TAG, "Exception connecting GATT client: ${e.message}")', 'Log.e("BLE_TX", "Exception connecting GATT client: ${e.message}")')
content = content.replace('Log.e(TAG, "Failed to send chunk for message ${op?.messageId} to $address, status: $status")', 'Log.e("BLE_TX", "Failed to send chunk for message ${op?.messageId} to $address, status: $status")')
content = content.replace('Log.d(TAG, "Successfully sent chunk for message ${op.messageId} to $address")', 'Log.d("BLE_TX", "Successfully sent chunk for message ${op.messageId} to $address")')
content = content.replace('Log.d(TAG, "Reusing existing GATT connection for ${device.address}")', 'Log.d("BLE_TX", "Reusing existing GATT connection for ${device.address}")')
content = content.replace('Log.e(TAG, "Exception writing characteristic: ${e.message}")', 'Log.e("BLE_TX", "Exception writing characteristic: ${e.message}")')
content = content.replace('Log.e(TAG, "writeCharacteristic failed (legacy). Payload size: ${op.payload.size}")', 'Log.e("BLE_TX", "writeCharacteristic failed (legacy). Payload size: ${op.payload.size}")')
content = content.replace('Log.d(TAG, "GATT Idle timeout for $address")', 'Log.d("BLE_TX", "GATT Idle timeout for $address")')
content = content.replace('Log.e(TAG, "Exception in relay loop', 'Log.e("ROUTE", "Exception in relay loop')

# In startRelayLoop
content = content.replace('Log.w("BleTx", "Message ${message.id} expired or TTL <= 0")', 'Log.w("ROUTE", "Message ${message.id} expired or TTL <= 0")')
content = content.replace('Log.i(TAG, "Packet successfully reassembled: ${messageId}")', 'Log.i("DTN", "Packet successfully reassembled: ${messageId}")')
content = content.replace('Log.i("KeyExchange", "Successfully saved public key for Node: ${packet.senderId}")', 'Log.i("DTN", "Successfully saved public key for Node: ${packet.senderId}")')
content = content.replace('Log.d(TAG, "Dropping previously processed packet ${packet.id}")', 'Log.d("DTN", "Dropping previously processed packet ${packet.id}")')


# Fix emit logs
safeEmit = """    private fun safeEmitResult(result: TxResult) {
        scope.launch {
            _txResults.emit(result)
        }
    }"""
newSafeEmit = """    private fun safeEmitResult(result: TxResult) {
        scope.launch {
            if (result is TxResult.Success) {
                android.util.Log.i("BLE_TX", "Message ${result.msgId} TxResult: Success")
            } else if (result is TxResult.Failure) {
                android.util.Log.e("BLE_TX", "Message ${result.msgId} TxResult: Failure. Failed MACs: ${result.failedMacs}")
            }
            _txResults.emit(result)
        }
    }"""
content = content.replace(safeEmit, newSafeEmit)


with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
