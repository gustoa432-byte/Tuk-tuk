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
    val publicKey: String = ""
)
