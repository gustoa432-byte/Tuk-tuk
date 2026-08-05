package com.blink.dtn.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * Global server ban list (synced from GET /v1/moderation/blacklist).
 * Distinct from local [BlockedUser] — this is network-wide humanitarian moderation.
 */
@Entity(tableName = "banned_nodes")
data class BannedNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeId: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Long
)

@Dao
interface BannedNodeDao {
    @Query("SELECT node_id FROM banned_nodes")
    suspend fun allNodeIds(): List<String>

    @Query("SELECT COUNT(*) FROM banned_nodes WHERE node_id = :nodeId")
    suspend fun isBanned(nodeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BannedNodeEntity>)

    @Query("DELETE FROM banned_nodes")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(nodeIds: List<String>, syncedAt: Long = System.currentTimeMillis()) {
        clearAll()
        if (nodeIds.isEmpty()) return
        upsertAll(nodeIds.map { BannedNodeEntity(nodeId = it, syncedAt = syncedAt) })
    }
}
