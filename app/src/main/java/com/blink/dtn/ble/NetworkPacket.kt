package com.blink.dtn.ble

import com.blink.dtn.db.Message
import kotlinx.serialization.Serializable

/**
 * Explicit on-the-wire packet model for the mesh.
 *
 * This is intentionally separate from [Message], which remains the local
 * UI/persistence model. Keeping the two apart lets us:
 *  - carry the end-to-end message identity ([messageId], stable across hops and
 *    used for de-duplication and ACK correlation). [packetId] is kept equal to
 *    [messageId] so a message is recognised as the same logical packet no matter
 *    which path or hop it arrives by;
 *  - carry protocol-only fields (e.g. [originalMessageId] for ACKs) without
 *    overloading UI fields such as `Message.text`.
 *
 * De-duplication across the mesh is always keyed on the stable id
 * ([packetId] == [messageId]).
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
    val isAck: Boolean = false,
    /** Parcel rarity: 0 normal / 1 medium / 2 critical. Default keeps old peers compatible. */
    val priority: Int = 0,
    /** Chain of custody nicknames/ids accumulated across relays. */
    val hopHistory: List<String> = emptyList()
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
        originalMessageId = originalMessageId,
        priority = priority,
        hopHistory = hopHistory
    )

    companion object {
        /**
         * Build a wire packet from a local [Message]. [packetId] is set equal to
         * [messageId] (the stable end-to-end id carried by [Message.id]) so that
         * de-duplication recognises the same logical message across every hop and
         * retransmission — the packet id no longer changes per transmission.
         */
        fun fromMessage(msg: Message): NetworkPacket = NetworkPacket(
            packetId = msg.id,
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
            isAck = msg.isAck,
            priority = msg.priority,
            hopHistory = msg.hopHistory
        )
    }
}
