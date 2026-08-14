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

    /**
     * Single outbound gate for the gateway hop. Both the router path
     * ([pushEncryptedPayload]) and the periodic [performSync] go through it, so
     * the same message can never be pushed twice concurrently.
     */
    private val outboundInFlight: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastRegisterAt = 0L

    companion object {
        private const val TAG = "VpsBridge"

        /** Renew the access token this long before it expires. */
        private const val JWT_RENEW_BEFORE_MS = 48L * 60L * 60L * 1000L

        /** `/v1/register` only refreshes directory presence — 12s was pointless. */
        private const val REGISTER_INTERVAL_MS = 10L * 60L * 1000L

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
                    runCatching { renewSessionIfExpiringSoon() }
                    runCatching { register() }
                    // Messaging hop (push/pull) — always when VPS is up.
                    runCatching { performSync() }
                    if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
                        // Legacy social/platform surfaces — quarantined in Qq Core.
                        runCatching { syncDirectory() }
                        runCatching { syncOracleOrbits() }
                        runCatching { syncModerationBlacklist() }
                    }
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
        val row = dao.getMessageById(messageId)
        if (row != null && !isGatewayEligible(row)) return false
        if (!isBroadcastWorthPushing(row)) return false
        val to = recipientFor(row)
        if (!outboundInFlight.add(messageId)) {
            // Already being pushed by the sync loop — no double-push amplification.
            return false
        }
        return try {
            val envelope = VpsEnvelope(
                id = messageId,
                from = myNodeId,
                to = to,
                payloadB64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                ts = System.currentTimeMillis(),
                senderPubKey = mySenderPubKey()
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
                    // Gateway custody == neighbour custody: not an end-to-end ACK.
                    dao.markPushedToGateway(messageId)
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushEncryptedPayload failed: ${e.message}")
            reachable.set(false)
            false
        } finally {
            outboundInFlight.remove(messageId)
        }
    }

    /**
     * Photos are **not** carried by the gateway any more.
     *
     * `private_image` shipped a base64 JPEG inside a JSON envelope and the server
     * stored it verbatim — plaintext media on someone else's disk. Until media has
     * real end-to-end encryption there is no honest way to route a photo through
     * the gateway, so this refuses instead of leaking. Inbound `private_image`
     * ingest stays (older peers may still send one) and locally stored photos are
     * untouched.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun pushPrivateImage(msg: Message, jpegBytes: ByteArray): Boolean {
        Log.w(TAG, "Refused gateway push for PRIVATE_IMAGE ${msg.id}: no E2E for media")
        return false
    }

    /** Photos never leave through the gateway; terminal rows are not re-pushed. */
    private fun isGatewayEligible(msg: Message): Boolean =
        msg.type != Message.TYPE_PRIVATE_IMAGE &&
            !com.blink.dtn.db.MessageDeliverySm.isTerminal(msg.status)

    /**
     * Addressed delivery whenever the parcel has a destination: a PRIVATE body
     * used to be pushed as `to = "*"`, i.e. handed to every polling node.
     * Broadcast stays only for what is genuinely public (and Qq Core drops
     * PUBLIC on ingest, so it is not pushed there at all).
     */
    private fun recipientFor(msg: Message?): String {
        val target = msg?.targetId?.trim().orEmpty()
        if (target.isNotEmpty()) return target
        return "*"
    }

    private fun isBroadcastWorthPushing(msg: Message?): Boolean {
        if (msg == null) return true
        if (recipientFor(msg) != "*") return true
        if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) return true
        // Qq Core has no public chat surface — pushing PUBLIC to everyone is waste.
        return msg.type != "PUBLIC"
    }

    private fun baseUrl(): String = VpsConfig.baseUrl.value.trimEnd('/')

    private fun meshJwtOrNull(): String? {
        val jwt = com.blink.dtn.auth.AuthSessionStore.jwt(context)
        return jwt.takeIf { it.isNotBlank() }
    }

    /**
     * Keep the session alive on its own instead of waiting for a hard 401.
     * Lets the server shorten the access-token TTL without logging anyone out
     * (`/auth/refresh` cannot rotate the device key, so this is not a takeover
     * path for a stolen token).
     */
    private suspend fun renewSessionIfExpiringSoon() {
        val jwt = meshJwtOrNull() ?: return
        val expiresAt = VpsJwtSupport.expiryMsOrNull(jwt) ?: return
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining > JWT_RENEW_BEFORE_MS) return
        Log.i(TAG, "JWT expires in ${remaining / 60_000}m — renewing")
        runCatching { AuthApi(context).refreshSession() }
    }

    private fun register() {
        val jwt = meshJwtOrNull() ?: return
        // Was fired on every 12s sync tick; nothing about it changes that often.
        val now = System.currentTimeMillis()
        if (now - lastRegisterAt < REGISTER_INTERVAL_MS) return
        lastRegisterAt = now
        // Qq Core has no online directory surface — do not hand the server a nick.
        val nick = if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) "" else {
            context.getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
                .getString("nick", "") ?: ""
        }
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

    private fun mySenderPubKey(): String =
        com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64()

    /** Bulk roster is not a product path — exact username lookup is the find. */
    private suspend fun syncDirectory() {
        return
    }

    private suspend fun performSync() {
        val jwt = meshJwtOrNull() ?: return
        // 1) Push outbound as opaque signed mesh_bytes (same wire as BLE).
        //    PRIVATE bodies are RSA-hybrid ciphertext — never plaintext Room JSON.
        //    Photos never take this path at all.
        val outbox = dao.getGatewayOutbox(myNodeId)
            .filter { isGatewayEligible(it) && isBroadcastWorthPushing(it) }
        if (outbox.isNotEmpty()) {
            val envelopes = mutableListOf<VpsEnvelope>()
            val pushedIds = mutableListOf<String>()
            for (msg in outbox) {
                // Same gate as the router path: never push a message twice.
                if (!outboundInFlight.add(msg.id)) continue
                val bytes = prepareOpaqueMeshPayload(msg)
                if (bytes == null) {
                    outboundInFlight.remove(msg.id)
                    continue
                }
                envelopes.add(
                    VpsEnvelope(
                        id = msg.id,
                        from = myNodeId,
                        to = recipientFor(msg),
                        payloadB64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        ts = msg.timestamp,
                        kind = "mesh_bytes",
                        senderPubKey = mySenderPubKey()
                    )
                )
                pushedIds.add(msg.id)
            }
            if (envelopes.isNotEmpty()) {
                try {
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
                            // Accepted by the gateway == carrier custody, still not
                            // delivery. The UI used to keep saying "queued" here.
                            for (id in pushedIds) dao.markPushedToGateway(id)
                            reachable.set(true)
                        } else {
                            reachable.set(false)
                        }
                    }
                } finally {
                    outboundInFlight.removeAll(pushedIds.toSet())
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
        val consumedIds = mutableListOf<String>()
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
                // Only mailbox items are ours to release; broadcast is shared.
                if (env.to == myNodeId) consumedIds.add(env.id)
            }
            prefs.edit().putLong("vps_last_pull", maxTs).apply()
        }
        if (consumedIds.isNotEmpty()) {
            ackConsumed(jwt, consumedIds)
        }
    }

    /**
     * Delete-on-ack: tell the gateway the mailbox items are processed so they do
     * not linger on its disk. Best effort — the server also expires them.
     */
    private fun ackConsumed(jwt: String, ids: List<String>) {
        try {
            val body = json.encodeToString(AckRequest(ids.take(500)))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${baseUrl()}/v1/ack")
                .post(body)
                .header("X-Node-Id", myNodeId)
                .header("Authorization", "Bearer $jwt")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) {
                    Log.d(TAG, "ack rejected: ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ack failed: ${e.message}")
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
        if (env.senderPubKey.isNotBlank() && env.from.isNotBlank()) {
            com.blink.dtn.db.ContactKeyPolicy.applyDiscovered(
                dao = dao,
                nodeId = env.from,
                advertisedKey = env.senderPubKey,
                asStrangerIfNew = true
            )
        }
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
                    // Inbound copy — nothing was ACKed end-to-end here. Same value
                    // inbound PRIVATE rows get; DELIVERED_ACK used to make received
                    // photos show up in the Chronicle as parcels *we* delivered.
                    status = Message.STATUS_PENDING,
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
        if (!com.blink.dtn.auth.AuthSessionStore.hasVpsSession(context)) return
        val ids = ModerationApi(context).fetchBlacklist().getOrElse { return }
        val db = com.blink.dtn.db.BLinkDatabase.getDatabase(context)
        db.bannedNodeDao().replaceAll(ids)
        com.blink.dtn.moderation.GlobalBanCache.replaceAll(ids)
        Log.d(TAG, "moderation blacklist size=${ids.size}")
    }

    private fun refreshRouterSnapshot() {
        MessageRouter.refreshSnapshot(
            internetOnline = VpsConfig.isOnline(context),
            vpsConfigured = isConfigured(),
            vpsReachable = reachable.get(),
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
        val kind: String = "mesh_bytes",
        val senderPubKey: String = ""
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
    private data class PushRequest(val envelopes: List<VpsEnvelope>)

    @Serializable
    private data class AckRequest(val ids: List<String>)

    @Serializable
    private data class PullResponse(val envelopes: List<VpsEnvelope> = emptyList())
}
