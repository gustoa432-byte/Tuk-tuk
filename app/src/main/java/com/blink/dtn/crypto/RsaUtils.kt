package com.blink.dtn.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RsaUtils {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "BLinkRSAKey"

    // Hybrid envelope constants. RSA/ECB/PKCS1Padding (max ~245 bytes) can only wrap a
    // small key, so we RSA-wrap a fresh AES-256 key and AES-GCM the actual body.
    // Envelope format is a delimiter-joined string: "v1:<b64 rsaKey>:<b64 iv>:<b64 ct+tag>".
    // ':' never appears in Base64.NO_WRAP output (alphabet is A-Za-z0-9+/=), so it is an
    // unambiguous delimiter.
    private const val ENVELOPE_VERSION = "v1"
    private const val ENVELOPE_DELIMITER = ":"
    private const val AES_KEY_SIZE_BYTES = 32
    private const val GCM_IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun generateAndStoreKeyPair() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            val parameterSpec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        }
    }

    fun getPublicKeyBase64(): String {
        generateAndStoreKeyPair()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val publicKey = keyStore.getCertificate(ALIAS)?.publicKey ?: return ""
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    // Canonical public-key bytes (X.509 DER) — exactly what getPublicKeyBase64()
    // base64-encodes. These are the bytes hashed to derive the self-certifying
    // node id (see NodeIdentity).
    fun getPublicKeyDer(): ByteArray? {
        generateAndStoreKeyPair()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getCertificate(ALIAS)?.publicKey?.encoded
    }

    fun encryptAsymmetric(plainText: String, publicKeyBase64: String): String {
        try {
            // 1. Fresh random AES-256 key + 12-byte GCM IV.
            val secureRandom = SecureRandom()
            val aesKeyBytes = ByteArray(AES_KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(GCM_IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            val aesKey = SecretKeySpec(aesKeyBytes, "AES")

            // 2. AES/GCM/NoPadding over the UTF-8 body (128-bit tag appended to ciphertext).
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherText = aesCipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // 3. RSA/ECB/PKCS1Padding wrap of the 32-byte AES key with the recipient key.
            val publicBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(publicBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec)

            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val wrappedKey = rsaCipher.doFinal(aesKeyBytes)

            // 4. Package the envelope string.
            return listOf(
                ENVELOPE_VERSION,
                Base64.encodeToString(wrappedKey, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP),
                Base64.encodeToString(cipherText, Base64.NO_WRAP)
            ).joinToString(ENVELOPE_DELIMITER)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun decryptAsymmetric(cipherTextBase64: String): String {
        try {
            // 1. Parse the envelope. Anything malformed -> "".
            val parts = cipherTextBase64.split(ENVELOPE_DELIMITER)
            if (parts.size != 4 || parts[0] != ENVELOPE_VERSION) return ""
            val wrappedKey = Base64.decode(parts[1], Base64.NO_WRAP)
            val iv = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[3], Base64.NO_WRAP)

            // 2. RSA-decrypt the AES key with the AndroidKeyStore private key.
            generateAndStoreKeyPair()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey ?: return ""

            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
            val aesKeyBytes = rsaCipher.doFinal(wrappedKey)

            // 3. AES/GCM decrypt the body.
            val aesKey = SecretKeySpec(aesKeyBytes, "AES")
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainText = aesCipher.doFinal(cipherText)

            // 4. UTF-8 plaintext.
            return String(plainText, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}
