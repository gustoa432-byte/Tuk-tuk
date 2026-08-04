package com.blink.dtn.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Journal B — Seen Cache (meteor filter).
 *
 * Columns keep historical Room names (`id`, `receivedAt`) so v22→v23 stays additive.
 * Semantically: [packetId] = wire packet/message id, [receivedAt] = first sighting.
 */
@Entity(tableName = "seen_packets")
data class SeenPacket(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val packetId: String,
    @ColumnInfo(name = "receivedAt")
    val receivedAt: Long
) {
    /** Backward-compatible alias used by older call sites. */
    val id: String get() = packetId
}
