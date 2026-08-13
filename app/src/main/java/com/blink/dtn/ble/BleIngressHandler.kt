package com.blink.dtn.ble

import android.util.Log
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.BlockedUser
import com.blink.dtn.db.Message
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
    /** Per-peer RX token buckets (MAC or senderId) — ~20 packets / 2s window. */
    private val rxWindows = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
    private val identityRequestWindows = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

    data class DecodedWirePacket(
        val packet: NetworkPacket,
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
        /** Peer asked us to push our installed APK over Wi‑Fi Direct (experimental). */
        fun onApkUpdateRequest(fromPeerId: String) {}
        /** Journal A: record Social Orbit meet for a stable mesh nodeId. */
        fun noteSocialOrbitMeet(nodeId: String) {}
        /** Bind BLE MAC → mesh nodeId after a verified IDENTITY. */
        fun bindPeerMac(mac: String?, nodeId: String) {}
    }

    fun decodeWirePacket(jsonString: String): DecodedWirePacket {
        return try {
            val packet = Json.decodeFromString<NetworkPacket>(jsonString)
            DecodedWirePacket(
                packet = packet,
                message = packet.toMessage(),
                dedupKey = packet.packetId.ifEmpty { packet.messageId }
            )
        } catch (_: Exception) {
            val legacyMessage = Json.decodeFromString<Message>(jsonString)
            DecodedWirePacket(
                packet = NetworkPacket.fromMessage(legacyMessage),
                message = legacyMessage,
                dedupKey = legacyMessage.id
            )
        }
    }

    /**
     * Drop spoofed / unsigned frames before any Room write or relay.
     * IDENTITY may bootstrap the pubkey from the packet body.
     */
    suspend fun verifyEnvelope(packet: NetworkPacket): Boolean {
        val pubFromIdentity = if (
            packet.type == "IDENTITY_ANNOUNCEMENT" || packet.type == "SYSTEM_PROFILE"
        ) {
            packet.payload.split("|").getOrNull(2).orEmpty()
        } else {
            ""
        }
        val profileKey = dao.getProfileById(packet.senderId)?.publicKey.orEmpty()
        val pubKey = when {
            pubFromIdentity.isNotBlank() -> pubFromIdentity
            profileKey.isNotBlank() -> profileKey
            else -> ""
        }
        if (pubKey.isBlank()) {
            Log.w("DTN", "No pubkey to verify envelope from ${packet.senderId} type=${packet.type}")
            // Allow IDENTITY_REQUEST without prior key (unsigned bootstrap ask).
            return packet.type == "IDENTITY_REQUEST"
        }
        if (!com.blink.dtn.crypto.MeshEnvelopeCrypto.senderMatchesKey(packet.senderId, pubKey)) {
            Log.w("DTN", "senderId/key mismatch ${packet.senderId}")
            return false
        }
        val ok = com.blink.dtn.crypto.MeshEnvelopeCrypto.verify(packet, pubKey)
        if (!ok) {
            Log.w("DTN", "Bad mesh envelope signature from ${packet.senderId} type=${packet.type}")
        }
        return ok
    }

    fun handle(packet: Message, dedupKey: String = packet.id, fromMac: String? = null) {
        scopeProvider().launch {
            if (packet.senderId == myNodeId && !packet.isAck) {
                return@launch
            }

            val rateKey = fromMac?.takeIf { it.isNotBlank() } ?: packet.senderId
            if (!allowRx(rateKey)) {
                Log.w("DTN", "RX rate-limit drop from=$rateKey type=${packet.type}")
                return@launch
            }

            if (dao.isUserIdBlocked(packet.senderId) || dao.isUserBlocked(packet.senderNick)) {
                Log.i("DTN", "Silently dropped packet from blocked sender ${packet.senderId}")
                return@launch
            }

            if (com.blink.dtn.moderation.GlobalBanCache.isBanned(packet.senderId)) {
                Log.i("DTN", "Dropped packet from globally banned node ${packet.senderId}")
                return@launch
            }
            if (com.blink.dtn.moderation.GlobalBanCache.isBanned(packet.targetId)) {
                // Do not relay toward a banned destination.
                if (packet.targetId != myNodeId) {
                    Log.i("DTN", "Dropped packet addressed to banned node ${packet.targetId}")
                    return@launch
                }
            }

            val now = System.currentTimeMillis()

            if (!deps.markSeen(dedupKey)) {
                return@launch
            }
            if (dao.hasSeenPacket(dedupKey)) {
                return@launch
            }
            dao.rememberSeenPacket(dedupKey, now)

            val messageTtlMs = 48 * 60 * 60 * 1000L
            if (now - packet.timestamp > messageTtlMs) {
                return@launch
            }
            // Reject far-future clocks (would never age out of TTL window).
            if (packet.timestamp > now + 5 * 60_000L) {
                Log.w("DTN", "Dropped future-dated packet ${packet.id}")
                return@launch
            }

            var packet = packet
            if (packet.hopHistory.size > 16) {
                packet = packet.copy(hopHistory = packet.hopHistory.takeLast(16))
            }
            if (packet.ttl > 32) {
                packet = packet.copy(ttl = 32)
            } else if (packet.ttl < 0) {
                packet = packet.copy(ttl = 0)
            }
            if (packet.type == Message.TYPE_PRIVATE_IMAGE) {
                Log.w("DTN", "Dropped PRIVATE_IMAGE on mesh ingress ${packet.id}")
                com.blink.dtn.telemetry.ErrorJournal.record(
                    "MESH_DROP_IMAGE",
                    detail = "id=${packet.id} from=${packet.senderId}"
                )
                return@launch
            }

            if (com.blink.dtn.security.SecurityConfig.requiresAuthorSignature(packet.type)) {
                val isValid = withContext(Dispatchers.Default) {
                    SecurityConfig.verifySignature(packet.text, packet.authorSignature)
                }
                if (!isValid) {
                    Log.w("DTN", "Rejected unsigned/invalid ${packet.type} from ${packet.senderNick}")
                    dao.blockUser(
                        BlockedUser(
                            blockedNick = packet.senderNick.ifBlank { packet.senderId },
                            blockedUserId = packet.senderId,
                            blockedAt = System.currentTimeMillis()
                        )
                    )
                    return@launch
                }
            }

            if (packet.isAck) {
                val consumed = handleAck(packet, now)
                if (consumed) return@launch
            } else when (packet.type) {
                "IDENTITY_ANNOUNCEMENT", "SYSTEM_PROFILE" -> {
                    if (!IdentityRelayPolicy.acceptDirectIdentity(
                            ttl = packet.ttl,
                            hopHistorySize = packet.hopHistory.size,
                            defaultTtl = BleMeshManager.DEFAULT_TTL
                        )
                    ) {
                        Log.d("DTN", "Drop relayed IDENTITY from ${packet.senderId} ttl=${packet.ttl}")
                        return@launch
                    }
                    handleIdentity(packet)
                    deps.bindPeerMac(fromMac, packet.senderId)
                    return@launch // never multi-hop IDENTITY
                }
                "IDENTITY_REQUEST" -> {
                    if (!allowIdentityRequest(packet.senderId)) {
                        Log.w("DTN", "IDENTITY_REQUEST flood from ${packet.senderId}")
                        return@launch
                    }
                    if (!IdentityRelayPolicy.acceptDirectIdentity(
                            ttl = packet.ttl,
                            hopHistorySize = packet.hopHistory.size,
                            defaultTtl = BleMeshManager.DEFAULT_TTL
                        )
                    ) {
                        Log.d("DTN", "Drop relayed IDENTITY_REQUEST from ${packet.senderId}")
                        return@launch
                    }
                    handleIdentityRequest(packet)
                    return@launch
                }
                "UPDATE_REQUEST" -> {
                    if (packet.targetId == null || packet.targetId == myNodeId) {
                        deps.onApkUpdateRequest(packet.senderId)
                    }
                }
                "PUBLIC", "SYSTEM_ANNOUNCEMENT", "VERSION_ANNOUNCEMENT" -> {
                    if (packet.type == "PUBLIC" && com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
                        Log.d("DTN", "QQ_CORE_ONLY: drop PUBLIC ${packet.id}")
                        return@launch
                    }
                    if (packet.type == "PUBLIC" && MeshLimits.exceedsTextLimit(packet.text)) {
                        Log.w("DTN", "Dropped oversized PUBLIC ${packet.id} len=${packet.text.length}")
                        com.blink.dtn.telemetry.ErrorJournal.record(
                            "MESH_DROP_OVERSIZE",
                            detail = "PUBLIC id=${packet.id} len=${packet.text.length}"
                        )
                        return@launch
                    }
                    dao.insertMessageWithConversation(packet)
                    if (packet.type == "PUBLIC") {
                        com.blink.dtn.ui.GamificationStore.noteReceived()
                    }
                    deps.notifyIncoming(packet)
                }
                "PRIVATE" -> {
                    if (packet.targetId == myNodeId) {
                        // Destination: ciphertext must be hybrid RSA envelope.
                        if (!com.blink.dtn.crypto.RsaUtils.looksLikePrivateEnvelope(packet.text)) {
                            Log.w("DTN", "Dropped PRIVATE without RSA envelope ${packet.id}")
                            return@launch
                        }
                        handlePrivateForMe(packet)
                        return@launch
                    } else {
                        // Backpack: append our nodeId to hopHistory (Chronicle custody).
                        val forBackpack = packet.copy(
                            hopHistory = packet.hopHistory + myNodeId
                        )
                        dao.insertRelayPacket(forBackpack)
                        deps.trace(
                            forBackpack.id,
                            com.blink.dtn.telemetry.TraceStages.MESH_RELAY_STORE,
                            com.blink.dtn.telemetry.detailsOf(
                                "ttl" to forBackpack.ttl,
                                "from" to forBackpack.senderId,
                                "to" to forBackpack.targetId,
                                "hops" to forBackpack.hopHistory.size
                            ),
                            visual = "🚲 Relay дальше"
                        )
                        forBackpack.ttl = forBackpack.ttl - 1
                        if (forBackpack.ttl > 0) {
                            deps.enqueueMessage(forBackpack)
                        }
                        return@launch
                    }
                }
            }

            // PUBLIC / announcements only — IDENTITY never reaches here (early return).
            if (packet.type == "PUBLIC" ||
                packet.type == "SYSTEM_ANNOUNCEMENT" ||
                packet.type == "VERSION_ANNOUNCEMENT"
            ) {
                packet.ttl = packet.ttl - 1
                if (packet.ttl > 0) {
                    val toForward = packet.copy(hopHistory = packet.hopHistory + myNodeId)
                    deps.trace(
                        packet.id,
                        com.blink.dtn.telemetry.TraceStages.MESH_FORWARD,
                        com.blink.dtn.telemetry.detailsOf(
                            "ttlAfter" to toForward.ttl,
                            "viaNode" to myNodeId
                        )
                    )
                    deps.enqueueMessage(toForward)
                } else {
                    deps.trace(
                        packet.id,
                        com.blink.dtn.telemetry.TraceStages.MESH_SKIP,
                        com.blink.dtn.telemetry.detailsOf("reason" to "ttl_exhausted")
                    )
                }
                return@launch
            }

            // Hard stop: never epidemic-relay IDENTITY* (defense in depth).
            if (!IdentityRelayPolicy.mayRelay(packet.type)) {
                Log.d("DTN", "Refuse IDENTITY relay type=${packet.type}")
                return@launch
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
        val ackedMessageId = packet.originalMessageId?.takeIf { it.isNotEmpty() }
            ?: packet.text.takeIf { it.isNotEmpty() }
            ?: return packet.targetId == myNodeId

        packet.originalMessageId?.takeIf { it.isNotEmpty() }?.let { ackedId ->
            deps.markSeen(ackedId)
            dao.rememberSeenPacket(ackedId, now)
        }

        if (packet.targetId != myNodeId) {
            // Relay path: only drop backpack copy if ACK is from the real destination.
            val held = dao.getMessageById(ackedMessageId)
            if (held != null && AckPolicy.acceptBackpackWipe(held.targetId, packet.senderId)) {
                dao.deleteMessageById(ackedMessageId)
            }
            return false
        }

        val ackedMsg = dao.getMessageById(ackedMessageId) ?: return true
        if (!AckPolicy.acceptDeliveryAck(ackedMsg.targetId, packet.senderId)) {
            Log.w(
                "DTN",
                "Rejected ACK forgery id=$ackedMessageId from=${packet.senderId} expected=${ackedMsg.targetId}"
            )
            return true
        }
        val status = if (ackedMsg.type == "PRIVATE") {
            Message.STATUS_DELIVERED_ACK
        } else {
            // PUBLIC: ACK is not e2e to a target — stay honest about neighbor custody.
            Message.STATUS_STORED_IN_NEIGHBOR
        }
        dao.updateMessageStatus(ackedMessageId, status)
        val latency = System.currentTimeMillis() - ackedMsg.timestamp
        deps.trace(
            ackedMessageId,
            com.blink.dtn.telemetry.TraceStages.ACK_RECEIVED,
            com.blink.dtn.telemetry.detailsOf(
                "ackFrom" to packet.senderId,
                "latencyMs" to latency,
                "status" to status
            ),
            visual = if (status == Message.STATUS_DELIVERED_ACK) "✅ Доставлено (ACK)" else "📦 У соседа"
        )
        com.blink.dtn.telemetry.TraceStore.finish(
            ackedMessageId,
            if (status == Message.STATUS_DELIVERED_ACK) "DeliveredAck" else "StoredInNeighbor",
            com.blink.dtn.telemetry.detailsOf("ackLatencyMs" to latency)
        )
        if (status == Message.STATUS_DELIVERED_ACK) {
            com.blink.dtn.router.MessageRouter.noteShipmentStatus(ackedMessageId, "друг получил")
            com.blink.dtn.router.MessageRouter.clearShipmentIf(ackedMessageId)
            com.blink.dtn.ui.GamificationStore.noteSavedDelivery()
        }
        return true
    }

    private suspend fun handleIdentity(packet: Message) {
        val parts = packet.text.split("|")
        if (parts.size < 2) return

        val nick = parts[0]
        val isVip = parts[1].toBoolean()
        val pubKey = if (parts.size >= 3) parts[2] else ""
        val appVersionCode = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val appVersionName = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: ""

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
        // Preserve local alias, trust, and gifts — identity packets only refresh nick/key/version.
        dao.insertOrUpdateProfile(
            com.blink.dtn.db.UserProfile(
                userId = packet.senderId,
                nickname = nick,
                lastSeen = System.currentTimeMillis(),
                isVip = isVip,
                publicKey = trustedPublicKey,
                giftRoses = existingProfile?.giftRoses ?: 0,
                giftBears = existingProfile?.giftBears ?: 0,
                giftDiamonds = existingProfile?.giftDiamonds ?: 0,
                giftCoffee = existingProfile?.giftCoffee ?: 0,
                giftRockets = existingProfile?.giftRockets ?: 0,
                giftCrowns = existingProfile?.giftCrowns ?: 0,
                localAlias = existingProfile?.localAlias ?: "",
                trustStatus = existingProfile?.trustStatus
                    ?: com.blink.dtn.db.UserProfile.TRUST_STRANGER,
                verifiedOutOfBand = existingProfile?.verifiedOutOfBand ?: false,
                appVersionCode = if (appVersionCode > 0) appVersionCode
                else existingProfile?.appVersionCode ?: 0L,
                appVersionName = appVersionName.ifBlank {
                    existingProfile?.appVersionName.orEmpty()
                },
                avatarBlob = existingProfile?.avatarBlob
            )
        )
        Log.i("DTN", "Successfully saved public key for Node: ${packet.senderId}")
        if (appVersionCode > 0) {
            com.blink.dtn.update.VersionGossip.notePeerVersionFromProfile(
                packet.senderId,
                nick,
                appVersionCode,
                appVersionName
            )
        }
        if (trustedPublicKey.isNotEmpty()) {
            deps.ensureTrace(packet.id, "IDENTITY_ANNOUNCEMENT", packet.senderId, null)
            com.blink.dtn.telemetry.PeerDirectory.noteNode(packet.senderId, nick)
            // Journal A: stable nodeId after identity handshake (not MAC).
            deps.noteSocialOrbitMeet(packet.senderId)
            deps.trace(
                packet.id,
                com.blink.dtn.telemetry.TraceStages.ID_STORED,
                com.blink.dtn.telemetry.detailsOf(
                    "nodeId" to packet.senderId,
                    "nick" to nick,
                    "keyFingerprint" to com.blink.dtn.crypto.NodeIdentity.deriveNodeId(trustedPublicKey),
                    "keyChanged" to keyChanged,
                    "appVersionCode" to appVersionCode,
                    "appVersionName" to appVersionName
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
        val existing = dao.getProfileById(packet.senderId)
        if (existing?.isBlocked == true || dao.isUserBlocked(packet.senderNick)) {
            Log.i("DTN", "Dropping private from blocked peer ${packet.senderId}")
            return
        }

        // First inbound private from an unknown peer → stranger (message request).
        // Do not escalate trust here; Accept in UI promotes to CONTACT.
        if (existing == null) {
            dao.insertOrUpdateProfile(
                com.blink.dtn.db.UserProfile(
                    userId = packet.senderId,
                    nickname = packet.senderNick.ifBlank { packet.senderId },
                    lastSeen = System.currentTimeMillis(),
                    isVip = false,
                    trustStatus = com.blink.dtn.db.UserProfile.TRUST_STRANGER
                )
            )
        } else if (packet.senderNick.isNotBlank() && existing.nickname != packet.senderNick) {
            dao.insertOrUpdateProfile(
                existing.copy(
                    nickname = packet.senderNick,
                    lastSeen = System.currentTimeMillis()
                )
            )
        }

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
        if (MeshLimits.exceedsTextLimit(plainText)) {
            Log.w("DTN", "Dropped oversized PRIVATE plaintext ${packet.id} len=${plainText.length}")
            com.blink.dtn.telemetry.ErrorJournal.record(
                "MESH_DROP_OVERSIZE",
                detail = "PRIVATE id=${packet.id} len=${plainText.length}"
            )
            com.blink.dtn.telemetry.TraceStore.finish(
                packet.id,
                "Dropped",
                com.blink.dtn.telemetry.detailsOf("reason" to "oversize_plaintext")
            )
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
        val sealed = com.blink.dtn.crypto.MessageAtRest.seal(plainText)
        val finalMsg = packet.copy(text = sealed)
        dao.insertMessageWithConversation(finalMsg)
        deps.trace(
            packet.id,
            com.blink.dtn.telemetry.TraceStages.RX_CONVERSATION,
            com.blink.dtn.telemetry.detailsOf("insertDurationMs" to (System.currentTimeMillis() - convStart))
        )
        com.blink.dtn.ui.GamificationStore.noteReceived()
        deps.notifyIncoming(finalMsg.copy(text = plainText))

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

    private fun allowRx(key: String): Boolean =
        allowWindow(rxWindows, key, max = 20, windowMs = 2_000L)

    private fun allowIdentityRequest(senderId: String): Boolean =
        allowWindow(identityRequestWindows, senderId, max = 3, windowMs = 60_000L)

    private fun allowWindow(
        map: java.util.concurrent.ConcurrentHashMap<String, LongArray>,
        key: String,
        max: Int,
        windowMs: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        val arr = map.compute(key) { _, prev ->
            val kept = (prev?.filter { now - it < windowMs } ?: emptyList()).toMutableList()
            if (kept.size >= max) return@compute kept.toLongArray()
            kept.add(now)
            kept.toLongArray()
        } ?: return false
        return arr.size <= max && arr.isNotEmpty() && arr.last() == now
    }
}
