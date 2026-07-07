package com.blink.dtn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BLinkDao {
    @androidx.room.Transaction
    open suspend fun insertMessageWithConversation(msg: Message) {
        val convId = if (msg.type == "PUBLIC" || msg.type == "SYSTEM_ANNOUNCEMENT") {
            "general"
        } else {
            if (msg.isMine) msg.targetId ?: msg.senderId else msg.senderId
        }
        
        msg.conversationId = convId
        
        var conv = getConversationByIdInternal(convId)
        if (conv == null) {
            val displayName = if (convId == "general") "General Chat" else (if (msg.isMine) msg.targetId else msg.senderNick) ?: "Unknown"
            val peerId = if (convId == "general") null else convId
            conv = Conversation(
                conversationId = convId,
                peerId = peerId,
                displayName = displayName,
                lastMessage = msg.text,
                lastTimestamp = msg.timestamp,
                unreadCount = if (msg.isMine) 0 else 1
            )
            insertConversationInternal(conv)
        } else {
            conv = conv.copy(
                lastMessage = msg.text,
                lastTimestamp = maxOf(msg.timestamp, conv.lastTimestamp),
                unreadCount = conv.unreadCount + if (msg.isMine) 0 else 1
            )
            updateConversationInternal(conv)
        }
        
        insertMessage(msg)
        android.util.Log.d("DB_INSERT", "ConversationId=${msg.conversationId} MessageId=${msg.id} Status=${msg.status}")
    }

    @Query("SELECT * FROM conversations WHERE conversationId = :id LIMIT 1")
    abstract suspend fun getConversationByIdInternal(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertConversationInternal(conversation: Conversation)

    @androidx.room.Update
    abstract suspend fun updateConversationInternal(conversation: Conversation)

    
    // --- Message ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMessage(message: Message): Long

    @Query("SELECT * FROM messages WHERE (type = 'PUBLIC' OR type = 'SYSTEM_ANNOUNCEMENT') AND senderNick NOT IN (SELECT blockedNick FROM blocked_users) ORDER BY CASE WHEN type = 'SYSTEM_ANNOUNCEMENT' THEN 1 ELSE 0 END DESC, timestamp DESC")
    abstract fun getPublicMessagesFlow(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE type = 'PRIVATE' AND (targetId = :myNodeId OR senderNick = :myNick) ORDER BY timestamp ASC")
    abstract fun getPrivateMessagesFlow(myNodeId: String, myNick: String): Flow<List<Message>>
    
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    abstract suspend fun getMessageById(id: String): Message?

    @Query("SELECT * FROM messages WHERE is_bridge_synced = :isSynced")
    abstract suspend fun getUnsyncedMessages(isSynced: Boolean = false): List<Message>

    @Query("UPDATE messages SET is_bridge_synced = :isSynced WHERE id IN (:ids)")
    abstract suspend fun markAsSynced(ids: List<String>, isSynced: Boolean = true)

    @Query("DELETE FROM messages WHERE id = :msgId AND senderId != :myId AND targetId != :myId")
    abstract suspend fun deleteTransitMessage(msgId: String, myId: String)

    @Query("UPDATE messages SET status = :status WHERE id = :msgId")
    abstract suspend fun updateMessageStatus(msgId: String, status: Int)

    @Query("UPDATE messages SET status = :status, retryCount = :retryCount WHERE id = :msgId")
    abstract suspend fun updateMessageStatusAndRetryCount(msgId: String, status: Int, retryCount: Int)
    @androidx.room.Update
    abstract suspend fun updateMessageInternal(message: Message)

    @Query("UPDATE messages SET status = 0 WHERE status = 1")
    abstract suspend fun revertInFlightMessages()
    
    @Query("SELECT * FROM messages WHERE status = 0")
    abstract suspend fun getPendingMessages(): List<Message>

    @Query("SELECT * FROM messages WHERE status IN (0, 1) ORDER BY timestamp ASC")
    abstract suspend fun getQueuedMessages(): List<Message>

    
    @Query("SELECT * FROM messages WHERE status = 4 AND targetId = :userId")
    abstract suspend fun getMessagesPendingKeyForUser(userId: String): List<Message>
    
    @Query("SELECT * FROM messages WHERE status = 0")
    abstract fun getPendingMessagesFlow(): Flow<List<Message>>

    @Query("DELETE FROM messages WHERE timestamp < :threshold")
    abstract suspend fun deleteOldMessages(threshold: Long)

    // --- SeenPacket ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSeenPacket(packet: SeenPacket): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE id = :id)")
    abstract suspend fun hasSeenPacket(id: String): Boolean

    // --- UserProfile ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrUpdateProfile(profile: UserProfile)

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    abstract suspend fun getProfileById(userId: String): UserProfile?
    
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    abstract fun getProfileByIdFlow(userId: String): Flow<UserProfile?>

    @Query("DELETE FROM user_profiles WHERE lastSeen < :threshold")
    abstract suspend fun deleteOldProfiles(threshold: Long)

    // --- BlockedUser ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun blockUser(blockedUser: BlockedUser)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE blockedNick = :senderNick)")
    abstract suspend fun isUserBlocked(senderNick: String): Boolean
}
