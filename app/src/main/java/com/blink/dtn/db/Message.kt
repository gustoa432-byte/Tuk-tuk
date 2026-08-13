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
     * Local-only insertion / receive time on this device (wall clock).
     * Used for dialog previews and soft display-time correction — not for chat order.
     * Origin [timestamp] stays on the wire for TTL / display when clocks agree.
     */
    @Transient @ColumnInfo(name = "received_at") val receivedAt: Long = 0L,
    /**
     * Local-only monotonic insert sequence on this device.
     * Chat UI orders strictly by this (ASC = oldest top, newest bottom).
     * Never taken from the wire or peer clocks.
     */
    @Transient @ColumnInfo(name = "local_seq") val localSeq: Long = 0L,
    /** Local-only absolute path to image file for PRIVATE_IMAGE (never on mesh). */
    @Transient @ColumnInfo(name = "media_path") val mediaPath: String? = null,

    /**
     * Local-only: wall clock of the current outbound attempt — set when the parcel
     * enters IN_FLIGHT and when a neighbour (or the gateway) takes custody.
     * 0 = no attempt in progress. Drives [CustodyPolicy]; see [custodyRounds].
     */
    @Transient @ColumnInfo(name = "custody_since") val custodySince: Long = 0L,
    /** Local-only: how many custody rounds this parcel already used ([CustodyPolicy.MAX_CUSTODY_ROUNDS]). */
    @Transient @ColumnInfo(name = "custody_rounds") val custodyRounds: Int = 0,

    /**
     * Parcel rarity / urgency ([MessagePriority]): 0 normal, 1 medium, 2 critical/SOS.
     * On the wire via [com.blink.dtn.ble.NetworkPacket.priority].
     */
    @ColumnInfo(name = "priority") val priority: Int = MessagePriority.NORMAL.code,

    /**
     * Chain of custody — nicknames/ids of devices that relayed this parcel.
     * Persisted as JSON via [Converters].
     */
    @ColumnInfo(name = "hop_history") val hopHistory: List<String> = emptyList(),

    @Transient @ColumnInfo(name = "conversationId") var conversationId: String = ""
) {
    fun priorityLevel(): MessagePriority = MessagePriority.fromCode(priority)
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_IN_FLIGHT = 1
        /**
         * Packet written to ≥1 neighbor GATT (or VPS hop).
         * Not end-to-end delivery — UI must not show "delivered".
         */
        const val STATUS_STORED_IN_NEIGHBOR = 2
        const val STATUS_FAILED = 3
        const val STATUS_PENDING_KEY = 4
        /** Cryptographic ACK from the destination [targetId]. Only this is "delivered". */
        const val STATUS_DELIVERED_ACK = 5
        /**
         * Honest TTL expiry: the parcel lived out its 48h age limit without an
         * end-to-end ACK. Distinct from [STATUS_FAILED] ("could not hand it over"):
         * this one *was* carried, nobody confirmed receipt in time.
         */
        const val STATUS_EXPIRED = 6

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

    /** Dialog list preview key (activity time), not bubble order. */
    fun localOrderMs(): Long = if (receivedAt > 0L) receivedAt else timestamp
}
