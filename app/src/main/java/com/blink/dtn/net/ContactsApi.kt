package com.blink.dtn.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hidden BLE handshake: exchange `publicBleKey` via online contact add.
 */
class ContactsApi(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun base(): String {
        if (!VpsConfig.isConfigured(context)) VpsConfig.init(context)
        val url = VpsConfig.baseUrl.value
        if (url.isBlank()) error("VPS URL not configured")
        return url
    }

    suspend fun addContact(targetUserId: String): Result<AddContactResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val body = json.encodeToString(AddContactRequest(targetUserId.trim()))
                        .toRequestBody(JSON)
                    val req = Request.Builder()
                        .url("${base()}/contacts/add")
                        .post(body)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $jwt")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val err = runCatching {
                                json.decodeFromString<ErrorBody>(text).error
                            }.getOrDefault(text.ifBlank { "request_failed" })
                            throw ApiException(resp.code, err)
                        }
                        json.decodeFromString<AddContactResponse>(text)
                    }
                }
            }.onFailure { Log.w(TAG, "addContact: ${it.message}") }
        }

    companion object {
        private const val TAG = "ContactsApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class AddContactRequest(val userId: String)

@Serializable
data class AddContactResponse(
    val ok: Boolean = false,
    val userId: String = "",
    /**
     * Empty while [pending]: the gateway only hands out a key once the other
     * side added you back, so a bare UUID can no longer harvest identities.
     */
    val publicBleKey: String = "",
    /** Request recorded, consent still missing. Older servers never send this. */
    val pending: Boolean = false
)
