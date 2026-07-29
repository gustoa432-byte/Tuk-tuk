package com.blink.dtn.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_users",
    indices = [
        // Name must match MIGRATION_16_17 — Room validates indices after migrate.
        Index(value = ["blockedUserId"], name = "index_blocked_users_blockedUserId")
    ]
)
data class BlockedUser(
    @PrimaryKey val blockedNick: String,
    val blockedUserId: String = "",
    val blockedAt: Long
)
