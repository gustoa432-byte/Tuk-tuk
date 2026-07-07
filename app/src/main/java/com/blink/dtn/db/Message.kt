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
        Index(value = ["conversationId"])
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
    val status: Int = STATUS_PENDING,
    var retryCount: Int = 0,

    @Transient @ColumnInfo(name = "conversationId") var conversationId: String = ""
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_IN_FLIGHT = 1
        const val STATUS_SENT = 2
        const val STATUS_FAILED = 3
        const val STATUS_PENDING_KEY = 4
    }
}
