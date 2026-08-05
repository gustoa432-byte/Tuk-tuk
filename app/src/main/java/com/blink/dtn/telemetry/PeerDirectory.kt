package com.blink.dtn.telemetry

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent sightings of mesh peers (MAC / node id → human label).
 * Cap + TTL + debounced disk writes to bound flash/battery use.
 */
object PeerDirectory {
    private const val MAX_PEERS = 400
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val PERSIST_DEBOUNCE_MS = 5_000L

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val peers = ConcurrentHashMap<String, DeviceHistoryEntry>()
    private var file: File? = null
    private val dirty = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { flushPersist() }

    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.applicationContext.filesDir, "traces").also { it.mkdirs() }
        file = File(dir, "peer_directory.json")
        load()
        pruneExpired()
    }

    fun labelFor(id: String): String {
        val name = get(id)?.displayName?.trim().orEmpty()
        if (name.isNotEmpty() && !name.startsWith("Node ", ignoreCase = true) && !looksLikeMac(name)) {
            return name
        }
        return "" // UI maps empty → Neighbor / Friend — never show Node/MAC/UUID
    }

    fun humanLabel(id: String, lang: String = "ru"): String {
        val raw = labelFor(id)
        if (raw.isNotEmpty()) return raw
        return if (lang == "en") "Neighbor" else "Сосед"
    }

    private fun looksLikeMac(s: String): Boolean =
        s.contains(':') && s.length >= 11

    fun get(id: String): DeviceHistoryEntry? = peers[id]

    fun snapshot(): List<DeviceHistoryEntry> = peers.values.sortedByDescending { it.lastSeen }

    /** Recent human-facing neighbors (no MAC / Node ids in display). */
    fun liveNeighbors(limit: Int = 16): List<Pair<String, String>> {
        return snapshot()
            .asSequence()
            .filter { entry ->
                val ageOk = System.currentTimeMillis() - entry.lastSeen < 15 * 60_000L
                val name = entry.displayName
                ageOk && name.isNotBlank() && !name.startsWith("Node ", ignoreCase = true) && !looksLikeMac(name)
            }
            .take(limit)
            .map { it.nodeId to it.displayName }
            .toList()
    }

    fun noteBleDevice(device: BluetoothDevice, rssi: Int? = null) {
        val id = device.address
        val name = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias ?: device.name else device.name
        } catch (_: SecurityException) {
            device.name
        } ?: id
        upsert(
            id = id,
            device = name,
            manufacturer = null,
            android = null,
            rssi = rssi,
            forwarded = false
        )
    }

    fun noteNode(nodeId: String, nick: String?, modelHint: String? = null) {
        upsert(
            id = nodeId,
            device = modelHint ?: nick ?: short(nodeId),
            manufacturer = null,
            android = null,
            rssi = null,
            forwarded = false
        )
    }

    fun noteForward(id: String, delayMs: Long? = null) {
        val now = System.currentTimeMillis()
        val existing = peers[id]
        if (existing == null) {
            peers[id] = DeviceHistoryEntry(
                nodeId = id,
                device = short(id),
                firstSeen = now,
                lastSeen = now,
                packetsForwarded = 1,
                averageDelayMs = delayMs
            )
        } else {
            val n = existing.packetsForwarded + 1
            val avg = when {
                delayMs == null -> existing.averageDelayMs
                existing.averageDelayMs == null -> delayMs
                else -> (existing.averageDelayMs * (n - 1) + delayMs) / n
            }
            peers[id] = existing.copy(
                lastSeen = now,
                packetsForwarded = n,
                averageDelayMs = avg
            )
        }
        schedulePersist()
    }

    fun noteError(id: String) {
        val now = System.currentTimeMillis()
        val existing = peers[id]
        peers[id] = (existing ?: DeviceHistoryEntry(id, short(id), firstSeen = now, lastSeen = now))
            .copy(lastSeen = now, errorCount = (existing?.errorCount ?: 0) + 1)
        schedulePersist()
    }

    private fun upsert(
        id: String,
        device: String,
        manufacturer: String?,
        android: String?,
        rssi: Int?,
        forwarded: Boolean
    ) {
        val now = System.currentTimeMillis()
        val existing = peers[id]
        peers[id] = if (existing == null) {
            DeviceHistoryEntry(
                nodeId = id,
                device = device,
                manufacturer = manufacturer,
                android = android,
                lastRssi = rssi,
                firstSeen = now,
                lastSeen = now,
                packetsForwarded = if (forwarded) 1 else 0
            )
        } else {
            existing.copy(
                device = if (device != short(id)) device else existing.device,
                manufacturer = manufacturer ?: existing.manufacturer,
                android = android ?: existing.android,
                lastRssi = rssi ?: existing.lastRssi,
                lastSeen = now,
                packetsForwarded = existing.packetsForwarded + if (forwarded) 1 else 0
            )
        }
        pruneIfNeeded()
        schedulePersist()
    }

    private fun short(id: String) = "peer"

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { now - it.value.lastSeen > TTL_MS }
        pruneIfNeeded()
    }

    private fun pruneIfNeeded() {
        if (peers.size <= MAX_PEERS) return
        val overflow = peers.size - MAX_PEERS
        peers.values.sortedBy { it.lastSeen }.take(overflow).forEach { peers.remove(it.nodeId) }
    }

    private fun schedulePersist() {
        dirty.set(true)
        handler.removeCallbacks(persistRunnable)
        handler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun flushPersist() {
        if (!dirty.compareAndSet(true, false)) return
        persist()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val list = json.decodeFromString<List<DeviceHistoryEntry>>(f.readText())
            list.forEach { peers[it.nodeId] = it }
        }
    }

    private fun persist() {
        val f = file ?: return
        runCatching { f.writeText(json.encodeToString(peers.values.toList())) }
    }
}
