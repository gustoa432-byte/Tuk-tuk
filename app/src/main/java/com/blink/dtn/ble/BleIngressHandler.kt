package com.blink.dtn.ble

import android.util.Log
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.BlockedUser
import com.blink.dtn.db.Message
import com.blink.dtn.db.SeenPacket
import com.blink.dtn.security.SecurityConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Decode + ingress handling for packets arriving from the mesh.
 * Extracted from [BleMeshManager].
 */
internal class BleIngressHandler(
    private val dao: BLinkDao,
    private val myNodeId: String,
    private val scopeProvider: () -> CoroutineScope,
    private val deps: Deps
) {
    data class DecodedWirePacket(
        val message: Message,
        val dedupKey: String
    )

    interface Deps {
        fun currentNick(): String
        fun enqueueMessage(msg: Message)
        fun enqueueProfileBroadcast()
        fun notifyIncoming(packet: Message)
        fun ensureTrace(messageId: String, type: String? = null, senderId: String? = null, targetId: String? = null)
        fun trace(messageId: String, stage: String, details: Map<String, String> = emptyMap(), visual: String? = null)
        fun markSeen(dedupKey: String): Boolean
    }

    fun decodeWirePacket(jsonString: String): DecodedWirePacket {
        return try {
            val packet = Json.decodeFromString<NetworkPacket>(jsonString)
            DecodedWirePacket(
                message = packet.toMessage(),
                dedupKey = packet.packetId.ifEmpty { packet.messageId }
            )
        } catch (_: Exception) {
            val legacyMessage = Json.decodeFromString<Message>(jsonString)
            DecodedWirePacket(
                message = legacyMessage,
                dedupKey = legacyMessage.id
            )
        }
    }

    fun handle(packet: Message, dedupKey: String = packet.id) {
        Log.d("DTN", "Received packet: id=${packet.id} type=${packet.type} from=${packet.senderNick} ttl=${packet.ttl}")
        scopeProvider().launch {
            if (packet.senderId == myNodeId && !packet.isAck) {
                return@launch
            }

            val now = System.currentTimeMillis()

            if (!deps.markSeen(dedupKey)) {
                return@launch
            }
            if (dao.hasSeenPacket(dedupKey)) {
                return@launch
            }
            dao.insertSeenPacket(SeenPacket(dedupKey, now))

            val messageTtlMs = 48 * 60 * 60 * 1000L
            if (now - packet.timestamp > messageTtlMs) {
                return@launch
            }

            if (packet.type == "SYSTEM_ANNOUNCEMENT") {
                val isValid = withContext(Dispatchers.Default) {
                    SecurityConfig.verifySignature(packet.text, packet.authorSignature)
                }
                if (!isValid) {
                    dao.blockUser(BlockedUser(packet.senderNick, System.currentTimeMillis()))
                    return@launch
                }
            }

            if (packet.isAck) {
                val consumed = handleAck(packet, now)
                if (consumed) return@launch
            } else when (packet.type) {
                "IDENTITY_ANNOUNCEMENT", "SYSTEM_PROFILE" -> handleIdentity(packet)
                "IDENTITY_REQUEST" -> handleIdentityRequest(packet)
                "PUBLIC", "SYSTEM_ANNOUNCEMENT" -> {
                    dao.insertMessageWithConversation(packet)
                    deps.notifyIncoming(packet)
                }
                "PRIVATE" -> {
                    if (packet.targetId == myNodeId) {
                        handlePrivateForMe(packet)
                        return@launch
                    } else {
                        dao.insertRelayPacket(packet)
                        deps.trace(
                            packet.id,
                            com.blink.dtn.telemetry.TraceStages.MESH_RELAY_STORE,
                            com.blink.dtn.telemetry.detailsOf(
                                "ttl" to packet.ttl,
                                "from" to packet.senderId,
                                "to" to packet.targetId
                            ),
                            visual = "🚲 Relay дальше"
                        )
                    }
                }
            }

            packet.ttl = packet.ttl - 1
            if (packet.ttl > 0) {
                deps.trace(
                    packet.id,
                    com.blink.dtn.telemetry.TraceStages.MESH_FORWARD,
                    com.blink.dtn.telemetry.detailsOf("ttlAfter" to packet.ttl, "viaNode" to myNodeId)
                )
                deps.enqueueMessage(packet)
            } else {
                deps.trace(
                    packet.id,
                    com.blink.dtn.telemetry.TraceStages.MESH_SKIP,
                    com.blink.dtn.telemetry.detailsOf("reason" to "ttl_exhausted")
                )
            }
        }
    }

    /** @return true if this node consumed the ACK (no further flood). */
    private suspend fun handleAck(packet: Message, now: Long): Boolean {
        packet.originalMessageId?.takeIf { it.isNotEmpty() }?.let { ackedId ->
            deps.markSeen(ackedId)
            dao.insertSeenPacket(SeenPacket(ackedId, now))
            if (packet.targetId != myNodeId) {
                dao.deleteMessageById(ackedId)
            }
        }
        if (packet.targetId != myNodeId) return false

        val ackedMessageId = packet.originalMessageId?.takeIf { it.isNotEmpty() }
            ?: packet.text.takeIf { it.isNotEmpty() }
            ?: return true
        val ackedMsg = dao.getMessageById(ackedMessageId)
        val status = if (ackedMsg?.type == "PRIVATE") {
            Message.STATUS_DELIVERED
        } else {
            Message.STATUS_SENT
        }
        dao.updateMessageStatus(ackedMessageId, status)
        val latency = ackedMsg?.let { System.currentTimeMillis() - it.timestamp }
        deps.trace(
            ackedMessageId,
            com.blink.dtn.telemetry.TraceStages.ACK_RECEIVED,
            com.blink.dtn.telemetry.detailsOf(
                "ackFrom" to packet.senderId,
                "latencyMs" to latency,
                "status" to status
            ),
            visual = "✅ Доставлено"
        )
        com.blink.dtn.telemetry.TraceStore.finish(
            ackedMessageId,
            if (status == Message.STATUS_DELIVERED) "Delivered" else "Sent",
            com.blink.dtn.telemetry.detailsOf("ackLatencyMs" to latency)
        )
        return true
    }

    private suspend fun handleIdentity(packet: Message) {
        val parts = packet.text.split("|")
        if (parts.size < 2) return

        val nick = parts[0]
        val isVip = parts[1].toBoolean()
        val pubKey = if (parts.size >= 3) parts[2] else ""

        if (pubKey.isNotEmpty()) {
            val expectedId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pubKey)
            if (expectedId.isEmpty() || expectedId != packet.senderId) {
                Log.w(
                    "DTN",
                    "Rejected IDENTITY spoof: senderId=${packet.senderId} does not match key hash=$expectedId"
                )
                return
            }
        }

        val existingProfile = dao.getProfileById(packet.senderId)
        val keyChanged = pubKey.isNotEmpty() &&
            !existingProfile?.publicKey.isNullOrEmpty() &&
            existingProfile!!.publicKey != pubKey
        val trustedPublicKey = when {
            pubKey.isEmpty() -> existingProfile?.publicKey ?: ""
            existingProfile?.publicKey.isNullOrEmpty() -> pubKey
            existingProfile?.publicKey == pubKey -> pubKey
            else -> {
                Log.w("DTN", "Public key CHANGED for Node: ${packet.senderId}, accepting new key")
                pubKey
            }
        }
        dao.insertOrUpdateProfile(
            com.blink.dtn.db.UserProfile(
                packet.senderId,
                nick,
                System.currentTimeMillis(),
                isVip,
                trustedPublicKey
            )
        )
        Log.i("DTN", "Successfully saved public key for Node: ${packet.senderId}")
        if (trustedPublicKey.isNotEmpty()) {
            deps.ensureTrace(packet.id, "IDENTITY_ANNOUNCEMENT", packet.senderId, null)
            com.blink.dtn.telemetry.PeerDirectory.noteNode(packet.senderId, nick)
            deps.trace(
                packet.id,
                com.blink.dtn.telemetry.TraceStages.ID_STORED,
                com.blink.dtn.telemetry.detailsOf(
                    "nodeId" to packet.senderId,
                    "nick" to nick,
                    "keyFingerprint" to com.blink.dtn.crypto.NodeIdentity.deriveNodeId(trustedPublicKey),
                    "keyChanged" to keyChanged
                ),
                visual = "🔑 Ключ сохранён"
            )
        }

        if (keyChanged) {
            val undelivered = dao.getUndeliveredPrivateToPeer(packet.senderId, myNodeId)
            for (old in undelivered) {
                val resend = old.copy(
                    id = com.blink.dtn.utils.MeshIdGenerator.next(myNodeId),
                    status = Message.STATUS_PENDING,
                    retryCount = 0,
                    timestamp = System.currentTimeMillis()
                )
                dao.deleteMessageById(old.id)
                dao.insertMessageWithConversation(resend)
                deps.enqueueMessage(resend)
            }
        }

        if (existingProfile == null || existingProfile.publicKey.isEmpty()) {
            deps.enqueueProfileBroadcast()
        }

        if (trustedPublicKey.isNotEmpty()) {
            val pendingMsgs = dao.getMessagesPendingKeyForUser(packet.senderId)
            for (msg in pendingMsgs) {
                deps.enqueueMessage(msg)
            }
        }
    }

    private suspend fun handleIdentityRequest(packet: Message) {
        if (packet.targetId == myNodeId) {
            deps.enqueueProfileBroadcast()
        } else {
            dao.deleteTransitMessage(packet.id, myNodeId)
            dao.insertRelayPacket(packet)
        }
    }

    private suspend fun handlePrivateForMe(packet: Message) {
        deps.ensureTrace(packet.id, "PRIVATE", packet.senderId, packet.targetId)
        deps.trace(
            packet.id,
            com.blink.dtn.telemetry.TraceStages.RX_PACKET,
            com.blink.dtn.telemetry.detailsOf(
                "from" to packet.senderId,
                "ttl" to packet.ttl,
                "cipherLen" to packet.text.length
            ),
            visual = "📍 Получено устройством"
        )
        val decStart = System.currentTimeMillis()
        deps.trace(packet.id, com.blink.dtn.telemetry.TraceStages.RSA_DECRYPT_START)
        val plainText = com.blink.dtn.crypto.RsaUtils.decryptAsymmetric(packet.text)
        if (plainText.isEmpty()) {
            Log.e("DTN", "Private decrypt failed for message ${packet.id}; dropping without ACK")
            deps.trace(
                packet.id,
                com.blink.dtn.telemetry.TraceStages.RSA_DECRYPT_FAIL,
                com.blink.dtn.telemetry.detailsOf(
                    "durationMs" to (System.currentTimeMillis() - decStart),
                    "failureReason" to "empty_plaintext_stale_or_wrong_key"
                )
            )
            com.blink.dtn.telemetry.TraceStore.finish(
                packet.id,
                "Dropped",
                com.blink.dtn.telemetry.detailsOf("reason" to "decrypt_failed")
            )
            deps.enqueueProfileBroadcast()
            return
        }
        deps.trace(
            packet.id,
            com.blink.dtn.telemetry.TraceStages.RSA_DECRYPT_DONE,
            com.blink.dtn.telemetry.detailsOf(
                "durationMs" to (System.currentTimeMillis() - decStart),
                "plainLen" to plainText.length
            ),
            visual = "🔓 Расшифровано"
        )
        val convStart = System.currentTimeMillis()
        val finalMsg = packet.copy(text = plainText)
        dao.insertMessageWithConversation(finalMsg)
        deps.trace(
            packet.id,
            com.blink.dtn.telemetry.TraceStages.RX_CONVERSATION,
            com.blink.dtn.telemetry.detailsOf("insertDurationMs" to (System.currentTimeMillis() - convStart))
        )
        deps.notifyIncoming(finalMsg)

        val ack = Message(
            id = com.blink.dtn.utils.MeshIdGenerator.next(myNodeId),
            type = "ACK",
            senderId = myNodeId,
            senderNick = deps.currentNick(),
            targetId = packet.senderId,
            text = "",
            originalMessageId = packet.id,
            timestamp = System.currentTimeMillis(),
            ttl = 7,
            isAck = true
        )
        deps.trace(
            packet.id,
            com.blink.dtn.telemetry.TraceStages.ACK_GENERATED,
            com.blink.dtn.telemetry.detailsOf("ackId" to ack.id, "to" to packet.senderId)
        )
        deps.enqueueMessage(ack)
        deps.trace(
            packet.id,
            com.blink.dtn.telemetry.TraceStages.ACK_QUEUED,
            com.blink.dtn.telemetry.detailsOf("ackId" to ack.id)
        )
    }
}
