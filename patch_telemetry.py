import re

with open("app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt", "r") as f:
    content = f.read()

# BLE_QUEUE
old_queue = """            android.util.Log.d("QUEUE", "Message enqueued: ${updatedMsg.id}")
            triggerRelay()"""
new_queue = """            android.util.Log.d("BLE_QUEUE", "MessageId=${updatedMsg.id} Type=${updatedMsg.type} Receiver=${updatedMsg.targetId ?: "null"} RetryCount=${updatedMsg.retryCount}")
            triggerRelay()"""
content = content.replace(old_queue, new_queue)

# BLE_TX
old_execute = """    private fun executeWrite(op: BleOperation, address: String) {
        try {
            var successFlag = false"""
new_execute = """    private fun executeWrite(op: BleOperation, address: String) {
        try {
            android.util.Log.d("BLE_TX", "MessageId=${op.messageId} DeviceMAC=${address} PayloadSize=${op.payload.size}")
            var successFlag = false"""
content = content.replace(old_execute, new_execute)

# BLE_WRITE_FAIL
old_write_fail = """                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLE_TX", "Failed to send chunk for message ${op?.messageId} to $address, status: $status")"""
new_write_fail = """                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLE_WRITE_FAIL", "MessageId=${op?.messageId} status=false gattStatus=$status")"""
content = content.replace(old_write_fail, new_write_fail)

# BLE_WRITE_OK
old_write_ok = """                        if (op != null) {
                            Log.d("BLE_TX", "Successfully sent chunk for message ${op.messageId} to $address")"""
new_write_ok = """                        if (op != null) {
                            Log.d("BLE_WRITE_OK", "MessageId=${op.messageId} DeviceMAC=$address")"""
content = content.replace(old_write_ok, new_write_ok)

# BLE_RX_RAW, BLE_PACKET, BLE_PROCESS
old_rx = """                    val jsonString = com.blink.dtn.crypto.CryptoUtils.decrypt(assembledValue) ?: throw Exception("Decryption returned null")
                    val message = Json.decodeFromString<Message>(jsonString)
                    Log.d("DTN", "Successfully reassembled message ${message.id} type=${message.type}")
                    handleIncomingPacket(message)"""
new_rx = """                    val jsonString = com.blink.dtn.crypto.CryptoUtils.decrypt(assembledValue) ?: throw Exception("Decryption returned null")
                    val message = Json.decodeFromString<Message>(jsonString)
                    Log.d("BLE_RX_RAW", "MessageId=${message.id} Size=${assembledValue.size} SenderMAC=${device.address}")
                    Log.d("BLE_PACKET", "Type=${message.type} SenderId=${message.senderId} ReceiverId=${message.targetId ?: "null"} TTL=${message.ttl}")
                    Log.d("BLE_PROCESS", "MessageId=${message.id} Type=${message.type}")
                    handleIncomingPacket(message)"""
content = content.replace(old_rx, new_rx)

with open("app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt", "w") as f:
    f.write(content)
