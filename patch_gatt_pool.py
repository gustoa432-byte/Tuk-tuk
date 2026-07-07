import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

# Fix 1: Explicit removal in onConnectionStateChange
old_state_change = """                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val address = gatt.device.address
                        discoveredDevices.removeIf { it.address == address }"""

new_state_change = """                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val address = gatt.device.address
                        activeGattConnections.remove(address)
                        activeMtuMap.remove(address)
                        connectionLastUsedMap.remove(address)
                        discoveredDevices.removeIf { it.address == address }"""
content = content.replace(old_state_change, new_state_change)

# Fix 2: Batch freeze (Call handleOperationResult on null characteristic)
old_freeze = """            } else {
                disconnectGatt(existingGatt)
                // Let the next iteration retry by establishing a new connection
            }
            return"""
            
new_freeze = """            } else {
                disconnectGatt(existingGatt)
                handleOperationResult(messageId, device.address, false)
                // Let the next iteration retry by establishing a new connection
            }
            return"""
content = content.replace(old_freeze, new_freeze)


# Fix 3: Idle timeout map and job
maps = """    private val activeGattConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private val activeMtuMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val connectionLastUsedMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var idleCleanupJob: Job? = null"""
content = content.replace("    private val activeGattConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()\n    private val activeMtuMap = java.util.concurrent.ConcurrentHashMap<String, Int>()", maps)

# update connectionLastUsedMap in sendPayloadToDevice for reuse
old_reuse = """        if (existingGatt != null) {
            Log.d(TAG, "Reusing existing GATT connection for ${device.address}")"""
new_reuse = """        if (existingGatt != null) {
            Log.d(TAG, "Reusing existing GATT connection for ${device.address}")
            connectionLastUsedMap[device.address] = System.currentTimeMillis()"""
content = content.replace(old_reuse, new_reuse)

# update connectionLastUsedMap in sendPayloadToDevice for new connection
old_new_conn = """                        activeGattConnections[address] = gatt
                        activeMtuMap[address] = currentMtu
                        
                        val service = gatt.getService(SERVICE_UUID)"""
new_new_conn = """                        activeGattConnections[address] = gatt
                        activeMtuMap[address] = currentMtu
                        connectionLastUsedMap[address] = System.currentTimeMillis()
                        
                        val service = gatt.getService(SERVICE_UUID)"""
content = content.replace(old_new_conn, new_new_conn)

# Add startIdleCleanupLoop
idle_cleanup = """    private fun startIdleCleanupLoop() {
        idleCleanupJob?.cancel()
        idleCleanupJob = scope.launch {
            while (isActive) {
                delay(10000)
                val now = System.currentTimeMillis()
                val timeoutMs = 60_000L
                for ((address, lastUsed) in connectionLastUsedMap.entries) {
                    if (now - lastUsed > timeoutMs) {
                        Log.d(TAG, "GATT Idle timeout for $address")
                        val gatt = activeGattConnections[address]
                        if (gatt != null) {
                            disconnectGatt(gatt)
                        }
                        connectionLastUsedMap.remove(address)
                    }
                }
            }
        }
    }"""
content = content.replace("    fun startMesh() {", idle_cleanup + "\n\n    fun startMesh() {")

# Call startIdleCleanupLoop in startMesh
content = content.replace("        try {\n            startGattServer()", "        try {\n            startIdleCleanupLoop()\n            startGattServer()")

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
