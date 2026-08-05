package com.blink.dtn.crypto

import android.util.Log
import com.blink.dtn.ble.NetworkPacket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Mesh wire codec: UTF-8 JSON [NetworkPacket] with author RSA signature.
 * No shared AES passphrase — authenticity via [MeshEnvelopeCrypto], confidentiality
 * of PRIVATE bodies via [RsaUtils.encryptAsymmetric] to the recipient publicBleKey.
 */
object CryptoUtils {
    private const val TAG = "CryptoUtils"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Pack a signed wire packet to BLE/VPS opaque bytes. */
    fun packSigned(packet: NetworkPacket): ByteArray {
        val unsigned = packet.copy(authorSignature = null)
        val signature = MeshEnvelopeCrypto.sign(unsigned)
        require(signature.isNotBlank()) { "mesh_envelope_sign_failed" }
        val signed = unsigned.copy(authorSignature = signature)
        return json.encodeToString(signed).toByteArray(Charsets.UTF_8)
    }

    /** Decode wire bytes to JSON string (new clear JSON, or legacy AES if present). */
    fun decrypt(cipherTextWithIv: ByteArray): String? {
        if (cipherTextWithIv.isEmpty()) return null
        // New format: UTF-8 JSON starting with '{'
        if (cipherTextWithIv[0] == '{'.code.toByte()) {
            return try {
                String(cipherTextWithIv, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "UTF-8 decode failed: ${e.message}")
                null
            }
        }
        Log.w(TAG, "Rejected non-JSON mesh frame (legacy shared-AES removed)")
        return null
    }

    @Deprecated("Removed — use packSigned(NetworkPacket)", level = DeprecationLevel.ERROR)
    @Suppress("UNUSED_PARAMETER")
    fun encrypt(plainText: String): ByteArray {
        error("CryptoUtils.encrypt(String) removed — use packSigned(NetworkPacket)")
    }
}
