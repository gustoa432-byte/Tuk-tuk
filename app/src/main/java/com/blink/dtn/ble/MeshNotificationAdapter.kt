package com.blink.dtn.ble

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.blink.dtn.ui.IncomingMessageSound
import com.blink.dtn.utils.AppForegroundState

/**
 * Small, dependency-free adapter that turns delivered mesh messages into system
 * notifications. Extracted out of [BleMeshManager] so the transport layer no
 * longer owns Android UI concerns and can be tested/replaced independently.
 */
class MeshNotificationAdapter(private val context: Context) {

    fun notifyIncoming(id: String, isPrivate: Boolean, senderNick: String, body: String) {
        // Private dialog messages always get a short sound (debounced), including
        // when the app is in the foreground and no tray notification is posted.
        if (isPrivate) {
            IncomingMessageSound.playPrivateMessage(context)
        }

        if (AppForegroundState.isForeground) return
        if (!isPrivate) return

        val notificationManager =
            context.getSystemService(NotificationManager::class.java) ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.blink.dtn.R.drawable.ic_notification)
            .setContentTitle(senderNick.ifBlank { "Qq" })
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        if (com.blink.dtn.ui.QqFeedbackPrefs.soundEnabled(context)) {
            val soundUri = IncomingMessageSound.defaultNotificationUri(context)
            if (soundUri != null) builder.setSound(soundUri)
        } else {
            builder.setSilent(true)
        }
        if (!com.blink.dtn.ui.QqFeedbackPrefs.vibrationEnabled(context)) {
            builder.setVibrate(longArrayOf(0L))
        }

        notificationManager.notify(id.hashCode(), builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "tuktuk_messages"
    }
}
