package com.blink.dtn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.blink.dtn.ble.BleMeshManager
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message
import com.blink.dtn.db.SeenPacket
import com.blink.dtn.db.BlockedUser
import com.blink.dtn.security.SecurityConfig
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CellularBridge private constructor(
    private val context: Context,
    private val dao: BLinkDao,
    private val bleMeshManager: BleMeshManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "CellularBridge"
        // Placeholder URL
        private const val BRIDGE_URL = "https://example.com/api/bridge"
        
        @Volatile
        private var INSTANCE: CellularBridge? = null

        fun getInstance(context: Context, dao: BLinkDao, bleMeshManager: BleMeshManager): CellularBridge {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CellularBridge(context.applicationContext, dao, bleMeshManager).also { INSTANCE = it }
            }
        }
    }

    fun start() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                if (isOnline()) {
                    performSync()
                }
                delay(15000)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        scope.cancel()
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun performSync() {
        try {
            // 1. Push unsynced local messages to the cloud
            val unsynced = dao.getUnsyncedMessages()
            if (unsynced.isNotEmpty()) {
                val payload = json.encodeToString(unsynced)
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("$BRIDGE_URL/push")
                    .post(body)
                    .build()
                
                // Execute synchronously in IO dispatcher
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    dao.markAsSynced(unsynced.map { it.id })
                }
                response.close()
            }

            // 2. Fetch incoming messages from the cloud
            val prefs = context.getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
            val lastPullTimestamp = prefs.getLong("last_pull_timestamp", 0L)
            
            val getRequest = Request.Builder()
                .url("$BRIDGE_URL/pull?since=$lastPullTimestamp")
                .get()
                .build()

            val response = client.newCall(getRequest).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (!responseBody.isNullOrBlank()) {
                    val incomingMessages = json.decodeFromString<List<Message>>(responseBody)
                    for (msg in incomingMessages) {
                        msg.isBridgeSynced = true
                        val isSeen = dao.hasSeenPacket(msg.id)
                        if (!isSeen) {
                            dao.insertSeenPacket(SeenPacket(msg.id, System.currentTimeMillis()))
                            if (SecurityConfig.requiresAuthorSignature(msg.type)) {
                                val isValid = SecurityConfig.verifySignature(msg.text, msg.authorSignature)
                                if (!isValid) {
                                    dao.blockUser(BlockedUser(msg.senderNick, System.currentTimeMillis()))
                                    continue
                                }
                            }
                            dao.insertMessageWithConversation(msg)
                            // Forward into BLE mesh
                            msg.ttl = msg.ttl - 1
                            if (msg.ttl > 0) {
                                bleMeshManager.enqueueMessage(msg)
                            }
                        }
                    }
                    prefs.edit().putLong("last_pull_timestamp", System.currentTimeMillis()).apply()
                }
            }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
        }
    }
}
