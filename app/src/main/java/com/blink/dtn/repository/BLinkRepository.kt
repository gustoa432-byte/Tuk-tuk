package com.blink.dtn.repository

import com.blink.dtn.crypto.MessageAtRest
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Conversation
import com.blink.dtn.db.ConversationDao
import com.blink.dtn.db.Message
import com.blink.dtn.utils.MeshIdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BLinkRepository(
    private val bLinkDao: BLinkDao,
    private val conversationDao: ConversationDao,
    private val myNodeId: String,
    private val myNick: String
) {
    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations().map { list ->
            list.map { conv ->
                val preview = conv.lastMessage
                if (preview == null || !MessageAtRest.isSealed(preview)) {
                    conv
                } else {
                    conv.copy(lastMessage = MessageAtRest.reveal(preview).take(120))
                }
            }
        }
    }

    fun getPublicChatHistory(): Flow<List<Message>> {
        return bLinkDao.getPublicMessagesFlow()
    }

    /** Room-scoped public chat — uses the indexed per-room query. */
    fun getPublicChatHistoryForRoom(room: String): Flow<List<Message>> {
        return bLinkDao.getPublicMessagesForRoomFlow(com.blink.dtn.ble.MeshRoom.normalise(room))
    }

    fun getDialogHistory(conversationId: String): Flow<List<Message>> {
        return conversationDao.getMessagesForConversation(conversationId).map { list ->
            list.map { msg ->
                if (msg.type == "PRIVATE" && MessageAtRest.isSealed(msg.text)) {
                    msg.copy(text = MessageAtRest.reveal(msg.text))
                } else {
                    msg
                }
            }
        }
    }

    suspend fun createAndSavePublicMessage(text: String, room: String = com.blink.dtn.ble.MeshRoom.GENERAL): Message {
        val now = System.currentTimeMillis()
        val msg = Message(
            id = MeshIdGenerator.next(myNodeId),
            type = "PUBLIC",
            senderId = myNodeId,
            senderNick = myNick,
            targetId = null,
            text = com.blink.dtn.ble.MeshLimits.clampText(text),
            room = com.blink.dtn.ble.MeshRoom.normalise(room),
            timestamp = now,
            ttl = 7,
            isMine = true,
            receivedAt = now
        )
        bLinkDao.insertMessageWithConversation(msg)
        return msg
    }

    suspend fun createAndSavePrivateMessage(
        text: String,
        targetId: String,
        isPendingKey: Boolean = false,
        encryptedText: String? = null,
        replyToId: String? = null
    ): Pair<Message, Message?> {
        val now = System.currentTimeMillis()
        val plain = com.blink.dtn.ble.MeshLimits.clampText(text)
        val localMsg = Message(
            id = MeshIdGenerator.next(myNodeId),
            type = "PRIVATE",
            senderId = myNodeId,
            senderNick = myNick,
            targetId = targetId,
            text = plain,
            timestamp = now,
            ttl = 7,
            isMine = true,
            status = if (isPendingKey) Message.STATUS_PENDING_KEY else Message.STATUS_PENDING,
            replyToId = replyToId,
            receivedAt = now
        )
        bLinkDao.insertMessageWithConversation(localMsg)

        val networkMsg = if (!isPendingKey && encryptedText != null) {
            localMsg.copy(text = encryptedText)
        } else null

        return Pair(localMsg.copy(text = plain), networkMsg)
    }

    suspend fun createAndSavePrivateImage(
        caption: String,
        targetId: String,
        mediaPath: String
    ): Message {
        val now = System.currentTimeMillis()
        val localMsg = Message(
            id = MeshIdGenerator.next(myNodeId),
            type = Message.TYPE_PRIVATE_IMAGE,
            senderId = myNodeId,
            senderNick = myNick,
            targetId = targetId,
            text = caption.ifBlank { "📷" },
            timestamp = now,
            ttl = 1, // never mesh-relay
            isMine = true,
            status = Message.STATUS_PENDING,
            receivedAt = now,
            mediaPath = mediaPath
        )
        bLinkDao.insertMessageWithConversation(localMsg)
        return localMsg
    }
}
