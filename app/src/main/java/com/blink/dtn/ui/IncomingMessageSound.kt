package com.blink.dtn.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.SystemClock

/**
 * Short notification sound for inbound private dialog messages.
 * Used in foreground (when no system notification is posted) and as a
 * fallback helper. Debounced so bursty mesh delivery does not spam.
 */
object IncomingMessageSound {

    private const val DEBOUNCE_MS = 700L
    private var lastPlayedElapsed = 0L

    fun playPrivateMessage(context: Context) {
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

    fun defaultNotificationUri(context: Context): Uri? {
        val resId = context.resources.getIdentifier("tuktuk", "raw", context.packageName)
        if (resId != 0) {
            return Uri.parse("android.resource://${context.packageName}/$resId")
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }
}
