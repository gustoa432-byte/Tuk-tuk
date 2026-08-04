package com.blink.dtn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Journal A — physical peer intersections for future Oracle scoring.
 */
@Dao
abstract class SocialOrbitDao {

    @Query("SELECT * FROM social_orbit ORDER BY meet_count DESC, last_meet_at DESC")
    abstract fun getAllOrbits(): Flow<List<SocialOrbitEntity>>

    @Query("SELECT * FROM social_orbit ORDER BY meet_count DESC, last_meet_at DESC")
    abstract suspend fun getAllOrbitsOnce(): List<SocialOrbitEntity>

    @Query("SELECT * FROM social_orbit WHERE node_id = :nodeId LIMIT 1")
    abstract suspend fun getByNodeId(nodeId: String): SocialOrbitEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: SocialOrbitEntity): Long

    @Update
    abstract suspend fun update(entity: SocialOrbitEntity)

    /**
     * Record a BLE identity handshake with [nodeId].
     * Increments [SocialOrbitEntity.meetCount] only if the previous meet was
     * at least [JournalLimits.MEET_COOLDOWN_MS] ago (anti-spam).
     */
    @Transaction
    open suspend fun upsertContact(
        nodeId: String,
        now: Long = System.currentTimeMillis(),
        cooldownMs: Long = JournalLimits.MEET_COOLDOWN_MS
    ) {
        val id = nodeId.trim()
        if (id.isEmpty()) return
        val existing = getByNodeId(id)
        if (existing == null) {
            insert(
                SocialOrbitEntity(
                    nodeId = id,
                    lastMeetAt = now,
                    meetCount = 1
                )
            )
            return
        }
        if (now - existing.lastMeetAt < cooldownMs) {
            return
        }
        update(
            existing.copy(
                lastMeetAt = now,
                meetCount = existing.meetCount + 1
            )
        )
    }
}
