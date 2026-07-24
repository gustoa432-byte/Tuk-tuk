package com.blink.dtn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BLinkDao {
    companion object {
        const val RELAY_CONVERSATION_ID = "__relay__"
    }

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

    @androidx.room.Transaction
    open suspend fun insertRelayPacket(msg: Message) {
        var relayConversation = getConversationByIdInternal(RELAY_CONVERSATION_ID)
        if (relayConversation == null) {
            relayConversation = Conversation(
                conversationId = RELAY_CONVERSATION_ID,
                peerId = null,
                displayName = "Relay Queue",
                lastMessage = null,
                lastTimestamp = System.currentTimeMillis(),
                unreadCount = 0
            )
            insertConversationInternal(relayConversation)
        }

        msg.conversationId = RELAY_CONVERSATION_ID
        insertMessage(msg)
        android.util.Log.d("DB_INSERT", "RelayPacketId=${msg.id} Type=${msg.type} Status=${msg.status}")
    }

    @Query("SELECT * FROM conversations WHERE conversationId = :id LIMIT 1")
    abstract suspend fun getConversationByIdInternal(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertConversationInternal(conversation: Conversation)

    @androidx.room.Update
    abstract suspend fun updateConversationInternal(conversation: Conversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :conversationId")
    abstract suspend fun markConversationRead(conversationId: String)

    
    // --- Message ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMessage(message: Message): Long

    // Oldest → newest so UI can pin the latest bubble above the input.
    @Query("SELECT * FROM messages WHERE (type = 'PUBLIC' OR type = 'SYSTEM_ANNOUNCEMENT') AND senderNick NOT IN (SELECT blockedNick FROM blocked_users) ORDER BY timestamp ASC")
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

    // Unconditional purge of a message row by id. Used when an end-to-end ACK
    // passes through a transit node to drop the relay-queued copy of the acked
    // message, and for local user delete / cancel-send.
    @Query("DELETE FROM messages WHERE id = :id")
    abstract suspend fun deleteMessageById(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    abstract suspend fun getLatestMessageInConversation(conversationId: String): Message?

    /** Local-only delete; refreshes conversation preview when needed. */
    @androidx.room.Transaction
    open suspend fun deleteMessageLocally(id: String) {
        val msg = getMessageById(id) ?: return
        deleteMessageById(id)
        val convId = msg.conversationId
        if (convId.isEmpty() || convId == RELAY_CONVERSATION_ID) return
        val conv = getConversationByIdInternal(convId) ?: return
        val latest = getLatestMessageInConversation(convId)
        updateConversationInternal(
            conv.copy(
                lastMessage = latest?.text,
                lastTimestamp = latest?.timestamp ?: conv.lastTimestamp
            )
        )
    }

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

    @Query("SELECT * FROM messages WHERE type = 'PRIVATE' AND targetId = :peerId AND senderId = :myId AND status IN (2, 3)")
    abstract suspend fun getUndeliveredPrivateToPeer(peerId: String, myId: String): List<Message>

    @Query("SELECT DISTINCT targetId FROM messages WHERE status = 4 AND targetId IS NOT NULL")
    abstract suspend fun getPendingKeyTargets(): List<String>
    
    @Query("SELECT * FROM messages WHERE status = 0")
    abstract fun getPendingMessagesFlow(): Flow<List<Message>>

    @Query("DELETE FROM messages WHERE timestamp < :threshold")
    abstract suspend fun deleteOldMessages(threshold: Long)

    // --- SeenPacket ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSeenPacket(packet: SeenPacket): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE id = :id)")
    abstract suspend fun hasSeenPacket(id: String): Boolean

    // Bounded growth: prune de-dup entries older than the mesh message TTL. Once
    // a packet id is this old the underlying message can no longer be re-relayed
    // (it is dropped as expired), so keeping the seen-marker is unnecessary.
    @Query("DELETE FROM seen_packets WHERE receivedAt < :threshold")
    abstract suspend fun deleteOldSeenPackets(threshold: Long)

    // --- UserProfile ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrUpdateProfile(profile: UserProfile)

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    abstract suspend fun getProfileById(userId: String): UserProfile?
    
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    abstract fun getProfileByIdFlow(userId: String): Flow<UserProfile?>

    @Query("DELETE FROM user_profiles WHERE lastSeen < :threshold")
    abstract suspend fun deleteOldProfiles(threshold: Long)

    @Query("DELETE FROM user_profiles WHERE userId = :userId")
    abstract suspend fun deleteProfileById(userId: String)

    @Query("DELETE FROM user_profiles WHERE length(userId) = 8 AND userId GLOB '[0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F]'")
    abstract suspend fun deleteLegacyFormatProfiles()

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId")
    abstract suspend fun deleteConversationById(conversationId: String)

    @Query("DELETE FROM conversations WHERE conversationId != 'general' AND length(conversationId) = 8")
    abstract suspend fun deleteLegacyFormatConversations()

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteMessagesInConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId != 'general' AND length(conversationId) = 8")
    abstract suspend fun deleteLegacyFormatPrivateMessages()

    @Query("UPDATE messages SET senderId = :newNodeId WHERE senderId = :oldNodeId AND conversationId = 'general'")
    abstract suspend fun reattributePublicMessages(oldNodeId: String, newNodeId: String)

    @Query("DELETE FROM messages WHERE senderId = :oldNodeId AND status IN (0, 1, 4)")
    abstract suspend fun deleteQueuedMessagesFromSender(oldNodeId: String)

    @Query("DELETE FROM seen_packets")
    abstract suspend fun deleteAllSeenPackets()

    /**
     * One-time purge after the random-id -> self-certifying-id migration.
     * Keeps the public chat; drops stale private/relay state and re-attributes
     * our own public messages to the new node id.
     */
    @androidx.room.Transaction
    open suspend fun cleanupLegacyNodeIdData(oldNodeId: String, newNodeId: String) {
        deleteMessagesInConversation(RELAY_CONVERSATION_ID)
        deleteConversationById(RELAY_CONVERSATION_ID)

        deleteLegacyFormatPrivateMessages()
        deleteLegacyFormatConversations()

        reattributePublicMessages(oldNodeId, newNodeId)
        deleteQueuedMessagesFromSender(oldNodeId)

        deleteProfileById(oldNodeId)
        deleteLegacyFormatProfiles()

        deleteAllSeenPackets()
    }

    // --- BlockedUser ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun blockUser(blockedUser: BlockedUser)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE blockedNick = :senderNick)")
    abstract suspend fun isUserBlocked(senderNick: String): Boolean
}
