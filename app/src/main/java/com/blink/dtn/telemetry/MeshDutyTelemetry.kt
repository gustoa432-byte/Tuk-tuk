package com.blink.dtn.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Session duty-cycle / battery / BLE write counters for Delivery Observatory.
 * Proves mesh activity vs battery drain without a second analytics backend.
 */
object MeshDutyTelemetry {
    private const val TAG = "MeshDuty"
    private const val MAX_BATTERY_SAMPLES = 240 // ~2h at 30s, or denser on change

    private val writeAttempts = AtomicLong(0)
    private val writeSuccesses = AtomicLong(0)
    private val writeFailures = AtomicLong(0)
    private val bytesAttempted = AtomicLong(0)
    private val bytesSucceeded = AtomicLong(0)
    private val gattConnectStarts = AtomicLong(0)
    private val gattConnectOk = AtomicLong(0)
    private val gattConnectFail = AtomicLong(0)
    private val budgetDownshifts = AtomicLong(0)

    private val batterySamples = CopyOnWriteArrayList<BatterySample>()
    private val downshiftLog = CopyOnWriteArrayList<BudgetDownshiftEvent>()

    private val _snapshot = MutableStateFlow(DutySnapshot())
    val snapshot: StateFlow<DutySnapshot> = _snapshot.asStateFlow()

    @Volatile private var startedAt = 0L
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        startedAt = System.currentTimeMillis()
        sampleBatteryOnce(context.applicationContext)
        publish()
    }

    fun startBatteryReceiver(context: Context) {
        init(context)
        if (receiver != null) return
        val ctx = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                recordBatteryFromIntent(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(r, filter)
        }
        receiver = r
        Log.i(TAG, "Battery receiver started")
    }

    fun stopBatteryReceiver() {
        val ctx = appContext ?: return
        receiver?.let {
            runCatching { ctx.unregisterReceiver(it) }
            receiver = null
        }
    }

    fun noteWriteAttempt(bytes: Int) {
        writeAttempts.incrementAndGet()
        bytesAttempted.addAndGet(bytes.toLong())
        publish()
    }

    fun noteWriteSuccess(bytes: Int) {
        writeSuccesses.incrementAndGet()
        bytesSucceeded.addAndGet(bytes.toLong())
        publish()
    }

    fun noteWriteFailure(bytes: Int = 0) {
        writeFailures.incrementAndGet()
        if (bytes > 0) bytesAttempted.addAndGet(bytes.toLong())
        publish()
    }

    fun noteGattConnectStart() {
        gattConnectStarts.incrementAndGet()
        publish()
    }

    fun noteGattConnectOk() {
        gattConnectOk.incrementAndGet()
        publish()
    }

    fun noteGattConnectFail() {
        gattConnectFail.incrementAndGet()
        publish()
    }

    fun noteBudgetDownshift(address: String, fromBytes: Int, toBytes: Int, reason: String) {
        budgetDownshifts.incrementAndGet()
        downshiftLog.add(
            BudgetDownshiftEvent(
                timestamp = System.currentTimeMillis(),
                address = address,
                fromBytes = fromBytes,
                toBytes = toBytes,
                reason = reason
            )
        )
        while (downshiftLog.size > 64) downshiftLog.removeAt(0)
        publish()
    }

    fun sampleBatteryOnce(context: Context) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return
        recordBatteryFromIntent(intent)
    }

    fun current(): DutySnapshot = _snapshot.value

    private fun recordBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val sample = BatterySample(
            timestamp = System.currentTimeMillis(),
            pct = pct,
            charging = charging
        )
        val last = batterySamples.lastOrNull()
        if (last != null && last.pct == pct && last.charging == charging &&
            sample.timestamp - last.timestamp < 25_000L
        ) {
            return
        }
        batterySamples.add(sample)
        while (batterySamples.size > MAX_BATTERY_SAMPLES) batterySamples.removeAt(0)
        publish()
    }

    private fun publish() {
        val samples = batterySamples.toList()
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        val drainPct = if (first != null && last != null && !last.charging && first.pct >= last.pct) {
            first.pct - last.pct
        } else null
        val sessionMs = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        _snapshot.value = DutySnapshot(
            startedAt = startedAt,
            sessionMs = sessionMs,
            writeAttempts = writeAttempts.get(),
            writeSuccesses = writeSuccesses.get(),
            writeFailures = writeFailures.get(),
            bytesAttempted = bytesAttempted.get(),
            bytesSucceeded = bytesSucceeded.get(),
            gattConnectStarts = gattConnectStarts.get(),
            gattConnectOk = gattConnectOk.get(),
            gattConnectFail = gattConnectFail.get(),
            budgetDownshifts = budgetDownshifts.get(),
            batterySamples = samples,
            batteryDrainPct = drainPct,
            recentDownshifts = downshiftLog.toList().takeLast(12)
        )
    }
}

@Serializable
data class BatterySample(
    val timestamp: Long,
    val pct: Int,
    val charging: Boolean
)

@Serializable
data class BudgetDownshiftEvent(
    val timestamp: Long,
    val address: String,
    val fromBytes: Int,
    val toBytes: Int,
    val reason: String
)

@Serializable
data class DutySnapshot(
    val startedAt: Long = 0,
    val sessionMs: Long = 0,
    val writeAttempts: Long = 0,
    val writeSuccesses: Long = 0,
    val writeFailures: Long = 0,
    val bytesAttempted: Long = 0,
    val bytesSucceeded: Long = 0,
    val gattConnectStarts: Long = 0,
    val gattConnectOk: Long = 0,
    val gattConnectFail: Long = 0,
    val budgetDownshifts: Long = 0,
    val batterySamples: List<BatterySample> = emptyList(),
    val batteryDrainPct: Int? = null,
    val recentDownshifts: List<BudgetDownshiftEvent> = emptyList()
) {
    /** Rough duty hint: successful write bytes per minute of session. */
    fun bytesPerMinute(): Double {
        val minutes = sessionMs / 60_000.0
        if (minutes < 0.05) return 0.0
        return bytesSucceeded / minutes
    }

    fun writeSuccessRate(): Double {
        if (writeAttempts == 0L) return 0.0
        return writeSuccesses.toDouble() / writeAttempts
    }
}
