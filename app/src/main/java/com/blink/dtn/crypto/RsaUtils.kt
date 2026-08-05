package com.blink.dtn.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RsaUtils {
    private const val TAG = "RsaUtils"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "BLinkRSAKey"

    // Hybrid envelope: RSA-wrap AES-256 + AES-GCM body.
    // Format: "v1:<b64 rsaKey>:<b64 iv>:<b64 ct+tag>"
    private const val ENVELOPE_VERSION = "v1"
    private const val ENVELOPE_DELIMITER = ":"
    private const val AES_KEY_SIZE_BYTES = 32
    private const val GCM_IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun generateAndStoreKeyPair() {
        ensureSigningCapableKey()
    }

    /**
     * Ensure the device RSA key supports SIGN|VERIFY|ENCRYPT|DECRYPT.
     * Old installs (encrypt-only) are rotated once — nodeId changes with the new key.
     */
    fun ensureSigningCapableKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(ALIAS)) {
            if (canSignWithStoredKey(keyStore)) return
            Log.w(TAG, "Rotating RSA key to add SIGN purpose (nodeId will change once)")
            keyStore.deleteEntry(ALIAS)
        }
        val keyPairGenerator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val parameterSpec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT or
                KeyProperties.PURPOSE_SIGN or
                KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .build()
        keyPairGenerator.initialize(parameterSpec)
        keyPairGenerator.generateKeyPair()
    }

    private fun canSignWithStoredKey(keyStore: KeyStore): Boolean {
        return try {
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey ?: return false
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(privateKey)
            sig.update(byteArrayOf(1, 2, 3))
            sig.sign()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getPublicKeyBase64(): String {
        ensureSigningCapableKey()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val publicKey = keyStore.getCertificate(ALIAS)?.publicKey ?: return ""
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun getPublicKeyDer(): ByteArray? {
        ensureSigningCapableKey()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getCertificate(ALIAS)?.publicKey?.encoded
    }

    fun encryptAsymmetric(plainText: String, publicKeyBase64: String): String {
        try {
            val secureRandom = SecureRandom()
            val aesKeyBytes = ByteArray(AES_KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(GCM_IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            val aesKey = SecretKeySpec(aesKeyBytes, "AES")

            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherText = aesCipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val publicBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(publicBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec)

            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val wrappedKey = rsaCipher.doFinal(aesKeyBytes)

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
            val parts = cipherTextBase64.split(ENVELOPE_DELIMITER)
            if (parts.size != 4 || parts[0] != ENVELOPE_VERSION) return ""
            val wrappedKey = Base64.decode(parts[1], Base64.NO_WRAP)
            val iv = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[3], Base64.NO_WRAP)

            ensureSigningCapableKey()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey ?: return ""

            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
            val aesKeyBytes = rsaCipher.doFinal(wrappedKey)

            val aesKey = SecretKeySpec(aesKeyBytes, "AES")
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainText = aesCipher.doFinal(cipherText)

            return String(plainText, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    /** True when [text] looks like a hybrid RSA envelope (PRIVATE on the wire). */
    fun looksLikePrivateEnvelope(text: String): Boolean {
        val parts = text.split(ENVELOPE_DELIMITER)
        return parts.size == 4 && parts[0] == ENVELOPE_VERSION
    }
}
