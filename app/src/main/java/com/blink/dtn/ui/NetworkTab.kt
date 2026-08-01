package com.blink.dtn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.blink.dtn.router.MessageRouter
import com.blink.dtn.router.RoutePath
import com.blink.dtn.telemetry.PeerDirectory
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel

@Composable
fun NetworkStatusBanner(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val lang by AppLang.lang.collectAsState()
    val snap by MessageRouter.networkLive.collectAsState()
    val active = isConnected || snap.sloganActive
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .glassPanel(corner = 12.dp, strong = active)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (active) AccentLime else DividerColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (active) S.networkActive(lang) else S.networkWaiting(lang),
            color = if (active) TextPrimary else TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun NetworkTab(viewModel: BLinkViewModel) {
    val lang by AppLang.lang.collectAsState()
    val snap by MessageRouter.networkLive.collectAsState()
    val shipment by MessageRouter.activeShipment.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val pending by viewModel.pendingCount.collectAsState(0)
    val peers = remember { PeerDirectory.snapshot().take(12) }
    val hopsToday = remember {
        TraceStore.listRecent(80).sumOf { t ->
            t.events.count { e ->
                e.stage.contains("TX", ignoreCase = true) ||
                    e.stage.contains("Relay", ignoreCase = true) ||
                    e.stage == "Router.Path"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Tuk-Tuk", style = Typography.titleLarge, color = TextPrimary)
        Text(S.slogan(lang), style = Typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        NetworkStatusBanner(isConnected = snap.sloganActive || peerCount > 0)

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NetworkStatCard(S.transferred(lang), pending.toString(), Modifier.weight(1f))
            NetworkStatCard(S.hopsToday(lang), hopsToday.toString(), Modifier.weight(1f))
            NetworkStatCard(S.devicesNearby(lang), peerCount.toString(), Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.preferredRoute(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 14.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (snap.preferred) {
                    RoutePath.INTERNET -> Icons.Default.Cloud
                    RoutePath.WIFI_DIRECT -> Icons.Default.Wifi
                    RoutePath.BLE -> Icons.Default.Bluetooth
                },
                contentDescription = null,
                tint = AccentLime,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    if (lang == "en") snap.preferred.labelEn() else snap.preferred.labelRu(),
                    color = TextPrimary,
                    style = Typography.titleMedium
                )
                Text(
                    buildString {
                        if (snap.internetOnline) append(if (lang == "en") "online" else "онлайн")
                        else append(if (lang == "en") "offline" else "офлайн")
                        if (snap.vpsConfigured) {
                            append(" · VPS ")
                            append(if (snap.vpsReachable) "OK" else "…")
                        }
                        if (snap.wifiDirectReady) append(" · Wi‑Fi")
                        append(" · BLE $peerCount")
                    },
                    color = TextSecondary,
                    style = Typography.labelSmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.currentShipment(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 14.dp)
                .padding(14.dp)
        ) {
            val ship = shipment
            if (ship == null) {
                Text(S.noShipment(lang), color = TextSecondary, style = Typography.bodySmall)
            } else {
                Column {
                    Text(
                        "#${ship.messageId.takeLast(6)} · ${ship.statusLabelRu}",
                        color = TextPrimary,
                        style = Typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        S.routeVia(
                            lang,
                            if (lang == "en") ship.path.labelEn() else ship.path.labelRu()
                        ),
                        color = AccentLime,
                        style = Typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MessageTrackerStrip(path = ship.path, statusRu = ship.statusLabelRu)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.neighbors(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        if (peers.isEmpty() && peerCount == 0) {
            Text(S.noNeighbors(lang), color = TextSecondary, style = Typography.bodySmall)
        } else {
            peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DividerColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            peer.device.take(1).uppercase(),
                            color = TextPrimary,
                            style = Typography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.device, color = TextPrimary, style = Typography.bodyMedium)
                        Text(
                            peer.nodeId.take(12) + if (peer.nodeId.length > 12) "…" else "",
                            color = TextSecondary,
                            style = Typography.labelSmall
                        )
                    }
                    if (peer.packetsForwarded > 0) {
                        Text(
                            "×${peer.packetsForwarded}",
                            color = AccentLime,
                            style = Typography.labelSmall
                        )
                    }
                }
            }
            if (peerCount > 0 && peers.isEmpty()) {
                Text(
                    "${S.devicesNearby(lang)}: $peerCount (BLE)",
                    color = TextSecondary,
                    style = Typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun NetworkStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .glassPanel(corner = 12.dp)
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(value, color = AccentLime, style = Typography.titleLarge)
        Text(label, color = TextSecondary, style = Typography.labelSmall)
    }
}

/** Compact A → hops → B tracker used in Network tab and chat voyage. */
@Composable
fun MessageTrackerStrip(
    path: RoutePath,
    statusRu: String,
    modifier: Modifier = Modifier
) {
    val lang by AppLang.lang.collectAsState()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TrackerNode(if (lang == "en") "You" else "Вы")
        TrackerLine()
        TrackerNode(
            when (path) {
                RoutePath.INTERNET -> "VPS"
                RoutePath.WIFI_DIRECT -> "Wi‑Fi"
                RoutePath.BLE -> "BLE"
            },
            accent = true
        )
        TrackerLine()
        TrackerNode(if (lang == "en") "Peer" else "Адресат")
    }
    Text(
        statusRu,
        color = TextSecondary,
        style = Typography.labelSmall,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun TrackerNode(label: String, accent: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (accent) AccentLime else TextPrimary, CircleShape)
        )
        Text(label, color = TextSecondary, style = Typography.labelSmall)
    }
}

@Composable
private fun TrackerLine() {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(2.dp)
            .background(DividerColor, RoundedCornerShape(1.dp))
    )
}
