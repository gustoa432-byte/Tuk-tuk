package com.blink.dtn.telemetry

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transport-agnostic delivery observatory built ON TOP of flat TraceEvent lists.
 * Tomorrow LoRa / VK / Wi‑Fi Direct just emit new TraceStages.* events — this
 * analyzer picks them up without rewriting the store.
 */

@Serializable
data class TraceTreeNode(
    val id: String,
    val title: String,
    val summary: String = "",
    val details: Map<String, String> = emptyMap(),
    val children: List<TraceTreeNode> = emptyList(),
    val eventCount: Int = 0,
    val durationMs: Long? = null,
    val ok: Boolean? = null
)

@Serializable
data class RouteHop(
    val label: String,
    val nodeId: String? = null,
    val transport: String = "BLE", // BLE | LoRa | VK | WiFi | Internet | Local
    val detail: String? = null,
    val rssi: Int? = null
)

@Serializable
data class JourneyStep(
    val emojiTitle: String,
    val elapsedMs: Long,
    val timestamp: Long,
    val stage: String? = null
)

@Serializable
data class TimelineEntry(
    val timestamp: Long,
    val clock: String,
    val elapsedMs: Long,
    val stage: String,
    val summary: String,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class MessageStatistics(
    val utf8Bytes: Int? = null,
    val messageLength: Int? = null,
    val chunks: Int? = null,
    val relayCount: Int = 0,
    val averageRssi: Double? = null,
    val maxRssi: Int? = null,
    val minRssi: Int? = null,
    val encryptionMs: Long? = null,
    val dbMs: Long? = null,
    val queueMs: Long? = null,
    val bleMs: Long? = null,
    val deliveryMs: Long? = null,
    val ackLatencyMs: Long? = null,
    val peerTouches: Int = 0
)

@Serializable
data class DeviceHistoryEntry(
    val nodeId: String,
    val device: String,
    val android: String? = null,
    val manufacturer: String? = null,
    val lastRssi: Int? = null,
    val firstSeen: Long,
    val lastSeen: Long,
    val packetsForwarded: Int = 0,
    val averageDelayMs: Long? = null,
    val errorCount: Int = 0
) {
    val displayName: String get() = device.ifBlank { "Node ${nodeId.takeLast(4)}" }
}

@Serializable
data class MeshGraphNode(
    val id: String,
    val label: String,
    val kind: String = "phone", // phone | lora | vk | wifi | internet
    val transport: String = "BLE"
)

@Serializable
data class MeshGraphEdge(
    val from: String,
    val to: String,
    val transport: String = "BLE",
    val weight: Int = 1,
    val errors: Int = 0
)

@Serializable
data class MeshGraph(
    val nodes: List<MeshGraphNode>,
    val edges: List<MeshGraphEdge>
)

@Serializable
data class HeatCell(
    val key: String,
    val label: String,
    val transfers: Int,
    val errors: Int
)

@Serializable
data class HealthScore(
    val overall: Int,
    val ble: Int,
    val queue: Int,
    val rsa: Int,
    val ack: Int,
    val notes: List<String> = emptyList()
)

@Serializable
data class Diagnosis(
    val stopReason: String?,
    val stopReasonFirstLine: String?,
    val likelyCause: String?,
    val recommendation: String?
)

@Serializable
data class ObservatoryReport(
    val traceId: String,
    val messageId: String?,
    val messageType: String?,
    val terminalStatus: String?,
    val tree: TraceTreeNode,
    val route: List<RouteHop>,
    val journey: List<JourneyStep>,
    val timeline: List<TimelineEntry>,
    val statistics: MessageStatistics,
    val devices: List<DeviceHistoryEntry>,
    val mesh: MeshGraph,
    val heatmap: List<HeatCell>,
    val health: HealthScore,
    val diagnosis: Diagnosis
)

object TraceAnalyzer {
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun analyze(trace: MessageTrace, peerDirectory: PeerDirectory = PeerDirectory): ObservatoryReport {
        val events = trace.events.toList()
        val timeline = buildTimeline(events)
        val stats = buildStatistics(trace, events)
        val diagnosis = diagnose(trace, events)
        val health = healthScore(trace, events, diagnosis)
        return ObservatoryReport(
            traceId = trace.traceId,
            messageId = trace.messageId,
            messageType = trace.messageType,
            terminalStatus = trace.terminalStatus,
            tree = buildTree(trace, events),
            route = buildRoute(trace, events, peerDirectory),
            journey = buildJourney(trace, events),
            timeline = timeline,
            statistics = stats,
            devices = peerDirectory.snapshot() + devicesFromEvents(events, peerDirectory),
            mesh = buildMeshGraph(trace, events, peerDirectory),
            heatmap = buildHeatmap(events),
            health = health,
            diagnosis = diagnosis
        )
    }

    fun analyzeAll(traces: List<MessageTrace>): List<ObservatoryReport> =
        traces.map { analyze(it) }

    private fun buildTimeline(events: List<TraceEvent>): List<TimelineEntry> =
        events.map { e ->
            TimelineEntry(
                timestamp = e.timestamp,
                clock = clockFmt.format(Date(e.timestamp)),
                elapsedMs = e.elapsedFromStartMs,
                stage = e.stage,
                summary = humanStage(e.stage),
                details = e.details
            )
        }

    private fun buildTree(trace: MessageTrace, events: List<TraceEvent>): TraceTreeNode {
        fun branch(id: String, title: String, prefixes: List<String>): TraceTreeNode {
            val matched = events.filter { ev -> prefixes.any { ev.stage.startsWith(it) || ev.stage == it } }
            val start = matched.minOfOrNull { it.timestamp }
            val end = matched.maxOfOrNull { it.timestamp }
            val failed = matched.any { it.stage.contains("Fail", ignoreCase = true) || it.stage.contains("Missing", ignoreCase = true) }
            val ok = when {
                matched.isEmpty() -> null
                failed -> false
                else -> true
            }
            val kids = when (id) {
                "ble" -> {
                    val peers = matched.mapNotNull { it.details["peer"] }.distinct()
                    peers.map { peer ->
                        val peerEvents = matched.filter { it.details["peer"] == peer }
                        TraceTreeNode(
                            id = "ble.$peer",
                            title = PeerDirectory.labelFor(peer),
                            summary = "${peerEvents.size} events",
                            details = peerEvents.lastOrNull()?.details ?: emptyMap(),
                            eventCount = peerEvents.size,
                            ok = peerEvents.none { it.stage.contains("Fail") }
                        )
                    }
                }
                else -> emptyList()
            }
            return TraceTreeNode(
                id = id,
                title = title,
                summary = if (matched.isEmpty()) "—" else "${matched.size} events",
                details = matched.lastOrNull()?.details ?: emptyMap(),
                children = kids,
                eventCount = matched.size,
                durationMs = if (start != null && end != null) end - start else null,
                ok = ok
            )
        }

        return TraceTreeNode(
            id = "message",
            title = "📦 Message",
            summary = trace.terminalStatus ?: "Pending",
            details = mapOf(
                "traceId" to trace.traceId,
                "messageId" to (trace.messageId ?: ""),
                "type" to (trace.messageType ?: "")
            ),
            children = listOf(
                branch("ui", "UI", listOf("UI.")),
                branch("db", "Database", listOf("DB.")),
                branch("rsa", "RSA", listOf("RSA.")),
                branch("queue", "Queue", listOf("Queue.")),
                branch("chunk", "Chunking", listOf("Chunk.")),
                branch("ble", "BLE / Transport", listOf("BLE.", "GATT.", "TX.", "LoRa.", "VK.", "WiFi.")),
                branch("relay", "Relay / Mesh", listOf("Relay.", "Mesh.")),
                branch("identity", "Identity", listOf("Identity.")),
                branch("rx", "Receiver", listOf("RX.")),
                branch("ack", "ACK", listOf("ACK.")),
                branch("done", "Completed", listOf("Done"))
            ),
            eventCount = events.size,
            durationMs = (trace.finishedAt ?: System.currentTimeMillis()) - trace.startedAt,
            ok = when (trace.terminalStatus) {
                "Delivered", "Sent" -> true
                null, "Pending" -> null
                else -> false
            }
        )
    }

    private fun buildRoute(
        trace: MessageTrace,
        events: List<TraceEvent>,
        peers: PeerDirectory
    ): List<RouteHop> {
        val hops = mutableListOf<RouteHop>()
        hops += RouteHop(label = "Я", nodeId = trace.senderId, transport = "Local", detail = "origin")

        events.filter { it.stage == TraceStages.GATT_READY || it.stage == TraceStages.GATT_WRITE_DONE || it.stage == TraceStages.MESH_FORWARD }
            .forEach { ev ->
                val peer = ev.details["peer"] ?: ev.details["viaNode"]
                if (peer != null && hops.none { it.nodeId == peer }) {
                    val info = peers.get(peer)
                    hops += RouteHop(
                        label = info?.displayName ?: shortNode(peer),
                        nodeId = peer,
                        transport = transportOf(ev.stage),
                        detail = info?.let { listOfNotNull(it.device, it.android?.let { a -> "Android $a" }).joinToString(" · ") },
                        rssi = ev.details["rssi"]?.toIntOrNull() ?: info?.lastRssi
                    )
                }
            }

        events.filter { it.stage == TraceStages.MESH_RELAY_STORE || it.stage == TraceStages.MESH_FORWARD }
            .forEach { ev ->
                val via = ev.details["viaNode"]
                if (via != null && hops.none { it.nodeId == via }) {
                    hops += RouteHop(label = shortNode(via), nodeId = via, transport = "BLE", detail = "relay")
                }
            }

        if (trace.targetId != null) {
            hops += RouteHop(
                label = if (events.any { it.stage == TraceStages.ACK_RECEIVED || it.stage == TraceStages.RSA_DECRYPT_DONE })
                    "Получатель" else "Получатель (ожидание)",
                nodeId = trace.targetId,
                transport = "BLE",
                detail = peers.get(trace.targetId!!)?.displayName
            )
        } else if (trace.messageType == "PUBLIC") {
            hops += RouteHop(label = "Broadcast", transport = "BLE")
        }
        return hops
    }

    private fun buildJourney(trace: MessageTrace, events: List<TraceEvent>): List<JourneyStep> {
        val steps = mutableListOf<JourneyStep>()
        fun add(stageMatch: (TraceEvent) -> Boolean, title: String) {
            val e = events.firstOrNull(stageMatch) ?: return
            steps += JourneyStep(title, e.elapsedFromStartMs, e.timestamp, e.stage)
        }
        add({ it.stage == TraceStages.UI_SEND_PRESSED }, "📦 Упаковано")
        add({ it.stage == TraceStages.QUEUE_ADDED }, "🌱 Началось распыление")
        add({ it.stage == TraceStages.BLE_PEERS }, "👥 Нашло устройства")
        add({ it.stage == TraceStages.GATT_READY || it.stage == TraceStages.GATT_WRITE_START }, "🚶 Передано соседу")
        add({ it.stage == TraceStages.MESH_FORWARD || it.stage == TraceStages.MESH_RELAY_STORE }, "🚲 Передано дальше")
        add({ it.stage == TraceStages.TX_BATCH_RESULT }, "🌉 Покинул район")
        add({ it.stage == TraceStages.RX_PACKET }, "📍 Получено")
        add({ it.stage == TraceStages.RSA_DECRYPT_DONE }, "🔓 Расшифровано")
        add({ it.stage == TraceStages.ACK_RECEIVED || it.stage == TraceStages.DONE }, "✅ Доставлено")

        // Prefer recorded visualSteps if richer
        if (steps.isEmpty() && trace.visualSteps.isNotEmpty()) {
            return trace.visualSteps.mapIndexed { i, s ->
                JourneyStep(s.substringBefore(" (+"), events.getOrNull(i)?.elapsedFromStartMs ?: 0L, events.getOrNull(i)?.timestamp ?: trace.startedAt)
            }
        }
        return steps.distinctBy { it.emojiTitle }
    }

    private fun buildStatistics(trace: MessageTrace, events: List<TraceEvent>): MessageStatistics {
        fun detail(stage: String, key: String) =
            events.lastOrNull { it.stage == stage }?.details?.get(key)

        fun durationBetween(startStage: String, endStage: String): Long? {
            val a = events.firstOrNull { it.stage == startStage }?.timestamp ?: return null
            val b = events.firstOrNull { it.stage == endStage }?.timestamp ?: return null
            return (b - a).coerceAtLeast(0)
        }

        val rssi = events.mapNotNull { it.details["rssi"]?.toIntOrNull() }
        val peers = events.mapNotNull { it.details["peer"] }.distinct()

        return MessageStatistics(
            utf8Bytes = detail(TraceStages.UI_SEND_PRESSED, "utf8Bytes")?.toIntOrNull(),
            messageLength = detail(TraceStages.UI_SEND_PRESSED, "messageLength")?.toIntOrNull(),
            chunks = detail(TraceStages.CHUNK_ENCODE, "chunksCount")?.toIntOrNull(),
            relayCount = events.count { it.stage == TraceStages.MESH_FORWARD || it.stage == TraceStages.MESH_RELAY_STORE },
            averageRssi = rssi.takeIf { it.isNotEmpty() }?.average(),
            maxRssi = rssi.maxOrNull(),
            minRssi = rssi.minOrNull(),
            encryptionMs = detail(TraceStages.RSA_ENCRYPT_DONE, "encryptionDurationMs")?.toLongOrNull()
                ?: durationBetween(TraceStages.RSA_ENCRYPT_START, TraceStages.RSA_ENCRYPT_DONE),
            dbMs = detail(TraceStages.DB_INSERT_DONE, "insertDurationMs")?.toLongOrNull()
                ?: durationBetween(TraceStages.DB_INSERT_START, TraceStages.DB_INSERT_DONE),
            queueMs = durationBetween(TraceStages.QUEUE_ADDED, TraceStages.RELAY_PROCESS),
            bleMs = durationBetween(TraceStages.GATT_WRITE_START, TraceStages.TX_BATCH_RESULT)
                ?: durationBetween(TraceStages.GATT_READY, TraceStages.GATT_WRITE_DONE),
            deliveryMs = trace.finishedAt?.let { it - trace.startedAt }
                ?: events.lastOrNull()?.elapsedFromStartMs,
            ackLatencyMs = detail(TraceStages.ACK_RECEIVED, "latencyMs")?.toLongOrNull(),
            peerTouches = peers.size
        )
    }

    private fun devicesFromEvents(events: List<TraceEvent>, peers: PeerDirectory): List<DeviceHistoryEntry> {
        val ids = events.mapNotNull { it.details["peer"] ?: it.details["viaNode"] }.distinct()
        return ids.map { id ->
            peers.get(id) ?: DeviceHistoryEntry(
                nodeId = id,
                device = shortNode(id),
                firstSeen = events.first().timestamp,
                lastSeen = events.last().timestamp,
                packetsForwarded = events.count { it.details["peer"] == id || it.details["viaNode"] == id }
            )
        }
    }

    private fun buildMeshGraph(
        trace: MessageTrace,
        events: List<TraceEvent>,
        peers: PeerDirectory
    ): MeshGraph {
        val nodes = linkedMapOf<String, MeshGraphNode>()
        val edges = mutableListOf<MeshGraphEdge>()
        val me = trace.senderId ?: "me"
        nodes[me] = MeshGraphNode(me, "Я", "phone", "Local")

        var prev = me
        buildRoute(trace, events, peers).drop(1).forEach { hop ->
            val id = hop.nodeId ?: hop.label
            nodes.putIfAbsent(id, MeshGraphNode(id, hop.label, kindFor(hop.transport), hop.transport))
            edges += MeshGraphEdge(prev, id, hop.transport)
            prev = id
        }

        // Future transports appear as soon as stages are logged
        events.filter { it.stage.startsWith("LoRa.") || it.stage.startsWith("VK.") || it.stage.startsWith("WiFi.") }
            .forEach { ev ->
                val transport = transportOf(ev.stage)
                val id = ev.details["peer"] ?: transport
                nodes.putIfAbsent(id, MeshGraphNode(id, id, kindFor(transport), transport))
            }

        return MeshGraph(nodes.values.toList(), edges)
    }

    private fun buildHeatmap(events: List<TraceEvent>): List<HeatCell> {
        val byPeer = events.mapNotNull { it.details["peer"] }.groupingBy { it }.eachCount()
        val errors = events.filter { it.stage.contains("Fail") }
            .mapNotNull { it.details["peer"] }
            .groupingBy { it }
            .eachCount()
        val peerCells = byPeer.map { (peer, n) ->
            HeatCell(peer, PeerDirectory.labelFor(peer), n, errors[peer] ?: 0)
        }
        val stageCells = events.groupingBy { it.stage.substringBefore('.') }
            .eachCount()
            .map { (k, n) ->
                HeatCell("stage.$k", k, n, events.count { it.stage.startsWith(k) && it.stage.contains("Fail") })
            }
        return (peerCells + stageCells).sortedByDescending { it.transfers + it.errors * 2 }
    }

    private fun diagnose(trace: MessageTrace, events: List<TraceEvent>): Diagnosis {
        val fail = events.lastOrNull {
            it.stage.contains("Fail") ||
                it.stage == TraceStages.RSA_MISSING_KEY ||
                it.stage == TraceStages.MESH_SKIP
        }
        val reason = when {
            events.any { it.stage == TraceStages.RSA_MISSING_KEY } -> "Public key missing"
            events.any { it.stage == TraceStages.RSA_ENCRYPT_FAIL } -> "Encryption failed"
            events.any { it.stage == TraceStages.RSA_DECRYPT_FAIL } -> "Decrypt failed"
            events.any { it.stage == TraceStages.GATT_WRITE_FAIL } -> "WriteCharacteristic failed"
            events.any { it.stage == TraceStages.BLE_PEERS && (it.details["peersCount"]?.toIntOrNull() ?: 0) == 0 } -> "No peers"
            trace.terminalStatus == "Timeout" || events.any { it.stage == TraceStages.TX_BATCH_RESULT && it.details["result"] == "Failure" } -> "ACK timeout / TX failed"
            trace.terminalStatus == "Expired" -> "TTL / time expired"
            trace.terminalStatus == "Dropped" -> fail?.details?.get("failureReason") ?: "Dropped"
            trace.terminalStatus in listOf("Delivered", "Sent") -> null
            else -> trace.terminalStatus
        }
        val cause = when (reason) {
            "Public key missing" -> "Public Key missing / rotation"
            "Decrypt failed" -> "Public Key Rotation (stale key on sender)"
            "WriteCharacteristic failed" -> "Android BLE stack / link instability"
            "No peers" -> "Recipient / mesh neighborhood empty"
            "ACK timeout / TX failed" -> "Recipient disappeared or link loss"
            else -> reason?.let { "See stage details: $it" }
        }
        val recommendation = when (reason) {
            "Public key missing" -> "Дождаться IDENTITY_ANNOUNCEMENT или пересканировать QR контакта"
            "Decrypt failed" -> "Переустановка пира — запросить новый ключ (уже auto-rekey)"
            "WriteCharacteristic failed" -> "Проверить BLE permissions, battery optimization, расстояние"
            "No peers" -> "Подойти ближе / включить Bluetooth на соседях"
            "ACK timeout / TX failed" -> "Повторить отправку при живых пирах"
            else -> null
        }
        val firstLine = reason?.let { "❌ Delivery stopped — $it" }
            ?: if (trace.terminalStatus in listOf("Delivered", "Sent")) "✅ Delivery ok" else null
        return Diagnosis(reason, firstLine, cause, recommendation)
    }

    private fun healthScore(trace: MessageTrace, events: List<TraceEvent>, d: Diagnosis): HealthScore {
        fun score(ok: Boolean, partial: Boolean = false) = when {
            ok -> 100
            partial -> 50
            else -> 0
        }
        val rsaOk = events.any { it.stage == TraceStages.RSA_ENCRYPT_DONE || it.stage == TraceStages.RSA_DECRYPT_DONE } ||
            trace.messageType == "PUBLIC" || events.none { it.stage.startsWith("RSA.") }
        val rsaFail = events.any { it.stage.contains("RSA.") && (it.stage.contains("Fail") || it.stage.contains("Missing")) }
        val queueOk = events.any { it.stage == TraceStages.QUEUE_ADDED }
        val bleOk = events.any { it.stage == TraceStages.GATT_WRITE_DONE || it.stage == TraceStages.TX_BATCH_RESULT }
        val bleFail = events.any { it.stage == TraceStages.GATT_WRITE_FAIL }
        val ackOk = events.any { it.stage == TraceStages.ACK_RECEIVED } ||
            (trace.messageType == "PUBLIC" && trace.terminalStatus != null)
        val ackFail = d.stopReason?.contains("ACK") == true || d.stopReason?.contains("timeout") == true

        val ble = when {
            bleOk && !bleFail -> 98
            bleOk && bleFail -> 60
            bleFail -> 20
            else -> 40
        }
        val queue = if (queueOk) 100 else 30
        val rsa = when {
            rsaFail -> 0
            rsaOk -> 100
            else -> 70
        }
        val ack = when {
            ackOk -> 100
            ackFail -> 0
            trace.terminalStatus == "Pending" || trace.terminalStatus == null -> 40
            else -> 20
        }
        val overall = listOf(ble, queue, rsa, ack).average().toInt()
        return HealthScore(overall, ble, queue, rsa, ack, listOfNotNull(d.likelyCause))
    }

    private fun humanStage(stage: String): String = when (stage) {
        TraceStages.UI_SEND_PRESSED -> "Send pressed"
        TraceStages.RSA_ENCRYPT_START -> "RSA started"
        TraceStages.RSA_ENCRYPT_DONE -> "RSA finished"
        TraceStages.CHUNK_ENCODE -> "Chunking"
        TraceStages.QUEUE_ADDED -> "Queue"
        TraceStages.GATT_WRITE_START -> "BLE write"
        TraceStages.ACK_RECEIVED -> "ACK received"
        else -> stage
    }

    private fun transportOf(stage: String): String = when {
        stage.startsWith("LoRa.") -> "LoRa"
        stage.startsWith("VK.") -> "VK"
        stage.startsWith("WiFi.") -> "WiFi"
        stage.startsWith("Internet.") -> "Internet"
        else -> "BLE"
    }

    private fun kindFor(transport: String): String = when (transport) {
        "LoRa" -> "lora"
        "VK" -> "vk"
        "WiFi" -> "wifi"
        "Internet" -> "internet"
        "Local" -> "phone"
        else -> "phone"
    }

    private fun shortNode(id: String): String =
        if (id.length <= 8) "Node $id" else "Node ${id.takeLast(4)}"
}
