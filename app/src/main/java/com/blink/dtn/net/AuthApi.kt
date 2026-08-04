package com.blink.dtn.net

import android.content.Context
import android.util.Log
import com.blink.dtn.crypto.RsaUtils
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
 * HTTP client for VPS auth endpoints (`/auth/email/send|verify`, `/auth/telegram`).
 */
class AuthApi(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun base(): String {
        if (!VpsConfig.isConfigured(context)) {
            VpsConfig.init(context)
        }
        val url = VpsConfig.baseUrl.value
        if (url.isBlank()) error("VPS URL not configured")
        return url
    }

    suspend fun sendEmailOtp(email: String): Result<EmailSendResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(EmailSendRequest(email.trim()))
                .toRequestBody(JSON)
            val req = Request.Builder()
                .url("${base()}/auth/email/send")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, parseError(text))
                }
                json.decodeFromString<EmailSendResponse>(text)
            }
        }.onFailure { Log.w(TAG, "sendEmailOtp: ${it.message}") }
    }

    suspend fun verifyEmailOtp(
        email: String,
        otp: String,
        publicBleKey: String = RsaUtils.getPublicKeyBase64()
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(publicBleKey.isNotBlank()) { "publicBleKey missing — generate RSA first" }
            val body = json.encodeToString(
                EmailVerifyRequest(
                    email = email.trim(),
                    otp = otp.trim(),
                    publicBleKey = publicBleKey
                )
            ).toRequestBody(JSON)
            val req = Request.Builder()
                .url("${base()}/auth/email/verify")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, parseError(text))
                }
                json.decodeFromString<AuthResponse>(text)
            }
        }.onFailure { Log.w(TAG, "verifyEmailOtp: ${it.message}") }
    }

    suspend fun telegramAuth(
        initData: String,
        publicBleKey: String = RsaUtils.getPublicKeyBase64()
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(publicBleKey.isNotBlank()) { "publicBleKey missing" }
            val body = json.encodeToString(
                TelegramAuthRequest(
                    initData = initData.trim(),
                    publicBleKey = publicBleKey
                )
            ).toRequestBody(JSON)
            val req = Request.Builder()
                .url("${base()}/auth/telegram")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, parseError(text))
                }
                json.decodeFromString<AuthResponse>(text)
            }
        }.onFailure { Log.w(TAG, "telegramAuth: ${it.message}") }
    }

    private fun parseError(text: String): String =
        runCatching { json.decodeFromString<ErrorBody>(text).error }
            .getOrDefault(text.ifBlank { "request_failed" })

    companion object {
        private const val TAG = "AuthApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ApiException(val httpCode: Int, override val message: String) : Exception(message)

@Serializable
data class EmailSendRequest(val email: String)

@Serializable
data class EmailSendResponse(
    val ok: Boolean = false,
    val devCode: String? = null
)

@Serializable
data class EmailVerifyRequest(
    val email: String,
    val otp: String,
    val publicBleKey: String
)

@Serializable
data class TelegramAuthRequest(
    val initData: String,
    val publicBleKey: String
)

@Serializable
data class AuthResponse(
    val ok: Boolean = false,
    val token: String = "",
    val userId: String = "",
    val authMethod: String = "",
    val authId: String = "",
    val publicBleKey: String = ""
)

@Serializable
data class ErrorBody(val error: String = "")
