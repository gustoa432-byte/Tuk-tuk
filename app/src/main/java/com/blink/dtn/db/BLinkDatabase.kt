package com.blink.dtn.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Message::class, SeenPacket::class, BlockedUser::class, UserProfile::class, Conversation::class], version = 11, exportSchema = false)
abstract class BLinkDatabase : RoomDatabase() {

    abstract fun bLinkDao(): BLinkDao
    abstract fun conversationDao(): ConversationDao

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

        fun getDatabase(context: Context): BLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BLinkDatabase::class.java,
                    "blink_database"
                )
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                // Do NOT wipe user data on every unknown schema change. Real
                // migrations are provided for 9->10->11; destructive fallback is
                // deliberately limited to legacy pre-9 schemas (which never had a
                // migration path) and to downgrades. Any future forgotten
                // migration will now surface as a crash in debug instead of
                // silently deleting messages.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
