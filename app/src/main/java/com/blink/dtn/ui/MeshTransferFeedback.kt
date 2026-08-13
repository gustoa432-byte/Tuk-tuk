package com.blink.dtn.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Soft haptic/audio feedback for mesh handoff — reuses notification sound assets
 * via [IncomingMessageSound]. Does not touch BLE/DTN engines.
 *
 * Call sites (existing events):
 * - TxResult.Success (GATT hop done) → short pulse (throttled)
 * - DELIVERED_ACK → slightly longer “done” pulse + optional soft sound
 */
object MeshTransferFeedback {

    private const val HOP_DEBOUNCE_MS = 1_200L
    private const val DONE_DEBOUNCE_MS = 2_000L
    private var lastHopElapsed = 0L
    private var lastDoneElapsed = 0L

    /** Packet/hop left this phone toward a neighbor. */
    fun onHopCompleted(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHopElapsed < HOP_DEBOUNCE_MS) return
        lastHopElapsed = now
        shortPulse(context, 18)
    }

    /** End-to-end ACK — positive “done” cue (sound TBD; soft for now). */
    fun onDeliveredAck(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDoneElapsed < DONE_DEBOUNCE_MS) return
        lastDoneElapsed = now
        shortPulse(context, 35)
        IncomingMessageSound.playPrivateMessage(context)
    }

    fun tickHaptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun shortPulse(context: Context, ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java) ?: return
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            }
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {
            // Best-effort UX.
        }
    }
}
