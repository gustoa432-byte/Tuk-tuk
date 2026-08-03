package com.blink.dtn.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId"]),
        // Names must match MIGRATION_15_16 exactly — Room validates after migrate.
        Index(value = ["room"], name = "index_messages_room"),
        Index(value = ["room", "timestamp"], name = "index_messages_room_ts")
    ]
)
data class Message(
    @PrimaryKey val id: String,
    val type: String, // "PUBLIC" or "PRIVATE" or "SYSTEM_PROFILE"
    val senderId: String = "",
    val senderNick: String,
    val targetId: String? = null,
    val text: String,
    val room: String = "general",
    val timestamp: Long,
    var ttl: Int,
    val authorSignature: String? = null,
    @Transient @ColumnInfo(name = "is_mine") val isMine: Boolean = false,
    @Transient @ColumnInfo(name = "is_bridge_synced") var isBridgeSynced: Boolean = false,
    val isAck: Boolean = false,
    // End-to-end id of the message this ACK confirms. Null for non-ACK messages.
    // Replaces the previous overloaded use of `text` as the ACK payload.
    val originalMessageId: String? = null,
    val status: Int = STATUS_PENDING,
    var retryCount: Int = 0,

    /** Local-only: id of the message this one replies to (quote still goes in [text] for the wire). */
    @Transient @ColumnInfo(name = "reply_to_id") val replyToId: String? = null,
    /** Local-only: when this message was last edited on this device (0 = never). */
    @Transient @ColumnInfo(name = "edited_at") val editedAt: Long = 0L,
    /**
     * Local-only insertion / receive time on this device.
     * Chat UI orders by this field so peer clock skew cannot invert the thread.
     * Origin [timestamp] stays on the wire for TTL / display when clocks agree.
     */
    @Transient @ColumnInfo(name = "received_at") val receivedAt: Long = 0L,
    /** Local-only absolute path to image file for PRIVATE_IMAGE (never on mesh). */
    @Transient @ColumnInfo(name = "media_path") val mediaPath: String? = null,

    @Transient @ColumnInfo(name = "conversationId") var conversationId: String = ""
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_IN_FLIGHT = 1
        // Transmitted to at least one mesh peer (best-effort). Terminal good state for PUBLIC.
        const val STATUS_SENT = 2
        const val STATUS_FAILED = 3
        const val STATUS_PENDING_KEY = 4
        // End-to-end ACK received from the destination. Terminal good state for PRIVATE.
        const val STATUS_DELIVERED = 5

        const val TYPE_PRIVATE = "PRIVATE"
        const val TYPE_PRIVATE_IMAGE = "PRIVATE_IMAGE"
        const val TYPE_PUBLIC = "PUBLIC"

        /** Skew window: if origin clock differs from local order by more than this, show local time. */
        const val DISPLAY_SKEW_MS = 2L * 60L * 60L * 1000L
    }

    /** Clock shown on the bubble — origin time unless peer clock is badly skewed. */
    fun displayClockMs(): Long {
        if (receivedAt <= 0L) return timestamp
        return if (kotlin.math.abs(timestamp - receivedAt) > DISPLAY_SKEW_MS) receivedAt else timestamp
    }

    /** Local sort / dialog preview key. */
    fun localOrderMs(): Long = if (receivedAt > 0L) receivedAt else timestamp
}
