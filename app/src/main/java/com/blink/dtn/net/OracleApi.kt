package com.blink.dtn.net

import android.content.Context
import android.util.Log
import com.blink.dtn.db.SocialOrbitEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Oracle HTTP client: Journal A sync + courier hints.
 * OkHttp + kotlinx.serialization (same stack as [AuthApi] / [ContactsApi]).
 */
class OracleApi(private val context: Context) {
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

    /** `POST /v1/oracle/sync` — push local social-orbit rows. */
    suspend fun sync(orbits: List<SocialOrbitEntity>): Result<OracleSyncResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val body = json.encodeToString(
                        OracleSyncRequest(
                            orbits = orbits.map {
                                OracleOrbitDto(
                                    targetNode = it.nodeId,
                                    meetCount = it.meetCount.toLong(),
                                    lastMeetAt = it.lastMeetAt
                                )
                            }
                        )
                    ).toRequestBody(JSON)
                    executeAuthorized(
                        path = "/v1/oracle/sync",
                        jwt = jwt,
                        body = body
                    ) { text -> json.decodeFromString<OracleSyncResponse>(text) }
                }
            }.onFailure { Log.w(TAG, "sync: ${it.message}") }
        }

    /** `POST /v1/oracle/hint` — top-3 courier candidates toward [targetNode]. */
    suspend fun hint(targetNode: String): Result<OracleHintResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = targetNode.trim()
                require(target.isNotEmpty()) { "target_node_required" }
                VpsJwtSupport.withJwtRetry(context) { jwt ->
                    val body = json.encodeToString(OracleHintRequest(targetNode = target))
                        .toRequestBody(JSON)
                    executeAuthorized(
                        path = "/v1/oracle/hint",
                        jwt = jwt,
                        body = body
                    ) { text -> json.decodeFromString<OracleHintResponse>(text) }
                }
            }.onFailure { Log.w(TAG, "hint: ${it.message}") }
        }

    private fun <T> executeAuthorized(
        path: String,
        jwt: String,
        body: okhttp3.RequestBody,
        parse: (String) -> T
    ): T {
        val req = Request.Builder()
            .url("${base()}$path")
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
            return parse(text)
        }
    }

    companion object {
        private const val TAG = "OracleApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class OracleSyncRequest(
    val orbits: List<OracleOrbitDto>
)

@Serializable
data class OracleOrbitDto(
    @SerialName("target_node") val targetNode: String,
    @SerialName("meet_count") val meetCount: Long,
    @SerialName("last_meet_at") val lastMeetAt: Long
)

@Serializable
data class OracleSyncResponse(
    val ok: Boolean = false,
    val accepted: Int = 0
)

@Serializable
data class OracleHintRequest(
    @SerialName("target_node") val targetNode: String
)

@Serializable
data class OracleHintResponse(
    @SerialName("recommended_couriers")
    val recommendedCouriers: List<OracleCourierHint> = emptyList()
)

@Serializable
data class OracleCourierHint(
    @SerialName("node_id") val nodeId: String = "",
    val score: Double = 0.0
)
