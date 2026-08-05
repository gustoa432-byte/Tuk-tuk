package com.blink.dtn.net

import android.content.Context
import android.util.Base64
import android.util.Log
import com.blink.dtn.ble.BleMeshManager
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message
import com.blink.dtn.router.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Online VPS store-and-forward bridge.
 * Pushes opaque signed mesh payloads ([CryptoUtils.packSigned]); PRIVATE bodies are
 * hybrid-encrypted before leave the device. Pull ingest reuses BLE verifyEnvelope rules.
 */
class VpsBridge private constructor(
    private val context: Context,
    private val dao: BLinkDao,
    private val bleMeshManager: BleMeshManager,
    private val myNodeId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val reachable = AtomicBoolean(false)

    companion object {
        private const val TAG = "VpsBridge"

        @Volatile
        private var INSTANCE: VpsBridge? = null

        fun getInstance(
            context: Context,
            dao: BLinkDao,
            bleMeshManager: BleMeshManager,
            myNodeId: String
        ): VpsBridge {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VpsBridge(
                    context.applicationContext,
                    dao,
                    bleMeshManager,
                    myNodeId
                ).also { INSTANCE = it }
            }
        }
    }

    fun isConfigured(): Boolean = VpsConfig.isConfigured(context)

    fun isReachable(): Boolean = reachable.get()

    fun start() {
        VpsConfig.init(context)
        syncJob?.cancel()
        syncJob = scope.launch {
            // Hydrate ban cache from Room before first network round.
            runCatching {
                val ids = com.blink.dtn.db.BLinkDatabase
                    .getDatabase(context)
                    .bannedNodeDao()
                    .allNodeIds()
                com.blink.dtn.moderation.GlobalBanCache.replaceAll(ids)
            }
            while (isActive) {
                refreshRouterSnapshot()
                if (isConfigured() && VpsConfig.isOnline(context)) {
                    runCatching { register() }
                    runCatching { syncDirectory() }
                    runCatching { performSync() }
                    runCatching { syncOracleOrbits() }
                    runCatching { syncModerationBlacklist() }
                } else {
                    reachable.set(false)
                }
                delay(12_000L)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
    }

    /** Immediate opaque hop used by [com.blink.dtn.router.MessageRouter]. */
    suspend fun pushEncryptedPayload(bytes: ByteArray, messageId: String): Boolean {
        if (!isConfigured() || !VpsConfig.isOnline(context)) return false
        val jwt = meshJwtOrNull() ?: return false
        return try {
            val envelope = VpsEnvelope(
                id = messageId,
                from = myNodeId,
                to = "*",
                payloadB64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                ts = System.currentTimeMillis()
            )
            val body = json.encodeToString(PushRequest(listOf(envelope)))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${baseUrl()}/v1/push")
                .post(body)
                .header("X-Node-Id", myNodeId)
                .header("Authorization", "Bearer $jwt")
                .build()
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                reachable.set(ok)
                if (ok) {
                    // Mark matching DB row bridge-synced when present.
                    dao.getMessageById(messageId)?.let { msg ->
                        msg.isBridgeSynced = true
                        dao.updateMessageInternal(msg)
                    }
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushEncryptedPayload failed: ${e.message}")
            reachable.set(false)
            false
        }
    }

    /**
     * Internet-only photo: never meshes. Store-and-forward to [msg.targetId].
     */
    suspend fun pushPrivateImage(msg: Message, jpegBytes: ByteArray): Boolean {
        val to = msg.targetId ?: return false
        if (!isConfigured() || !VpsConfig.isOnline(context)) return false
        if (jpegBytes.isEmpty() || jpegBytes.size > 400_000) return false
        val jwt = meshJwtOrNull() ?: return false
        return try {
            val payload = PrivateImagePayload(
                id = msg.id,
                from = msg.senderId.ifBlank { myNodeId },
                to = to,
                senderNick = msg.senderNick,
                caption = msg.text,
                timestamp = msg.timestamp,
                imageB64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            )
            val envelope = VpsEnvelope(
                id = msg.id,
                from = myNodeId,
                to = to,
                payloadB64 = Base64.encodeToString(
                    json.encodeToString(payload).toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                ),
                ts = System.currentTimeMillis(),
                kind = "private_image"
            )
            val body = json.encodeToString(PushRequest(listOf(envelope)))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${baseUrl()}/v1/push")
                .post(body)
                .header("X-Node-Id", myNodeId)
                .header("Authorization", "Bearer $jwt")
                .build()
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                reachable.set(ok)
                if (ok) {
                    dao.updateMessageStatus(msg.id, Message.STATUS_SENT)
                    dao.getMessageById(msg.id)?.let {
                        it.isBridgeSynced = true
                        dao.updateMessageInternal(it)
                    }
                    MessageRouter.notePath(msg.id, com.blink.dtn.router.RoutePath.INTERNET, "фото через интернет")
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushPrivateImage failed: ${e.message}")
            reachable.set(false)
            false
        }
    }

    private fun baseUrl(): String = VpsConfig.baseUrl.value.trimEnd('/')

    private fun meshJwtOrNull(): String? {
        val jwt = com.blink.dtn.auth.AuthSessionStore.jwt(context)
        return jwt.takeIf { it.isNotBlank() }
    }

    private fun register() {
        val jwt = meshJwtOrNull() ?: return
        val nick = context.getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
            .getString("nick", "") ?: ""
        val body = json.encodeToString(
            RegisterRequest(nodeId = myNodeId, nick = nick, pubkey = com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64())
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("${baseUrl()}/v1/register")
            .post(body)
            .header("X-Node-Id", myNodeId)
            .header("Authorization", "Bearer $jwt")
            .build()
        client.newCall(req).execute().use { resp ->
            reachable.set(resp.isSuccessful)
        }
    }

    /** Pull online directory → local contacts (same identity online ↔ mesh). */
    private suspend fun syncDirectory() {
        val jwt = meshJwtOrNull() ?: return
        val req = Request.Builder()
            .url("${baseUrl()}/v1/directory")
            .get()
            .header("Authorization", "Bearer $jwt")
            .header("X-Node-Id", myNodeId)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                reachable.set(false)
                return
            }
            reachable.set(true)
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) return
            val dir = json.decodeFromString<DirectoryResponse>(text)
            for (node in dir.nodes) {
                if (node.nodeId.isBlank() || node.nodeId == myNodeId) continue
                // Identity binding: refuse directory rows where pubkey does not derive to nodeId.
                if (node.pubkey.isNotBlank()) {
                    val derived = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(node.pubkey)
                    if (derived.isBlank() || derived != node.nodeId) {
                        Log.w(TAG, "Skip directory node unbound pubkey nodeId=${node.nodeId}")
                        continue
                    }
                }
                val existing = dao.getProfileById(node.nodeId)
                if (existing?.isBlocked == true) continue
                val nick = node.nick.ifBlank { existing?.nickname.orEmpty() }
                val profile = existing?.copy(
                    nickname = if (node.nick.isNotBlank()) node.nick else existing.nickname,
                    lastSeen = maxOf(existing.lastSeen, node.seenAt),
                    publicKey = node.pubkey.ifBlank { existing.publicKey },
                    trustStatus = if (existing.trustStatus == com.blink.dtn.db.UserProfile.TRUST_BLOCKED)
                        existing.trustStatus
                    else
                        com.blink.dtn.db.UserProfile.TRUST_CONTACT
                ) ?: com.blink.dtn.db.UserProfile(
                    userId = node.nodeId,
                    nickname = nick.ifBlank { "Friend" },
                    lastSeen = if (node.seenAt > 0) node.seenAt else System.currentTimeMillis(),
                    isVip = false,
                    publicKey = node.pubkey,
                    trustStatus = com.blink.dtn.db.UserProfile.TRUST_CONTACT
                )
                dao.insertOrUpdateProfile(profile)
                com.blink.dtn.telemetry.PeerDirectory.noteNode(node.nodeId, profile.nickname)
            }
        }
    }

    private suspend fun performSync() {
        val jwt = meshJwtOrNull() ?: return
        // 1) Push unsynced outbound as opaque signed mesh_bytes (same wire as BLE).
        //    PRIVATE bodies are RSA-hybrid ciphertext — never plaintext Room JSON.
        //    PRIVATE_IMAGE stays on [pushPrivateImage] only.
        val unsynced = dao.getUnsyncedMessages()
            .filter { it.type != Message.TYPE_PRIVATE_IMAGE }
            .filter { it.senderId == myNodeId || it.isMine }
            .take(40)
        if (unsynced.isNotEmpty()) {
            val envelopes = mutableListOf<VpsEnvelope>()
            val syncedIds = mutableListOf<String>()
            for (msg in unsynced) {
                val bytes = prepareOpaqueMeshPayload(msg) ?: continue
                envelopes.add(
                    VpsEnvelope(
                        id = msg.id,
                        from = myNodeId,
                        to = msg.targetId ?: "*",
                        payloadB64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        ts = msg.timestamp,
                        kind = "mesh_bytes"
                    )
                )
                syncedIds.add(msg.id)
            }
            if (envelopes.isNotEmpty()) {
                val body = json.encodeToString(PushRequest(envelopes))
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("${baseUrl()}/v1/push")
                    .post(body)
                    .header("X-Node-Id", myNodeId)
                    .header("Authorization", "Bearer $jwt")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        dao.markAsSynced(syncedIds)
                        reachable.set(true)
                    } else {
                        reachable.set(false)
                    }
                }
            }
        }

        // 2) Pull addressed envelopes
        val prefs = context.getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
        val since = prefs.getLong("vps_last_pull", 0L)
        val pullReq = Request.Builder()
            .url("${baseUrl()}/v1/pull?nodeId=$myNodeId&since=$since")
            .get()
            .header("X-Node-Id", myNodeId)
            .header("Authorization", "Bearer $jwt")
            .build()
        client.newCall(pullReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                reachable.set(false)
                return
            }
            reachable.set(true)
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) return
            val pull = json.decodeFromString<PullResponse>(text)
            var maxTs = since
            for (env in pull.envelopes) {
                maxTs = maxOf(maxTs, env.ts)
                if (env.from == myNodeId) continue
                ingestEnvelope(env)
            }
            prefs.edit().putLong("vps_last_pull", maxTs).apply()
        }
    }

    /**
     * BLE-equivalent wire bytes for VPS store-and-forward.
     * PRIVATE: hybrid RSA envelope in payload, then MeshEnvelopeCrypto sign.
     * Returns null if we cannot encrypt yet (missing peer key) — leave unsynced.
     */
    private suspend fun prepareOpaqueMeshPayload(msg: Message): ByteArray? {
        var wire = msg.copy(senderId = myNodeId)
        if (wire.type == Message.TYPE_PRIVATE || wire.type == "PRIVATE") {
            val target = wire.targetId
            if (target.isNullOrBlank()) {
                Log.w(TAG, "Skip VPS push PRIVATE ${wire.id}: no targetId")
                return null
            }
            if (!com.blink.dtn.crypto.RsaUtils.looksLikePrivateEnvelope(wire.text)) {
                val profile = dao.getProfileById(target)
                val pub = profile?.publicKey.orEmpty()
                if (pub.isBlank()) {
                    Log.w(TAG, "Skip VPS push PRIVATE ${wire.id}: missing recipient pubkey")
                    return null
                }
                val enc = com.blink.dtn.crypto.RsaUtils.encryptAsymmetric(wire.text, pub)
                if (enc.isEmpty()) {
                    Log.w(TAG, "Skip VPS push PRIVATE ${wire.id}: encrypt failed")
                    return null
                }
                wire = wire.copy(text = enc)
            }
        }
        return try {
            com.blink.dtn.crypto.CryptoUtils.packSigned(
                com.blink.dtn.ble.NetworkPacket.fromMessage(wire)
            )
        } catch (e: Exception) {
            Log.w(TAG, "packSigned failed for ${msg.id}: ${e.message}")
            null
        }
    }

    /**
     * Same trust gate as BLE [com.blink.dtn.ble.BleIngressHandler.verifyEnvelope]:
     * senderId ↔ pubkey binding + MeshEnvelopeCrypto signature.
     */
    private suspend fun verifyInboundMeshPacket(
        packet: com.blink.dtn.ble.NetworkPacket
    ): Boolean {
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
            Log.w(TAG, "No pubkey to verify VPS packet from ${packet.senderId} type=${packet.type}")
            return packet.type == "IDENTITY_REQUEST"
        }
        if (!com.blink.dtn.crypto.MeshEnvelopeCrypto.senderMatchesKey(packet.senderId, pubKey)) {
            Log.w(TAG, "senderId/key mismatch on VPS ingest ${packet.senderId}")
            return false
        }
        val ok = com.blink.dtn.crypto.MeshEnvelopeCrypto.verify(packet, pubKey)
        if (!ok) {
            Log.w(TAG, "Bad mesh envelope signature on VPS ingest from ${packet.senderId}")
        }
        return ok
    }

    private suspend fun ingestEnvelope(env: VpsEnvelope) {
        if (dao.hasSeenPacket(env.id)) return
        dao.rememberSeenPacket(env.id)
        val raw = try {
            Base64.decode(env.payloadB64, Base64.DEFAULT)
        } catch (_: Exception) {
            return
        }
        when (env.kind) {
            "private_image" -> {
                val payload = runCatching {
                    json.decodeFromString<PrivateImagePayload>(String(raw, Charsets.UTF_8))
                }.getOrNull() ?: return
                if (payload.to != myNodeId && payload.to.isNotBlank()) return
                // Envelope `from` is JWT-bound on server; reject JSON spoof of sender.
                if (payload.from.isNotBlank() && payload.from != env.from) {
                    Log.w(TAG, "Dropped private_image spoof: json.from=${payload.from} env.from=${env.from}")
                    return
                }
                val jpeg = try {
                    Base64.decode(payload.imageB64, Base64.DEFAULT)
                } catch (_: Exception) {
                    return
                }
                if (jpeg.isEmpty()) return
                val file = com.blink.dtn.ui.ChatPhotoCompressor.writeBytes(context, payload.id, jpeg)
                val now = System.currentTimeMillis()
                val msg = Message(
                    id = payload.id,
                    type = Message.TYPE_PRIVATE_IMAGE,
                    senderId = env.from,
                    senderNick = payload.senderNick,
                    targetId = payload.to.ifBlank { myNodeId },
                    text = payload.caption.ifBlank { "📷" },
                    timestamp = payload.timestamp,
                    ttl = 1,
                    isMine = false,
                    status = Message.STATUS_DELIVERED,
                    receivedAt = now,
                    mediaPath = file?.absolutePath,
                    isBridgeSynced = true
                )
                dao.insertMessageWithConversation(msg)
                // No mesh relay for photos.
            }
            "message_json" -> {
                // Legacy kind: still may exist on VPS. Never trust blindly —
                // require MeshEnvelopeCrypto + env.from == senderId, then mesh ingress.
                val msg = runCatching {
                    json.decodeFromString<Message>(String(raw, Charsets.UTF_8))
                }.getOrNull() ?: return
                if (msg.type == Message.TYPE_PRIVATE_IMAGE) return
                if (msg.senderId.isBlank() || msg.senderId != env.from) {
                    Log.w(
                        TAG,
                        "Dropped message_json spoof: senderId=${msg.senderId} env.from=${env.from}"
                    )
                    return
                }
                val packet = com.blink.dtn.ble.NetworkPacket.fromMessage(msg)
                if (!verifyInboundMeshPacket(packet)) {
                    Log.w(TAG, "Dropped message_json without valid mesh signature id=${msg.id}")
                    return
                }
                if ((packet.type == Message.TYPE_PRIVATE || packet.type == "PRIVATE") &&
                    !com.blink.dtn.crypto.RsaUtils.looksLikePrivateEnvelope(packet.payload)
                ) {
                    Log.w(TAG, "Dropped plaintext PRIVATE over VPS bridge id=${msg.id}")
                    return
                }
                try {
                    // Re-enter the same verify+handle path as BLE (signature checked again).
                    bleMeshManager.injectEncryptedPayload(
                        json.encodeToString(packet).toByteArray(Charsets.UTF_8)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "message_json inject failed: ${e.message}")
                }
            }
            else -> {
                // Opaque signed NetworkPacket bytes → mesh ingress (verify inside).
                try {
                    bleMeshManager.injectEncryptedPayload(raw)
                } catch (e: Exception) {
                    Log.w(TAG, "injectEncryptedPayload failed: ${e.message}")
                }
            }
        }
    }

    /** Push Journal A (social orbit) to Oracle when the user has a VPS JWT. */
    private suspend fun syncOracleOrbits() {
        if (!com.blink.dtn.auth.AuthSessionStore.hasVpsSession(context)) return
        val orbits = com.blink.dtn.db.BLinkDatabase
            .getDatabase(context)
            .socialOrbitDao()
            .getAllOrbitsOnce()
        if (orbits.isEmpty()) return
        OracleApi(context).sync(orbits).onSuccess { resp ->
            Log.d(TAG, "oracle sync accepted=${resp.accepted}")
        }
    }

    /** Pull global ban list → Room + [com.blink.dtn.moderation.GlobalBanCache]. */
    private suspend fun syncModerationBlacklist() {
        val ids = ModerationApi(context).fetchBlacklist().getOrElse { return }
        val db = com.blink.dtn.db.BLinkDatabase.getDatabase(context)
        db.bannedNodeDao().replaceAll(ids)
        com.blink.dtn.moderation.GlobalBanCache.replaceAll(ids)
        Log.d(TAG, "moderation blacklist size=${ids.size}")
    }

    private fun refreshRouterSnapshot() {
        val wifi = bleMeshManager.transportRegistry
            ?.byId("wifi_direct") as? com.blink.dtn.transport.WifiDirectTransport
        MessageRouter.refreshSnapshot(
            internetOnline = VpsConfig.isOnline(context),
            vpsConfigured = isConfigured(),
            vpsReachable = reachable.get(),
            wifiDirectReady = wifi?.isGroupReady() == true,
            blePeers = bleMeshManager.peerCount.value
        )
    }

    @Serializable
    private data class RegisterRequest(
        val nodeId: String,
        val nick: String,
        val pubkey: String = ""
    )

    @Serializable
    data class VpsEnvelope(
        val id: String,
        val from: String,
        val to: String,
        val payloadB64: String,
        val ts: Long,
        val kind: String = "mesh_bytes"
    )

    @Serializable
    private data class PrivateImagePayload(
        val id: String,
        val from: String,
        val to: String,
        val senderNick: String = "",
        val caption: String = "📷",
        val timestamp: Long = 0L,
        val imageB64: String
    )

    @Serializable
    private data class DirectoryResponse(val nodes: List<DirectoryNode> = emptyList())

    @Serializable
    private data class DirectoryNode(
        val nodeId: String = "",
        val nick: String = "",
        val pubkey: String = "",
        val seenAt: Long = 0L
    )

    @Serializable
    private data class PushRequest(val envelopes: List<VpsEnvelope>)

    @Serializable
    private data class PullResponse(val envelopes: List<VpsEnvelope> = emptyList())
}
