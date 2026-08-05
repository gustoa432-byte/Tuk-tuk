package com.blink.dtn.crypto

import android.util.Base64
import android.util.Log
import com.blink.dtn.ble.NetworkPacket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Mesh envelope authenticity: SHA256withRSA over stable packet fields.
 * [hopHistory] and [ttl] are excluded so relays can append custody without resigning.
 */
object MeshEnvelopeCrypto {
    private const val TAG = "MeshEnvelope"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "BLinkRSAKey"

    /**
     * Canonical bytes the author signs / peers verify.
     * Must stay stable across hops (no ttl / hopHistory / authorSignature).
     */
    fun canonicalBytes(packet: NetworkPacket): ByteArray {
        val target = packet.targetId.orEmpty()
        val orig = packet.originalMessageId.orEmpty()
        val line = listOf(
            packet.packetId,
            packet.messageId,
            packet.type,
            packet.senderId,
            packet.senderNick,
            target,
            packet.payload,
            orig,
            packet.room,
            packet.timestamp.toString(),
            packet.priority.toString(),
            packet.isAck.toString()
        ).joinToString("\u001f")
        return line.toByteArray(Charsets.UTF_8)
    }

    fun sign(packet: NetworkPacket): String {
        RsaUtils.ensureSigningCapableKey()
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
                ?: return ""
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(privateKey)
            sig.update(canonicalBytes(packet))
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "sign failed: ${e.message}")
            ""
        }
    }

    fun verify(packet: NetworkPacket, publicKeyBase64: String): Boolean {
        val signatureBase64 = packet.authorSignature ?: return false
        if (signatureBase64.isBlank() || publicKeyBase64.isBlank()) return false
        return try {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(canonicalBytes(packet))
            sig.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.w(TAG, "verify failed: ${e.message}")
            false
        }
    }

    /** Self-check: senderId must equal NodeIdentity derived from [publicKeyBase64]. */
    fun senderMatchesKey(senderId: String, publicKeyBase64: String): Boolean {
        if (senderId.isBlank() || publicKeyBase64.isBlank()) return false
        return NodeIdentity.deriveNodeId(publicKeyBase64) == senderId
    }
}
