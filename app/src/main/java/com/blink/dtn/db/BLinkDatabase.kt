package com.blink.dtn.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Message::class,
        SeenPacket::class,
        BlockedUser::class,
        UserProfile::class,
        Conversation::class,
        SocialOrbitEntity::class,
        BannedNodeEntity::class
    ],
    version = 24,
    exportSchema = false
)
@androidx.room.TypeConverters(Converters::class)
abstract class BLinkDatabase : RoomDatabase() {

    abstract fun bLinkDao(): BLinkDao
    abstract fun conversationDao(): ConversationDao
    abstract fun socialOrbitDao(): SocialOrbitDao
    abstract fun bannedNodeDao(): BannedNodeDao

    companion object {
        @Volatile
        private var INSTANCE: BLinkDatabase? = null

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create conversations table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `conversationId` TEXT NOT NULL,
                        `peerId` TEXT,
                        `displayName` TEXT,
                        `lastMessage` TEXT,
                        `lastTimestamp` INTEGER NOT NULL,
                        `unreadCount` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`)
                    )
                    """.trimIndent()
                )

                // 2. Populate conversations based on existing messages
                database.execSQL(
                    """
                    INSERT INTO `conversations` (`conversationId`, `peerId`, `displayName`, `lastMessage`, `lastTimestamp`, `unreadCount`)
                    SELECT 
                        CASE 
                            WHEN type = 'PUBLIC' THEN 'general'
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderId END)
                        END as conversationId,
                        CASE 
                            WHEN type = 'PUBLIC' THEN NULL
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderId END)
                        END as peerId,
                        CASE 
                            WHEN type = 'PUBLIC' THEN 'General Chat'
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderNick END)
                        END as displayName,
                        text as lastMessage,
                        MAX(timestamp) as lastTimestamp,
                        0 as unreadCount
                    FROM messages
                    GROUP BY 1
                    """.trimIndent()
                )

                // 3. Create new messages table with conversationId and foreign key
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `senderId` TEXT NOT NULL, 
                        `senderNick` TEXT NOT NULL, 
                        `targetId` TEXT, 
                        `text` TEXT NOT NULL, 
                        `room` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL, 
                        `ttl` INTEGER NOT NULL, 
                        `authorSignature` TEXT, 
                        `is_mine` INTEGER NOT NULL, 
                        `is_bridge_synced` INTEGER NOT NULL, 
                        `isAck` INTEGER NOT NULL, 
                        `status` INTEGER NOT NULL, 
                        `retryCount` INTEGER NOT NULL, 
                        `conversationId` TEXT NOT NULL, 
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`conversationId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // 4. Copy data from old messages to new messages
                database.execSQL(
                    """
                    INSERT INTO `messages_new` (
                        `id`, `type`, `senderId`, `senderNick`, `targetId`, `text`, `room`, 
                        `timestamp`, `ttl`, `authorSignature`, `is_mine`, `is_bridge_synced`, 
                        `isAck`, `status`, `retryCount`, `conversationId`
                    )
                    SELECT 
                        `id`, `type`, `senderId`, `senderNick`, `targetId`, `text`, `room`, 
                        `timestamp`, `ttl`, `authorSignature`, `is_mine`, `is_bridge_synced`, 
                        `isAck`, `status`, `retryCount`,
                        CASE 
                            WHEN type = 'PUBLIC' THEN 'general'
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderId END)
                        END as conversationId
                    FROM messages
                    """.trimIndent()
                )

                // 5. Drop old table and rename new table
                database.execSQL("DROP TABLE `messages`")
                database.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")

                // 6. Create index on conversationId
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
            }
        }

        // Adds originalMessageId, which carries the end-to-end id an ACK confirms
        // (previously this was overloaded into the `text`/`payload` field).
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `originalMessageId` TEXT")
            }
        }

        // Local alias + trust status for dialog rename / stranger message-request flow.
        // Existing peers are grandfathered as CONTACT so current dialogs stay normal.
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `user_profiles` ADD COLUMN `localAlias` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `user_profiles` ADD COLUMN `trustStatus` TEXT NOT NULL DEFAULT 'CONTACT'"
                )
            }
        }

        // QR out-of-band verification flag («проверен» vs «из сети»).
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `user_profiles` ADD COLUMN `verifiedOutOfBand` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Peer app version gossip (IDENTITY_ANNOUNCEMENT vc|vn).
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `user_profiles` ADD COLUMN `appVersionCode` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `user_profiles` ADD COLUMN `appVersionName` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        // Compact avatar JPEG blob for profiles / contact QR.
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `user_profiles` ADD COLUMN `avatarBlob` BLOB DEFAULT NULL"
                )
            }
        }

        /**
         * v15 → v16: Room-aware public chat.
         *
         * 1. Index on `room` column for fast per-room queries.
         * 2. Normalise legacy "general" → "1" (MeshRoom.GENERAL) so all rows use
         *    the compact single-char wire IDs going forward.
         *
         * Drop Policy helper: a dedicated index on (room, timestamp) lets the
         * overflow-pruner find and delete old FLOOD ("9") messages efficiently
         * without a full-table scan.
         */
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Fast filter for getPublicMessagesForRoomFlow
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_room` ON `messages` (`room`)"
                )
                // Drop Policy pruner index — (room, timestamp) composite
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_room_ts` ON `messages` (`room`, `timestamp`)"
                )
                // Normalise legacy "general" → "1"
                database.execSQL(
                    "UPDATE `messages` SET `room` = '1' WHERE `room` = 'general'"
                )
            }
        }

        // Silent block by stable UID while preserving legacy nick-based blocks.
        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `blocked_users` ADD COLUMN `blockedUserId` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_blocked_users_blockedUserId` ON `blocked_users` (`blockedUserId`)"
                )
            }
        }

        // Dialog list: persistent pin + archive (Phase 2).
        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `pinnedAt` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Chat: local reply link + edit timestamp (Phase 3).
        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `reply_to_id` TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `edited_at` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Local receive/send order (clock-skew safe) + optional image path.
        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `received_at` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `media_path` TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "UPDATE `messages` SET `received_at` = `timestamp` WHERE `received_at` = 0"
                )
            }
        }

        // Monotonic local chat order (independent of wall clocks / peer timestamps).
        // Also repairs received_at wiped to 0 by enqueue→update clobber on PUBLIC rows.
        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `local_seq` INTEGER NOT NULL DEFAULT 0"
                )
                // Preserve insert order via SQLite rowid; new inserts get MAX+1 in DAO.
                database.execSQL(
                    "UPDATE `messages` SET `local_seq` = `rowid` WHERE `local_seq` = 0"
                )
                database.execSQL(
                    """
                    UPDATE `messages`
                    SET `received_at` = CASE
                        WHEN `received_at` > 0 THEN `received_at`
                        WHEN `timestamp` > 0 THEN `timestamp`
                        ELSE `rowid`
                    END
                    WHERE `received_at` = 0
                    """.trimIndent()
                )
            }
        }

        // Human Layer: parcel priority + hop chain of custody.
        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `hop_history` TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        /**
         * Two Journals prep for predictive routing.
         * Additive only: Journal A table. Journal B keeps existing seen_packets columns.
         */
        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `social_orbit` (
                        `node_id` TEXT NOT NULL,
                        `last_meet_at` INTEGER NOT NULL,
                        `meet_count` INTEGER NOT NULL,
                        PRIMARY KEY(`node_id`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_social_orbit_meet` ON `social_orbit` (`meet_count`, `last_meet_at`)"
                )
            }
        }

        val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `banned_nodes` (
                        `node_id` TEXT NOT NULL,
                        `synced_at` INTEGER NOT NULL,
                        PRIMARY KEY(`node_id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): BLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                // Never wipe blink_database on migration failure — user messages must survive.
                val instance = buildAndOpen(context)
                INSTANCE = instance
                instance
            }
        }

        private fun buildAndOpen(context: Context): BLinkDatabase {
            val db = Room.databaseBuilder(
                context.applicationContext,
                BLinkDatabase::class.java,
                "blink_database"
            )
                .addMigrations(
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24
                )
                // Pre-v9 never had migrations; wipe those only.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
            // Force migration + schema validation now (not lazily on first DAO call).
            db.openHelper.writableDatabase
            return db
        }
    }
}
