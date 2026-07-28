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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private suspend fun tickOnce() {
        val messages = deps.queuedMessages()
        val now = System.currentTimeMillis()
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
            // DTN: wait for devices, do not fail the message.
            return
        }

        deps.trace(
            message.id,
            com.blink.dtn.telemetry.TraceStages.BLE_PEERS,
            com.blink.dtn.telemetry.detailsOf(
                "peersCount" to peers.size,
                "nearbyDevices" to peers.joinToString(",") { it.address }
            )
        )

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
                    deps.trace(
                        networkMessage.id,
                        com.blink.dtn.telemetry.TraceStages.RSA_ENCRYPT_START,
                        com.blink.dtn.telemetry.detailsOf(
                            "keyFingerprint" to com.blink.dtn.crypto.NodeIdentity.deriveNodeId(profile.publicKey),
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
            val jsonPayload = Json.encodeToString(wirePacket)
            bytes = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)
        } catch (e: Exception) {
            Log.e("ROUTE", "Relay encode/encrypt failed for ${message.id}: ${e.message}")
            messageBackoffMap[message.id] =
                System.currentTimeMillis() + calculateBackoff(message.retryCount)
            return
        }

        val validDevices = peers.filter { device ->
            now >= deps.peerBackoffUntil(device.address)
        }

        Log.i(
            "ROUTE",
            "Processing message ${message.id} attempt=${message.retryCount} to ${validDevices.size} valid devices"
        )

        if (validDevices.isEmpty()) {
            messageBackoffMap[message.id] = now + 5_000L
            return
        }

        val batch = TxBatch(validDevices.size)
        activeBatches[message.id] = batch
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

    companion object {
        fun calculateBackoff(retryCount: Int): Long {
            val baseMs = 5_000L
            return baseMs * (1 shl minOf(retryCount, 6))
        }
    }
}
