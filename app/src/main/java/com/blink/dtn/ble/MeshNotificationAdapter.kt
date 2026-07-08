package com.blink.dtn.ble

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Small, dependency-free adapter that turns delivered mesh messages into system
 * notifications. Extracted out of [BleMeshManager] so the transport layer no
 * longer owns Android UI concerns and can be tested/replaced independently.
 */
class MeshNotificationAdapter(private val context: Context) {

    fun notifyIncoming(id: String, isPrivate: Boolean, senderNick: String, body: String) {
        if (com.blink.dtn.utils.AppForegroundState.isForeground) return

        val notificationManager =
            context.getSystemService(NotificationManager::class.java) ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(
                if (isPrivate) "Private message from $senderNick" else "New message in TukTuk"
            )
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(id.hashCode(), builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "tuktuk_messages"
    }
}
