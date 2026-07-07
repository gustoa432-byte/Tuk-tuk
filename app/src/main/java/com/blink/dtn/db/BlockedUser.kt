package com.blink.dtn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_users")
data class BlockedUser(
    @PrimaryKey val blockedNick: String,
    val blockedAt: Long
)
