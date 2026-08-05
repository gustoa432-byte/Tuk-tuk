package com.blink.dtn.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.blink.dtn.db.Message
import com.blink.dtn.db.UserProfile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Store-and-forward relay loop + TX batch accounting, extracted from [BleMeshManager].
 */
internal class BleRelayEngine(
    private val scopeProvider: () -> CoroutineScope,
    private val deps: Deps
) {
    interface Deps {
        suspend fun queuedMessages(): List<Message>
        fun peerDevices(): Collection<BluetoothDevice>
        fun peerBackoffUntil(mac: String): Long
        fun myNodeId(): String
        fun currentNick(): String
        suspend fun profile(targetId: String): UserProfile?
        suspend fun updateMessage(msg: Message)
        fun enqueueMessage(msg: Message)
        fun sendPayload(device: BluetoothDevice, bytes: ByteArray, messageId: String)
        fun setPeerBackoff(mac: String, durationMs: Long)
        fun trace(messageId: String, stage: String, details: Map<String, String> = emptyMap(), visual: String? = null)
        fun emitTxResult(result: TxResult)
        fun defaultTtl(): Int
        /** Prefer Wi‑Fi Direct hop when a group is up; false → fall back to BLE. */
        suspend fun tryAlternateTransport(bytes: ByteArray, messageId: String): Boolean = false
        fun maxPeersPerBatch(): Int = 6

        // ── Drop Policy ──────────────────────────────────────────────────────
        /** Max total public messages stored. Excess triggers Drop Policy. */
        fun publicMessageQueueLimit(): Int = 2_000
        suspend fun countPublicMessages(): Int = 0
        suspend fun countFloodMessages(): Int = 0
        /** Delete oldest [n] FLOOD ("9") messages. */
        suspend fun deleteOldestFloodMessages(n: Int) {}
        /** Delete oldest [n] non-SOS public messages (second-tier drop). */
        suspend fun deleteOldestNonSosMessages(n: Int) {}
        suspend fun isSenderBlocked(userId: String, nick: String): Boolean = false
        suspend fun deleteQueuedMessage(messageId: String) {}
        /** Oracle-preferred courier nodeIds (from hint). Empty = no preference. */
        fun oraclePriorityNodeIds(): Set<String> = emptySet()
        fun nodeIdForMac(mac: String): String? = null
        /** Refresh Oracle hints for [targetNode] when online + JWT. */
        suspend fun refreshOracleHints(targetNode: String?) {}
    }

    private class TxBatch(val totalAttempts: Int) {
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val failedMacs = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val isResolved = AtomicBoolean(false)
        var watchdogJob: Job? = null
    }

    private val messageBackoffMap = ConcurrentHashMap<String, Long>()
    private val activeBatches = ConcurrentHashMap<String, TxBatch>()
    private val relayTrigger = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )
    private var relayJob: Job? = null

    fun trigger() {
        relayTrigger.trySend(Unit)
    }

    fun hasActiveBatch(messageId: String): Boolean = activeBatches.containsKey(messageId)

    fun dropBatch(messageId: String) {
        activeBatches[messageId]?.watchdogJob?.cancel()
        activeBatches.remove(messageId)
        messageBackoffMap.remove(messageId)
    }

    fun clear() {
        activeBatches.values.forEach { it.watchdogJob?.cancel() }
        activeBatches.clear()
        messageBackoffMap.clear()
    }

    fun stop() {
        relayJob?.cancel()
        relayJob = null
        clear()
    }

    fun start() {
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            Log.e("ROUTE", "Exception in relay loop: ${exception.message}")
        }
        relayJob?.cancel()
        relayJob = scopeProvider().launch(exceptionHandler) {
            while (isActive) {
                tickOnce()
            }
        }
    }

    fun onPeerWriteResult(messageId: String, mac: String?, success: Boolean, softRetry: Boolean = false) {
        if (mac != null && !success) {
            deps.setPeerBackoff(mac, if (softRetry) 500L else 10_000L)
        }
        val batch = activeBatches[messageId]
        if (batch == null) {
            if (success) {
                deps.emitTxResult(TxResult.Success(messageId))
            } else {
                messageBackoffMap[messageId] =
                    System.currentTimeMillis() + if (softRetry) 500L else 10_000L
                deps.emitTxResult(
                    TxResult.Failure(messageId, if (mac != null) listOf(mac) else emptyList())
                )
            }
            return
        }

        if (success) {
            batch.successes.incrementAndGet()
        } else {
            batch.failures.incrementAndGet()
            if (mac != null) batch.failedMacs.add(mac)
        }

        val s = batch.successes.get()
        val f = batch.failures.get()
        if (s + f >= batch.totalAttempts) {
            if (batch.isResolved.compareAndSet(false, true)) {
                batch.watchdogJob?.cancel()
                activeBatches.remove(messageId)
                if (s > 0) {
                    deps.trace(
                        messageId,
                        com.blink.dtn.telemetry.TraceStages.TX_BATCH_RESULT,
                        com.blink.dtn.telemetry.detailsOf(
                            "successes" to s,
                            "failures" to f,
                            "result" to "Success"
                        ),
                        visual = "📤 Передано соседям"
                    )
                    deps.emitTxResult(TxResult.Success(messageId))
                } else {
                    deps.trace(
                        messageId,
                        com.blink.dtn.telemetry.TraceStages.TX_BATCH_RESULT,
                        com.blink.dtn.telemetry.detailsOf(
                            "successes" to s,
                            "failures" to f,
                            "failedMacs" to batch.failedMacs.joinToString(","),
                            "result" to "Failure"
                        )
                    )
                    messageBackoffMap[messageId] = System.currentTimeMillis() + 10_000L
                    deps.emitTxResult(TxResult.Failure(messageId, batch.failedMacs.toList()))
                }
            }
        }
    }

    /**
     * QoS message comparator applied to the relay queue before pick.
     *
     * Priority tiers (lower = higher urgency):
     *   0 — System/ACK (IDENTITY_ANNOUNCEMENT, VERSION_ANNOUNCEMENT, ACK, IDENTITY_REQUEST)
     *   1 — SOS room "0"
     *   2 — Base rooms "1".."8"  (and PRIVATE messages regardless of room)
     *   3 — FLOOD room "9"
     *
     * Within the same priority tier, older messages (lower timestamp) go first
     * so we honour FIFO within each class.
     */
    private fun qosPriority(msg: Message): Int {
        if (msg.isAck) return 0
        return when (msg.type) {
            "IDENTITY_ANNOUNCEMENT", "VERSION_ANNOUNCEMENT",
            "IDENTITY_REQUEST", "SYSTEM_PROFILE", "UPDATE_REQUEST" -> 0
            "PRIVATE" -> 2      // PRIVATE always mid-priority regardless of room
            "PUBLIC", "SYSTEM_ANNOUNCEMENT" -> MeshRoom.priority(msg.room)
            else -> 2
        }
    }

    private suspend fun tickOnce() {
        // ── Drop Policy: overflow check before processing ────────────────────
        // Runs on every tick but is cheap: one COUNT(*) query, fast on the index.
        runDropPolicyIfNeeded()

        val rawMessages = deps.queuedMessages()
        val now = System.currentTimeMillis()

        // ── QoS sort: priority ASC, then timestamp ASC within same priority ──
        val messages = rawMessages.sortedWith(
            compareBy({ qosPriority(it) }, { it.timestamp })
        )

        var selectedMessage: Message? = null
        var nextWakeTime = Long.MAX_VALUE

        for (msg in messages) {
            if (activeBatches.containsKey(msg.id)) continue
            val backoff = messageBackoffMap[msg.id] ?: 0L
            if (now >= backoff) {
                selectedMessage = msg
                break
            } else if (backoff < nextWakeTime) {
                nextWakeTime = backoff
            }
        }

        val message = selectedMessage
        if (message == null) {
            var waitTime = 15_000L
            if (nextWakeTime != Long.MAX_VALUE) {
                waitTime = (nextWakeTime - now).coerceIn(100L, 15_000L)
            }
            kotlinx.coroutines.withTimeoutOrNull(waitTime) {
                relayTrigger.receive()
            }
            delay(200)
            return
        }

        if (message.senderId != deps.myNodeId() && deps.isSenderBlocked(message.senderId, message.senderNick)) {
            Log.i("ROUTE", "Drop queued packet from blocked sender ${message.senderId}")
            deps.deleteQueuedMessage(message.id)
            return
        }

        if (message.type == Message.TYPE_PRIVATE_IMAGE) {
            Log.w("ROUTE", "Skip mesh relay for PRIVATE_IMAGE ${message.id}")
            return
        }

        Log.d("ROUTE", "Processing message ${message.id} type=${message.type}")
        val peers = deps.peerDevices()
        deps.trace(
            message.id,
            com.blink.dtn.telemetry.TraceStages.RELAY_PROCESS,
            com.blink.dtn.telemetry.detailsOf(
                "type" to message.type,
                "ttl" to message.ttl,
                "retryCount" to message.retryCount,
                "peersCount" to peers.size
            ),
            visual = "🌫 Распыляется по сети"
        )

        val messageTtlMs = 48 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - message.timestamp > messageTtlMs || message.ttl <= 0) {
            Log.w("ROUTE", "Message ${message.id} expired or TTL <= 0")
            com.blink.dtn.telemetry.TraceStore.finish(
                message.id,
                "Expired",
                com.blink.dtn.telemetry.detailsOf(
                    "ttl" to message.ttl,
                    "ageMs" to (System.currentTimeMillis() - message.timestamp)
                )
            )
            deps.emitTxResult(TxResult.Failure(message.id, emptyList()))
            return
        }

        if (peers.isEmpty()) {
            deps.trace(
                message.id,
                com.blink.dtn.telemetry.TraceStages.BLE_PEERS,
                com.blink.dtn.telemetry.detailsOf("peersCount" to 0, "nearbyDevices" to "")
            )
        } else {
            deps.trace(
                message.id,
                com.blink.dtn.telemetry.TraceStages.BLE_PEERS,
                com.blink.dtn.telemetry.detailsOf(
                    "peersCount" to peers.size,
                    "nearbyDevices" to peers.joinToString(",") { it.address }
                )
            )
        }

        var networkMessage = message
        if (networkMessage.type == "PRIVATE" &&
            networkMessage.senderId == deps.myNodeId() &&
            networkMessage.text.isNotEmpty()
        ) {
            val targetId = networkMessage.targetId
            if (targetId != null) {
                val profile = deps.profile(targetId)
                if (profile != null && profile.publicKey.isNotEmpty()) {
                    val encStart = System.currentTimeMillis()
                    // publicKey from Room (QR / VPS /contacts/add handshake / mesh identity)
                    deps.trace(
                        networkMessage.id,
                        com.blink.dtn.telemetry.TraceStages.RSA_ENCRYPT_START,
                        com.blink.dtn.telemetry.detailsOf(
                            "keyFingerprint" to com.blink.dtn.crypto.NodeIdentity.deriveNodeId(profile.publicKey),
                            "keySource" to "user_profiles",
                            "plainUtf8Bytes" to networkMessage.text.toByteArray(Charsets.UTF_8).size
                        ),
                        visual = "🔐 Шифрование"
                    )
                    val encryptedText =
                        com.blink.dtn.crypto.RsaUtils.encryptAsymmetric(networkMessage.text, profile.publicKey)
                    if (encryptedText.isEmpty()) {
                        Log.e("ROUTE", "Private encryption failed for ${networkMessage.id}; backing off")
                        deps.trace(
                            networkMessage.id,
                            com.blink.dtn.telemetry.TraceStages.RSA_ENCRYPT_FAIL,
                            com.blink.dtn.telemetry.detailsOf(
                                "durationMs" to (System.currentTimeMillis() - encStart),
                                "error" to "encryptAsymmetric returned empty"
                            )
                        )
                        messageBackoffMap[networkMessage.id] =
                            System.currentTimeMillis() + calculateBackoff(networkMessage.retryCount)
                        return
                    }
                    deps.trace(
                        networkMessage.id,
                        com.blink.dtn.telemetry.TraceStages.RSA_ENCRYPT_DONE,
                        com.blink.dtn.telemetry.detailsOf(
                            "cipherLength" to encryptedText.length,
                            "encryptionDurationMs" to (System.currentTimeMillis() - encStart)
                        ),
                        visual = "🔐 Зашифровано"
                    )
                    networkMessage = networkMessage.copy(text = encryptedText)
                } else {
                    deps.updateMessage(networkMessage.copy(status = Message.STATUS_PENDING_KEY))
                    deps.trace(
                        networkMessage.id,
                        com.blink.dtn.telemetry.TraceStages.RSA_MISSING_KEY,
                        com.blink.dtn.telemetry.detailsOf(
                            "reason" to "key_missing_at_relay_time",
                            "queuedIdentityRequest" to true
                        ),
                        visual = "🔑 Ключ пропал — IDENTITY_REQUEST"
                    )
                    deps.enqueueMessage(
                        Message(
                            id = com.blink.dtn.utils.MeshIdGenerator.next(deps.myNodeId()),
                            type = "IDENTITY_REQUEST",
                            senderId = deps.myNodeId(),
                            senderNick = deps.currentNick(),
                            targetId = targetId,
                            text = "",
                            room = "system",
                            timestamp = System.currentTimeMillis(),
                            ttl = deps.defaultTtl()
                        )
                    )
                    return
                }
            }
        }

        val bytes: ByteArray
        try {
            val wirePacket = NetworkPacket.fromMessage(networkMessage)
            bytes = com.blink.dtn.crypto.CryptoUtils.packSigned(wirePacket)
        } catch (e: Exception) {
            Log.e("ROUTE", "Relay encode/sign failed for ${message.id}: ${e.message}")
            messageBackoffMap[message.id] =
                System.currentTimeMillis() + calculateBackoff(message.retryCount)
            return
        }

        // Prefer denser Wi‑Fi Direct hop when a group exists; BLE remains fallback.
        // Works even with zero BLE peers (group-only hop).
        if (deps.tryAlternateTransport(bytes, message.id)) {
            if (message.status == Message.STATUS_PENDING || message.status == Message.STATUS_FAILED) {
                deps.updateMessage(message.copy(status = Message.STATUS_IN_FLIGHT))
            }
            val path = com.blink.dtn.router.MessageRouter.pathFor(message.id)
            val transport = path?.traceId() ?: "alternate"
            deps.trace(
                message.id,
                com.blink.dtn.telemetry.TraceStages.TX_BATCH_RESULT,
                com.blink.dtn.telemetry.detailsOf(
                    "successes" to 1,
                    "failures" to 0,
                    "result" to "Success",
                    "transport" to transport
                ),
                visual = "📡 Ушло: ${path?.labelRu() ?: transport}"
            )
            deps.emitTxResult(TxResult.Success(message.id))
            return
        }

        if (peers.isEmpty()) {
            // DTN: wait for devices, do not fail the message.
            messageBackoffMap[message.id] = now + 8_000L
            return
        }

        // Oracle: when online, prefer hinted couriers toward PRIVATE target.
        if (networkMessage.type == "PRIVATE") {
            deps.refreshOracleHints(networkMessage.targetId)
        }

        val priorityNodes = deps.oraclePriorityNodeIds()
        val validDevices = peers.filter { device ->
            now >= deps.peerBackoffUntil(device.address)
        }.sortedByDescending { device ->
            val nid = deps.nodeIdForMac(device.address)
            when {
                nid != null && nid in priorityNodes -> 2
                priorityNodes.isEmpty() -> 0
                else -> 0
            }
        }.take(deps.maxPeersPerBatch().coerceAtLeast(1))

        Log.i(
            "ROUTE",
            "Processing message ${message.id} attempt=${message.retryCount} to ${validDevices.size} valid devices (oraclePri=${priorityNodes.size})"
        )

        if (validDevices.isEmpty()) {
            // Keep PENDING so UI stays "отправляется" until neighbors appear or retries exhaust.
            if (message.status == Message.STATUS_FAILED) {
                deps.updateMessage(message.copy(status = Message.STATUS_PENDING))
            }
            messageBackoffMap[message.id] = now + 5_000L
            return
        }

        val batch = TxBatch(validDevices.size)
        activeBatches[message.id] = batch
        // Surface "у соседей" in chat UI while GATT writes are in progress.
        if (message.status == Message.STATUS_PENDING ||
            message.status == Message.STATUS_FAILED ||
            message.status == Message.STATUS_PENDING_KEY
        ) {
            deps.updateMessage(message.copy(status = Message.STATUS_IN_FLIGHT))
        }
        batch.watchdogJob = scopeProvider().launch {
            delay(45_000L)
            if (batch.isResolved.compareAndSet(false, true)) {
                activeBatches.remove(message.id)
                Log.w("BLE_TX", "Watchdog timeout for message ${message.id}")
                messageBackoffMap[message.id] =
                    System.currentTimeMillis() + calculateBackoff(message.retryCount)
                deps.emitTxResult(TxResult.Failure(message.id, batch.failedMacs.toList()))
            }
        }

        for (device in validDevices) {
            deps.sendPayload(device, bytes, message.id)
        }
    }

    /**
     * Drop Policy: keeps the public message buffer under [Deps.publicMessageQueueLimit].
     *
     * Eviction order (strict hierarchy):
     *   1. Delete oldest FLOOD ("9") messages first.
     *   2. If still over limit — delete oldest non-SOS public messages.
     *   SOS ("0") messages are NEVER deleted by this policy.
     *
     * The policy evicts in batches of [DROP_BATCH_SIZE] to amortise the cost of
     * the COUNT query: one drop run removes up to [DROP_BATCH_SIZE] rows and the
     * next tick re-checks if another run is needed.
     */
    private suspend fun runDropPolicyIfNeeded() {
        val limit = deps.publicMessageQueueLimit()
        val total = deps.countPublicMessages()
        if (total <= limit) return

        val excess = total - limit
        val toDrop = minOf(excess + DROP_BATCH_SIZE, excess * 2).coerceAtLeast(1)

        Log.w("ROUTE", "Drop Policy triggered: total=$total limit=$limit dropping up to $toDrop")

        // Tier 1: flood messages
        val floodCount = deps.countFloodMessages()
        val floodDrop = minOf(toDrop, floodCount)
        if (floodDrop > 0) {
            deps.deleteOldestFloodMessages(floodDrop)
            Log.i("ROUTE", "Drop Policy: removed $floodDrop FLOOD messages")
        }

        // Tier 2: if flood didn't cover the excess, drop non-SOS
        val remaining = toDrop - floodDrop
        if (remaining > 0) {
            deps.deleteOldestNonSosMessages(remaining)
            Log.i("ROUTE", "Drop Policy: removed $remaining non-SOS messages (tier 2)")
        }
    }

    companion object {
        fun calculateBackoff(retryCount: Int): Long {
            val baseMs = 5_000L
            return baseMs * (1 shl minOf(retryCount, 6))
        }

        /** Messages removed per drop run to avoid heavy I/O spikes. */
        private const val DROP_BATCH_SIZE = 50
    }
}
