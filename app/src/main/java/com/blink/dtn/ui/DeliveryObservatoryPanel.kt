package com.blink.dtn.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blink.dtn.telemetry.ObservatoryReport
import com.blink.dtn.telemetry.PeerDirectory
import com.blink.dtn.telemetry.TraceAnalyzer
import com.blink.dtn.telemetry.TraceAutoSend
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.telemetry.TraceTreeNode
import com.blink.dtn.ui.theme.AppBackgroundBrush
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel
import kotlinx.coroutines.delay

/**
 * Delivery Observatory — developer UI that reuses the same TraceAnalyzer
 * journey/timeline engine intended for the future user-facing "message voyage" UX.
 */
@Composable
fun DeliveryObservatoryPanel(viewModel: BLinkViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val peerCount by viewModel.peerCount.collectAsState()
    val pending by viewModel.pendingCount.collectAsState(0)
    val peers by viewModel.activePeers.collectAsState(emptyList())
    var autoSend by remember { mutableStateOf(TraceAutoSend.isOptedIn(context)) }
    var tab by remember { mutableStateOf("Journey") }
    val traces = remember { TraceStore.listRecent(30) }
    var selectedIdx by remember { mutableIntStateOf(0) }
    val selectedTrace = traces.getOrNull(selectedIdx)
    val report = remember(selectedTrace?.traceId) {
        selectedTrace?.let { TraceAnalyzer.analyze(it) }
    }
    var replayIndex by remember { mutableIntStateOf(-1) }
    var replaying by remember { mutableStateOf(false) }

    LaunchedEffect(replaying, report?.journey?.size) {
        if (!replaying) return@LaunchedEffect
        val steps = report?.journey ?: return@LaunchedEffect
        replayIndex = 0
        for (i in steps.indices) {
            replayIndex = i
            delay(700)
        }
        replaying = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
            }
            Text("Delivery Observatory", style = Typography.titleLarge, color = TextPrimary)
        }
        Text("Peers $peerCount · Queue $pending · traces ${traces.size}", color = TextSecondary, style = Typography.bodySmall)
        val healthSummary = remember(traces.size) { DeliveryHealthSummary.fromRecentTraces() }
        if (healthSummary.total > 0) {
            Text(
                "Delivery health ${healthSummary.successRatePct}% · доставлено ${healthSummary.delivered} · сбой ${healthSummary.failed}",
                color = TextSecondary,
                style = Typography.labelSmall
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .glassPanel(corner = 12.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            listOf("Journey", "Tree", "Route", "Timeline", "Mesh", "Heat", "Stats", "Duty").forEach { name ->
                Text(
                    name,
                    color = if (tab == name) TextPrimary else TextSecondary,
                    style = Typography.labelSmall,
                    modifier = Modifier
                        .clickable { tab = name }
                        .padding(4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 16.dp)
                .padding(12.dp)
        ) {
            if (traces.isEmpty()) {
                Text("Нет traces — отправьте сообщение.", color = TextSecondary, style = Typography.bodyMedium)
            } else {
                Text("Выбор trace:", color = TextSecondary, style = Typography.labelSmall)
                traces.take(8).forEachIndexed { idx, t ->
                    val mark = if (idx == selectedIdx) "●" else "○"
                    Text(
                        "$mark ${t.messageType ?: t.kind} ${t.messageId?.take(14) ?: t.traceId.take(8)}… → ${t.terminalStatus ?: "Pending"}",
                        color = TextSecondary,
                        style = Typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIdx = idx; replayIndex = -1 }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }

        report?.let { r ->
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 16.dp, strong = true)
                    .padding(12.dp)
            ) {
                r.diagnosis.stopReasonFirstLine?.let {
                    Text(it, color = TextPrimary, style = Typography.titleMedium)
                }
                r.diagnosis.likelyCause?.let {
                    Text("Вероятная причина: $it", color = TextSecondary, style = Typography.bodySmall)
                }
                r.diagnosis.recommendation?.let {
                    Text("Рекомендация: $it", color = TextSecondary, style = Typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(8.dp))
                HealthBlock(r)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 16.dp)
                    .padding(12.dp)
            ) {
                when (tab) {
                    "Journey" -> JourneyBlock(r, replayIndex)
                    "Tree" -> TreeBlock(r.tree)
                    "Route" -> RouteBlock(r)
                    "Timeline" -> TimelineBlock(r)
                    "Mesh" -> MeshBlock(r, viewModel)
                    "Heat" -> HeatBlock(r)
                    "Stats" -> StatsBlock(r)
                    "Duty" -> DutyBlock(viewModel)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QqButton(onClick = {
                    replaying = true
                    tab = "Journey"
                }) { Text("Replay Trace", color = TextPrimary, style = Typography.labelMedium) }
                QqButton(onClick = {
                    TraceStore.shareExport(context, peerCount, peers)
                    if (autoSend) TraceAutoSend.maybeQueueUpload(context, peerCount, peers)
                    Toast.makeText(context, "Отчёт → tuktukfb@internet.ru", Toast.LENGTH_SHORT).show()
                }) { Text("Export ZIP", color = TextPrimary, style = Typography.labelMedium) }
            }
        }
        if (report == null && tab == "Duty") {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 16.dp)
                    .padding(12.dp)
            ) {
                DutyBlock(viewModel)
            }
            Spacer(modifier = Modifier.height(12.dp))
            QqButton(onClick = {
                TraceStore.shareExport(context, peerCount, peers)
                Toast.makeText(context, "Отчёт → tuktukfb@internet.ru", Toast.LENGTH_SHORT).show()
            }) { Text("Export ZIP", color = TextPrimary, style = Typography.labelMedium) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 12.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            androidx.compose.material3.Switch(
                checked = autoSend,
                onCheckedChange = {
                    autoSend = it
                    TraceAutoSend.setOptIn(context, it)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Авто-отправка trace при интернете", color = TextSecondary, style = Typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 16.dp)
                .padding(12.dp)
        ) {
            Text("История устройств (${PeerDirectory.snapshot().size})", style = Typography.titleMedium, color = TextPrimary)
            PeerDirectory.snapshot().take(10).forEach { d ->
                Text(
                    "${d.displayName} · fwd=${d.packetsForwarded} · err=${d.errorCount}" +
                        (d.lastRssi?.let { " · RSSI $it" } ?: ""),
                    color = TextSecondary,
                    style = Typography.labelSmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HealthBlock(r: ObservatoryReport) {
    Text("Health ${r.health.overall}%  BLE ${r.health.ble} · Queue ${r.health.queue} · RSA ${r.health.rsa} · ACK ${r.health.ack}",
        color = TextSecondary, style = Typography.bodySmall)
    Spacer(modifier = Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = r.health.overall / 100f,
        modifier = Modifier.fillMaxWidth().height(6.dp)
    )
}

@Composable
private fun JourneyBlock(r: ObservatoryReport, replayIndex: Int) {
    Text("Путешествие сообщения", style = Typography.titleMedium, color = TextPrimary)
    Spacer(modifier = Modifier.height(6.dp))
    r.journey.forEachIndexed { i, step ->
        val active = replayIndex < 0 || i <= replayIndex
        Text(
            if (i < r.journey.lastIndex) "${step.emojiTitle}\n↓" else step.emojiTitle,
            color = if (active) TextPrimary else TextSecondary,
            style = Typography.bodyMedium,
            modifier = Modifier.padding(vertical = 2.dp)
        )
        Text("+${step.elapsedMs}ms", color = TextSecondary, style = Typography.labelSmall)
    }
}

@Composable
private fun TreeBlock(node: TraceTreeNode, depth: Int = 0) {
    var expanded by remember(node.id) { mutableStateOf(depth < 1) }
    val prefix = "  ".repeat(depth)
    val mark = when (node.ok) {
        true -> "✓"
        false -> "✗"
        null -> "·"
    }
    Text(
        "$prefix$mark ${node.title}  ${node.summary}" +
            (node.durationMs?.let { " (${it}ms)" } ?: ""),
        color = TextPrimary,
        style = Typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
    )
    if (expanded) {
        node.details.entries.take(6).forEach { (k, v) ->
            Text("$prefix  $k=$v", color = TextSecondary, style = Typography.labelSmall)
        }
        node.children.forEach { TreeBlock(it, depth + 1) }
    }
}

@Composable
private fun RouteBlock(r: ObservatoryReport) {
    Text("Маршрут", style = Typography.titleMedium, color = TextPrimary)
    r.route.forEachIndexed { i, hop ->
        Text(hop.label, color = TextPrimary, style = Typography.bodyMedium)
        hop.detail?.let { Text(it, color = TextSecondary, style = Typography.labelSmall) }
        hop.rssi?.let { Text("RSSI $it", color = TextSecondary, style = Typography.labelSmall) }
        if (i < r.route.lastIndex) Text("↓", color = TextSecondary, style = Typography.bodySmall)
    }
}

@Composable
private fun TimelineBlock(r: ObservatoryReport) {
    Text("Timeline", style = Typography.titleMedium, color = TextPrimary)
    r.timeline.takeLast(40).forEach { e ->
        Text("${e.clock}  ${e.summary}", color = TextPrimary, style = Typography.labelSmall)
        if (e.details.isNotEmpty()) {
            Text("  ${e.details.entries.take(4).joinToString { "${it.key}=${it.value}" }}",
                color = TextSecondary, style = Typography.labelSmall)
        }
        Text("↓", color = TextSecondary, style = Typography.labelSmall)
    }
}

@Composable
private fun MeshBlock(r: ObservatoryReport, viewModel: BLinkViewModel) {
    Text("Mesh Explorer", style = Typography.titleMedium, color = TextPrimary)
    Text("BLE — основной транспорт. Wi‑Fi Direct — экспериментально.", color = TextSecondary, style = Typography.labelSmall)
    Spacer(modifier = Modifier.height(6.dp))
    val registry = remember { viewModel.bleMeshManager.transportRegistry }
    registry?.all()?.forEach { t ->
        val peers by t.discoveredPeers.collectAsState()
        val avail by t.isAvailable.collectAsState()
        Text(
            "• ${t.displayNameRu}: ${if (avail) "доступен" else "нет"} · peers=${peers.size}",
            color = TextPrimary,
            style = Typography.bodySmall
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    r.mesh.nodes.forEach { n ->
        Text("• [${n.kind}/${n.transport}] ${n.label}", color = TextPrimary, style = Typography.bodySmall)
    }
    Spacer(modifier = Modifier.height(6.dp))
    r.mesh.edges.forEach { e ->
        Text("${e.from.take(8)} —${e.transport}→ ${e.to.take(8)}", color = TextSecondary, style = Typography.labelSmall)
    }
}

@Composable
private fun HeatBlock(r: ObservatoryReport) {
    Text("Heatmap", style = Typography.titleMedium, color = TextPrimary)
    r.heatmap.take(12).forEach { c ->
        Text("${c.label}: transfers=${c.transfers} errors=${c.errors}", color = TextSecondary, style = Typography.bodySmall)
    }
}

@Composable
private fun StatsBlock(r: ObservatoryReport) {
    Text("Статистика", style = Typography.titleMedium, color = TextPrimary)
    val s = r.statistics
    listOf(
        "UTF8 bytes" to s.utf8Bytes,
        "Length" to s.messageLength,
        "Chunks" to s.chunks,
        "Relay count" to s.relayCount,
        "Peer touches" to s.peerTouches,
        "Avg RSSI" to s.averageRssi?.toInt(),
        "Max RSSI" to s.maxRssi,
        "Min RSSI" to s.minRssi,
        "Encrypt ms" to s.encryptionMs,
        "DB ms" to s.dbMs,
        "Queue ms" to s.queueMs,
        "BLE ms" to s.bleMs,
        "Delivery ms" to s.deliveryMs,
        "ACK latency" to s.ackLatencyMs
    ).forEach { (k, v) ->
        Text("$k: ${v ?: "—"}", color = TextSecondary, style = Typography.bodySmall)
    }
}

@Composable
private fun DutyBlock(viewModel: BLinkViewModel) {
    val duty by com.blink.dtn.telemetry.MeshDutyTelemetry.snapshot.collectAsState()
    val preset by com.blink.dtn.ble.MeshDutyPrefs.preset.collectAsState()
    val health = remember(duty.writeAttempts, duty.sessionMs) {
        DeliveryHealthSummary.fromRecentTraces()
    }
    val budget = remember(duty.writeAttempts, duty.budgetDownshifts) {
        viewModel.bleMeshManager.writeBudgetSnapshot()
    }
    val context = LocalContext.current
    Text("Нагрузка / батарея", style = Typography.titleMedium, color = TextPrimary)
    Text(
        "Сессия ${(duty.sessionMs / 60_000)} мин · режим ${preset.labelRu}",
        color = TextSecondary,
        style = Typography.labelSmall
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text("Пресет сети", style = Typography.labelMedium, color = TextPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        com.blink.dtn.ble.MeshDutyPreset.entries.forEach { p ->
            Text(
                p.labelRu,
                color = if (preset == p) TextPrimary else TextSecondary,
                style = Typography.labelSmall,
                modifier = Modifier
                    .clickable {
                        viewModel.setDutyPreset(p)
                        Toast.makeText(context, "Режим: ${p.labelRu}", Toast.LENGTH_SHORT).show()
                    }
                    .padding(4.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Здоровье доставки: ${health.successRatePct}% · ок ${health.delivered} · сбой ${health.failed} · в пути ${health.pending} (из ${health.total} следов)",
        color = TextPrimary,
        style = Typography.bodySmall
    )
    Spacer(modifier = Modifier.height(8.dp))
    val bat = duty.batterySamples.lastOrNull()
    Text(
        "Батарея: ${bat?.pct?.let { "$it%" } ?: "—"}" +
            (if (bat?.charging == true) " (зарядка)" else "") +
            (duty.batteryDrainPct?.let { " · спад сессии −$it%" } ?: ""),
        color = TextPrimary,
        style = Typography.bodyMedium
    )
    if (duty.batterySamples.size >= 2) {
        val spark = duty.batterySamples.takeLast(24).joinToString("") { s ->
            when {
                s.pct >= 80 -> "█"
                s.pct >= 60 -> "▇"
                s.pct >= 40 -> "▅"
                s.pct >= 20 -> "▃"
                else -> "▁"
            }
        }
        Text("Заряд: $spark", color = TextSecondary, style = Typography.labelSmall)
    }
    Spacer(modifier = Modifier.height(8.dp))
    listOf(
        "GATT write попыток" to duty.writeAttempts,
        "успешных" to duty.writeSuccesses,
        "ошибок" to duty.writeFailures,
        "байт (успех)" to duty.bytesSucceeded,
        "байт/мин" to "%.0f".format(duty.bytesPerMinute()),
        "GATT connect start/ok/fail" to "${duty.gattConnectStarts}/${duty.gattConnectOk}/${duty.gattConnectFail}",
        "бюджет downshift" to duty.budgetDownshifts,
        "успех write %" to "%.0f%%".format(duty.writeSuccessRate() * 100)
    ).forEach { (k, v) ->
        Text("$k: $v", color = TextSecondary, style = Typography.bodySmall)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text("BleWriteBudget peer caps", style = Typography.labelMedium, color = TextPrimary)
    if (budget.peerCaps.isEmpty()) {
        Text("Пока без downshift (все peers на MTU/512).", color = TextSecondary, style = Typography.labelSmall)
    } else {
        budget.peerCaps.entries.take(12).forEach { (mac, cap) ->
            Text("$mac → max $cap B", color = TextSecondary, style = Typography.labelSmall)
        }
    }
    if (duty.recentDownshifts.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text("Недавние downshift", style = Typography.labelMedium, color = TextPrimary)
        duty.recentDownshifts.takeLast(6).forEach { e ->
            Text(
                "${e.address.takeLast(8)} ${e.fromBytes}→${e.toBytes} (${e.reason})",
                color = TextSecondary,
                style = Typography.labelSmall
            )
        }
    }
}
