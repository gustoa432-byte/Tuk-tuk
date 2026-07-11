package com.blink.dtn.utils

import android.content.Context

/**
 * Generates stable, globally-unique end-to-end message ids of the form
 * "<nodeId>-<epoch>-<seq>".
 *
 * The id is minted ONCE at the origin and preserved unchanged across every hop
 * and retransmission, so the mesh can de-duplicate on it reliably.
 *
 *  - nodeId : the local 8-char unique node id.
 *  - epoch  : a per-install constant captured on first run. It survives the
 *             reinstall-collision problem: after a reinstall the seq counter
 *             resets to 0, but the epoch differs, so freshly generated ids can
 *             never collide with pre-reinstall ids still remembered by peers'
 *             seen-caches.
 *  - seq    : a monotonically increasing counter persisted across process
 *             restarts and incremented atomically per generated id.
 */
object MeshIdGenerator {
    private const val PREFS_NAME = "mesh_ids"
    private const val KEY_EPOCH = "epoch"
    private const val KEY_SEQ = "seq"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    // Cached in memory after the first read; the epoch is immutable per install.
    @Volatile
    private var epoch: Long = 0L

    /**
     * Idempotently initialise the generator. Safe to call from multiple
     * entry points (mesh manager, view model); only the first call does work.
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!p.contains(KEY_EPOCH)) {
                // commit (not apply) so the epoch is durable before any id is minted.
                p.edit().putLong(KEY_EPOCH, System.currentTimeMillis()).commit()
            }
            epoch = p.getLong(KEY_EPOCH, System.currentTimeMillis())
            prefs = p
        }
    }

    /**
     * Return the next stable id for a message originated by [nodeId].
     * Synchronized so concurrent coroutines never share a seq value.
     */
    @Synchronized
    fun next(nodeId: String): String {
        val p = prefs
            ?: throw IllegalStateException("MeshIdGenerator.init(context) must be called before next()")
        val seq = p.getLong(KEY_SEQ, 0L)
        // commit so a crash right after minting can never re-issue the same seq.
        p.edit().putLong(KEY_SEQ, seq + 1).commit()
        return "$nodeId-$epoch-$seq"
    }
}
