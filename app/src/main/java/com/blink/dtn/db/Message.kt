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
    }
}
