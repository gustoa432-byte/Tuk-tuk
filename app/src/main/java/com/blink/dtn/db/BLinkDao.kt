package com.blink.dtn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BLinkDao {
    companion object {
        const val RELAY_CONVERSATION_ID = "__relay__"
    }

    @androidx.room.Transaction
    open suspend fun insertMessageWithConversation(msg: Message) {
        val convId = if (msg.type == "PUBLIC" ||
            msg.type == "SYSTEM_ANNOUNCEMENT" ||
            msg.type == "VERSION_ANNOUNCEMENT"
        ) {
            "general"
        } else {
            if (msg.isMine) msg.targetId ?: msg.senderId else msg.senderId
        }
        
        // Normalise legacy "general" room wire value → "1" on the way in
        var normalisedMsg = if (msg.room == com.blink.dtn.ble.MeshRoom.LEGACY_GENERAL)
            msg.copy(room = com.blink.dtn.ble.MeshRoom.GENERAL)
        else msg
        // Local receive time (previews / soft clock) — never used as chat sort key.
        if (normalisedMsg.receivedAt <= 0L) {
            normalisedMsg = normalisedMsg.copy(receivedAt = System.currentTimeMillis())
        }
        // Strict chat order: monotonic local_seq assigned once on this device.
        if (normalisedMsg.localSeq <= 0L) {
            normalisedMsg = normalisedMsg.copy(localSeq = maxLocalSeq() + 1L)
        }
        normalisedMsg.conversationId = convId
        val orderTs = normalisedMsg.localOrderMs()
        val previewText = when {
            normalisedMsg.type == Message.TYPE_PRIVATE_IMAGE ->
                if (normalisedMsg.text.isBlank() || normalisedMsg.text == "📷") "📷 Фото"
                else normalisedMsg.text
            normalisedMsg.type == "PRIVATE" -> {
                val plain = com.blink.dtn.crypto.MessageAtRest.reveal(normalisedMsg.text)
                    .ifBlank { normalisedMsg.text }
                normalisedMsg = normalisedMsg.copy(
                    text = com.blink.dtn.crypto.MessageAtRest.seal(plain)
                ).also { it.conversationId = convId }
                com.blink.dtn.crypto.MessageAtRest.seal(plain.take(120))
            }
            else -> normalisedMsg.text
        }

        var conv = getConversationByIdInternal(convId)
        if (conv == null) {
            val displayName = if (convId == "general") "General Chat" else (if (normalisedMsg.isMine) normalisedMsg.targetId else normalisedMsg.senderNick) ?: "Unknown"
            val peerId = if (convId == "general") null else convId
            conv = Conversation(
                conversationId = convId,
                peerId = peerId,
                displayName = displayName,
                lastMessage = previewText,
                lastTimestamp = orderTs,
                unreadCount = if (normalisedMsg.isMine) 0 else 1
            )
            insertConversationInternal(conv)
        } else {
            conv = conv.copy(
                lastMessage = previewText,
                lastTimestamp = maxOf(orderTs, conv.lastTimestamp),
                unreadCount = conv.unreadCount + if (normalisedMsg.isMine) 0 else 1
            )
            updateConversationInternal(conv)
        }

        insertMessage(normalisedMsg)
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
        val toStore = if (msg.localSeq <= 0L || msg.receivedAt <= 0L) {
            msg.copy(
                localSeq = if (msg.localSeq > 0L) msg.localSeq else maxLocalSeq() + 1L,
                receivedAt = if (msg.receivedAt > 0L) msg.receivedAt else System.currentTimeMillis()
            ).also { it.conversationId = RELAY_CONVERSATION_ID }
        } else {
            msg
        }
        insertMessage(toStore)
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

    // Legacy broad query kept for migration safety; new code uses getPublicMessagesForRoomFlow.
    // Rewritten with NOT EXISTS to avoid the O(N²) correlated subquery of NOT IN.
    @Query("""
        SELECT * FROM messages
        WHERE (type = 'PUBLIC' OR type = 'SYSTEM_ANNOUNCEMENT' OR type = 'VERSION_ANNOUNCEMENT')
          AND NOT EXISTS (
              SELECT 1 FROM blocked_users
              WHERE blocked_users.blockedUserId = messages.senderId
                 OR (blocked_users.blockedUserId = '' AND blocked_users.blockedNick = messages.senderNick)
          )
        ORDER BY local_seq ASC, id ASC
    """)
    abstract fun getPublicMessagesFlow(): Flow<List<Message>>

    /**
     * Room-scoped public chat query.
     *
     * Uses the composite index `index_messages_room` for the room filter and
     * avoids the O(N²) NOT IN pattern via a correlated NOT EXISTS which SQLite
     * can short-circuit as soon as it finds the first blocked row.
     *
     * Pass [room] as a single-char string per [MeshRoom] constants ("0".."9").
     * Pass "1" for the general room (normalised from legacy "general" in v16).
     */
    @Query("""
        SELECT * FROM messages
        WHERE room = :room
          AND (type = 'PUBLIC' OR type = 'SYSTEM_ANNOUNCEMENT' OR type = 'VERSION_ANNOUNCEMENT')
          AND NOT EXISTS (
              SELECT 1 FROM blocked_users
              WHERE blocked_users.blockedUserId = messages.senderId
                 OR (blocked_users.blockedUserId = '' AND blocked_users.blockedNick = messages.senderNick)
          )
        ORDER BY local_seq ASC, id ASC
    """)
    abstract fun getPublicMessagesForRoomFlow(room: String): Flow<List<Message>>

    /**
     * Drop Policy: count of FLOOD ("9") messages in the public queue.
     * Called by the overflow pruner before inserting into a full buffer.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE room = '9' AND type = 'PUBLIC'")
    abstract suspend fun countFloodMessages(): Int

    /**
     * Drop Policy: delete the oldest [limit] FLOOD messages.
     * FLOOD room ("9") is purged first; SOS ("0") is never touched.
     */
    @Query("""
        DELETE FROM messages WHERE id IN (
            SELECT id FROM messages
            WHERE room = '9' AND type = 'PUBLIC'
            ORDER BY timestamp ASC
            LIMIT :limit
        )
    """)
    abstract suspend fun deleteOldestFloodMessages(limit: Int)

    /**
     * Drop Policy second tier: if FLOOD is exhausted, delete oldest non-SOS,
     * non-system public messages to make room.
     */
    @Query("""
        DELETE FROM messages WHERE id IN (
            SELECT id FROM messages
            WHERE room != '0'
              AND (type = 'PUBLIC' OR type = 'SYSTEM_ANNOUNCEMENT')
            ORDER BY timestamp ASC
            LIMIT :limit
        )
    """)
    abstract suspend fun deleteOldestNonSosMessages(limit: Int)

    /** Total count of public messages (for overflow check). */
    @Query("SELECT COUNT(*) FROM messages WHERE type = 'PUBLIC' OR type = 'SYSTEM_ANNOUNCEMENT'")
    abstract suspend fun countPublicMessages(): Int

    @Query("SELECT * FROM messages WHERE (type = 'PRIVATE' OR type = 'PRIVATE_IMAGE') AND (targetId = :myNodeId OR senderNick = :myNick) ORDER BY local_seq ASC, id ASC")
    abstract fun getPrivateMessagesFlow(myNodeId: String, myNick: String): Flow<List<Message>>

    /** Next chat-order key — assigned only on first local insert. */
    @Query("SELECT IFNULL(MAX(local_seq), 0) FROM messages")
    abstract suspend fun maxLocalSeq(): Long
    
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    abstract suspend fun getMessageById(id: String): Message?

    @Query("SELECT * FROM messages WHERE is_bridge_synced = :isSynced")
    abstract suspend fun getUnsyncedMessages(isSynced: Boolean = false): List<Message>

    @Query("UPDATE messages SET is_bridge_synced = :isSynced WHERE id IN (:ids)")
    abstract suspend fun markAsSynced(ids: List<String>, isSynced: Boolean = true)

    /**
     * Single outbound set for the internet gateway: our own parcels that have
     * not been handed to the gateway yet and are not already terminal
     * (delivered / expired). Photos never go through the gateway.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE is_bridge_synced = 0
          AND status IN (0, 1, 2, 3, 4)
          AND type != 'PRIVATE_IMAGE'
          AND (senderId = :myId OR is_mine = 1)
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    abstract suspend fun getGatewayOutbox(myId: String, limit: Int = 40): List<Message>

    /**
     * The gateway accepted the parcel: that is a carrier hop, not delivery.
     * Sets the same custody clock as a BLE neighbour handover.
     */
    @Transaction
    open suspend fun markPushedToGateway(msgId: String, now: Long = System.currentTimeMillis()) {
        val msg = getMessageById(msgId) ?: return
        val next = MessageDeliverySm.applyAuto(msg.status, Message.STATUS_STORED_IN_NEIGHBOR)
        if (next == Message.STATUS_STORED_IN_NEIGHBOR) {
            updateMessageStatusAndCustody(msgId, next, now)
        }
        markAsSynced(listOf(msgId))
    }

    @Query("DELETE FROM messages WHERE id = :msgId AND senderId != :myId AND targetId != :myId")
    abstract suspend fun deleteTransitMessage(msgId: String, myId: String)

    // Unconditional purge of a message row by id. Used when an end-to-end ACK
    // passes through a transit node to drop the relay-queued copy of the acked
    // message, and for local user delete / cancel-send.
    @Query("DELETE FROM messages WHERE id = :id")
    abstract suspend fun deleteMessageById(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY local_seq DESC, id DESC LIMIT 1")
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
                lastMessage = latest?.let {
                    if (it.type == Message.TYPE_PRIVATE_IMAGE) {
                        if (it.text.isBlank() || it.text == "📷") "📷 Фото" else it.text
                    } else it.text
                },
                lastTimestamp = latest?.localOrderMs() ?: conv.lastTimestamp
            )
        )
    }

    @Query("UPDATE messages SET status = :status WHERE id = :msgId")
    abstract suspend fun updateMessageStatus(msgId: String, status: Int)

    @Query("UPDATE messages SET status = :status, retryCount = :retryCount WHERE id = :msgId")
    abstract suspend fun updateMessageStatusAndRetryCount(msgId: String, status: Int, retryCount: Int)

    // ── Custody bookkeeping ([CustodyPolicy]) ────────────────────────────────

    @Query("UPDATE messages SET status = :status, custody_since = :since WHERE id = :msgId")
    abstract suspend fun updateMessageStatusAndCustody(msgId: String, status: Int, since: Long)

    @Query(
        """
        UPDATE messages
        SET status = :status,
            custody_since = 0,
            custody_rounds = :rounds,
            is_bridge_synced = 0
        WHERE id = :msgId
        """
    )
    abstract suspend fun updateMessageCustodyRequeue(msgId: String, status: Int, rounds: Int)

    /**
     * Enter neighbour/gateway custody. Applies [MessageDeliverySm] so a message
     * that was already ACKed cannot be downgraded by a late TX result.
     */
    @Transaction
    open suspend fun noteHandedToCarrier(msgId: String, now: Long = System.currentTimeMillis()) {
        val msg = getMessageById(msgId) ?: return
        val next = MessageDeliverySm.applyAuto(msg.status, Message.STATUS_STORED_IN_NEIGHBOR)
        if (next == Message.STATUS_STORED_IN_NEIGHBOR) {
            updateMessageStatusAndCustody(msgId, next, now)
        }
    }

    /** Mark the start of an outbound attempt (IN_FLIGHT) for the stale sweep. */
    @Transaction
    open suspend fun noteOutboundAttempt(msgId: String, now: Long = System.currentTimeMillis()) {
        val msg = getMessageById(msgId) ?: return
        val next = MessageDeliverySm.applyAuto(msg.status, Message.STATUS_IN_FLIGHT)
        if (next == Message.STATUS_IN_FLIGHT && msg.status != Message.STATUS_IN_FLIGHT) {
            updateMessageStatusAndCustody(msgId, next, now)
        }
    }

    /**
     * Own outbound parcels that the custody sweep has to look at: waiting for an
     * end-to-end ACK at a neighbour, or stuck mid-attempt.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE senderId = :myId
          AND isAck = 0
          AND status IN (1, 2)
          AND type IN ('PRIVATE', 'PUBLIC')
        ORDER BY timestamp ASC
        LIMIT 400
        """
    )
    abstract suspend fun getCustodyCandidates(myId: String): List<Message>

    /** Own parcels that never reached a carrier and are past the hard age limit. */
    @Query(
        """
        SELECT * FROM messages
        WHERE senderId = :myId
          AND isAck = 0
          AND status IN (0, 1, 2, 3, 4)
          AND timestamp < :ageThreshold
        LIMIT 400
        """
    )
    abstract suspend fun getAgedOutOwnMessages(myId: String, ageThreshold: Long): List<Message>

    @Query("UPDATE messages SET text = :text, edited_at = :editedAt WHERE id = :msgId")
    abstract suspend fun updateMessageText(msgId: String, text: String, editedAt: Long)

    @Query(
        "UPDATE messages SET text = :text, edited_at = :editedAt, status = :status, retryCount = :retryCount WHERE id = :msgId"
    )
    abstract suspend fun updateMessageTextStatus(
        msgId: String,
        text: String,
        editedAt: Long,
        status: Int,
        retryCount: Int
    )

    /** Edit local message text and refresh conversation preview when this was the latest. */
    @androidx.room.Transaction
    open suspend fun editMessageLocally(msgId: String, text: String, editedAt: Long, resend: Boolean) {
        val msg = getMessageById(msgId) ?: return
        if (resend) {
            updateMessageTextStatus(msgId, text, editedAt, Message.STATUS_PENDING, 0)
        } else {
            updateMessageText(msgId, text, editedAt)
        }
        val convId = msg.conversationId
        if (convId.isEmpty() || convId == RELAY_CONVERSATION_ID) return
        val conv = getConversationByIdInternal(convId) ?: return
        val latest = getLatestMessageInConversation(convId)
        if (latest?.id == msgId) {
            updateConversationInternal(
                conv.copy(lastMessage = text, lastTimestamp = latest.timestamp)
            )
        }
    }

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    abstract suspend fun deleteMessagesByIds(ids: List<String>)

    @androidx.room.Update
    abstract suspend fun updateMessageInternal(message: Message)

    @Query("UPDATE messages SET status = 0 WHERE status = 1")
    abstract suspend fun revertInFlightMessages()
    
    @Query("SELECT * FROM messages WHERE status = 0")
    abstract suspend fun getPendingMessages(): List<Message>

    @Query("SELECT * FROM messages WHERE status IN (0, 1) AND type != 'PRIVATE_IMAGE' ORDER BY timestamp ASC")
    abstract suspend fun getQueuedMessages(): List<Message>

    
    @Query("SELECT * FROM messages WHERE status = 4 AND targetId = :userId")
    abstract suspend fun getMessagesPendingKeyForUser(userId: String): List<Message>

    /**
     * PENDING_KEY → PENDING once the target's public key is known.
     * Without this the rows stay at status 4 forever: [getQueuedMessages] only
     * selects 0/1, so nothing would ever pick them up again.
     */
    @Query("UPDATE messages SET status = 0, custody_since = 0 WHERE status = 4 AND targetId = :userId")
    abstract suspend fun releasePendingKeyMessages(userId: String)

    @Query("SELECT * FROM messages WHERE type = 'PRIVATE' AND targetId = :peerId AND senderId = :myId AND status IN (2, 3)")
    abstract suspend fun getUndeliveredPrivateToPeer(peerId: String, myId: String): List<Message>

    @Query("SELECT DISTINCT targetId FROM messages WHERE status = 4 AND targetId IS NOT NULL")
    abstract suspend fun getPendingKeyTargets(): List<String>
    
    @Query("SELECT * FROM messages WHERE status = 0")
    abstract fun getPendingMessagesFlow(): Flow<List<Message>>

    /**
     * Human Layer backpack: parcels waiting / in flight / need key.
     * Excludes ACKs and mesh control packets.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE status IN (0, 1, 2, 4)
          AND isAck = 0
          AND type IN ('PRIVATE', 'PUBLIC')
        ORDER BY priority DESC, timestamp ASC
        """
    )
    abstract fun getBackpackMessagesFlow(): Flow<List<Message>>

    /**
     * Dialog list delivery status: status of the newest message in a conversation,
     * but only when that message is ours. Read-only projection — no schema change.
     */
    @Query(
        """
        SELECT status FROM (
            SELECT status, is_mine FROM messages
            WHERE conversationId = :conversationId
              AND isAck = 0
            ORDER BY local_seq DESC, timestamp DESC
            LIMIT 1
        ) WHERE is_mine = 1
        """
    )
    abstract fun getLastOwnMessageStatusFlow(conversationId: String): Flow<Int?>

    /**
     * Chronicle: successfully delivered user parcels with optional hop chain.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE status = 5
          AND isAck = 0
          AND type IN ('PRIVATE', 'PRIVATE_IMAGE', 'PUBLIC')
        ORDER BY timestamp DESC
        LIMIT 80
        """
    )
    abstract fun getChronicleMessagesFlow(): Flow<List<Message>>

    @Query(
        """
        SELECT * FROM user_profiles
        WHERE length(publicKey) > 0
          AND userId != :myId
        ORDER BY lastSeen DESC
        LIMIT 24
        """
    )
    abstract fun getRecentKeyedPeersFlow(myId: String): Flow<List<UserProfile>>

    @Query("DELETE FROM messages WHERE timestamp < :threshold")
    abstract suspend fun deleteOldMessages(threshold: Long)

    // --- SeenPacket (Journal B) ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSeenPacket(packet: SeenPacket): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE id = :id)")
    abstract suspend fun hasSeenPacket(id: String): Boolean

    @Query("SELECT COUNT(*) FROM seen_packets")
    abstract suspend fun countSeenPackets(): Int

    /**
     * Drop oldest sightings (ASC receivedAt) to enforce Journal B LRU cap.
     */
    @Query(
        """
        DELETE FROM seen_packets WHERE id IN (
            SELECT id FROM seen_packets
            ORDER BY receivedAt ASC
            LIMIT :limit
        )
        """
    )
    abstract suspend fun deleteOldestSeenPackets(limit: Int)

    // Bounded growth: prune de-dup entries older than the mesh message TTL. Once
    // a packet id is this old the underlying message can no longer be re-relayed
    // (it is dropped as expired), so keeping the seen-marker is unnecessary.
    @Query("DELETE FROM seen_packets WHERE receivedAt < :threshold")
    abstract suspend fun deleteOldSeenPackets(threshold: Long)

    /**
     * Journal B write path: insert then enforce [JournalLimits.SEEN_CACHE_CAP].
     */
    @Transaction
    open suspend fun rememberSeenPacket(
        packetId: String,
        receivedAt: Long = System.currentTimeMillis()
    ) {
        if (packetId.isBlank()) return
        insertSeenPacket(SeenPacket(packetId = packetId, receivedAt = receivedAt))
        enforceSeenPacketCap(JournalLimits.SEEN_CACHE_CAP)
    }

    @Transaction
    open suspend fun enforceSeenPacketCap(cap: Int = JournalLimits.SEEN_CACHE_CAP) {
        val count = countSeenPackets()
        if (count > cap) {
            deleteOldestSeenPackets(count - cap)
        }
    }

    /** Age prune (48h) + hard cap — call from background cleanup. */
    @Transaction
    open suspend fun pruneSeenJournal(now: Long = System.currentTimeMillis()) {
        deleteOldSeenPackets(now - JournalLimits.SEEN_TTL_MS)
        enforceSeenPacketCap(JournalLimits.SEEN_CACHE_CAP)
    }

    // --- UserProfile ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrUpdateProfile(profile: UserProfile)

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    abstract suspend fun getProfileById(userId: String): UserProfile?
    
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    abstract fun getProfileByIdFlow(userId: String): Flow<UserProfile?>

    @Query("UPDATE user_profiles SET localAlias = :alias WHERE userId = :userId")
    abstract suspend fun updateLocalAlias(userId: String, alias: String)

    @Query("UPDATE user_profiles SET trustStatus = :trustStatus WHERE userId = :userId")
    abstract suspend fun updateTrustStatus(userId: String, trustStatus: String)

    @Query("UPDATE user_profiles SET avatarBlob = :avatarBlob, lastSeen = :lastSeen WHERE userId = :userId")
    abstract suspend fun updateAvatarBlob(userId: String, avatarBlob: ByteArray?, lastSeen: Long)

    @Query("UPDATE conversations SET displayName = :displayName WHERE conversationId = :conversationId")
    abstract suspend fun updateConversationDisplayName(conversationId: String, displayName: String)

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

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE blockedUserId = :userId)")
    abstract suspend fun isUserIdBlocked(userId: String): Boolean
}
