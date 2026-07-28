package com.blink.dtn.update

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blink.dtn.security.BuildIntegrity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mesh version gossip: peers advertise versionCode/Name in IDENTITY_ANNOUNCEMENT.
 * Older devices surface an in-app banner + optional notification.
 */
object VersionGossip {
    private const val TAG = "VersionGossip"
    private const val PREFS = "blink_prefs"
    private const val KEY_DISMISSED_VC = "update_dismissed_vc"
    private const val KEY_NOTIFIED_VC = "update_notified_vc"
    private const val CHANNEL = "tuktuk_messages"

    data class NearbyUpdate(
        val peerId: String,
        val peerNick: String,
        val versionCode: Long,
        val versionName: String
    )

    private val _offer = MutableStateFlow<NearbyUpdate?>(null)
    val nearbyUpdate: StateFlow<NearbyUpdate?> = _offer.asStateFlow()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun notePeerVersion(
        context: Context,
        peerId: String,
        peerNick: String,
        versionCode: Long,
        versionName: String
    ) {
        if (versionCode <= 0L) return
        init(context)
        val mine = BuildIntegrity.myVersionCode(context)
        if (versionCode <= mine) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dismissed = prefs.getLong(KEY_DISMISSED_VC, 0L)
        if (versionCode <= dismissed) return

        val current = _offer.value
        if (current != null && current.versionCode >= versionCode) return

        val offer = NearbyUpdate(peerId, peerNick.ifBlank { peerId }, versionCode, versionName.ifBlank { "?" })
        _offer.value = offer
        Log.i(TAG, "Nearby newer build ${offer.versionName} ($versionCode) from ${offer.peerNick}")

        val notified = prefs.getLong(KEY_NOTIFIED_VC, 0L)
        if (versionCode > notified) {
            prefs.edit().putLong(KEY_NOTIFIED_VC, versionCode).apply()
            notifySystem(context, offer)
        }
    }

    /** Called from BLE ingress when Application context was already [init]-ed. */
    fun notePeerVersionFromProfile(
        peerId: String,
        peerNick: String,
        versionCode: Long,
        versionName: String
    ) {
        val ctx = appContext ?: return
        notePeerVersion(ctx, peerId, peerNick, versionCode, versionName)
    }

    fun dismiss(context: Context) {
        val vc = _offer.value?.versionCode ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DISMISSED_VC, vc).apply()
        _offer.value = null
    }

    fun clearOfferOnly() {
        _offer.value = null
    }

    private fun notifySystem(context: Context, offer: NearbyUpdate) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Доступна версия ${offer.versionName} рядом")
            .setContentText("У ${offer.peerNick} новее · можно запросить быструю передачу")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(("upd_" + offer.versionCode).hashCode(), n)
    }
}
