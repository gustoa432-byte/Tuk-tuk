package com.blink.dtn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_packets")
data class SeenPacket(
    @PrimaryKey val id: String,
    val receivedAt: Long
)
