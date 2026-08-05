package com.blink.dtn.vk

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object VkRelayManager {
    val VK_ACCESS_TOKEN = com.blink.dtn.BuildConfig.VK_ACCESS_TOKEN
    const val VK_GROUP_ID = ""     // User to fill
    private const val VK_API_VERSION = "5.199"
    
    private val _relayActive = MutableStateFlow(false)
    val relayActive = _relayActive.asStateFlow()

    suspend fun pushPayloadToWall(encryptedPayload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (VK_ACCESS_TOKEN.isEmpty() || VK_GROUP_ID.isEmpty()) return@withContext false
        try {
            val base64Text = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP)
            val encodedText = URLEncoder.encode(base64Text, "UTF-8")
            
            val urlString = "https://api.vk.com/method/wall.post?owner_id=-$VK_GROUP_ID&message=$encodedText&v=$VK_API_VERSION&access_token=$VK_ACCESS_TOKEN"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                return@withContext json.has("response")
            }
            return@withContext false
        } catch (e: Exception) {
            android.util.Log.w("VkRelay", "push failed: ${e.message}")
            return@withContext false
        }
    }

    suspend fun fetchPayloadsFromWall(): List<ByteArray> = withContext(Dispatchers.IO) {
        val payloads = mutableListOf<ByteArray>()
        if (VK_ACCESS_TOKEN.isEmpty() || VK_GROUP_ID.isEmpty()) return@withContext payloads
        try {
            val urlString = "https://api.vk.com/method/wall.get?owner_id=-$VK_GROUP_ID&count=20&v=$VK_API_VERSION&access_token=$VK_ACCESS_TOKEN"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                _relayActive.value = true
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                val items = json.optJSONObject("response")?.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val text = item.optString("text", "")
                        if (text.isNotBlank()) {
                            try {
                                val bytes = Base64.decode(text.trim(), Base64.NO_WRAP)
                                payloads.add(bytes)
                            } catch (e: Exception) {
                                // Not a base64 payload or corrupted
                            }
                        }
                    }
                }
            } else {
                _relayActive.value = false
            }
        } catch (e: Exception) {
            _relayActive.value = false
            android.util.Log.w("VkRelay", "fetch failed: ${e.message}")
        }
        return@withContext payloads
    }
}
