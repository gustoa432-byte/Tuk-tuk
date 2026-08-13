package com.blink.dtn.ble

import kotlin.math.min
import kotlin.random.Random

/**
 * Pure backoff helpers for scan retry and GATT connect storms.
 * Kept Android-free so unit tests run on the JVM.
 */
object BleRadioBackoff {
    const val SCAN_BASE_MS = 2_000L
    const val SCAN_MAX_MS = 32_000L
    const val SCAN_MAX_ATTEMPTS = 8

    const val GATT_BASE_MS = 1_000L
    const val GATT_MAX_MS = 16_000L
    const val GATT_133 = 133

    fun scanDelayMs(attempt: Int, jitter: Int = Random.nextInt(0, 400)): Long {
        val exp = SCAN_BASE_MS shl attempt.coerceIn(0, 4)
        return min(SCAN_MAX_MS, exp) + jitter
    }

    fun gattDelayMs(attempt: Int, status: Int, jitter: Int = Random.nextInt(0, 250)): Long {
        val base = if (status == GATT_133) GATT_BASE_MS * 2 else GATT_BASE_MS
        val exp = base shl attempt.coerceIn(0, 4)
        return min(GATT_MAX_MS, exp) + jitter
    }

    fun shouldRetryScan(attempt: Int): Boolean = attempt < SCAN_MAX_ATTEMPTS
}
