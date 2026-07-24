package com.blink.dtn.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * In-app black-box for message / identity delivery.
 * Independent of Logcat: events live in memory + durable JSON under filesDir/traces/.
 */
object TraceStore {
    private const val TAG = "MessageTrace"
    private const val MAX_ACTIVE = 256
    private const val MAX_ON_DISK = 200

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val byTraceId = ConcurrentHashMap<String, MessageTrace>()
    private val messageIdIndex = ConcurrentHashMap<String, String>() // messageId -> traceId

    private var appContext: Context? = null
    private var tracesDir: File? = null

    private val _recentVisual = MutableStateFlow<List<String>>(emptyList())
    val recentVisual: StateFlow<List<String>> = _recentVisual.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            tracesDir = File(context.applicationContext.filesDir, "traces").also { it.mkdirs() }
        }
    }

    fun begin(
        kind: TraceKind = TraceKind.MESSAGE,
        messageId: String? = null,
        conversationId: String? = null,
        targetId: String? = null,
        senderId: String? = null,
        messageType: String? = null
    ): MessageTrace {
        val trace = MessageTrace(
            traceId = UUID.randomUUID().toString(),
            kind = kind,
            messageId = messageId,
            conversationId = conversationId,
            targetId = targetId,
            senderId = senderId,
            messageType = messageType
        )
        byTraceId[trace.traceId] = trace
        if (messageId != null) messageIdIndex[messageId] = trace.traceId
        trimActive()
        persist(trace)
        return trace
    }

    fun attachMessageId(traceId: String, messageId: String) {
        val t = byTraceId[traceId] ?: return
        t.messageId = messageId
        messageIdIndex[messageId] = traceId
        persist(t)
    }

    fun stage(
        key: String,
        stage: String,
        details: Map<String, String> = emptyMap(),
        visual: String? = null
    ) {
        val t = resolve(key) ?: return
        synchronized(t) {
            val now = System.currentTimeMillis()
            t.events.add(
                TraceEvent(
                    timestamp = now,
                    elapsedFromStartMs = now - t.startedAt,
                    stage = stage,
                    details = details
                )
            )
            if (visual != null) {
                t.visualSteps.add("$visual (+${now - t.startedAt}ms)")
                _recentVisual.value = t.visualSteps.toList()
            }
        }
        persist(t)
        Log.d(TAG, "trace=${t.traceId.take(8)} msg=${t.messageId} $stage $details")
    }

    fun finish(key: String, status: String, details: Map<String, String> = emptyMap()) {
        val t = resolve(key) ?: return
        stage(key, TraceStages.DONE, details + mapOf("status" to status), visual = terminalVisual(status))
        t.terminalStatus = status
        t.finishedAt = System.currentTimeMillis()
        persist(t)
    }

    fun getByMessageId(messageId: String): MessageTrace? =
        messageIdIndex[messageId]?.let { byTraceId[it] } ?: loadFromDiskByMessageId(messageId)

    fun getByTraceId(traceId: String): MessageTrace? = byTraceId[traceId] ?: loadFromDisk(traceId)

    fun listRecent(limit: Int = 50): List<MessageTrace> {
        val mem = byTraceId.values.sortedByDescending { it.startedAt }
        if (mem.size >= limit) return mem.take(limit)
        val disk = (tracesDir?.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { runCatching { json.decodeFromString<MessageTrace>(it.readText()) }.getOrNull() }
        val merged = (mem + disk).distinctBy { it.traceId }.sortedByDescending { it.startedAt }
        return merged.take(limit)
    }

    /**
     * Write a zip under cacheDir and return the shareable File, or null on failure.
     */
    fun exportZip(context: Context, peerCount: Int = 0, peers: List<String> = emptyList()): File? {
        init(context)
        return try {
            val stamp = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.US).format(Date())
            val outFile = File(context.cacheDir, "trace_$stamp.zip")
            val traces = listRecent(100)
            val bundle = TraceExportBundle(
                exportedAt = System.currentTimeMillis(),
                appVersion = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
                }.getOrDefault("1.0"),
                device = captureDevice(context),
                mesh = MeshSnapshot(peerCount, peers),
                traces = traces
            )
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
                zos.putNextEntry(ZipEntry("export.json"))
                zos.write(json.encodeToString(bundle).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                traces.forEach { t ->
                    zos.putNextEntry(ZipEntry("traces/${t.traceId}.json"))
                    zos.write(json.encodeToString(t).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("device.json"))
                zos.write(json.encodeToString(bundle.device).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "exportZip failed: ${e.message}", e)
            null
        }
    }

    fun shareExport(context: Context, peerCount: Int, peers: List<String>) {
        val file = exportZip(context, peerCount, peers) ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TukTuk MessageTrace $file.name")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export Trace").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun captureDevice(context: Context): DeviceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val batIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return DeviceSnapshot(
            brand = Build.BRAND,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            bluetoothLeSupported = context.packageManager.hasSystemFeature("android.hardware.bluetooth_le"),
            totalRamMb = mem.totalMem / (1024 * 1024),
            availRamMb = mem.availMem / (1024 * 1024),
            batteryPct = pct,
            isPowerSave = pm?.isPowerSaveMode
        )
    }

    private fun resolve(key: String): MessageTrace? =
        byTraceId[key]
            ?: messageIdIndex[key]?.let { byTraceId[it] }
            ?: loadFromDisk(key)
            ?: loadFromDiskByMessageId(key)?.also { byTraceId[it.traceId] = it }

    private fun persist(t: MessageTrace) {
        val dir = tracesDir ?: return
        try {
            File(dir, "${t.traceId}.json").writeText(json.encodeToString(t))
            pruneDisk()
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    private fun loadFromDisk(traceId: String): MessageTrace? {
        val f = File(tracesDir ?: return null, "$traceId.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<MessageTrace>(f.readText()) }.getOrNull()
            ?.also { byTraceId[it.traceId] = it; it.messageId?.let { mid -> messageIdIndex[mid] = it.traceId } }
    }

    private fun loadFromDiskByMessageId(messageId: String): MessageTrace? {
        val dir = tracesDir ?: return null
        dir.listFiles()?.forEach { f ->
            if (f.extension != "json") return@forEach
            val t = runCatching { json.decodeFromString<MessageTrace>(f.readText()) }.getOrNull() ?: return@forEach
            if (t.messageId == messageId) {
                byTraceId[t.traceId] = t
                messageIdIndex[messageId] = t.traceId
                return t
            }
        }
        return null
    }

    private fun trimActive() {
        if (byTraceId.size <= MAX_ACTIVE) return
        val oldest = byTraceId.values.sortedBy { it.startedAt }.take(byTraceId.size - MAX_ACTIVE)
        oldest.forEach {
            byTraceId.remove(it.traceId)
            it.messageId?.let { mid -> messageIdIndex.remove(mid) }
        }
    }

    private fun pruneDisk() {
        val dir = tracesDir ?: return
        val files = dir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_ON_DISK).forEach { it.delete() }
    }

    private fun terminalVisual(status: String): String = when (status) {
        "Delivered" -> "✅ Доставлено"
        "Expired" -> "⌛ Истекло"
        "Dropped" -> "🗑 Отброшено"
        "RetryLimit" -> "🔁 Лимит повторов"
        "Timeout" -> "⏱ Таймаут"
        "Failed" -> "❌ Ошибка"
        else -> "⏹ $status"
    }
}

object TraceAutoSend {
    private const val PREFS = "blink_prefs"
    private const val KEY_OPT_IN = "trace_auto_send_opt_in"

    fun isOptedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OPT_IN, false)

    fun setOptIn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OPT_IN, enabled).apply()
    }

    /**
     * Stub: when online and opted-in, queue the latest export for developer delivery.
     * Real Telegram/email/backend transport lands when credentials exist; for now we
     * write a pending marker file next to the zip so nothing is lost.
     */
    fun maybeQueueUpload(context: Context, peerCount: Int, peers: List<String>) {
        if (!isOptedIn(context)) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val online = cm?.activeNetworkInfo?.isConnected == true
        if (!online) return
        val zip = TraceStore.exportZip(context, peerCount, peers) ?: return
        File(context.filesDir, "traces_pending_upload").mkdirs()
        val dest = File(context.filesDir, "traces_pending_upload/${zip.name}")
        zip.copyTo(dest, overwrite = true)
        Log.i("MessageTrace", "Queued auto-upload artifact ${dest.absolutePath}")
    }
}
