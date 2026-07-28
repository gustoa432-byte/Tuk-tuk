package com.blink.dtn.repository

import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Conversation
import com.blink.dtn.db.ConversationDao
import com.blink.dtn.db.Message
import com.blink.dtn.utils.MeshIdGenerator
import kotlinx.coroutines.flow.Flow

class BLinkRepository(
    private val bLinkDao: BLinkDao,
    private val conversationDao: ConversationDao,
    private val myNodeId: String,
    private val myNick: String
) {
    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
    }

    fun getPublicChatHistory(): Flow<List<Message>> {
        return bLinkDao.getPublicMessagesFlow()
    }

    fun getDialogHistory(conversationId: String): Flow<List<Message>> {
        return conversationDao.getMessagesForConversation(conversationId)
    }

    suspend fun createAndSavePublicMessage(text: String, room: String = "general"): Message {
        val msg = Message(
            id = MeshIdGenerator.next(myNodeId),
            type = "PUBLIC",
            senderId = myNodeId,
            senderNick = myNick,
            targetId = null,
            text = text,
            room = room,
            timestamp = System.currentTimeMillis(),
            ttl = 7,
            isMine = true
        )
        bLinkDao.insertMessageWithConversation(msg)
        return msg
    }

    suspend fun createAndSavePrivateMessage(text: String, targetId: String, isPendingKey: Boolean = false, encryptedText: String? = null): Pair<Message, Message?> {
        val localMsg = Message(
            id = MeshIdGenerator.next(myNodeId),
            type = "PRIVATE",
            senderId = myNodeId,
            senderNick = myNick,
            targetId = targetId,
            text = text, // Save plain text locally
            timestamp = System.currentTimeMillis(),
            ttl = 7,
            isMine = true,
            status = if (isPendingKey) Message.STATUS_PENDING_KEY else Message.STATUS_PENDING
        )
        bLinkDao.insertMessageWithConversation(localMsg)
        
        val networkMsg = if (!isPendingKey && encryptedText != null) {
            localMsg.copy(text = encryptedText)
        } else null
        
        return Pair(localMsg, networkMsg)
    }
}
