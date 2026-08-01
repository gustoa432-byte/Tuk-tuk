package com.blink.dtn.router

import com.blink.dtn.net.VpsBridge
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.telemetry.detailsOf
import com.blink.dtn.transport.WifiDirectTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified send router: Internet (VPS) → Wi‑Fi Direct → BLE fallback.
 * BLE remains the DTN default when [tryAlternateTransports] returns false.
 */
object MessageRouter {

    private val lastPathByMessage = ConcurrentHashMap<String, RoutePath>()
    private val _networkLive = MutableStateFlow(NetworkSnapshot())
    val networkLive: StateFlow<NetworkSnapshot> = _networkLive

    private val _activeShipment = MutableStateFlow<ActiveShipment?>(null)
    val activeShipment: StateFlow<ActiveShipment?> = _activeShipment

    data class NetworkSnapshot(
        val internetOnline: Boolean = false,
        val vpsConfigured: Boolean = false,
        val vpsReachable: Boolean = false,
        val wifiDirectReady: Boolean = false,
        val blePeers: Int = 0,
        val preferred: RoutePath = RoutePath.BLE,
        val sloganActive: Boolean = false
    )

    data class ActiveShipment(
        val messageId: String,
        val path: RoutePath,
        val statusLabelRu: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    fun decide(
        internetOnline: Boolean,
        vpsReady: Boolean,
        wifiDirectReady: Boolean,
        blePeers: Int
    ): RouteDecision {
        return when {
            internetOnline && vpsReady -> RouteDecision(
                RoutePath.INTERNET,
                "Есть интернет — через VPS",
                "Online — via VPS"
            )
            wifiDirectReady -> RouteDecision(
                RoutePath.WIFI_DIRECT,
                "Рядом группа Wi‑Fi Direct",
                "Wi‑Fi Direct group nearby"
            )
            blePeers > 0 -> RouteDecision(
                RoutePath.BLE,
                "Соседи по Bluetooth ($blePeers)",
                "Bluetooth neighbors ($blePeers)"
            )
            else -> RouteDecision(
                RoutePath.BLE,
                "Ждём людей рядом (BLE DTN)",
                "Waiting for nearby people (BLE DTN)"
            )
        }
    }

    fun refreshSnapshot(
        internetOnline: Boolean,
        vpsConfigured: Boolean,
        vpsReachable: Boolean,
        wifiDirectReady: Boolean,
        blePeers: Int
    ) {
        val preferred = decide(
            internetOnline = internetOnline,
            vpsReady = vpsConfigured && (vpsReachable || internetOnline),
            wifiDirectReady = wifiDirectReady,
            blePeers = blePeers
        ).path
        _networkLive.value = NetworkSnapshot(
            internetOnline = internetOnline,
            vpsConfigured = vpsConfigured,
            vpsReachable = vpsReachable,
            wifiDirectReady = wifiDirectReady,
            blePeers = blePeers,
            preferred = preferred,
            sloganActive = internetOnline || wifiDirectReady || blePeers > 0
        )
    }

    fun pathFor(messageId: String): RoutePath? = lastPathByMessage[messageId]

    fun notePath(messageId: String, path: RoutePath, statusRu: String = "в пути") {
        lastPathByMessage[messageId] = path
        _activeShipment.value = ActiveShipment(messageId, path, statusRu)
        TraceStore.stage(
            messageId,
            "Router.Path",
            detailsOf("path" to path.traceId(), "label" to path.labelRu()),
            visual = "🔀 ${path.labelRu()}"
        )
    }

    fun noteShipmentStatus(messageId: String, statusRu: String) {
        val path = lastPathByMessage[messageId] ?: return
        _activeShipment.value = ActiveShipment(messageId, path, statusRu)
    }

    fun clearShipmentIf(messageId: String) {
        if (_activeShipment.value?.messageId == messageId) {
            _activeShipment.value = null
        }
    }

    /**
     * Try denser/online hops before BLE. Returns true if the payload left this device
     * without needing the GATT path.
     */
    suspend fun tryAlternateTransports(
        bytes: ByteArray,
        messageId: String,
        internetOnline: Boolean,
        vps: VpsBridge?,
        wifi: WifiDirectTransport?,
        blePeers: Int
    ): Boolean {
        val vpsReady = vps != null && vps.isConfigured() && internetOnline
        val wifiReady = wifi != null && wifi.isGroupReady()
        val decision = decide(internetOnline, vpsReady, wifiReady, blePeers)

        if (decision.path == RoutePath.INTERNET && vps != null) {
            val ok = vps.pushEncryptedPayload(bytes, messageId)
            if (ok) {
                notePath(messageId, RoutePath.INTERNET, "через интернет")
                return true
            }
        }
        if (wifiReady && wifi != null) {
            val ok = wifi.send(bytes, messageId = messageId)
            if (ok) {
                notePath(messageId, RoutePath.WIFI_DIRECT, "через Wi‑Fi Direct")
                return true
            }
        }
        // BLE path is handled by BleRelayEngine after we return false.
        notePath(messageId, RoutePath.BLE, if (blePeers > 0) "через Bluetooth" else "ждём соседей")
        return false
    }
}
