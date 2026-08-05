package com.blink.dtn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.blink.dtn.ble.BleMeshManager
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.BlockedUser
import com.blink.dtn.db.Message
import kotlinx.coroutines.flow.map
import com.blink.dtn.repository.BLinkRepository
import com.blink.dtn.db.ConversationDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.blink.dtn.utils.MeshIdGenerator
import com.blink.dtn.telemetry.TraceKind
import com.blink.dtn.telemetry.TraceStages
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.telemetry.detailsOf

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class BLinkViewModel(
    application: Application,
    private val dao: BLinkDao,
    private val conversationDao: ConversationDao,
    private val repository: BLinkRepository,
    val bleMeshManager: BleMeshManager,
    val myNodeId: String,
    var myNick: String
) : AndroidViewModel(application) {

    companion object {
        val fastSyncTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        private fun uiTextDetails(text: String) = detailsOf(
            "messageLength" to text.length,
            "utf8Bytes" to text.toByteArray(Charsets.UTF_8).size,
            "emojiCount" to text.codePoints().toArray().count { cp ->
                cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF
            },
            "newLineCount" to text.count { it == '\n' }
        )
    }


    init {
        MeshIdGenerator.init(application)
        TraceStore.init(application)
        com.blink.dtn.telemetry.MeshDutyTelemetry.init(application)
        bleMeshManager.updateMyProfile(myNick, false)
        
        // Execute background cleanup periodically while the app is alive
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // 48 hours for messages
                val messageTtl = System.currentTimeMillis() - com.blink.dtn.db.JournalLimits.SEEN_TTL_MS
                dao.deleteOldMessages(messageTtl)
                dao.pruneSeenJournal()
                
                // 7 days for user profiles
                val profileTtl = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                dao.deleteOldProfiles(profileTtl)
                
                // Sleep for 1 hour before next cleanup
                kotlinx.coroutines.delay(60 * 60 * 1000L)
            }
    }
    }

    // a) dialogs: StateFlow со списком всех приватных Conversation.
    val dialogs = repository.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    /**
     * Currently selected public room (wire ID per [MeshRoom] constants).
     * Changing this value instantly switches the DB subscription via [flatMapLatest]
     * without reloading the whole table.
     */
    val selectedRoom = MutableStateFlow(com.blink.dtn.ble.MeshRoom.GENERAL)

    fun selectRoom(roomId: String) {
        selectedRoom.value = com.blink.dtn.ble.MeshRoom.normalise(roomId)
    }

    // b) publicMessages: реактивный Flow переключается при смене комнаты.
    // flatMapLatest отменяет предыдущую подписку сразу при смене selectedRoom —
    // UI обновляется без full-table scan, только данные выбранной комнаты.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val publicMessages = selectedRoom
        .flatMapLatest { room -> repository.getPublicChatHistoryForRoom(room) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // c) Стейт для текущего открытого диалога
    val pendingCount = dao.getPendingMessagesFlow().map { it.size }

    val currentDialogId = MutableStateFlow<String?>(null)
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentDialogMessages = currentDialogId
        .flatMapLatest { convId ->
            if (convId != null) repository.getDialogHistory(convId) else kotlinx.coroutines.flow.flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
        
    fun setCurrentDialog(conversationId: String?) {
        currentDialogId.value = conversationId
        if (conversationId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.markConversationRead(conversationId)
            }
        }
    }

    fun togglePinDialog(conversationId: String) {
        if (conversationId.isBlank() || conversationId == "general") return
        viewModelScope.launch(Dispatchers.IO) {
            val conv = conversationDao.getConversationById(conversationId) ?: return@launch
            val next = if (conv.isPinned) 0L else System.currentTimeMillis()
            conversationDao.setPinnedAt(conversationId, next)
        }
    }

    fun setDialogArchived(conversationId: String, archived: Boolean) {
        if (conversationId.isBlank() || conversationId == "general") return
        viewModelScope.launch(Dispatchers.IO) {
            conversationDao.setArchived(conversationId, archived)
        }
    }

    val peerCount = bleMeshManager.peerCount
    val activePeers = bleMeshManager.activePeers

    /** Human Layer: parcels in the courier backpack (Room → NetworkPacket). */
    val backpackPackets = dao.getBackpackMessagesFlow()
        .map { rows -> rows.map { com.blink.dtn.ble.NetworkPacket.fromMessage(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    /** Human Layer: delivered routes for Chronicle. */
    val chronicleMessages = dao.getChronicleMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    /** Peers with a known publicBleKey (identity handshake done). */
    val recentKeyedPeers = dao.getRecentKeyedPeersFlow(myNodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val vkActive: kotlinx.coroutines.flow.StateFlow<Boolean> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(com.blink.dtn.utils.NetworkUtils.isInternetAvailable(getApplication()))
            kotlinx.coroutines.delay(5000L)
    }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, false)
        

    val relayActive = com.blink.dtn.vk.VkRelayManager.relayActive
        

    fun updateMyProfile(nick: String, isVip: Boolean) {
        myNick = nick
        val prefs = getApplication<Application>().getSharedPreferences("blink_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("nick", nick).apply()
        
        bleMeshManager.updateMyProfile(nick, isVip) // triggers enqueueProfileBroadcast() inside BleMeshManager
        
        viewModelScope.launch(Dispatchers.IO) {
            val pubKey = com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64()
            val existing = dao.getProfileById(myNodeId)
            val newProfile = existing?.copy(
                nickname = nick,
                isVip = isVip,
                lastSeen = System.currentTimeMillis(),
                publicKey = pubKey
            ) ?: com.blink.dtn.db.UserProfile(
                userId = myNodeId,
                nickname = nick,
                lastSeen = System.currentTimeMillis(),
                isVip = isVip,
                publicKey = pubKey
            )
            dao.insertOrUpdateProfile(newProfile)
    }
    }

    /**
     * First-run auth completion. Empty [displayName] → random dino stub.
     * Mesh nick = trimmed [nick] if set, otherwise the resolved display name.
     */
    fun completeOnboarding(
        displayName: String,
        nick: String,
        provider: com.blink.dtn.auth.AuthProvider
    ) {
        val lang = com.blink.dtn.ui.AppLang.lang.value
        val name = com.blink.dtn.auth.DinoNameGenerator.resolveDisplayName(displayName, lang)
        val trimmedNick = nick.trim().take(com.blink.dtn.auth.DinoNameGenerator.MAX_LEN)
        val meshNick = trimmedNick.ifEmpty { name }
        val app = getApplication<Application>()
        com.blink.dtn.auth.AuthSessionStore.complete(app, name, meshNick, provider)
        updateMyProfile(meshNick, false)
    }

    fun displayName(): String {
        val stored = com.blink.dtn.auth.AuthSessionStore.displayName(getApplication())
        return stored.ifBlank { myNick }
    }

    /** Save profile name + optional nick from Profile tab. Empty name → dino stub. */
    fun updateMyNameAndNick(displayName: String, nick: String) {
        val lang = com.blink.dtn.ui.AppLang.lang.value
        val name = com.blink.dtn.auth.DinoNameGenerator.resolveDisplayName(displayName, lang)
        val trimmedNick = nick.trim()
            .removePrefix("@")
            .replace("@", "")
            .take(com.blink.dtn.auth.DinoNameGenerator.MAX_LEN)
        val meshNick = trimmedNick.ifEmpty { name }
        val app = getApplication<Application>()
        com.blink.dtn.auth.AuthSessionStore.setDisplayName(app, name)
        com.blink.dtn.auth.AuthSessionStore.setNick(app, meshNick)
        updateMyProfile(meshNick, false)
    }

    /**
     * Save a mesh-compressed avatar for [userId] (own profile or peer).
     * Returns false via [onDone] if the profile row could not be updated.
     */
    fun setAvatarBlob(userId: String, blob: ByteArray?, onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val existing = dao.getProfileById(userId)
                val now = System.currentTimeMillis()
                if (existing != null) {
                    dao.updateAvatarBlob(userId, blob, now)
                    true
                } else if (userId == myNodeId) {
                    val pubKey = com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64()
                    dao.insertOrUpdateProfile(
                        com.blink.dtn.db.UserProfile(
                            userId = myNodeId,
                            nickname = myNick,
                            lastSeen = now,
                            isVip = false,
                            publicKey = pubKey,
                            avatarBlob = blob
                        )
                    )
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            if (onDone != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok) }
            }
        }
    }

    fun getProfileFlow(userId: String) = dao.getProfileByIdFlow(userId)

    // Contact QR payload: carries our public key so a scan can pin the key
    // out-of-band without waiting for a BLE identity announcement. Optional
    // compact avatar (`av` = base64 JPEG) when it fits the QR budget.
    suspend fun buildContactQr(): String {
        val avatar = dao.getProfileById(myNodeId)?.avatarBlob
        return org.json.JSONObject().apply {
            put("v", 1)
            put("id", myNodeId)
            put("pk", com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64())
            put("n", myNick)
            val qrAvatar = avatar?.let { AvatarCompressor.fitForQr(it) }
            if (qrAvatar != null) {
                put(
                    "av",
                    android.util.Base64.encodeToString(qrAvatar, android.util.Base64.NO_WRAP)
                )
            }
        }.toString()
    }

    /** Sync getter used by UI when avatar is not needed in the payload. */
    val myContactQr: String
        get() = org.json.JSONObject().apply {
            put("v", 1)
            put("id", myNodeId)
            put("pk", com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64())
            put("n", myNick)
        }.toString()

    /**
     * Ensure a peer profile exists and is a CONTACT (QR, manual ID, or user-initiated chat).
     * Does not downgrade BLOCKED / overwrite localAlias.
     */
    fun ensureContact(peerId: String, nick: String = "") {
        if (peerId.isBlank() || peerId == myNodeId) return
        viewModelScope.launch(Dispatchers.IO) {
            upsertPeerAsContact(peerId, nick)
        }
    }

    /**
     * Hidden handshake: resolve contact via VPS `/contacts/add`, store `publicBleKey` in Room
     * under the mesh nodeId derived from that key (what BLE encryption already reads).
     *
     * Falls back to local [ensureContact] when offline / no JWT / non-UUID id.
     */
    fun addContactOnlineOrLocal(
        rawId: String,
        onDone: ((ok: Boolean, meshId: String, message: String) -> Unit)? = null
    ) {
        val id = rawId.trim()
        if (id.isBlank() || id == myNodeId) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            com.blink.dtn.net.VpsConfig.init(app)
            val looksLikeUuid = id.length >= 32 && id.count { it == '-' } >= 4
            val canOnline = looksLikeUuid &&
                com.blink.dtn.auth.AuthSessionStore.hasVpsSession(app) &&
                com.blink.dtn.net.VpsConfig.isConfigured(app)

            if (canOnline) {
                val result = com.blink.dtn.net.ContactsApi(app).addContact(id)
                result.fold(
                    onSuccess = { resp ->
                        val bleKey = resp.publicBleKey
                        val meshId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(bleKey)
                            .ifBlank { id }
                        upsertPeerAsContact(
                            peerId = meshId,
                            nick = meshId,
                            pubKeyBase64 = bleKey,
                            verifiedOutOfBand = true
                        )
                        // Remember server UUID → mesh id for later lookups
                        app.getSharedPreferences("blink_prefs", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putString("vps_uid_$id", meshId)
                            .apply()
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            onDone?.invoke(true, meshId, "ok")
                        }
                    },
                    onFailure = {
                        upsertPeerAsContact(id)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            onDone?.invoke(false, id, it.message ?: "fail")
                        }
                    }
                )
            } else {
                upsertPeerAsContact(id)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onDone?.invoke(true, id, "local")
                }
            }
        }
    }

    private suspend fun upsertPeerAsContact(
        peerId: String,
        nick: String = "",
        pubKeyBase64: String? = null,
        verifiedOutOfBand: Boolean = false,
        avatarBlob: ByteArray? = null
    ) {
        val existing = dao.getProfileById(peerId)
        if (existing?.isBlocked == true) return
        val resolvedNick = nick.ifBlank { existing?.nickname.orEmpty() }.ifBlank { peerId }
        val profile = if (existing != null) {
            existing.copy(
                nickname = if (nick.isNotBlank()) nick else existing.nickname,
                lastSeen = System.currentTimeMillis(),
                publicKey = pubKeyBase64?.takeIf { it.isNotEmpty() } ?: existing.publicKey,
                trustStatus = com.blink.dtn.db.UserProfile.TRUST_CONTACT,
                verifiedOutOfBand = existing.verifiedOutOfBand || verifiedOutOfBand,
                avatarBlob = avatarBlob ?: existing.avatarBlob
            )
        } else {
            com.blink.dtn.db.UserProfile(
                userId = peerId,
                nickname = resolvedNick,
                lastSeen = System.currentTimeMillis(),
                isVip = false,
                publicKey = pubKeyBase64.orEmpty(),
                trustStatus = com.blink.dtn.db.UserProfile.TRUST_CONTACT,
                verifiedOutOfBand = verifiedOutOfBand,
                avatarBlob = avatarBlob
            )
        }
        dao.insertOrUpdateProfile(profile)
        syncConversationDisplayName(peerId, profile)
    }

    private suspend fun syncConversationDisplayName(peerId: String, profile: com.blink.dtn.db.UserProfile) {
        val conv = conversationDao.getConversationById(peerId) ?: return
        val label = profile.displayLabel(conv.displayName)
        if (conv.displayName != label) {
            dao.updateConversationDisplayName(peerId, label)
        }
    }

    /** Local-only rename for a contact. Does not change their network nick. */
    fun setLocalAlias(peerId: String, alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getProfileById(peerId)
            val trimmed = alias.trim()
            if (existing != null) {
                dao.updateLocalAlias(peerId, trimmed)
                val updated = existing.copy(localAlias = trimmed)
                syncConversationDisplayName(peerId, updated)
            } else {
                val profile = com.blink.dtn.db.UserProfile(
                    userId = peerId,
                    nickname = peerId,
                    lastSeen = System.currentTimeMillis(),
                    isVip = false,
                    localAlias = trimmed,
                    trustStatus = com.blink.dtn.db.UserProfile.TRUST_CONTACT
                )
                dao.insertOrUpdateProfile(profile)
                syncConversationDisplayName(peerId, profile)
            }
        }
    }

    /** Promote a stranger (message request) to a normal contact. */
    fun acceptContact(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            upsertPeerAsContact(peerId)
        }
    }

    /**
     * Ignore / block a peer: drop future private ingress locally and remove the dialog.
     * Uses nodeId trustStatus (nick is not a unique id).
     */
    fun ignorePeer(peerId: String, nick: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getProfileById(peerId)
            val profile = (existing ?: com.blink.dtn.db.UserProfile(
                userId = peerId,
                nickname = nick.ifBlank { peerId },
                lastSeen = System.currentTimeMillis(),
                isVip = false
            )).copy(trustStatus = com.blink.dtn.db.UserProfile.TRUST_BLOCKED)
            dao.insertOrUpdateProfile(profile)
            val blockNick = nick.ifBlank { existing?.nickname.orEmpty() }
            dao.blockUser(
                BlockedUser(
                    blockedNick = blockNick.ifBlank { peerId },
                    blockedUserId = peerId,
                    blockedAt = System.currentTimeMillis()
                )
            )
            dao.deleteMessagesInConversation(peerId)
            dao.deleteConversationById(peerId)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (currentDialogId.value == peerId) {
                    currentDialogId.value = null
                }
            }
        }
    }

    // Persist a QR-scanned contact with its pinned public key. The id is the
    // self-certifying hash of pubKeyBase64, so this can only ever pin the one
    // key that matches the id (a later BLE announcement must carry the same key
    // or it is rejected at ingress). Optional compact avatar from QR `av`.
    fun addScannedContact(
        id: String,
        nick: String,
        pubKeyBase64: String,
        avatarBlob: ByteArray? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            upsertPeerAsContact(
                id,
                nick,
                pubKeyBase64,
                verifiedOutOfBand = true,
                avatarBlob = avatarBlob
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                setCurrentDialog(id)
            }
        }
    }

    fun sendPublicMessage(text: String, room: String = com.blink.dtn.ble.MeshRoom.GENERAL) {
        viewModelScope.launch(Dispatchers.IO) {
            val trace = TraceStore.begin(
                kind = TraceKind.MESSAGE,
                conversationId = "general",
                senderId = myNodeId,
                messageType = "PUBLIC"
            )
            TraceStore.stage(
                trace.traceId,
                TraceStages.UI_SEND_PRESSED,
                uiTextDetails(text) + detailsOf("conversationId" to "general", "targetNode" to "broadcast"),
                visual = "📦 Сообщение упаковано"
            )
            try {
                val dbStart = System.currentTimeMillis()
                TraceStore.stage(trace.traceId, TraceStages.DB_INSERT_START)
                val msg = repository.createAndSavePublicMessage(text, room)
                TraceStore.attachMessageId(trace.traceId, msg.id)
                TraceStore.stage(
                    msg.id,
                    TraceStages.DB_INSERT_DONE,
                    detailsOf(
                        "messageId" to msg.id,
                        "insertDurationMs" to (System.currentTimeMillis() - dbStart),
                        "ttl" to msg.ttl,
                        "needAck" to false,
                        "encrypted" to false,
                        "privatePublic" to "PUBLIC"
                    ),
                    visual = "📚 Записано в базу"
                )
                TraceStore.stage(
                    msg.id,
                    TraceStages.PREP_ENTITY,
                    detailsOf("packetId" to msg.id, "ttl" to msg.ttl, "priority" to "normal")
                )
                bleMeshManager.enqueueMessage(msg)
                com.blink.dtn.ui.BLinkViewModel.fastSyncTrigger.tryEmit(Unit)
            } catch (t: Throwable) {
                android.util.Log.e("SEND", "Public send crash", t)
                com.blink.dtn.telemetry.ErrorJournal.record("SEND_PUBLIC", t, context = getApplication())
                runCatching {
                    TraceStore.finish(
                        trace.traceId,
                        "Failed",
                        detailsOf("error" to t.message, "stack" to t.stackTraceToString().take(1500))
                    )
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "Send failed: ${t.message ?: t.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
    }

    fun sendPrivateMessage(text: String, targetId: String, replyToId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (com.blink.dtn.moderation.GlobalBanCache.isBanned(targetId)) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "Получатель в глобальном бан-листе",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            // Outbound private = user-initiated → contact (not a stranger request).
            upsertPeerAsContact(targetId)

            val trace = TraceStore.begin(
                kind = TraceKind.MESSAGE,
                conversationId = targetId,
                targetId = targetId,
                senderId = myNodeId,
                messageType = "PRIVATE"
            )
            TraceStore.stage(
                trace.traceId,
                TraceStages.UI_SEND_PRESSED,
                uiTextDetails(text) + detailsOf(
                    "conversationId" to targetId,
                    "targetNode" to targetId,
                    "traceId" to trace.traceId
                ),
                visual = "📦 Сообщение упаковано"
            )
            try {
                val profile = dao.getProfileById(targetId)
                val pubKey = profile?.publicKey
                TraceStore.stage(
                    trace.traceId,
                    TraceStages.RSA_KEY_CHECK,
                    detailsOf(
                        "recipientPublicKeyExists" to !pubKey.isNullOrEmpty(),
                        "publicKeySource" to if (profile != null) "user_profiles" else "none",
                        "keyFingerprint" to (pubKey?.let { com.blink.dtn.crypto.NodeIdentity.deriveNodeId(it) } ?: ""),
                        "keyAgeMs" to (profile?.let { System.currentTimeMillis() - it.lastSeen })
                    )
                )

                if (pubKey.isNullOrEmpty()) {
                    val dbStart = System.currentTimeMillis()
                    TraceStore.stage(trace.traceId, TraceStages.DB_INSERT_START)
                    val (pendingMsg, _) = repository.createAndSavePrivateMessage(
                        text, targetId, isPendingKey = true, replyToId = replyToId
                    )
                    TraceStore.attachMessageId(trace.traceId, pendingMsg.id)
                    TraceStore.stage(
                        pendingMsg.id,
                        TraceStages.DB_INSERT_DONE,
                        detailsOf("insertDurationMs" to (System.currentTimeMillis() - dbStart), "status" to "PENDING_KEY")
                    )
                    TraceStore.stage(
                        pendingMsg.id,
                        TraceStages.RSA_MISSING_KEY,
                        detailsOf("reason" to "no_public_key_in_profile", "queuedIdentityRequest" to true),
                        visual = "🔑 Нет ключа — запрос в сеть"
                    )

                    val idTrace = TraceStore.begin(
                        kind = TraceKind.IDENTITY,
                        targetId = targetId,
                        senderId = myNodeId,
                        messageType = "IDENTITY_REQUEST"
                    )
                    val reqMsg = Message(
                        id = MeshIdGenerator.next(myNodeId),
                        type = "IDENTITY_REQUEST",
                        senderId = myNodeId,
                        senderNick = myNick,
                        targetId = targetId,
                        text = "REQ",
                        room = "system",
                        timestamp = System.currentTimeMillis(),
                        ttl = 7,
                        isMine = true
                    )
                    TraceStore.attachMessageId(idTrace.traceId, reqMsg.id)
                    TraceStore.stage(reqMsg.id, TraceStages.ID_REQUEST, detailsOf("forMessageId" to pendingMsg.id), visual = "📡 IDENTITY_REQUEST")
                    bleMeshManager.enqueueMessage(reqMsg)
                    
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "Нет ключа собеседника — запросили по сети. Лучше сверить QR.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val dbStart = System.currentTimeMillis()
                TraceStore.stage(trace.traceId, TraceStages.DB_INSERT_START)
                val (localMsg, _) = repository.createAndSavePrivateMessage(
                    text, targetId, replyToId = replyToId
                )
                TraceStore.attachMessageId(trace.traceId, localMsg.id)
                TraceStore.stage(
                    localMsg.id,
                    TraceStages.DB_INSERT_DONE,
                    detailsOf(
                        "messageId" to localMsg.id,
                        "insertDurationMs" to (System.currentTimeMillis() - dbStart),
                        "ttl" to localMsg.ttl,
                        "needAck" to true,
                        "encrypted" to true,
                        "privatePublic" to "PRIVATE"
                    ),
                    visual = "📚 Записано в базу"
                )
                TraceStore.stage(localMsg.id, TraceStages.ID_USED, detailsOf("keyFingerprint" to com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pubKey)))
                bleMeshManager.enqueueMessage(localMsg)
                com.blink.dtn.ui.BLinkViewModel.fastSyncTrigger.tryEmit(Unit)
            } catch (t: Throwable) {
                android.util.Log.e("SEND", "Private send crash", t)
                com.blink.dtn.telemetry.ErrorJournal.record("SEND_PRIVATE", t, context = getApplication())
                runCatching {
                    TraceStore.finish(
                        trace.traceId,
                        "Failed",
                        detailsOf("error" to t.message, "stack" to t.stackTraceToString().take(1500))
                    )
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "Send failed: ${t.message ?: t.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
    }

    /** Forward plain texts to one or more private chats via Router/send path. */
    fun forwardMessagesToPeers(texts: List<String>, targetIds: List<String>) {
        val cleaned = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty() || targetIds.isEmpty()) return
        for (targetId in targetIds.distinct()) {
            if (targetId.isBlank() || targetId == "general" || targetId == myNodeId) continue
            for (body in cleaned) {
                val wire = if (body.startsWith("↗")) body else "↗\n$body"
                sendPrivateMessage(wire, targetId)
            }
        }
    }

    /**
     * Send a photo in a 1:1 chat via internet/VPS only. Never meshes.
     * Without internet or VPS URL → toast + FAILED local row.
     */
    fun sendPrivatePhoto(uri: android.net.Uri, targetId: String, caption: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val online = com.blink.dtn.net.VpsConfig.isOnline(app)
                val vpsConfigured = com.blink.dtn.net.VpsConfig.isConfigured(app)
                if (!online || !vpsConfigured) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            app,
                            if (!vpsConfigured)
                                "Фото только через интернет — укажите VPS в профиле"
                            else
                                "Фото только через интернет — нет сети",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val jpeg = ChatPhotoCompressor.compressToBytes(app, uri)
                if (jpeg == null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(app, "Не удалось сжать фото", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val draftId = MeshIdGenerator.next(myNodeId)
                val file = ChatPhotoCompressor.writeBytes(app, draftId, jpeg) ?: run {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(app, "Не удалось сохранить фото", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                // Re-create with stable id matching file name
                val msg = Message(
                    id = draftId,
                    type = Message.TYPE_PRIVATE_IMAGE,
                    senderId = myNodeId,
                    senderNick = myNick,
                    targetId = targetId,
                    text = caption.ifBlank { "📷" }.let { com.blink.dtn.ble.MeshLimits.clampText(it) },
                    timestamp = System.currentTimeMillis(),
                    ttl = 1,
                    isMine = true,
                    status = Message.STATUS_PENDING,
                    receivedAt = System.currentTimeMillis(),
                    mediaPath = file.absolutePath
                )
                dao.insertMessageWithConversation(msg)

                val vps = com.blink.dtn.net.VpsBridge.getInstance(app, dao, bleMeshManager, myNodeId)
                val ok = com.blink.dtn.router.MessageRouter.sendPhotoInternetOnly(
                    messageId = msg.id,
                    internetOnline = online,
                    vpsConfigured = true
                ) {
                    vps.pushPrivateImage(msg, jpeg)
                }
                if (!ok) {
                    dao.updateMessageStatus(msg.id, Message.STATUS_FAILED)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(app, "Не удалось отправить фото", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    com.blink.dtn.ui.BLinkViewModel.fastSyncTrigger.tryEmit(Unit)
                }
            } catch (t: Throwable) {
                android.util.Log.e("SEND", "Photo send crash", t)
                com.blink.dtn.telemetry.ErrorJournal.record("SEND_PHOTO", t, context = app)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        app,
                        "Photo failed: ${t.message ?: t.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Edit an own message on this device.
     * Still-queued / failed → update text and resend from queue.
     * Already sent/delivered → local edit only (mesh has no edit protocol yet).
     */
    fun editOwnMessage(messageId: String, newText: String) {
        val trimmed = com.blink.dtn.ble.MeshLimits.clampText(newText.trim())
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val msg = dao.getMessageById(messageId) ?: return@launch
            if (msg.senderId != myNodeId && !msg.isMine) return@launch
            if (msg.type == Message.TYPE_PRIVATE_IMAGE) return@launch
            val editedAt = System.currentTimeMillis()
            val resend = msg.status == Message.STATUS_PENDING ||
                msg.status == Message.STATUS_IN_FLIGHT ||
                msg.status == Message.STATUS_PENDING_KEY ||
                msg.status == Message.STATUS_FAILED
            if (resend) {
                bleMeshManager.abortOutgoingTx(messageId)
            }
            dao.editMessageLocally(messageId, trimmed, editedAt, resend = resend)
            if (resend && msg.status != Message.STATUS_PENDING_KEY) {
                bleMeshManager.triggerRelay()
            }
            com.blink.dtn.ui.BLinkViewModel.fastSyncTrigger.tryEmit(Unit)
        }
    }

    fun deleteMessagesLocally(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (id in messageIds) {
                bleMeshManager.cancelOutgoing(id)
                dao.deleteMessageLocally(id)
            }
        }
    }

    fun blockUser(peerId: String, nick: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            dao.blockUser(
                BlockedUser(
                    blockedNick = nick.ifBlank { peerId },
                    blockedUserId = peerId,
                    blockedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Submit decrypted message text + sender nodeId to VPS moderation.
     * Requires an online JWT session.
     */
    fun reportMessage(msg: Message) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val lang = com.blink.dtn.ui.AppLang.lang.value
            if (msg.senderId.isBlank() || msg.isMine || msg.senderId == myNodeId) {
                return@launch
            }
            if (com.blink.dtn.moderation.GlobalBanCache.isBanned(msg.senderId)) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        app,
                        com.blink.dtn.ui.S.reportSent(lang),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            val result = com.blink.dtn.net.ModerationApi(app).report(
                reportedNodeId = msg.senderId,
                decryptedMessageContent = com.blink.dtn.crypto.MessageAtRest.reveal(msg.text).take(8_000)
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    app,
                    if (result.isSuccess) com.blink.dtn.ui.S.reportSent(lang)
                    else com.blink.dtn.ui.S.reportFailed(lang),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Remove a message from this device only (own or others). */
    fun deleteMessageLocally(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // If still in the TX path, drop BLE ops first so it cannot reappear / finish late.
            bleMeshManager.cancelOutgoing(messageId)
            dao.deleteMessageLocally(messageId)
        }
    }

    /**
     * Cancel an outgoing send that is still pending / in flight / waiting for key / failed.
     * Already sent or delivered messages cannot be recalled from the mesh — only local delete.
     */
    fun cancelOutgoingMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = bleMeshManager.cancelOutgoing(messageId)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(),
                    if (ok) "Отправка отменена" else "Уже отправлено — можно только удалить у себя",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Re-queue a failed / stuck outgoing message. */
    fun retryOutgoingMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = dao.getMessageById(messageId) ?: return@launch
            if (msg.senderId != myNodeId) return@launch
            val retryable = msg.status == Message.STATUS_FAILED ||
                msg.status == Message.STATUS_PENDING ||
                msg.status == Message.STATUS_PENDING_KEY
            if (!retryable) return@launch
            dao.updateMessageStatusAndRetryCount(messageId, Message.STATUS_PENDING, 0)
            bleMeshManager.triggerRelay()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(getApplication(), "Повторная отправка…", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setDutyPreset(preset: com.blink.dtn.ble.MeshDutyPreset) {
        bleMeshManager.applyDutyPreset(preset)
    }

    fun currentDutyPreset(): com.blink.dtn.ble.MeshDutyPreset =
        bleMeshManager.currentDutyPreset()

    /** Stadium / nearby short frame (Crowd plane). */
    fun sendCrowd(kind: Byte, text: String) {
        bleMeshManager.sendCrowdMessage(kind, text)
    }

    fun startEmergencyBeacon() {
        bleMeshManager.startEmergencyBeacon()
    }

    val nearbyUpdate = com.blink.dtn.update.VersionGossip.nearbyUpdate

    fun dismissNearbyUpdate() {
        com.blink.dtn.update.VersionGossip.dismiss(getApplication())
    }

    fun requestNearbyApkUpdate(peerId: String) {
        bleMeshManager.requestApkUpdateFromPeer(peerId)
    }

    fun buildStatusLabel(): String =
        com.blink.dtn.security.BuildIntegrity.describe(getApplication()).labelRu

    fun myVersionLabel(): String {
        val ctx = getApplication<Application>()
        val vn = com.blink.dtn.security.BuildIntegrity.myVersionName(ctx)
        val vc = com.blink.dtn.security.BuildIntegrity.myVersionCode(ctx)
        return "$vn ($vc)"
    }
}

class BLinkViewModelFactory(
    private val application: Application,
    private val dao: BLinkDao,
    private val conversationDao: ConversationDao,
    private val bleMeshManager: BleMeshManager,
    private val myNodeId: String,
    private val myNick: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BLinkViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = BLinkRepository(dao, conversationDao, myNodeId, myNick)
            return BLinkViewModel(application, dao, conversationDao, repository, bleMeshManager, myNodeId, myNick) as T
    }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
