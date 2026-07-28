package com.blink.dtn.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental Wi‑Fi Direct path: peer discovery + same-group TCP payload send/receive.
 *
 * Honest limits:
 * - Not a full mesh / multi-hop Wi‑Fi fabric (group-local hop only).
 * - Requires Wi‑Fi Direct support + system group membership.
 * - Falls back to BLE when no group; label stays «экспериментально».
 *
 * // Future: LoRa / VPS bridge can share the same MeshTransport contract.
 */
class WifiDirectTransport(
    private val context: Context
) : MeshTransport {
    override val id: String = "wifi_direct"
    override val displayNameRu: String = "Wi‑Fi Direct (экспериментально)"
    override val isExperimental: Boolean = true

    private val _available = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _available.asStateFlow()

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val listening = AtomicBoolean(false)
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var isGroupOwner: Boolean = false
    @Volatile private var groupFormed: Boolean = false

    /** Client sockets accepted by GO (for push). */
    private val clientOut = ConcurrentHashMap<String, Socket>()

    @Volatile
    var onMeshPayload: ((ByteArray) -> Unit)? = null

    companion object {
        private const val TAG = "WifiDirectTx"
        const val PORT = 8988
        private const val MAGIC = 0x54544B31 // "TTK1"
        private const val MAX_PAYLOAD = 512 * 1024
    }

    fun isGroupReady(): Boolean = groupFormed && (isGroupOwner || !groupOwnerAddress.isNullOrBlank())

    override fun start() {
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            Log.w(TAG, "WifiP2pManager unavailable")
            _available.value = false
            return
        }
        manager = mgr
        channel = mgr.initialize(context, Looper.getMainLooper(), null)
        _available.value = true
        registerReceiver()
        discover()
    }

    override fun stop() {
        listening.set(false)
        unregisterReceiver()
        clientOut.values.forEach { runCatching { it.close() } }
        clientOut.clear()
        runCatching {
            channel?.let { ch ->
                manager?.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                })
            }
        }
        _available.value = false
        _peers.value = emptyList()
        groupOwnerAddress = null
        isGroupOwner = false
        groupFormed = false
    }

    fun discover() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "discoverPeers started")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "discoverPeers failed reason=$reason")
            }
        })
    }

    override suspend fun send(payload: ByteArray, peerId: String?, messageId: String?): Boolean {
        if (!_available.value || !groupFormed) return false
        return withContext(Dispatchers.IO) {
            try {
                val ok = if (isGroupOwner) {
                    pushAsGroupOwner(payload)
                } else {
                    pushToGroupOwner(payload)
                }
                if (ok) {
                    Log.i(TAG, "Sent ${payload.size} bytes via Wi‑Fi Direct")
                    com.blink.dtn.telemetry.TraceStore.stage(
                        messageId ?: "wifi_direct",
                        "WiFi.DirectSend",
                        com.blink.dtn.telemetry.detailsOf(
                            "bytes" to payload.size,
                            "host" to (groupOwnerAddress ?: "go"),
                            "asOwner" to isGroupOwner,
                            "experimental" to true
                        ),
                        visual = "📡 Wi‑Fi Direct (экспериментально)"
                    )
                }
                ok
            } catch (e: Exception) {
                Log.w(TAG, "Wi‑Fi Direct send failed: ${e.message}")
                false
            }
        }
    }

    private fun pushToGroupOwner(payload: ByteArray): Boolean {
        val host = groupOwnerAddress ?: return false
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, PORT), 4_000)
            writeFramed(socket, payload)
        }
        return true
    }

    private fun pushAsGroupOwner(payload: ByteArray): Boolean {
        val sockets = clientOut.values.toList()
        if (sockets.isEmpty()) {
            // No dialed-in clients yet — cannot push; BLE fallback.
            Log.d(TAG, "GO has no client sockets yet")
            return false
        }
        var any = false
        for (socket in sockets) {
            try {
                if (socket.isClosed || !socket.isConnected) continue
                writeFramed(socket, payload)
                any = true
            } catch (e: Exception) {
                Log.w(TAG, "GO push to client failed: ${e.message}")
                runCatching { socket.close() }
                clientOut.entries.removeIf { it.value === socket }
            }
        }
        return any
    }

    private fun writeFramed(socket: Socket, payload: ByteArray) {
        // Do not close the socket — GO keeps clients for reverse push.
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(MAGIC)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun startAcceptLoop() {
        if (!listening.compareAndSet(false, true)) return
        Thread({
            var server: ServerSocket? = null
            try {
                server = ServerSocket(PORT)
                server.reuseAddress = true
                Log.i(TAG, "Accept loop on port $PORT")
                while (listening.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        server.soTimeout = 15_000
                        val client = server.accept()
                        val remote = client.inetAddress?.hostAddress ?: "client"
                        // Keep socket open for GO→client push; also read one or more frames.
                        clientOut[remote] = client
                        Thread({
                            try {
                                val dis = DataInputStream(client.getInputStream())
                                while (listening.get() && !client.isClosed) {
                                    val magic = dis.readInt()
                                    if (magic != MAGIC) {
                                        Log.w(TAG, "Bad magic $magic")
                                        break
                                    }
                                    val len = dis.readInt().coerceIn(0, MAX_PAYLOAD)
                                    val buf = ByteArray(len)
                                    dis.readFully(buf)
                                    Log.i(TAG, "RX ${buf.size} bytes on Wi‑Fi Direct from $remote")
                                    onMeshPayload?.invoke(buf)
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Client session end $remote: ${e.message}")
                            } finally {
                                clientOut.remove(remote)
                                runCatching { client.close() }
                            }
                        }, "wifi-direct-client-$remote").start()
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep looping while group is up
                    } catch (e: Exception) {
                        if (listening.get()) Log.w(TAG, "Accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accept loop failed: ${e.message}")
            } finally {
                runCatching { server?.close() }
                listening.set(false)
            }
        }, "wifi-direct-accept").start()
    }

    /**
     * Non-GO: open a persistent session to GO so reverse push works, and so we
     * can also receive if GO writes frames to us.
     */
    private fun ensureDialIn() {
        if (isGroupOwner || groupOwnerAddress.isNullOrBlank()) return
        if (clientOut.containsKey("go-session")) return
        Thread({
            try {
                val host = groupOwnerAddress ?: return@Thread
                val socket = Socket()
                socket.connect(InetSocketAddress(host, PORT), 4_000)
                clientOut["go-session"] = socket
                // Register ourselves with a tiny hello (ignored on ingress when empty).
                writeFramed(socket, ByteArray(0))
                val dis = DataInputStream(socket.getInputStream())
                while (listening.get() || groupFormed) {
                    try {
                        socket.soTimeout = 20_000
                        val magic = dis.readInt()
                        if (magic != MAGIC) break
                        val len = dis.readInt().coerceIn(0, MAX_PAYLOAD)
                        val buf = ByteArray(len)
                        dis.readFully(buf)
                        if (buf.isNotEmpty()) {
                            Log.i(TAG, "RX ${buf.size} bytes from GO")
                            onMeshPayload?.invoke(buf)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        if (!groupFormed) break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Dial-in session end: ${e.message}")
            } finally {
                clientOut.remove("go-session")?.let { runCatching { it.close() } }
            }
        }, "wifi-direct-dialin").start()
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        _available.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        manager?.requestPeers(ch) { list: WifiP2pDeviceList ->
                            _peers.value = list.deviceList.map { d: WifiP2pDevice ->
                                MeshPeer(
                                    id = d.deviceAddress,
                                    displayName = d.deviceName?.ifBlank { d.deviceAddress }
                                        ?: d.deviceAddress,
                                    transportId = id
                                )
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        manager?.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                            if (info == null || !info.groupFormed) {
                                groupOwnerAddress = null
                                isGroupOwner = false
                                groupFormed = false
                                listening.set(false)
                                clientOut.values.forEach { runCatching { it.close() } }
                                clientOut.clear()
                                return@requestConnectionInfo
                            }
                            isGroupOwner = info.isGroupOwner
                            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                            groupFormed = true
                            Log.i(TAG, "Group formed owner=$isGroupOwner go=$groupOwnerAddress")
                            if (isGroupOwner) {
                                startAcceptLoop()
                            } else {
                                ensureDialIn()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(r, filter)
        }
        receiver = r
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
