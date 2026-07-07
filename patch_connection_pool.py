import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

# 1. Add Maps
maps = """    private val messageBackoffMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val activeGattConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private val activeMtuMap = java.util.concurrent.ConcurrentHashMap<String, Int>()"""
content = content.replace("    private val messageBackoffMap = java.util.concurrent.ConcurrentHashMap<String, Long>()", maps)

# 2. Update disconnectGatt
old_disconnect = """    private fun disconnectGatt(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            // ignore
        }
    }"""
new_disconnect = """    private fun disconnectGatt(gatt: BluetoothGatt) {
        try {
            val address = gatt.device.address
            activeGattConnections.remove(address)
            activeMtuMap.remove(address)
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            // ignore
        }
    }"""
content = content.replace(old_disconnect, new_disconnect)

# 3. Update sendPayloadToDevice
old_send = re.search(r'    private fun sendPayloadToDevice\(device: BluetoothDevice, payload: ByteArray, messageId: String\) \{.*?\n    \}\n', content, flags=re.DOTALL).group(0)

new_send = """    private fun sendPayloadToDevice(device: BluetoothDevice, payload: ByteArray, messageId: String) {
        val msgId = java.util.UUID.randomUUID().hashCode()
        val existingGatt = activeGattConnections[device.address]
        
        if (existingGatt != null) {
            Log.d(TAG, "Reusing existing GATT connection for ${device.address}")
            val service = existingGatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                val currentMtu = activeMtuMap[device.address] ?: 20
                val maxChunkSize = currentMtu - 10
                val safeChunkSize = if (maxChunkSize > 0) maxChunkSize else 10
                val chunks = payload.toList().chunked(safeChunkSize)
                val totalChunks = chunks.size
                for ((index, chunkList) in chunks.withIndex()) {
                    val b0 = (msgId shr 24).toByte()
                    val b1 = (msgId shr 16).toByte()
                    val b2 = (msgId shr 8).toByte()
                    val b3 = msgId.toByte()
                    val header = byteArrayOf(0xAB.toByte(), b0, b1, b2, b3, index.toByte(), totalChunks.toByte())
                    val chunkBytes = header + chunkList.toByteArray()
                    enqueueOperation(BleOperation(existingGatt, characteristic, chunkBytes, msgId, messageId))
                }
            } else {
                disconnectGatt(existingGatt)
                // Let the next iteration retry by establishing a new connection
            }
            return
        }

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                var currentMtu = 20 // Default BLE MTU

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException requesting MTU: ${e.message}")
                            disconnectGatt(gatt)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val address = gatt.device.address
                        discoveredDevices.removeIf { it.address == address }
                        connectedGattClients.removeIf { it.address == address }
                        _peerCount.value = discoveredDevices.size
                        _activePeers.value = discoveredDevices.map { it.address }
                        clearPendingOperationsForDevice(address)
                        disconnectGatt(gatt)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        currentMtu = mtu
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            disconnectGatt(gatt)
                        }
                    } else {
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            disconnectGatt(gatt)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val address = gatt.device.address
                        activeGattConnections[address] = gatt
                        activeMtuMap[address] = currentMtu
                        
                        val service = gatt.getService(SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            val maxChunkSize = currentMtu - 10
                            val safeChunkSize = if (maxChunkSize > 0) maxChunkSize else 10
                            val chunks = payload.toList().chunked(safeChunkSize)
                            val totalChunks = chunks.size
                            for ((index, chunkList) in chunks.withIndex()) {
                                val b0 = (msgId shr 24).toByte()
                                val b1 = (msgId shr 16).toByte()
                                val b2 = (msgId shr 8).toByte()
                                val b3 = msgId.toByte()
                                val header = byteArrayOf(0xAB.toByte(), b0, b1, b2, b3, index.toByte(), totalChunks.toByte())
                                val chunkBytes = header + chunkList.toByteArray()
                                enqueueOperation(BleOperation(gatt, characteristic, chunkBytes, msgId, messageId))
                            }
                        } else {
                            disconnectGatt(gatt)
                        }
                    } else {
                        disconnectGatt(gatt)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    val address = gatt.device.address
                    val queue = deviceQueues[address]
                    val op = queue?.peek()
                    
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Failed to send chunk for message ${op?.messageId} to $address, status: $status")
                        if (op != null) {
                            completeOperation(address, op, success = false)
                            handleOperationResult(op.messageId, address, false)
                        }
                        disconnectGatt(gatt)
                    } else {
                        if (op != null) {
                            Log.d(TAG, "Successfully sent chunk for message ${op.messageId} to $address")
                            completeOperation(address, op, success = true)
                            val queueAfter = deviceQueues[address]
                            val hasMoreOfSameMessage = queueAfter?.any { it.messageId == op.messageId } == true
                            if (!hasMoreOfSameMessage) {
                                handleOperationResult(op.messageId, address, true)
                            }
                        }
                        // Pool modification: do not disconnect GATT here, connection stays alive!
                    }
                }
            })
            Log.d(TAG, "Attempting to send to ${device.address} messageId=$messageId")
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting GATT client: ${e.message}")
        }
    }
"""
content = content.replace(old_send, new_send)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
