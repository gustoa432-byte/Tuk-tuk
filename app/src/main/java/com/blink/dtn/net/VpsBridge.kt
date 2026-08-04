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
 * Pushes opaque encrypted mesh payloads and pulls envelopes addressed to this node.
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
            while (isActive) {
                refreshRouterSnapshot()
                if (isConfigured() && VpsConfig.isOnline(context)) {
                    runCatching { register() }
                    runCatching { syncDirectory() }
                    runCatching { performSync() }
                    runCatching { syncOracleOrbits() }
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

    private fun register() {
        val nick = context.getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
            .getString("nick", "") ?: ""
        val body = json.encodeToString(
            RegisterRequest(nodeId = myNodeId, nick = nick, pubkey = com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64())
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("${baseUrl()}/v1/register")
            .post(body)
            .header("X-Node-Id", myNodeId)
            .build()
        client.newCall(req).execute().use { resp ->
            reachable.set(resp.isSuccessful)
        }
    }

    /** Pull online directory → local contacts (same identity online ↔ mesh). */
    private suspend fun syncDirectory() {
        val req = Request.Builder()
            .url("${baseUrl()}/v1/directory")
            .get()
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
        // 1) Push unsynced DB messages as opaque best-effort JSON fallback
        //    (PRIVATE_IMAGE is pushed only via [pushPrivateImage] with JPEG bytes).
        val unsynced = dao.getUnsyncedMessages()
            .filter { it.type != Message.TYPE_PRIVATE_IMAGE }
            .take(40)
        if (unsynced.isNotEmpty()) {
            val envelopes = unsynced.map { msg ->
                VpsEnvelope(
                    id = msg.id,
                    from = msg.senderId.ifBlank { myNodeId },
                    to = msg.targetId ?: "*",
                    payloadB64 = Base64.encodeToString(
                        json.encodeToString(msg).toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    ),
                    ts = msg.timestamp,
                    kind = "message_json"
                )
            }
            val body = json.encodeToString(PushRequest(envelopes))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${baseUrl()}/v1/push")
                .post(body)
                .header("X-Node-Id", myNodeId)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    dao.markAsSynced(unsynced.map { it.id })
                    reachable.set(true)
                } else {
                    reachable.set(false)
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
                    senderId = payload.from,
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
                val msg = runCatching {
                    json.decodeFromString<Message>(String(raw, Charsets.UTF_8))
                }.getOrNull() ?: return
                if (msg.type == Message.TYPE_PRIVATE_IMAGE) {
                    // Image bytes missing in JSON fallback — ignore.
                    return
                }
                msg.isBridgeSynced = true
                dao.insertMessageWithConversation(msg)
                if (msg.ttl > 1) {
                    msg.ttl = msg.ttl - 1
                    bleMeshManager.enqueueMessage(msg)
                }
            }
            else -> {
                // Opaque encrypted NetworkPacket bytes → mesh ingress
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
