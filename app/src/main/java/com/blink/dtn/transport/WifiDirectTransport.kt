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
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental Wi‑Fi Direct path: peer discovery + same-group TCP payload send.
 *
 * Honest limits (MVP):
 * - Not a full mesh / multi-hop Wi‑Fi fabric.
 * - Requires Wi‑Fi Direct support + user/system group membership.
 * - Falls back to BLE for normal messaging; this is an optional denser hop.
 *
 * TODO: integrate group formation UX, mutual auth, and DTN enqueue on RX.
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

    companion object {
        private const val TAG = "WifiDirectTx"
        const val PORT = 8988
        // TODO: negotiate framing version with peer
        private const val MAGIC = 0x54544B31 // "TTK1"
    }

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
        // TODO: start accept loop only after group formed
    }

    override fun stop() {
        listening.set(false)
        unregisterReceiver()
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
        if (!_available.value) return false
        val host = groupOwnerAddress
        if (host.isNullOrBlank() && !isGroupOwner) {
            Log.d(TAG, "No Wi‑Fi Direct group — cannot send (fall back to BLE)")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                if (isGroupOwner) {
                    // Owner expects clients to connect; without a known client IP we cannot push.
                    // TODO: maintain client socket map after accept.
                    Log.d(TAG, "Group owner push not wired yet; need client dial-in")
                    false
                } else {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, PORT), 4_000)
                        DataOutputStream(socket.getOutputStream()).use { out ->
                            out.writeInt(MAGIC)
                            out.writeInt(payload.size)
                            out.write(payload)
                            out.flush()
                        }
                    }
                    Log.i(TAG, "Sent ${payload.size} bytes via Wi‑Fi Direct to $host")
                    com.blink.dtn.telemetry.TraceStore.stage(
                        messageId ?: "wifi_direct",
                        "WiFi.DirectSend",
                        com.blink.dtn.telemetry.detailsOf(
                            "bytes" to payload.size,
                            "host" to host,
                            "experimental" to true
                        ),
                        visual = "📡 Wi‑Fi Direct (экспериментально)"
                    )
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wi‑Fi Direct send failed: ${e.message}")
                false
            }
        }
    }

    /** Call when this device becomes group owner to accept one framed payload (prototype). */
    fun startAcceptLoopOnce(onPayload: (ByteArray) -> Unit) {
        if (!listening.compareAndSet(false, true)) return
        Thread({
            try {
                ServerSocket(PORT).use { server ->
                    server.soTimeout = 30_000
                    val client = server.accept()
                    client.getInputStream().use { raw ->
                        val dis = java.io.DataInputStream(raw)
                        val magic = dis.readInt()
                        if (magic != MAGIC) {
                            Log.w(TAG, "Bad magic $magic")
                            return@use
                        }
                        val len = dis.readInt().coerceIn(0, 512 * 1024)
                        val buf = ByteArray(len)
                        dis.readFully(buf)
                        onPayload(buf)
                    }
                    client.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accept loop: ${e.message}")
            } finally {
                listening.set(false)
            }
        }, "wifi-direct-accept").start()
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
                                return@requestConnectionInfo
                            }
                            isGroupOwner = info.isGroupOwner
                            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                            Log.i(TAG, "Group formed owner=$isGroupOwner go=$groupOwnerAddress")
                            if (isGroupOwner) {
                                // Prototype RX: log only — DTN ingress wiring is TODO.
                                startAcceptLoopOnce { bytes ->
                                    Log.i(TAG, "RX ${bytes.size} bytes on Wi‑Fi Direct (not yet into DTN)")
                                }
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
