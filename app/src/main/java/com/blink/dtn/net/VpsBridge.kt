package com.blink.dtn.net

import android.content.Context
import android.util.Base64
import android.util.Log
import com.blink.dtn.ble.BleMeshManager
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message
import com.blink.dtn.db.SeenPacket
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
                    runCatching { performSync() }
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

    private suspend fun performSync() {
        // 1) Push unsynced DB messages as opaque best-effort JSON fallback
        val unsynced = dao.getUnsyncedMessages().take(40)
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
        dao.insertSeenPacket(SeenPacket(env.id, System.currentTimeMillis()))
        val raw = try {
            Base64.decode(env.payloadB64, Base64.DEFAULT)
        } catch (_: Exception) {
            return
        }
        when (env.kind) {
            "message_json" -> {
                val msg = runCatching {
                    json.decodeFromString<Message>(String(raw, Charsets.UTF_8))
                }.getOrNull() ?: return
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
    private data class PushRequest(val envelopes: List<VpsEnvelope>)

    @Serializable
    private data class PullResponse(val envelopes: List<VpsEnvelope> = emptyList())
}
