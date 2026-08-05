package com.blink.dtn.net

import android.content.Context
import android.util.Log
import com.blink.dtn.auth.AuthSessionStore
import com.blink.dtn.crypto.NodeIdentity
import com.blink.dtn.crypto.RsaUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Upload sanitized telemetry ZIP to VPS → Telegram proxy.
 */
object TelemetryApi {
    private const val TAG = "TelemetryApi"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class UploadResult(val ok: Boolean, val error: String? = null)

    fun uploadZip(
        context: Context,
        zip: File,
        note: String = "",
        peerNodeId: String? = null
    ): UploadResult {
        if (!zip.exists() || zip.length() <= 0L) {
            return UploadResult(false, "zip_empty")
        }
        if (zip.length() > 8L * 1024 * 1024) {
            return UploadResult(false, "zip_too_large")
        }
        val jwt = AuthSessionStore.jwt(context)
        if (jwt.isBlank()) {
            return UploadResult(false, "not_authenticated")
        }
        val base = VpsConfig.baseUrl.value.trimEnd('/')
        if (base.isBlank()) {
            return UploadResult(false, "vps_not_configured")
        }
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        val nodeId = peerNodeId
            ?: AuthSessionStore.serverNodeId(context).ifBlank {
                runCatching {
                    NodeIdentity.deriveNodeId(RsaUtils.getPublicKeyBase64())
                }.getOrDefault("")
            }

        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    zip.name,
                    zip.asRequestBody("application/zip".toMediaType())
                )
                .addFormDataPart("appVersion", appVersion)
                .addFormDataPart("nodeId", nodeId)
                .addFormDataPart("note", note.take(500))
                .build()
            val req = Request.Builder()
                .url("$base/v1/telemetry/upload")
                .post(body)
                .header("Authorization", "Bearer $jwt")
                .header("X-Node-Id", nodeId)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching {
                        JSONObject(text).optString("error").ifBlank { "http_${resp.code}" }
                    }.getOrDefault("http_${resp.code}")
                    Log.w(TAG, "upload failed: $err")
                    return UploadResult(false, err)
                }
                UploadResult(ok = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload exception: ${e.message}")
            UploadResult(false, e.message ?: "network_error")
        }
    }
}
