package com.blink.dtn.telemetry

import kotlinx.serialization.Serializable

/**
 * One stage in a message (or identity) delivery black-box recording.
 * Designed to be over-complete: prefer logging "too much" over missing a rare failure mode.
 */
@Serializable
data class TraceEvent(
    val timestamp: Long,
    val elapsedFromStartMs: Long,
    val stage: String,
    val details: Map<String, String> = emptyMap()
)

@Serializable
enum class TraceKind {
    MESSAGE,
    IDENTITY
}

@Serializable
data class MessageTrace(
    val traceId: String,
    val kind: TraceKind = TraceKind.MESSAGE,
    var messageId: String? = null,
    var conversationId: String? = null,
    var targetId: String? = null,
    var senderId: String? = null,
    var messageType: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long? = null,
    /** Delivered | Expired | Dropped | RetryLimit | Timeout | Pending | Failed */
    var terminalStatus: String? = null,
    val events: MutableList<TraceEvent> = mutableListOf(),
    val visualSteps: MutableList<String> = mutableListOf()
) {
    fun elapsed(): Long = System.currentTimeMillis() - startedAt
}

@Serializable
data class DeviceSnapshot(
    val brand: String,
    val model: String,
    val manufacturer: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val bluetoothLeSupported: Boolean,
    val totalRamMb: Long,
    val availRamMb: Long,
    val batteryPct: Int?,
    val isPowerSave: Boolean?,
    val capturedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MeshSnapshot(
    val peerCount: Int,
    val activePeers: List<String>,
    val capturedAt: Long = System.currentTimeMillis()
)

@Serializable
data class TraceExportBundle(
    val exportedAt: Long,
    val appVersion: String,
    val device: DeviceSnapshot,
    val mesh: MeshSnapshot?,
    val traces: List<MessageTrace>,
    val notes: String = "TukTuk MessageTrace black-box export"
)

object TraceStages {
    // UI
    const val UI_SEND_PRESSED = "UI.SendPressed"
    // Prep
    const val PREP_ENTITY = "Prep.MessageEntity"
    const val PREP_PACKET = "Prep.Packet"
    // DB
    const val DB_INSERT_START = "DB.InsertStart"
    const val DB_INSERT_DONE = "DB.InsertDone"
    // RSA
    const val RSA_KEY_CHECK = "RSA.KeyCheck"
    const val RSA_ENCRYPT_START = "RSA.EncryptStart"
    const val RSA_ENCRYPT_DONE = "RSA.EncryptDone"
    const val RSA_ENCRYPT_FAIL = "RSA.EncryptFail"
    const val RSA_MISSING_KEY = "RSA.MissingKey"
    const val RSA_DECRYPT_START = "RSA.DecryptStart"
    const val RSA_DECRYPT_DONE = "RSA.DecryptDone"
    const val RSA_DECRYPT_FAIL = "RSA.DecryptFail"
    // Chunking
    const val CHUNK_ENCODE = "Chunk.Encode"
    // Queue / Relay
    const val QUEUE_ADDED = "Queue.Added"
    const val RELAY_WAKE = "Relay.Wake"
    const val RELAY_PROCESS = "Relay.Process"
    // BLE / GATT
    const val BLE_PEERS = "BLE.Peers"
    const val GATT_CONNECT_START = "GATT.ConnectStart"
    const val GATT_CONNECT_OK = "GATT.ConnectOk"
    const val GATT_CONNECT_FAIL = "GATT.ConnectFail"
    const val GATT_MTU = "GATT.Mtu"
    const val GATT_SERVICES = "GATT.Services"
    const val GATT_READY = "GATT.Ready"
    const val GATT_WRITE_START = "GATT.WriteStart"
    const val GATT_WRITE_DONE = "GATT.WriteDone"
    const val GATT_WRITE_FAIL = "GATT.WriteFail"
    // TX complete
    const val TX_ALL_CHUNKS = "TX.AllChunksSent"
    const val TX_BATCH_RESULT = "TX.BatchResult"
    // Mesh hops
    const val MESH_SEEN = "Mesh.Seen"
    const val MESH_FORWARD = "Mesh.Forward"
    const val MESH_RELAY_STORE = "Mesh.RelayStore"
    const val MESH_SKIP = "Mesh.Skip"
    // RX
    const val RX_PACKET = "RX.Packet"
    const val RX_ASSEMBLED = "RX.Assembled"
    const val RX_CONVERSATION = "RX.Conversation"
    // ACK
    const val ACK_GENERATED = "ACK.Generated"
    const val ACK_QUEUED = "ACK.Queued"
    const val ACK_RECEIVED = "ACK.Received"
    // Identity
    const val ID_REQUEST = "Identity.Request"
    const val ID_ANNOUNCE = "Identity.Announce"
    const val ID_STORED = "Identity.Stored"
    const val ID_USED = "Identity.Used"
    // Terminal
    const val DONE = "Done"
}

fun detailsOf(vararg pairs: Pair<String, Any?>): Map<String, String> =
    pairs.mapNotNull { (k, v) ->
        if (v == null) null else k to v.toString()
    }.toMap()
