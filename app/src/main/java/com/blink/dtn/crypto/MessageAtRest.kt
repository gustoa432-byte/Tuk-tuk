package com.blink.dtn.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals PRIVATE plaintext before Room persistence (item #9 incremental).
 * Wire E2E stays RSA hybrid; this only protects the local DB dump.
 *
 * Format: `atrest:v1:<b64 iv>:<b64 ct+tag>`
 */
object MessageAtRest {
    private const val TAG = "MessageAtRest"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "TukTukMsgAtRest"
    private const val PREFIX = "atrest:v1:"
    private const val GCM_IV = 12
    private const val GCM_TAG_BITS = 128

    fun isSealed(text: String): Boolean = text.startsWith(PREFIX)

    fun seal(plainText: String): String {
        if (plainText.isEmpty() || isSealed(plainText)) return plainText
        return try {
            val key = secretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            PREFIX +
                Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "seal failed: ${e.message}")
            plainText
        }
    }

    fun reveal(stored: String): String {
        if (!isSealed(stored)) return stored
        return try {
            val body = stored.removePrefix(PREFIX)
            val parts = body.split(":", limit = 2)
            if (parts.size != 2) return stored
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "reveal failed: ${e.message}")
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
