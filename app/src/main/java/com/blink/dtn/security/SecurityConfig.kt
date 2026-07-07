package com.blink.dtn.security

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object SecurityConfig {
    // Dummy SHA256withRSA Public Key (Base64) - For OTA System Announcements
    const val AUTHOR_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzA3vB8..." // Dummy key
    
    fun verifySignature(text: String, signatureBase64: String?): Boolean {
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
            false
        }
    }
}
