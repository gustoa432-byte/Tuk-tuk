package com.blink.dtn.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object CryptoUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    // TEMPORARY mesh-wide transport key: encrypts the outer BLE JSON envelope for
    // all nodes sharing this APK. This is NOT end-to-end security — PRIVATE
    // payloads are additionally wrapped with per-peer RSA. Replace with a
    // negotiated group key before production deployment.
    private const val PASSPHRASE = "BlinkSurvivalMeshNetwork2026!@#"

    private val secretKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(PASSPHRASE.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, ALGORITHM)
    }

    fun encrypt(plainText: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        // Prepend IV to the ciphertext
        return iv + cipherText
    }

    fun decrypt(cipherTextWithIv: ByteArray): String? {
        return try {
            if (cipherTextWithIv.size < IV_LENGTH_BYTE) {
                android.util.Log.e("CryptoUtils", "Payload too short for IV")
                return null
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = cipherTextWithIv.copyOfRange(0, IV_LENGTH_BYTE)
            val cipherText = cipherTextWithIv.copyOfRange(IV_LENGTH_BYTE, cipherTextWithIv.size)
            
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
            
            val plainTextBytes = cipher.doFinal(cipherText)
            String(plainTextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtils", "Decryption failed: ${e.message}", e)
            null
        }
    }
}
