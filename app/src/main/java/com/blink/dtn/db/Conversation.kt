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
    val unreadCount: Int = 0
)
