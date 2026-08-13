package com.blink.dtn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.blink.dtn.ble.BleMeshManager
import com.blink.dtn.crypto.CryptoUtils
import com.blink.dtn.db.BLinkDatabase
import com.blink.dtn.utils.NetworkUtils
import com.blink.dtn.vk.VkRelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class BLinkMeshService : Service() {
    private lateinit var myNodeId: String
    private lateinit var bleMeshManager: BleMeshManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var vkRelayJob: Job? = null
    private var dtnRoutingJob: Job? = null
    private var txResultJob: Job? = null
    private var custodySweepJob: Job? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    private companion object {
        /** Custody / stale-in-flight reconciliation cadence. */
        const val CUSTODY_SWEEP_INTERVAL_MS = 60_000L
    }

    override fun onCreate() {
        super.onCreate()
        com.blink.dtn.telemetry.ErrorJournal.install(this)
        com.blink.dtn.crypto.RsaUtils.generateAndStoreKeyPair()
        val dao = BLinkDatabase.getDatabase(this).bLinkDao()
        runBlocking(Dispatchers.IO) {
            com.blink.dtn.utils.LegacyIdMigration.runIfNeeded(this@BLinkMeshService, dao)
        }

        val prefs = getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
        // Self-certifying node id derived from our RSA public key (same keystore key
        // as MainActivity → identical id). Overwrites any legacy random id.
        myNodeId = com.blink.dtn.crypto.NodeIdentity.myNodeId()
        prefs.edit().putString("node_id", myNodeId).apply()
        // Notification copy is localised — the service may start before any UI.
        com.blink.dtn.ui.AppLang.init(this)
        if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            com.blink.dtn.ui.GamificationStore.init(this)
        }

        // ZOMBIE SWEEP
        serviceScope.launch {
            dao.revertInFlightMessages()
        }
        bleMeshManager = BleMeshManager.getInstance(this, dao, myNodeId)

        com.blink.dtn.telemetry.MeshDutyTelemetry.init(this)
        com.blink.dtn.telemetry.MeshDutyTelemetry.startBatteryReceiver(this)
        com.blink.dtn.ble.MeshDutyPrefs.init(this)
        com.blink.dtn.crowd.EventRoomStore.init(this)

        runCatching {
            val transports = listOf<com.blink.dtn.transport.MeshTransport>(
                com.blink.dtn.transport.BleMeshTransport(bleMeshManager)
            )
            val registry = com.blink.dtn.transport.MeshTransportRegistry(transports)
            bleMeshManager.attachTransportRegistry(registry)
            registry.startAll()
        }.onFailure {
            android.util.Log.w("MeshService", "Transport registry init: ${it.message}")
        }

        runCatching {
            // VPS internet bridge stays active (push/pull). QQ Core only gates Hub/PUBLIC UI.
            com.blink.dtn.net.VpsConfig.init(this)
            val vps = com.blink.dtn.net.VpsBridge.getInstance(
                this,
                dao,
                bleMeshManager,
                myNodeId
            )
            bleMeshManager.vpsBridge = vps
            vps.start()
        }.onFailure {
            android.util.Log.w("MeshService", "VPS bridge init: ${it.message}")
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "mesh_channel")
            .setContentTitle("Qq")
            .setContentText(
                if (com.blink.dtn.ui.AppLang.isEn()) "Carrying messages from person to person"
                else "Передаёт сообщения от человека к человеку"
            )
            .setSmallIcon(com.blink.dtn.R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            ServiceCompat.startForeground(this, 1, notification, type)
        } else {
            startForeground(1, notification)
        }
        registerBluetoothReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Start BLE scanning and advertising
        bleMeshManager.startMesh()
        
        // 2. Start DTN Routing Engine
        startDtnRoutingEngine()

        // 3. Start VK Relay Loop (legacy — isolated in QQ Core)
        if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            startVkRelayLoop()
        }

        return START_STICKY
    }



    private fun startDtnRoutingEngine() {
        txResultJob?.cancel()
        custodySweepJob?.cancel()

        val dao = BLinkDatabase.getDatabase(this).bLinkDao()

        // 1. Listen for TxResults.
        //    Sequential `collect` (not collectLatest): every outcome must reach
        //    Room. collectLatest cancelled the previous handler mid-write, which
        //    together with the old DROP_OLDEST channel silently lost statuses.
        txResultJob = serviceScope.launch {
            bleMeshManager.txResults.collect { result: com.blink.dtn.ble.TxResult ->
                runCatching { applyTxResult(dao, result) }
                    .onFailure { android.util.Log.w("MeshService", "TxResult apply: ${it.message}") }
            }
        }

        // 2. Custody / stale-in-flight / honest-expiry reconciliation.
        //    Also the process-death and reboot recovery path: Room is the source
        //    of truth, so a cold start re-derives everything from the rows.
        custodySweepJob = serviceScope.launch {
            runCatching { com.blink.dtn.db.DeliverySweeper.sweep(dao, myNodeId) }
            while (isActive) {
                delay(CUSTODY_SWEEP_INTERVAL_MS)
                val result = runCatching {
                    com.blink.dtn.db.DeliverySweeper.sweep(dao, myNodeId)
                }.getOrNull()
                if (result?.changed == true) {
                    bleMeshManager.triggerRelay()
                }
            }
        }
    }

    private suspend fun applyTxResult(
        dao: com.blink.dtn.db.BLinkDao,
        result: com.blink.dtn.ble.TxResult
    ) {
        when (result) {
            is com.blink.dtn.ble.TxResult.Success -> {
                val currentMsg = dao.getMessageById(result.msgId)
                // Neighbour custody starts here — not delivery ([CustodyPolicy]).
                dao.noteHandedToCarrier(result.msgId)
                com.blink.dtn.router.MessageRouter.noteShipmentStatus(result.msgId, "у соседа")
                val isOthersMail = currentMsg != null &&
                    !currentMsg.isMine &&
                    currentMsg.senderId != myNodeId
                if (isOthersMail) {
                    com.blink.dtn.ui.MeshTransferFeedback.onHopCompleted(this@BLinkMeshService)
                    com.blink.dtn.ui.GamificationStore.noteHelpedRelay(this@BLinkMeshService)
                }
            }
            is com.blink.dtn.ble.TxResult.Failure -> {
                val currentMsg = dao.getMessageById(result.msgId)
                if (currentMsg != null &&
                    com.blink.dtn.db.MessageDeliverySm.mayAutoUpdate(
                        currentMsg.status,
                        com.blink.dtn.db.Message.STATUS_FAILED
                    ) &&
                    currentMsg.status != com.blink.dtn.db.Message.STATUS_STORED_IN_NEIGHBOR
                ) {
                    val newRetry = currentMsg.retryCount + 1
                    if (newRetry >= 10) {
                        dao.updateMessageStatus(result.msgId, com.blink.dtn.db.Message.STATUS_FAILED)
                        com.blink.dtn.telemetry.TraceStore.finish(
                            result.msgId,
                            "Failed",
                            com.blink.dtn.telemetry.detailsOf("retries" to newRetry)
                        )
                    } else {
                        dao.updateMessageStatusAndRetryCount(
                            result.msgId,
                            com.blink.dtn.db.Message.STATUS_PENDING,
                            newRetry
                        )
                    }
                }

                if (result.failedMacs.isNotEmpty()) {
                    // Temporal trigger to wake up DTN router after backoff expires
                    serviceScope.launch {
                        kotlinx.coroutines.delay(10_000L)
                        bleMeshManager.triggerRelay()
                    }
                }
            }
        }
    }
        private fun startVkRelayLoop() {
        vkRelayJob?.cancel()
        vkRelayJob = serviceScope.launch {
            val dao = BLinkDatabase.getDatabase(this@BLinkMeshService).bLinkDao()
            
            launch {
                com.blink.dtn.ui.BLinkViewModel.fastSyncTrigger.collect {
                    CoroutineScope(Dispatchers.IO).launch {
                        // Instantly wake up the relay loop
                        if (NetworkUtils.isInternetAvailable(this@BLinkMeshService)) {
                            try {
                                val unsyncedMessages = dao.getUnsyncedMessages()
                                val syncedIds = mutableListOf<String>()
                                for (msg in unsyncedMessages) {
                                    val wire = com.blink.dtn.ble.NetworkPacket.fromMessage(msg)
                                    val encryptedPayload =
                                        com.blink.dtn.crypto.CryptoUtils.packSigned(wire)
                                    val success = com.blink.dtn.vk.VkRelayManager.pushPayloadToWall(encryptedPayload)
                                    if (success) {
                                        syncedIds.add(msg.id)
                                    }
                                }
                                if (syncedIds.isNotEmpty()) {
                                    dao.markAsSynced(syncedIds)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
            
            while (true) {
                if (NetworkUtils.isInternetAvailable(this@BLinkMeshService)) {
                    try {
                        val incomingPayloads = VkRelayManager.fetchPayloadsFromWall()
                        for (payload in incomingPayloads) {
                            bleMeshManager.injectEncryptedPayload(payload)
                        }
                        
                        val unsyncedMessages = dao.getUnsyncedMessages()
                        val syncedIds = mutableListOf<String>()
                        for (msg in unsyncedMessages) {
                            val wire = com.blink.dtn.ble.NetworkPacket.fromMessage(msg)
                            val encryptedPayload = com.blink.dtn.crypto.CryptoUtils.packSigned(wire)
                            val success = VkRelayManager.pushPayloadToWall(encryptedPayload)
                            if (success) {
                                syncedIds.add(msg.id)
                            }
                        }
                        if (syncedIds.isNotEmpty()) {
                            dao.markAsSynced(syncedIds)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                delay(60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        vkRelayJob?.cancel()
        txResultJob?.cancel()
        custodySweepJob?.cancel()
        bleMeshManager.transportRegistry?.stopAll()
        com.blink.dtn.telemetry.MeshDutyTelemetry.stopBatteryReceiver()
        bleMeshManager.stopMesh()
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> bleMeshManager.pauseRadio()
                    BluetoothAdapter.STATE_ON -> bleMeshManager.resumeRadio()
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun unregisterBluetoothReceiver() {
        val receiver = bluetoothReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
        bluetoothReceiver = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            val en = com.blink.dtn.ui.AppLang.isEn()

            // Foreground service channel. Channel IDs stay stable (user sound/mute settings
            // are keyed by id) — only the user-visible names/descriptions are rebranded.
            val foregroundChannel = NotificationChannel(
                "mesh_channel",
                if (en) "Qq in background" else "Qq в фоне",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = if (en) "Keeps Qq connected to people nearby"
                else "Держит Qq на связи с людьми рядом"
            }
            notificationManager?.createNotificationChannel(foregroundChannel)
            
            // High priority messages channel
            val messagesChannel = NotificationChannel(
                "tuktuk_messages",
                if (en) "Qq messages" else "Сообщения Qq",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (en) "New message alerts" else "Уведомления о новых сообщениях"
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(
                    android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION
                    ),
                    audioAttributes
                )
            }
            notificationManager?.createNotificationChannel(messagesChannel)
        }
    }
}
