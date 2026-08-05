package com.blink.dtn.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blink.dtn.net.AuthResponse

/**
 * Local onboarding / auth session.
 * Mesh identity remains RSA nodeId; JWT links the device to the VPS account.
 *
 * Sensitive VPS session fields live in EncryptedSharedPreferences (`blink_vps_secure`).
 * Non-secret onboarding prefs stay in plain `blink_prefs`.
 */
object AuthSessionStore {
    private const val TAG = "AuthSessionStore"
    private const val PREFS = "blink_prefs"
    private const val SECURE_PREFS = "blink_vps_secure"
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    const val KEY_DISPLAY_NAME = "display_name"
    const val KEY_AUTH_PROVIDER = "auth_provider"
    const val KEY_NICK = "nick"
    private const val KEY_JWT = "vps_jwt"
    private const val KEY_SERVER_USER_ID = "vps_user_id"
    private const val KEY_AUTH_ID = "vps_auth_id"
    private const val KEY_NODE_ID = "vps_node_id"
    private const val KEY_SECURE_MIGRATED = "vps_secure_migrated_v1"

    @Volatile
    private var securePrefsCached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun securePrefs(context: Context): SharedPreferences {
        securePrefsCached?.let { return it }
        synchronized(this) {
            securePrefsCached?.let { return it }
            val app = context.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val sp = EncryptedSharedPreferences.create(
                app,
                SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            securePrefsCached = sp
            return sp
        }
    }

    /** Move JWT session out of plaintext blink_prefs (idempotent). */
    fun migrateSecureSessionIfNeeded(context: Context) {
        val plain = prefs(context)
        if (plain.getBoolean(KEY_SECURE_MIGRATED, false)) return
        try {
            val jwt = plain.getString(KEY_JWT, "").orEmpty()
            val userId = plain.getString(KEY_SERVER_USER_ID, "").orEmpty()
            val authId = plain.getString(KEY_AUTH_ID, "").orEmpty()
            val nodeId = plain.getString(KEY_NODE_ID, "").orEmpty()
            if (jwt.isNotBlank() || userId.isNotBlank() || authId.isNotBlank() || nodeId.isNotBlank()) {
                securePrefs(context).edit()
                    .putString(KEY_JWT, jwt)
                    .putString(KEY_SERVER_USER_ID, userId)
                    .putString(KEY_AUTH_ID, authId)
                    .putString(KEY_NODE_ID, nodeId)
                    .apply()
            }
            plain.edit()
                .remove(KEY_JWT)
                .remove(KEY_SERVER_USER_ID)
                .remove(KEY_AUTH_ID)
                .remove(KEY_NODE_ID)
                .putBoolean(KEY_SECURE_MIGRATED, true)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "secure session migrate failed: ${e.message}", e)
        }
    }

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun displayName(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_NAME, "") ?: ""

    fun authProvider(context: Context): AuthProvider =
        AuthProvider.fromWire(prefs(context).getString(KEY_AUTH_PROVIDER, null))

    fun jwt(context: Context): String {
        migrateSecureSessionIfNeeded(context)
        return securePrefs(context).getString(KEY_JWT, "") ?: ""
    }

    fun serverUserId(context: Context): String {
        migrateSecureSessionIfNeeded(context)
        return securePrefs(context).getString(KEY_SERVER_USER_ID, "") ?: ""
    }

    fun authId(context: Context): String {
        migrateSecureSessionIfNeeded(context)
        return securePrefs(context).getString(KEY_AUTH_ID, "") ?: ""
    }

    fun serverNodeId(context: Context): String {
        migrateSecureSessionIfNeeded(context)
        return securePrefs(context).getString(KEY_NODE_ID, "") ?: ""
    }

    fun hasVpsSession(context: Context): Boolean = jwt(context).isNotBlank()

    fun saveVpsSession(context: Context, resp: AuthResponse) {
        migrateSecureSessionIfNeeded(context)
        securePrefs(context).edit()
            .putString(KEY_JWT, resp.token)
            .putString(KEY_SERVER_USER_ID, resp.userId)
            .putString(KEY_AUTH_ID, resp.authId)
            .putString(KEY_NODE_ID, resp.nodeId)
            .apply()
        prefs(context).edit()
            .putString(
                KEY_AUTH_PROVIDER,
                when (resp.authMethod) {
                    "tg" -> AuthProvider.TELEGRAM.wireId
                    "email" -> AuthProvider.EMAIL.wireId
                    else -> AuthProvider.fromWire(resp.authMethod).wireId
                }
            )
            .putBoolean(KEY_SECURE_MIGRATED, true)
            .remove(KEY_JWT)
            .remove(KEY_SERVER_USER_ID)
            .remove(KEY_AUTH_ID)
            .remove(KEY_NODE_ID)
            .apply()
    }

    fun clearVpsSession(context: Context) {
        migrateSecureSessionIfNeeded(context)
        securePrefs(context).edit()
            .remove(KEY_JWT)
            .remove(KEY_SERVER_USER_ID)
            .remove(KEY_AUTH_ID)
            .remove(KEY_NODE_ID)
            .apply()
        prefs(context).edit()
            .remove(KEY_JWT)
            .remove(KEY_SERVER_USER_ID)
            .remove(KEY_AUTH_ID)
            .remove(KEY_NODE_ID)
            .apply()
    }

    /**
     * Existing installs that already set a nick skip the new auth screen.
     * Call once at cold start before gating UI.
     */
    fun migrateLegacyIfNeeded(context: Context) {
        migrateSecureSessionIfNeeded(context)
        val p = prefs(context)
        if (p.getBoolean(KEY_ONBOARDING_DONE, false)) return
        val nick = p.getString(KEY_NICK, null)?.trim().orEmpty()
        if (nick.isNotEmpty() && !nick.startsWith("User-")) {
            p.edit()
                .putBoolean(KEY_ONBOARDING_DONE, true)
                .putString(KEY_AUTH_PROVIDER, AuthProvider.OFFLINE.wireId)
                .apply {
                    if ((p.getString(KEY_DISPLAY_NAME, null).orEmpty()).isBlank()) {
                        putString(KEY_DISPLAY_NAME, nick.take(DinoNameGenerator.MAX_LEN))
                    }
                }
                .apply()
        }
    }

    /**
     * Persist onboarding result.
     * @param meshNick value written to `nick` for mesh (entered nick, else display name).
     */
    fun complete(
        context: Context,
        displayName: String,
        meshNick: String,
        provider: AuthProvider
    ) {
        prefs(context).edit()
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .putString(KEY_DISPLAY_NAME, displayName.take(DinoNameGenerator.MAX_LEN))
            .putString(KEY_NICK, meshNick.take(DinoNameGenerator.MAX_LEN))
            .putString(KEY_AUTH_PROVIDER, provider.wireId)
            .apply()
    }

    fun setDisplayName(context: Context, displayName: String) {
        prefs(context).edit()
            .putString(KEY_DISPLAY_NAME, displayName.take(DinoNameGenerator.MAX_LEN))
            .apply()
    }
}
