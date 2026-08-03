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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
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

/** Living network — people, queue, routes. No MAC / UUID. */
@Composable
fun NetworkTab(viewModel: BLinkViewModel) {
    val lang by AppLang.lang.collectAsState()
    val snap by MessageRouter.networkLive.collectAsState()
    val shipment by MessageRouter.activeShipment.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val pending by viewModel.pendingCount.collectAsState(0)
    // Live: re-read when peer count / router snap changes (not a one-shot remember).
    val peers = remember(peerCount, snap.sloganActive, snap.blePeers) {
        PeerDirectory.liveNeighbors(16)
    }
    val hopsToday = remember(peerCount, pending) {
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
        Text(S.network(lang), style = Typography.titleLarge, color = TextPrimary)
        Text(S.slogan(lang), style = Typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        NetworkStatusBanner(isConnected = snap.sloganActive || peerCount > 0)

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.connectionStatus(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        ConnectionStatusRow(S.connectionPeople(lang), peerCount > 0 || snap.blePeers > 0, lang)
        ConnectionStatusRow(S.connectionNearby(lang), snap.wifiDirectReady, lang)
        ConnectionStatusRow(S.connectionInternet(lang), snap.internetOnline && snap.vpsConfigured, lang)

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NetworkStatCard(S.queueNow(lang), pending.toString(), Modifier.weight(1f))
            NetworkStatCard(S.hopsToday(lang), hopsToday.toString(), Modifier.weight(1f))
            NetworkStatCard(S.peopleNearby(lang), peerCount.toString(), Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.lastRoute(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 14.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    humanPathLabel(snap.preferred, lang),
                    color = TextPrimary,
                    style = Typography.titleMedium
                )
                Text(
                    S.autoRouteHint(lang),
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
                    Text(S.packageInFlight(lang), color = TextPrimary, style = Typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        S.routeVia(lang, humanPathLabel(ship.path, lang)),
                        color = AccentLime,
                        style = Typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MessageTrackerStrip(path = ship.path, statusRu = ship.statusLabelRu)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.peopleNearby(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        if (peers.isEmpty() && peerCount == 0) {
            Text(S.noPeopleNearby(lang), color = TextSecondary, style = Typography.bodySmall)
        } else {
            peers.forEach { (nodeId, nameRaw) ->
                val name = nameRaw
                    .takeIf { it.isNotBlank() && !looksLikeTechId(it) }
                    ?: (if (lang == "en") "Neighbor" else "Сосед")
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
                        Text(name.take(1).uppercase(), color = TextPrimary, style = Typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, color = TextPrimary, style = Typography.bodyMedium)
                        Text(
                            S.nearbyNow(lang),
                            color = TextSecondary,
                            style = Typography.labelSmall
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(AccentLime, CircleShape)
                    )
                }
            }
            if (peerCount > peers.size) {
                Text(
                    S.morePeopleNearby(lang, peerCount - peers.size),
                    color = TextSecondary,
                    style = Typography.bodySmall
                )
            }
        }
    }
}

private fun looksLikeTechId(s: String): Boolean {
    if (s.count { it == ':' } >= 4) return true
    if (s.length >= 16 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return true
    return false
}

fun humanPathLabel(path: RoutePath, lang: String): String = when (path) {
    RoutePath.INTERNET -> S.pathInternet(lang)
    RoutePath.WIFI_DIRECT -> S.pathNearbyGroup(lang)
    RoutePath.BLE -> S.pathPeople(lang)
}

@Composable
private fun ConnectionStatusRow(label: String, on: Boolean, lang: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassPanel(corner = 12.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (on) AccentLime else DividerColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, color = TextPrimary, style = Typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (on) S.connectionOn(lang) else S.connectionOff(lang),
            color = if (on) AccentLime else TextSecondary,
            style = Typography.labelSmall
        )
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
                RoutePath.INTERNET -> S.pathInternetShort(lang)
                RoutePath.WIFI_DIRECT -> S.pathNearbyShort(lang)
                RoutePath.BLE -> S.pathPeopleShort(lang)
            },
            accent = true
        )
        TrackerLine()
        TrackerNode(if (lang == "en") "Friend" else "Друг")
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
            .background(DividerColor, androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
    )
}
