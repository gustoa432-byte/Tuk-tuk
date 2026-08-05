package com.blink.dtn.ble

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mesh aggressiveness. Economy saves battery; Max trades battery for discovery;
 * Crowd is for stadiums — short peer TTL, tight GATT, heavy advertise / light handshake.
 */
enum class MeshDutyPreset(val id: String, val labelRu: String, val labelEn: String) {
    ECONOMY("economy", "Экономия", "Economy"),
    NORMAL("normal", "Норма", "Normal"),
    MAX("max", "Максимум", "Maximum"),
    CROWD("crowd", "Толпа", "Crowd");

    companion object {
        fun fromId(id: String?): MeshDutyPreset =
            entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

data class MeshDutyCadence(
    val scanOnMs: Long,
    val scanOffMs: Long,
    val scanMode: Int,
    val advertiseMode: Int,
    val advertiseTxPower: Int,
    /** How long an idle GATT client stays open. */
    val gattIdleTimeoutMs: Long,
    /** IDENTITY_REQUEST poll interval. */
    val keyExchangeIntervalMs: Long,
    /** Soft cap on concurrent neighbor writes per relay tick (hint). */
    val maxPeersPerBatch: Int,
    /**
     * Drop discovered peers with no scan/GATT touch for this long
     * (keeps tables small in 100+ advertiser crowds).
     */
    val peerTtlMs: Long,
    /** Hard cap on simultaneous GATT client connections. */
    val maxConcurrentGatt: Int,
    /** Prefer short crowd frames / suppress IDENTITY storms. */
    val crowdMode: Boolean = false
) {
    companion object {
        fun forPreset(preset: MeshDutyPreset): MeshDutyCadence = when (preset) {
            MeshDutyPreset.ECONOMY -> MeshDutyCadence(
                scanOnMs = 4_000L,
                scanOffMs = 50_000L,
                scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                gattIdleTimeoutMs = 25_000L,
                keyExchangeIntervalMs = 60_000L,
                maxPeersPerBatch = 2,
                peerTtlMs = 120_000L,
                maxConcurrentGatt = 2
            )
            MeshDutyPreset.NORMAL -> MeshDutyCadence(
                scanOnMs = 10_000L,
                scanOffMs = 20_000L,
                scanMode = ScanSettings.SCAN_MODE_BALANCED,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                gattIdleTimeoutMs = 60_000L,
                keyExchangeIntervalMs = 20_000L,
                maxPeersPerBatch = 6,
                peerTtlMs = 180_000L,
                maxConcurrentGatt = 3
            )
            MeshDutyPreset.MAX -> MeshDutyCadence(
                scanOnMs = 14_000L,
                scanOffMs = 4_000L,
                scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                gattIdleTimeoutMs = 90_000L,
                keyExchangeIntervalMs = 12_000L,
                maxPeersPerBatch = 12,
                peerTtlMs = 240_000L,
                maxConcurrentGatt = 4
            )
            // Stadium: advertise hard, peer table ruthless, GATT still tiny (Android limit).
            MeshDutyPreset.CROWD -> MeshDutyCadence(
                scanOnMs = 8_000L,
                scanOffMs = 2_000L,
                scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                gattIdleTimeoutMs = 20_000L,
                keyExchangeIntervalMs = 90_000L,
                maxPeersPerBatch = 4,
                peerTtlMs = 45_000L,
                maxConcurrentGatt = 2,
                crowdMode = true
            )
        }
    }
}

object MeshDutyPrefs {
    private const val PREFS = "blink_prefs"
    private const val KEY = "mesh_duty_preset"
    private const val KEY_CROWD_UNTIL = "mesh_crowd_until_ms"
    private const val KEY_AUTO_CROWD = "mesh_auto_crowd"

    private val _preset = MutableStateFlow(MeshDutyPreset.NORMAL)
    val preset: StateFlow<MeshDutyPreset> = _preset.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY, MeshDutyPreset.NORMAL.id)
        _preset.value = MeshDutyPreset.fromId(id)
        // Auto-exit Crowd after expiry.
        val until = prefs.getLong(KEY_CROWD_UNTIL, 0L)
        if (_preset.value == MeshDutyPreset.CROWD && until > 0L && System.currentTimeMillis() > until) {
            set(context, MeshDutyPreset.NORMAL)
        }
    }

    fun current(): MeshDutyPreset = _preset.value

    fun cadence(): MeshDutyCadence = MeshDutyCadence.forPreset(current())

    fun isCrowd(): Boolean = current() == MeshDutyPreset.CROWD || cadence().crowdMode

    fun autoCrowdEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CROWD, true)

    fun setAutoCrowd(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CROWD, enabled)
            .apply()
    }

    fun set(context: Context, preset: MeshDutyPreset, crowdDurationMs: Long = 4L * 60 * 60 * 1000) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putString(KEY, preset.id)
        if (preset == MeshDutyPreset.CROWD) {
            edit.putLong(KEY_CROWD_UNTIL, System.currentTimeMillis() + crowdDurationMs)
        } else {
            edit.remove(KEY_CROWD_UNTIL)
        }
        edit.apply()
        _preset.value = preset
    }

    fun crowdUntilMs(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CROWD_UNTIL, 0L)
}
