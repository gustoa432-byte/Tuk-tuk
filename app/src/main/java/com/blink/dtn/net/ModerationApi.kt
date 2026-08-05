package com.blink.dtn.net

import android.content.Context
import android.util.Log
import com.blink.dtn.BuildConfig
import com.blink.dtn.moderation.BanlistVerifier
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
 * Moderation HTTP: report abuse + fetch global ban list.
 */
class ModerationApi(private val context: Context) {
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

    suspend fun fetchBlacklist(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            VpsJwtSupport.withJwtRetry(context) { jwt ->
                val req = Request.Builder()
                    .url("${base()}/v1/moderation/blacklist")
                    .get()
                    .header("Authorization", "Bearer $jwt")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ApiException(resp.code, text.ifBlank { "blacklist_failed" })
                    }
                    val body = json.decodeFromString<BlacklistResponseDto>(text)
                    if (!BanlistVerifier.verify(
                            body.nodes,
                            body.exp,
                            body.sig,
                            BuildConfig.BANLIST_HMAC_SECRET
                        )
                    ) {
                        throw ApiException(resp.code, "blacklist_sig_invalid")
                    }
                    body.nodes.map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }.onFailure { Log.w(TAG, "fetchBlacklist: ${it.message}") }
    }

    suspend fun report(
        reportedNodeId: String,
        decryptedMessageContent: String
    ): Result<ReportResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            VpsJwtSupport.withJwtRetry(context) { jwt ->
                val body = json.encodeToString(
                    ReportRequestDto(
                        reportedNodeId = reportedNodeId.trim(),
                        decryptedMessageContent = decryptedMessageContent
                    )
                ).toRequestBody(JSON)
                val req = Request.Builder()
                    .url("${base()}/v1/moderation/report")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $jwt")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val err = runCatching {
                            json.decodeFromString<ErrorBody>(text).error
                        }.getOrDefault(text.ifBlank { "report_failed" })
                        throw ApiException(resp.code, err)
                    }
                    json.decodeFromString<ReportResponseDto>(text)
                }
            }
        }.onFailure { Log.w(TAG, "report: ${it.message}") }
    }

    companion object {
        private const val TAG = "ModerationApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class BlacklistResponseDto(
    val nodes: List<String> = emptyList(),
    val exp: Long = 0,
    val sig: String = ""
)

@Serializable
data class ReportRequestDto(
    @kotlinx.serialization.SerialName("reported_node_id")
    val reportedNodeId: String,
    @kotlinx.serialization.SerialName("decrypted_message_content")
    val decryptedMessageContent: String
)

@Serializable
data class ReportResponseDto(
    val ok: Boolean = false,
    val id: Long = 0
)
