package com.blink.dtn.ble

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing mesh aggressiveness. Economy must not drain the phone in ~1h idle;
 * Max Network trades battery for discovery / connect density.
 *
 * Future LoRa / VPS bridge can read the same prefs key without changing UI.
 */
enum class MeshDutyPreset(val id: String, val labelRu: String, val labelEn: String) {
    ECONOMY("economy", "Экономия", "Economy"),
    NORMAL("normal", "Норма", "Normal"),
    MAX("max", "Максимум", "Maximum");

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
    val maxConcurrentGatt: Int
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
        }
    }
}

object MeshDutyPrefs {
    private const val PREFS = "blink_prefs"
    private const val KEY = "mesh_duty_preset"

    private val _preset = MutableStateFlow(MeshDutyPreset.NORMAL)
    val preset: StateFlow<MeshDutyPreset> = _preset.asStateFlow()

    fun init(context: Context) {
        val id = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, MeshDutyPreset.NORMAL.id)
        _preset.value = MeshDutyPreset.fromId(id)
    }

    fun current(): MeshDutyPreset = _preset.value

    fun cadence(): MeshDutyCadence = MeshDutyCadence.forPreset(current())

    fun set(context: Context, preset: MeshDutyPreset) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, preset.id)
            .apply()
        _preset.value = preset
    }
}
