package com.blink.dtn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT * FROM conversations
        WHERE conversationId != '${BLinkDao.RELAY_CONVERSATION_ID}'
        ORDER BY
            CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END,
            pinnedAt DESC,
            lastTimestamp DESC
        """
    )
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY received_at ASC, id ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: Conversation): Long

    @Update
    suspend fun updateConversation(conversation: Conversation)

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: String): Conversation?

    @Query("SELECT * FROM conversations WHERE peerId = :peerId LIMIT 1")
    suspend fun getConversationByPeerId(peerId: String): Conversation?

    @Query("UPDATE conversations SET pinnedAt = :pinnedAt WHERE conversationId = :conversationId")
    suspend fun setPinnedAt(conversationId: String, pinnedAt: Long)

    @Query("UPDATE conversations SET isArchived = :archived WHERE conversationId = :conversationId")
    suspend fun setArchived(conversationId: String, archived: Boolean)
}
