package com.blink.dtn.router

import com.blink.dtn.net.VpsBridge
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.telemetry.detailsOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified send router.
 * Default (legacy): Internet (VPS) → BLE fallback.
 * QQ_CORE_ONLY: BLE-first; VPS never required for text delivery.
 * Wi‑Fi Direct removed — product surface is BLE GATT/ADV + optional VPS only.
 */
object MessageRouter {

    private const val PATH_MAX_ENTRIES = 500
    private const val PATH_MAX_AGE_MS = 30L * 60_000L

    private val lastPathByMessage = ConcurrentHashMap<String, RoutePath>()
    private val pathNotedAt = ConcurrentHashMap<String, Long>()
    private val _networkLive = MutableStateFlow(NetworkSnapshot())
    val networkLive: StateFlow<NetworkSnapshot> = _networkLive

    private val _activeShipment = MutableStateFlow<ActiveShipment?>(null)
    val activeShipment: StateFlow<ActiveShipment?> = _activeShipment

    data class NetworkSnapshot(
        val internetOnline: Boolean = false,
        val vpsConfigured: Boolean = false,
        val vpsReachable: Boolean = false,
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
        blePeers: Int
    ): RouteDecision {
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            return if (blePeers > 0) {
                RouteDecision(
                    RoutePath.BLE,
                    "Люди рядом ($blePeers)",
                    "People nearby ($blePeers)"
                )
            } else {
                RouteDecision(
                    RoutePath.BLE,
                    "Ждём людей рядом",
                    "Waiting for people nearby"
                )
            }
        }
        return when {
            internetOnline && vpsReady -> RouteDecision(
                RoutePath.INTERNET,
                "Есть интернет — через VPS",
                "Online — via VPS"
            )
            blePeers > 0 -> RouteDecision(
                RoutePath.BLE,
                "Люди рядом ($blePeers)",
                "People nearby ($blePeers)"
            )
            else -> RouteDecision(
                RoutePath.BLE,
                "Ждём людей рядом",
                "Waiting for people nearby"
            )
        }
    }

    fun refreshSnapshot(
        internetOnline: Boolean,
        vpsConfigured: Boolean,
        vpsReachable: Boolean,
        blePeers: Int
    ) {
        val preferred = decide(
            internetOnline = internetOnline,
            vpsReady = vpsConfigured && (vpsReachable || internetOnline),
            blePeers = blePeers
        ).path
        _networkLive.value = NetworkSnapshot(
            internetOnline = internetOnline,
            vpsConfigured = vpsConfigured,
            vpsReachable = vpsReachable,
            blePeers = blePeers,
            preferred = preferred,
            sloganActive = internetOnline || blePeers > 0
        )
    }

    fun pathFor(messageId: String): RoutePath? = lastPathByMessage[messageId]

    fun notePath(messageId: String, path: RoutePath, statusRu: String = "в пути") {
        lastPathByMessage[messageId] = path
        pathNotedAt[messageId] = System.currentTimeMillis()
        prunePaths()
        _activeShipment.value = ActiveShipment(messageId, path, statusRu)
        TraceStore.stage(
            messageId,
            "Router.Path",
            detailsOf("path" to path.traceId(), "label" to path.labelRu()),
            visual = "🔀 ${path.labelRu()}"
        )
    }

    fun prunePaths(
        maxAgeMs: Long = PATH_MAX_AGE_MS,
        maxEntries: Int = PATH_MAX_ENTRIES
    ) {
        val now = System.currentTimeMillis()
        for ((id, at) in pathNotedAt.entries.toList()) {
            if (now - at > maxAgeMs) {
                pathNotedAt.remove(id)
                lastPathByMessage.remove(id)
            }
        }
        if (lastPathByMessage.size <= maxEntries) return
        val overflow = lastPathByMessage.size - maxEntries
        pathNotedAt.entries
            .sortedBy { it.value }
            .take(overflow)
            .forEach { (id, _) ->
                pathNotedAt.remove(id)
                lastPathByMessage.remove(id)
            }
    }

    fun noteShipmentStatus(messageId: String, statusRu: String) {
        val path = lastPathByMessage[messageId] ?: return
        _activeShipment.value = ActiveShipment(messageId, path, statusRu)
    }

    fun clearShipmentIf(messageId: String) {
        if (_activeShipment.value?.messageId == messageId) {
            _activeShipment.value = null
        }
        lastPathByMessage.remove(messageId)
        pathNotedAt.remove(messageId)
    }

    /**
     * Try alternate transports before BLE GATT.
     * Legacy: VPS first. QQ_CORE_ONLY: never — BLE / DTN queue only.
     * Returns true if the payload left without needing GATT.
     */
    suspend fun tryAlternateTransports(
        bytes: ByteArray,
        messageId: String,
        internetOnline: Boolean,
        vps: VpsBridge?,
        blePeers: Int,
        targetNodeId: String? = null
    ): Boolean {
        if (com.blink.dtn.moderation.GlobalBanCache.isBanned(targetNodeId)) {
            notePath(messageId, RoutePath.BLE, "получатель в бан-листе")
            return false
        }
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            notePath(messageId, RoutePath.BLE, if (blePeers > 0) "через Bluetooth" else "ждём соседей")
            return false
        }
        val vpsReady = vps != null && vps.isConfigured() && internetOnline
        val decision = decide(internetOnline, vpsReady, blePeers)

        if (decision.path == RoutePath.INTERNET && vps != null) {
            val ok = vps.pushEncryptedPayload(bytes, messageId)
            if (ok) {
                notePath(messageId, RoutePath.INTERNET, "через интернет")
                return true
            }
        }
        notePath(messageId, RoutePath.BLE, if (blePeers > 0) "через Bluetooth" else "ждём соседей")
        return false
    }

    suspend fun sendPhotoInternetOnly(
        messageId: String,
        internetOnline: Boolean,
        vpsConfigured: Boolean,
        push: suspend () -> Boolean
    ): Boolean {
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            notePath(messageId, RoutePath.BLE, "фото: только через интернет (вне Core)")
            return false
        }
        if (!internetOnline || !vpsConfigured) {
            notePath(messageId, RoutePath.INTERNET, "фото: нет интернета")
            return false
        }
        val ok = push()
        if (ok) {
            notePath(messageId, RoutePath.INTERNET, "фото через интернет")
        } else {
            notePath(messageId, RoutePath.INTERNET, "фото: ошибка отправки")
        }
        return ok
    }

    suspend fun refreshOracleHints(
        context: android.content.Context,
        targetNode: String?,
        apply: (List<String>) -> Unit
    ) {
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) return
        val target = targetNode?.trim().orEmpty()
        if (target.isEmpty()) return
        if (!com.blink.dtn.net.VpsConfig.isOnline(context)) return
        if (!com.blink.dtn.auth.AuthSessionStore.hasVpsSession(context)) return
        val result = com.blink.dtn.net.OracleApi(context).hint(target)
        result.onSuccess { resp ->
            val ids = resp.recommendedCouriers.map { it.nodeId }.filter { it.isNotBlank() }
            apply(ids)
            if (ids.isNotEmpty()) {
                TraceStore.stage(
                    "oracle-$target",
                    "Oracle.Hint",
                    detailsOf("target" to target, "couriers" to ids.joinToString(",")),
                    visual = "🔮 Оракул: ${ids.size} курьера"
                )
            }
        }
    }
}
