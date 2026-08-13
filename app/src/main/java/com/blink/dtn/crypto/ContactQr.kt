package com.blink.dtn.crypto

import android.util.Base64
import org.json.JSONObject

/**
 * Contact QR v1: `{v,id,pk,n?,av?}`.
 * Claimed [id] must equal [NodeIdentity.deriveNodeId](pk) (anti key-swap).
 */
object ContactQr {

    data class Parsed(
        val nodeId: String,
        val publicKeyBase64: String,
        val nick: String,
        /** Raw `av` field from QR (base64); UI may compress via AvatarCompressor. */
        val avatarAvBase64: String?,
        val version: Int
    ) {
        val hasPinnedKey: Boolean get() = publicKeyBase64.isNotBlank()
    }

    sealed class Result {
        data class Ok(val parsed: Parsed) : Result()
        data object KeyMismatch : Result()
        data object NotContact : Result()
        data object Self : Result()
    }

    /**
     * @param allowBareNodeId if true, a 16-char Base32 id without JSON is accepted
     *   (no pinned key → PENDING_KEY until IDENTITY).
     */
    fun parse(
        raw: String,
        myNodeId: String,
        allowBareNodeId: Boolean = true
    ): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.NotContact

        if (trimmed.startsWith("{")) {
            val json = try {
                JSONObject(trimmed)
            } catch (_: Throwable) {
                return Result.NotContact
            }
            val pk = json.optString("pk", "").trim()
            if (pk.isEmpty()) return Result.NotContact
            val derivedId = NodeIdentity.deriveNodeId(pk)
            val claimedId = json.optString("id", "").trim()
            if (derivedId.isEmpty() || (claimedId.isNotEmpty() && claimedId != derivedId)) {
                return Result.KeyMismatch
            }
            if (derivedId == myNodeId) return Result.Self
            val av = json.optString("av", "").trim().ifEmpty { null }
            return Result.Ok(
                Parsed(
                    nodeId = derivedId,
                    publicKeyBase64 = pk,
                    nick = json.optString("n", "").trim(),
                    avatarAvBase64 = av,
                    version = json.optInt("v", 1)
                )
            )
        }

        if (!allowBareNodeId) return Result.NotContact
        if (!NodeIdentity.looksLikeNodeId(trimmed)) return Result.NotContact
        val id = trimmed.uppercase()
        if (id == myNodeId) return Result.Self
        return Result.Ok(
            Parsed(
                nodeId = id,
                publicKeyBase64 = "",
                nick = "",
                avatarAvBase64 = null,
                version = 0
            )
        )
    }

    fun build(
        nodeId: String,
        publicKeyBase64: String,
        nick: String,
        avatarJpeg: ByteArray? = null
    ): String {
        return JSONObject().apply {
            put("v", 1)
            put("id", nodeId)
            put("pk", publicKeyBase64)
            put("n", nick)
            if (avatarJpeg != null && avatarJpeg.isNotEmpty()) {
                put("av", Base64.encodeToString(avatarJpeg, Base64.NO_WRAP))
            }
        }.toString()
    }

    fun avatarBytes(parsed: Parsed): ByteArray? {
        val av = parsed.avatarAvBase64 ?: return null
        return try {
            val raw = Base64.decode(av, Base64.DEFAULT)
            if (raw.isEmpty() || raw.size > 64_000) null else raw
        } catch (_: Exception) {
            null
        }
    }
}
