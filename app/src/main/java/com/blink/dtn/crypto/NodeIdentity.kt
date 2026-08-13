package com.blink.dtn.crypto

import android.util.Base64
import java.security.MessageDigest

/**
 * Self-certifying node identity.
 *
 * A node id is derived directly from the node's RSA public key:
 *     nodeId = Base32(SHA-256(publicKeyDER))[0..16]  (80 bits, uppercase, no padding)
 *
 * This cryptographically binds the id to the key: to announce a victim's id an
 * attacker would need an ~2^80 SHA-256 preimage, so a single ingress check
 * (senderId == deriveNodeId(announcedPubKey)) closes the key-impersonation gap.
 */
object NodeIdentity {

    // RFC 4648 Base32 alphabet (no padding). 80 bits = 16 * 5-bit groups → 16 chars.
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val ID_BYTES = 10   // first 80 bits of the SHA-256 digest
    private const val ID_CHARS = 16   // 80 bits / 5 bits per char

    /**
     * Derive the node id from a public key encoded as Base64.NO_WRAP (X.509 DER).
     * Returns "" for blank/invalid input.
     */
    fun deriveNodeId(publicKeyBase64: String): String {
        if (publicKeyBase64.isBlank()) return ""
        return try {
            val der = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            if (der.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            base32(digest.copyOfRange(0, ID_BYTES))
        } catch (e: Exception) {
            ""
        }
    }

    /** Convenience: our own node id, derived from our AndroidKeyStore public key. */
    fun myNodeId(): String = deriveNodeId(RsaUtils.getPublicKeyBase64())

    /** Length of a self-certifying node id (80-bit Base32, no padding). */
    const val NODE_ID_LENGTH = ID_CHARS

    /** True if [id] looks like a current self-certifying Base32 node id. */
    fun looksLikeNodeId(id: String?): Boolean {
        if (id.isNullOrBlank() || id.length != NODE_ID_LENGTH) return false
        return id.all { ch ->
            val c = ch.uppercaseChar()
            c in 'A'..'Z' || c in '2'..'7'
        }
    }

    /**
     * Pre-v2 ids were `UUID.randomUUID().substring(0, 8).uppercase()` — 8 hex chars,
     * unrelated to the key. Anything of that shape in peer/conversation references
     * is stale after the self-certifying-id migration.
     */
    fun isLegacyNodeId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (id == "general" || id == com.blink.dtn.db.BLinkDao.RELAY_CONVERSATION_ID) return false
        return id.length == 8 && id.all { it in '0'..'9' || it in 'A'..'F' }
    }

    /**
     * Base32-encode exactly [ID_BYTES] bytes (80 bits) into [ID_CHARS] chars.
     * No padding is needed because 80 is a multiple of 5.
     */
    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder(ID_CHARS)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(BASE32_ALPHABET[index])
                bitsLeft -= 5
            }
        }
        return sb.toString()
    }
}
