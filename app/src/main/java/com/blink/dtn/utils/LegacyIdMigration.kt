package com.blink.dtn.utils

import android.content.Context
import android.util.Log
import com.blink.dtn.crypto.NodeIdentity
import com.blink.dtn.db.BLinkDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time cleanup after migrating from random 8-char node ids to
 * self-certifying 16-char ids derived from the RSA public key.
 *
 * Purges orphaned private dialogs, stale peer profiles, relay-queue rows,
 * and seen-packet markers keyed on the old id scheme. Public chat history
 * is kept; messages we sent there are re-attributed to the new id.
 */
object LegacyIdMigration {
    private const val TAG = "LegacyIdMigration"
    private const val PREFS_NAME = "blink_prefs"
    private const val PREF_CLEANUP_DONE = "legacy_id_cleanup_v2"

    private val mutex = Mutex()

    suspend fun runIfNeeded(context: Context, dao: BLinkDao) = mutex.withLock {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_CLEANUP_DONE, false)) return

        val oldNodeId = prefs.getString("node_id", null)
        val newNodeId = NodeIdentity.myNodeId()

        if (oldNodeId != null && oldNodeId != newNodeId) {
            dao.cleanupLegacyNodeIdData(oldNodeId, newNodeId)
            Log.i(TAG, "Purged legacy mesh data: $oldNodeId -> $newNodeId")
        } else {
            Log.d(TAG, "No legacy node-id migration needed (fresh install or already current)")
        }

        prefs.edit().putBoolean(PREF_CLEANUP_DONE, true).apply()
    }
}
