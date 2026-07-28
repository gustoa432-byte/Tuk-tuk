package com.blink.dtn.security

import android.util.Base64
import android.util.Log
import com.blink.dtn.BuildConfig
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Developer / official-channel trust anchors.
 *
 * Honest limits (see also docs/SECURITY.md «Ключ от сети»):
 * - Play signing cert proves an APK came from the same publisher; mesh cannot stop forks.
 * - [AUTHOR_PUBLIC_KEY] proves SYSTEM_ANNOUNCEMENT / VERSION_ANNOUNCEMENT came from the
 *   project author — not that a random peer APK is “official TukTuk”.
 * - While [isAuthorKeyConfigured] is false, unsigned “official” claims are rejected.
 *
 * Set a real RSA-2048 public key (X.509 SubjectPublicKeyInfo, Base64, no PEM headers):
 * ```
 * openssl genrsa -out secrets/author_private.pem 2048
 * openssl rsa -in secrets/author_private.pem -pubout -outform DER | base64 -w0 > secrets/author_pub.b64
 * # paste author_pub.b64 into AUTHOR_PUBLIC_KEY and set AUTHOR_KEY_CONFIGURED = true
 * # sign: echo -n "$TEXT" | openssl dgst -sha256 -sign secrets/author_private.pem | base64 -w0
 * ```
 */
object SecurityConfig {
    private const val TAG = "SecurityConfig"

    /**
     * RSA-2048 public key (X.509 SubjectPublicKeyInfo, Base64). Private key stays in
     * gitignored `secrets/author_private.pem` — never ship it in the APK.
     */
    const val AUTHOR_PUBLIC_KEY: String =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvS3NgijtttZOAiaQNuXVkDY9PKQ0GMEJwO7j6MAP3KYRoAN6UDVen2kIfgr58uM1cKhjBRmY5/oEqwUzUVh03xN2SV9UWLVh/9IXxJ2MGxlwyagBx8qRLe5f97PY8vHKKVfZqExbphUejiUv/7VzLiQLLWQlUphQTds8q2W1RrksgkP8v4suATKFE8zz6JoNXGEGeYy5Vcn5OsdGrDjdX7gsLdbz/towBu9HKqZPQs5ofY2gOCCzgdvzwgcTUP/2fPDnqKeEBFp6kUuD3gbpG7+20ULhyfCzYWLbuox8PS8udwCarUqEF87RJG5przxNjNegYNgsLLV7Lf9YAVLNlwIDAQAB"

    /** True after [AUTHOR_PUBLIC_KEY] holds a full DER public key. */
    const val AUTHOR_KEY_CONFIGURED: Boolean = true

    /**
     * Fallback when [BuildConfig.EXPECTED_RELEASE_CERT_SHA256] is empty (e.g. no local
     * keystore.properties). Prefer the BuildConfig value injected from the release cert.
     */
    private const val FALLBACK_EXPECTED_RELEASE_CERT_SHA256: String = ""

    /**
     * SHA-256 hex of the expected release signing cert (lowercase, no colons).
     * Empty = Profile shows «подпись неизвестна» rather than claiming official.
     */
    val EXPECTED_RELEASE_CERT_SHA256: String
        get() {
            val fromBuild = BuildConfig.EXPECTED_RELEASE_CERT_SHA256.trim()
            return fromBuild.ifEmpty { FALLBACK_EXPECTED_RELEASE_CERT_SHA256 }
        }

    fun isAuthorKeyConfigured(): Boolean =
        AUTHOR_KEY_CONFIGURED &&
            AUTHOR_PUBLIC_KEY.isNotBlank() &&
            !AUTHOR_PUBLIC_KEY.contains("...") &&
            AUTHOR_PUBLIC_KEY.length >= 100

    /**
     * Verify author signature over UTF-8 [text] (SYSTEM_ANNOUNCEMENT / VERSION_ANNOUNCEMENT).
     * Returns false if key not configured, signature missing, or crypto verify fails.
     */
    fun verifySignature(text: String, signatureBase64: String?): Boolean {
        if (!isAuthorKeyConfigured()) {
            Log.w(TAG, "Author key not configured — rejecting signed-official claim")
            return false
        }
        if (signatureBase64.isNullOrBlank()) return false
        return try {
            val keyBytes = Base64.decode(AUTHOR_PUBLIC_KEY, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(spec)

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update(text.toByteArray(Charsets.UTF_8))

            val sigBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
            signature.verify(sigBytes)
        } catch (e: Exception) {
            Log.w(TAG, "verifySignature failed: ${e.message}")
            false
        }
    }

    /** Packet types that must carry a valid author signature to be accepted as official. */
    fun requiresAuthorSignature(packetType: String): Boolean =
        packetType == "SYSTEM_ANNOUNCEMENT" || packetType == "VERSION_ANNOUNCEMENT"
}
