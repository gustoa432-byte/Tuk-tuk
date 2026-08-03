package com.blink.dtn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val conversationId: String,
    val peerId: String?,
    val displayName: String?,
    val lastMessage: String?,
    val lastTimestamp: Long,
    val unreadCount: Int = 0,
    /** Epoch millis when pinned; 0 = not pinned. Newer pins sort higher among pins. */
    val pinnedAt: Long = 0L,
    val isArchived: Boolean = false
) {
    val isPinned: Boolean get() = pinnedAt > 0L
}
