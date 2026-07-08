package com.blink.dtn.ble

import com.blink.dtn.db.Message
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Explicit on-the-wire packet model for the mesh.
 *
 * This is intentionally separate from [Message], which remains the local
 * UI/persistence model. Keeping the two apart lets us:
 *  - separate the per-hop packet identity ([packetId], regenerated on every
 *    (re)transmission) from the end-to-end message identity ([messageId], stable
 *    across hops and used for de-duplication and ACK correlation);
 *  - carry protocol-only fields (e.g. [originalMessageId] for ACKs) without
 *    overloading UI fields such as `Message.text`.
 *
 * De-duplication across the mesh is always keyed on [messageId]; [packetId] is
 * only a per-hop identifier useful for tracing a single transmission.
 */
@Serializable
data class NetworkPacket(
    val packetId: String,
    val messageId: String,
    val type: String,
    val senderId: String,
    val senderNick: String,
    val targetId: String? = null,
    // Wire payload: plaintext for PUBLIC, RSA ciphertext for PRIVATE, or a
    // structured blob for IDENTITY_* packets. Empty for ACKs.
    val payload: String,
    // Set only on ACK packets: the end-to-end id of the confirmed message.
    val originalMessageId: String? = null,
    val room: String = "general",
    val timestamp: Long,
    var ttl: Int,
    val authorSignature: String? = null,
    val isAck: Boolean = false
) {
    /**
     * Convert this wire packet into a local [Message]. Local-only fields
     * (isMine, status, conversationId) keep their defaults; callers set them
     * according to how the packet is being handled (delivered vs. relayed).
     */
    fun toMessage(): Message = Message(
        id = messageId,
        type = type,
        senderId = senderId,
        senderNick = senderNick,
        targetId = targetId,
        text = payload,
        room = room,
        timestamp = timestamp,
        ttl = ttl,
        authorSignature = authorSignature,
        isAck = isAck,
        originalMessageId = originalMessageId
    )

    companion object {
        /**
         * Build a fresh wire packet from a local [Message]. A new [packetId] is
         * minted for this specific transmission while [messageId] preserves the
         * end-to-end identity carried by [Message.id].
         */
        fun fromMessage(msg: Message): NetworkPacket = NetworkPacket(
            packetId = UUID.randomUUID().toString(),
            messageId = msg.id,
            type = msg.type,
            senderId = msg.senderId,
            senderNick = msg.senderNick,
            targetId = msg.targetId,
            payload = msg.text,
            originalMessageId = msg.originalMessageId,
            room = msg.room,
            timestamp = msg.timestamp,
            ttl = msg.ttl,
            authorSignature = msg.authorSignature,
            isAck = msg.isAck
        )
    }
}
