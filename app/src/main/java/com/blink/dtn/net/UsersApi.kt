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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Username is an internet address, not a roster and not a public profile.
 * Exact lookup only — the gateway never lists people or similar names.
 */
class UsersApi(private val context: Context) {
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
        return url.trimEnd('/')
    }

    suspend fun lookup(username: String): Result<UserAddressResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = Username.normalize(username)
                if (!Username.isValid(normalized)) {
                    throw ApiException(400, "username_invalid")
                }
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val q = URLEncoder.encode(normalized, Charsets.UTF_8.name())
                    val req = Request.Builder()
                        .url("${base()}/v1/users/lookup?username=$q")
                        .get()
                        .header("Authorization", "Bearer $jwt")
                        .build()
                    execute(req)
                }
            }.onFailure { Log.w(TAG, "lookup: ${it.message}") }
        }

    suspend fun me(): Result<UserAddressResponse> = withContext(Dispatchers.IO) {
        runCatching {
            VpsJwtSupport.withJwtRetry(context) { jwt ->
                val req = Request.Builder()
                    .url("${base()}/v1/users/me")
                    .get()
                    .header("Authorization", "Bearer $jwt")
                    .build()
                execute(req)
            }
        }.onFailure { Log.w(TAG, "me: ${it.message}") }
    }

    suspend fun claim(username: String): Result<UserAddressResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = Username.normalize(username)
                if (!Username.isValid(normalized)) {
                    throw ApiException(400, "username_invalid")
                }
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val body = json.encodeToString(ClaimUsernameRequest(normalized))
                        .toRequestBody(JSON)
                    val req = Request.Builder()
                        .url("${base()}/v1/users/username")
                        .post(body)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $jwt")
                        .build()
                    execute(req)
                }
            }.onFailure { Log.w(TAG, "claim: ${it.message}") }
        }

    suspend fun claimPhone(hash: String): Result<UserAddressResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val body = json.encodeToString(ClaimPhoneRequest(hash.trim().lowercase()))
                        .toRequestBody(JSON)
                    val req = Request.Builder()
                        .url("${base()}/v1/users/phone")
                        .post(body)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $jwt")
                        .build()
                    execute(req)
                }
            }.onFailure { Log.w(TAG, "claimPhone: ${it.message}") }
        }

    suspend fun clearPhone(): Result<UserAddressResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val req = Request.Builder()
                        .url("${base()}/v1/users/phone/clear")
                        .post("{}".toRequestBody(JSON))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $jwt")
                        .build()
                    execute(req)
                }
            }.onFailure { Log.w(TAG, "clearPhone: ${it.message}") }
        }

    suspend fun lookupPhones(hashes: List<String>): Result<PhoneLookupResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (hashes.size > PhoneContactsMatcher.BATCH_SIZE) {
                    throw ApiException(400, "batch_too_large")
                }
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val body = json.encodeToString(PhoneLookupRequest(hashes))
                        .toRequestBody(JSON)
                    val req = Request.Builder()
                        .url("${base()}/v1/users/phone/lookup")
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
                        json.decodeFromString<PhoneLookupResponse>(text)
                    }
                }
            }.onFailure { Log.w(TAG, "lookupPhones: ${it.message}") }
        }

    private fun execute(req: Request): UserAddressResponse {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching {
                    json.decodeFromString<ErrorBody>(text).error
                }.getOrDefault(text.ifBlank { "request_failed" })
                throw ApiException(resp.code, err)
            }
            return json.decodeFromString<UserAddressResponse>(text)
        }
    }

    companion object {
        private const val TAG = "UsersApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class ClaimUsernameRequest(val username: String)

@Serializable
data class UserAddressResponse(
    val publicId: String = "",
    val username: String = "",
    val publicKey: String = "",
    val nodeId: String = "",
    val phoneDiscoverable: Boolean = false
)

@Serializable
data class ClaimPhoneRequest(val hash: String)

@Serializable
data class PhoneLookupRequest(val hashes: List<String>)

@Serializable
data class PhoneLookupResponse(
    val results: List<PhoneHit> = emptyList()
)

@Serializable
data class PhoneHit(
    val hash: String = "",
    val exists: Boolean = false,
    val nodeId: String? = null,
    val publicKey: String? = null,
    val username: String? = null
)
