package com.blink.dtn.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Journal A — Social Orbit.
 * One row per stable mesh [nodeId] (RSA-derived), never a BLE MAC.
 */
@Entity(
    tableName = "social_orbit",
    indices = [
        Index(value = ["meet_count", "last_meet_at"], name = "index_social_orbit_meet")
    ]
)
data class SocialOrbitEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "last_meet_at")
    val lastMeetAt: Long,
    @ColumnInfo(name = "meet_count")
    val meetCount: Int
)
