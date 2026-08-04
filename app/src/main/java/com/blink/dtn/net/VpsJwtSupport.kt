package com.blink.dtn.net

import android.content.Context
import android.util.Log
import com.blink.dtn.auth.AuthSessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared JWT helpers for VPS HTTP clients.
 * Quietly upgrades tokens that lack `node_id` when Oracle returns
 * [ERROR_JWT_MISSING_NODE_ID].
 */
object VpsJwtSupport {
    const val ERROR_JWT_MISSING_NODE_ID = "jwt_missing_node_id_reauth_required"

    private const val TAG = "VpsJwtSupport"
    private val refreshMutex = Mutex()

    fun isReauthRequired(error: String?): Boolean =
        error?.contains(ERROR_JWT_MISSING_NODE_ID) == true

    fun isReauthRequired(e: Throwable): Boolean =
        when (e) {
            is ApiException -> isReauthRequired(e.message)
            else -> isReauthRequired(e.message)
        }

    /**
     * Runs [block] with the current JWT. On [ERROR_JWT_MISSING_NODE_ID],
     * silently refreshes via `/auth/refresh` once and retries.
     */
    suspend fun <T> withJwtRetry(
        context: Context,
        block: suspend (jwt: String) -> T
    ): T {
        val firstJwt = AuthSessionStore.jwt(context)
        if (firstJwt.isBlank()) error("not_authenticated")
        return try {
            block(firstJwt)
        } catch (e: Throwable) {
            if (!isReauthRequired(e)) throw e
            Log.i(TAG, "JWT missing node_id — quiet refresh")
            quietRefresh(context)
            val fresh = AuthSessionStore.jwt(context)
            if (fresh.isBlank()) throw e
            block(fresh)
        }
    }

    suspend fun quietRefresh(context: Context): AuthResponse {
        refreshMutex.withLock {
            val result = AuthApi(context).refreshSession()
            return result.getOrElse { err ->
                Log.w(TAG, "quiet refresh failed: ${err.message}")
                throw err
            }
        }
    }
}
