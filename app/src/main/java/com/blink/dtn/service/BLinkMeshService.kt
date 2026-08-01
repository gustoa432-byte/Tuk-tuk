package com.blink.dtn.service
import kotlinx.coroutines.flow.collectLatest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
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

        // ZOMBIE SWEEP
        serviceScope.launch {
            dao.revertInFlightMessages()
        }
        bleMeshManager = BleMeshManager.getInstance(this, dao, myNodeId)

        com.blink.dtn.telemetry.MeshDutyTelemetry.init(this)
        com.blink.dtn.telemetry.MeshDutyTelemetry.startBatteryReceiver(this)
        com.blink.dtn.ble.MeshDutyPrefs.init(this)

        runCatching {
            val wifiDirect = com.blink.dtn.transport.WifiDirectTransport(applicationContext)
            val registry = com.blink.dtn.transport.MeshTransportRegistry(
                listOf(
                    com.blink.dtn.transport.BleMeshTransport(bleMeshManager),
                    wifiDirect
                )
            )
            bleMeshManager.attachTransportRegistry(registry)
            registry.startAll()
        }.onFailure {
            android.util.Log.w("MeshService", "Transport registry init: ${it.message}")
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "mesh_channel")
            .setContentTitle("Тук...")
            .setContentText("От человека... к человеку... тук")
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Start BLE scanning and advertising
        bleMeshManager.startMesh()
        
        // 2. Start DTN Routing Engine
        startDtnRoutingEngine()

        // 3. Start VK Relay Loop
        startVkRelayLoop()

        return START_STICKY
    }



    private fun startDtnRoutingEngine() {
                txResultJob?.cancel()
        
        val dao = BLinkDatabase.getDatabase(this).bLinkDao()
        
        // 1. Listen for TxResults
        txResultJob = serviceScope.launch {
            bleMeshManager.txResults.collectLatest { result: com.blink.dtn.ble.TxResult ->
                launch {
                    when (result) {
                        is com.blink.dtn.ble.TxResult.Success -> {
                            val currentMsg = dao.getMessageById(result.msgId)
                            // Don't clobber end-to-end DELIVERED; SENT means "у соседей" / "в пути".
                            if (currentMsg != null &&
                                currentMsg.status != com.blink.dtn.db.Message.STATUS_SENT &&
                                currentMsg.status != com.blink.dtn.db.Message.STATUS_DELIVERED
                            ) {
                                dao.updateMessageStatus(result.msgId, com.blink.dtn.db.Message.STATUS_SENT)
                            }
                        }
                        is com.blink.dtn.ble.TxResult.Failure -> {
                            val currentMsg = dao.getMessageById(result.msgId)
                            if (currentMsg != null &&
                                currentMsg.status != com.blink.dtn.db.Message.STATUS_SENT &&
                                currentMsg.status != com.blink.dtn.db.Message.STATUS_DELIVERED
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
                                    dao.updateMessageStatusAndRetryCount(result.msgId, com.blink.dtn.db.Message.STATUS_PENDING, newRetry)
                                }
                            }
                            
                            if (result.failedMacs.isNotEmpty()) {
                                // Temporal trigger to wake up DTN router after backoff expires
                                launch {
                                    kotlinx.coroutines.delay(10_000L)
                                    bleMeshManager.triggerRelay()
                                }
                            }
                        }
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
                                    val jsonPayload = kotlinx.serialization.json.Json.encodeToString(msg)
                                    val encryptedPayload = com.blink.dtn.crypto.CryptoUtils.encrypt(jsonPayload)
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
                            val jsonPayload = Json.encodeToString(msg)
                            val encryptedPayload = CryptoUtils.encrypt(jsonPayload)
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
        vkRelayJob?.cancel()
        txResultJob?.cancel()
        bleMeshManager.transportRegistry?.stopAll()
        com.blink.dtn.telemetry.MeshDutyTelemetry.stopBatteryReceiver()
        bleMeshManager.stopMesh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Foreground service channel
            val foregroundChannel = NotificationChannel(
                "mesh_channel",
                "Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains P2P Bluetooth connection in background"
            }
            notificationManager?.createNotificationChannel(foregroundChannel)
            
            // High priority messages channel
            val messagesChannel = NotificationChannel(
                "tuktuk_messages",
                "TukTuk Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for new mesh messages"
                
                // Custom sound setup
                val soundUri = android.net.Uri.parse("android.resource://${packageName}/raw/tuktuk")
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                
                // Check if resource exists, else fallback to default notification sound
                val resId = resources.getIdentifier("tuktuk", "raw", packageName)
                if (resId != 0) {
                    setSound(soundUri, audioAttributes)
                } else {
                    setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
                }
            }
            notificationManager?.createNotificationChannel(messagesChannel)
        }
    }
}
