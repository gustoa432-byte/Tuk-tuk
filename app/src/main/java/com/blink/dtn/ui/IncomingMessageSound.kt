package com.blink.dtn.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Two distinct audio cues:
 *
 * - [playPrivateMessage] — someone wrote to you. Uses the system notification
 *   sound so it matches whatever the user already chose for messages.
 * - [playDeliveryConfirmed] — your own message reached its recipient. A short
 *   generated two-beep, deliberately different from an incoming message.
 *
 * Both are debounced so bursty delivery does not turn into a rattle, and both
 * honour [QqFeedbackPrefs.sound].
 *
 * Deliberate: Qq ships no bundled notification audio. There is no `res/raw`
 * asset — [defaultNotificationUri] always returns the system notification
 * sound, so the app inherits the user's own choice instead of forcing a tone.
 */
object IncomingMessageSound {

    private const val DEBOUNCE_MS = 700L
    private const val CONFIRM_DEBOUNCE_MS = 2_000L

    /** Short enough to read as a confirmation blip, not a ringtone. */
    private const val CONFIRM_TONE_MS = 130

    /** 0..100 of the notification stream — quiet on purpose. */
    private const val CONFIRM_TONE_VOLUME = 35

    private var lastPlayedElapsed = 0L
    private var lastConfirmElapsed = 0L

    fun playPrivateMessage(context: Context) {
        if (!QqFeedbackPrefs.soundEnabled(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayedElapsed < DEBOUNCE_MS) return
        lastPlayedElapsed = now
        try {
            val uri = defaultNotificationUri(context) ?: return
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        } catch (_: Exception) {
            // Ignore — sound is best-effort UX.
        }
    }

    /** Your message was confirmed by the recipient — its own, distinct cue. */
    fun playDeliveryConfirmed(context: Context) {
        if (!QqFeedbackPrefs.soundEnabled(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfirmElapsed < CONFIRM_DEBOUNCE_MS) return
        lastConfirmElapsed = now
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, CONFIRM_TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, CONFIRM_TONE_MS)
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                (CONFIRM_TONE_MS + 120).toLong()
            )
        } catch (_: Exception) {
            // Ignore — sound is best-effort UX.
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun defaultNotificationUri(context: Context): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}
