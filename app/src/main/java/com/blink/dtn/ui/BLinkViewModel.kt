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
    private val bleMeshManager: BleMeshManager,
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
        bleMeshManager.updateMyProfile(myNick, false)
        
        // Execute background cleanup periodically while the app is alive
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // 48 hours for messages
                val messageTtl = System.currentTimeMillis() - (48 * 60 * 60 * 1000L)
                dao.deleteOldMessages(messageTtl)
                dao.deleteOldSeenPackets(messageTtl)
                
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
        
    // b) publicMessages: StateFlow с сообщениями глобального чата.
    val publicMessages = repository.getPublicChatHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // c) Стейт для текущего открытого диалога
    val pendingCount = dao.getPendingMessagesFlow().map { it.size }

    val currentDialogId = MutableStateFlow<String?>(null)
    
    @kotlinx.coroutines.ExperimentalCoroutinesApi
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

    val peerCount = bleMeshManager.peerCount
    val activePeers = bleMeshManager.activePeers
    
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

    fun getProfileFlow(userId: String) = dao.getProfileByIdFlow(userId)

    // Contact QR payload: carries our public key so a scan can pin the key
    // out-of-band without waiting for a BLE identity announcement. JSONObject
    // escapes the nick correctly.
    val myContactQr: String
        get() = org.json.JSONObject().apply {
            put("v", 1)
            put("id", myNodeId)
            put("pk", com.blink.dtn.crypto.RsaUtils.getPublicKeyBase64())
            put("n", myNick)
        }.toString()

    // Persist a QR-scanned contact with its pinned public key. The id is the
    // self-certifying hash of pubKeyBase64, so this can only ever pin the one
    // key that matches the id (a later BLE announcement must carry the same key
    // or it is rejected at ingress).
    fun addScannedContact(id: String, nick: String, pubKeyBase64: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertOrUpdateProfile(
                com.blink.dtn.db.UserProfile(
                    id,
                    nick.ifEmpty { id },
                    System.currentTimeMillis(),
                    false,
                    pubKeyBase64
                )
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                setCurrentDialog(id)
            }
        }
    }

    fun sendPublicMessage(text: String, room: String = "general") {
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
            } catch (e: Exception) {
                TraceStore.finish(trace.traceId, "Failed", detailsOf("error" to e.message, "stack" to e.stackTraceToString().take(1500)))
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Public message error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
    }
    }

    fun sendPrivateMessage(text: String, targetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
                    val (pendingMsg, _) = repository.createAndSavePrivateMessage(text, targetId, isPendingKey = true)
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
                        android.widget.Toast.makeText(getApplication(), "Missing public key. Requesting from mesh...", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val dbStart = System.currentTimeMillis()
                TraceStore.stage(trace.traceId, TraceStages.DB_INSERT_START)
                val (localMsg, _) = repository.createAndSavePrivateMessage(text, targetId)
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
            } catch (e: Exception) {
                TraceStore.finish(trace.traceId, "Failed", detailsOf("error" to e.message, "stack" to e.stackTraceToString().take(1500)))
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Private message error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
    }
    }

    fun blockUser(nick: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.blockUser(BlockedUser(nick, System.currentTimeMillis()))
    }
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
