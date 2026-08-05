package com.blink.dtn.ble

import android.content.Context
import com.blink.dtn.telemetry.MeshDutyTelemetry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects stadium-like BLE advertising density and optionally flips to [MeshDutyPreset.CROWD].
 */
object DenseCrowdDetector {
    /** Unique advertisers seen in the rolling window before suggesting Crowd. */
    const val DENSE_PEER_THRESHOLD = 25
    private const val WINDOW_MS = 15_000L
    private const val COOLDOWN_MS = 120_000L

    private val windowStart = AtomicLong(0L)
    private val peersInWindow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lastAutoSwitch = AtomicLong(0L)
    private val lastSuggestAt = AtomicLong(0L)

    @Volatile
    var lastSuggestedCrowd: Boolean = false
        private set

    fun noteAdvertiser(mac: String) {
        val now = System.currentTimeMillis()
        val start = windowStart.get()
        if (start == 0L || now - start > WINDOW_MS) {
            windowStart.set(now)
            peersInWindow.clear()
        }
        peersInWindow.add(mac)
        val n = peersInWindow.size
        MeshDutyTelemetry.noteScanPeer(n)
        if (n >= DENSE_PEER_THRESHOLD) {
            lastSuggestedCrowd = true
            lastSuggestAt.set(now)
        }
    }

    fun peersInWindow(): Int = peersInWindow.size

    /**
     * If auto-crowd enabled and density high, switch to CROWD once per cooldown.
     * @return true if preset was changed.
     */
    fun maybeAutoSwitch(context: Context): Boolean {
        if (!MeshDutyPrefs.autoCrowdEnabled(context)) return false
        if (MeshDutyPrefs.current() == MeshDutyPreset.CROWD) return false
        if (!lastSuggestedCrowd) return false
        val now = System.currentTimeMillis()
        if (now - lastSuggestAt.get() > WINDOW_MS * 2) return false
        if (now - lastAutoSwitch.get() < COOLDOWN_MS) return false
        if (peersInWindow.size < DENSE_PEER_THRESHOLD) return false
        MeshDutyPrefs.set(context, MeshDutyPreset.CROWD)
        lastAutoSwitch.set(now)
        MeshDutyTelemetry.noteCrowdAutoSwitch()
        return true
    }

    fun resetSuggestion() {
        lastSuggestedCrowd = false
    }
}
