package com.blink.dtn.moderation

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies VPS HMAC-SHA256 ban list (`nodes|exp` payload) before [GlobalBanCache.replaceAll].
 */
object BanlistVerifier {
    fun verify(nodes: List<String>, expEpochSec: Long, sigHex: String, secret: String): Boolean {
        if (secret.isBlank() || sigHex.isBlank()) return false
        val nowSec = System.currentTimeMillis() / 1000L
        if (expEpochSec < nowSec) return false
        val payload = nodes.joinToString("|") + "|" + expEpochSec
        val expected = hmacSha256Hex(secret, payload)
        return expected.equals(sigHex.trim(), ignoreCase = true)
    }

    fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { b ->
            "%02x".format(b)
        }
    }
}
