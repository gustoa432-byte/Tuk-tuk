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
 * Three distinct delivery signals. Presentation only — BLE/DTN engines are untouched.
 *
 * 1. Someone wrote to you — system notification sound
 *    ([IncomingMessageSound.playPrivateMessage], 700 ms debounce), fired from the
 *    notification adapter.
 * 2. [onHopCompleted] — this phone passed someone's message onward. Shortest and
 *    quietest of the three: a 12 ms tap, no sound, 3 s debounce.
 * 3. [onDeliveredAck] — your message was confirmed by its recipient. A 35 ms pulse
 *    plus its own short two-beep ([IncomingMessageSound.playDeliveryConfirmed]),
 *    2 s debounce, so "it arrived" never sounds like "someone wrote to you".
 *
 * Sound and vibration can be switched off independently in Settings
 * ([QqFeedbackPrefs]); every path below respects them.
 */
object MeshTransferFeedback {

    private const val HOP_DEBOUNCE_MS = 3_000L
    private const val DONE_DEBOUNCE_MS = 2_000L

    /** Barely-there tap: carrying a stranger's message must not feel like an event. */
    private const val HOP_PULSE_MS = 12L
    private const val DONE_PULSE_MS = 35L

    private var lastHopElapsed = 0L
    private var lastDoneElapsed = 0L

    /** This phone handed a message on to the next person. */
    fun onHopCompleted(context: Context) {
        if (!com.blink.dtn.utils.AppForegroundState.isForeground) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHopElapsed < HOP_DEBOUNCE_MS) return
        lastHopElapsed = now
        shortPulse(context, HOP_PULSE_MS)
    }

    /** End-to-end confirmation: the recipient's phone acknowledged the message. */
    fun onDeliveredAck(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDoneElapsed < DONE_DEBOUNCE_MS) return
        lastDoneElapsed = now
        shortPulse(context, DONE_PULSE_MS)
        IncomingMessageSound.playDeliveryConfirmed(context)
    }

    fun tickHaptic(view: View) {
        if (!QqFeedbackPrefs.vibrationEnabled(view.context)) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun shortPulse(context: Context, ms: Long) {
        if (!QqFeedbackPrefs.vibrationEnabled(context)) return
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
