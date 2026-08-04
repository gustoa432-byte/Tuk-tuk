package com.blink.dtn.auth

import android.content.Context
import android.content.SharedPreferences
import com.blink.dtn.net.AuthResponse

/**
 * Local onboarding / auth session on top of `blink_prefs`.
 * Mesh identity remains RSA nodeId; JWT links the device to the VPS account.
 */
object AuthSessionStore {
    private const val PREFS = "blink_prefs"
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    const val KEY_DISPLAY_NAME = "display_name"
    const val KEY_AUTH_PROVIDER = "auth_provider"
    const val KEY_NICK = "nick"
    private const val KEY_JWT = "vps_jwt"
    private const val KEY_SERVER_USER_ID = "vps_user_id"
    private const val KEY_AUTH_ID = "vps_auth_id"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun displayName(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_NAME, "") ?: ""

    fun authProvider(context: Context): AuthProvider =
        AuthProvider.fromWire(prefs(context).getString(KEY_AUTH_PROVIDER, null))

    fun jwt(context: Context): String =
        prefs(context).getString(KEY_JWT, "") ?: ""

    fun serverUserId(context: Context): String =
        prefs(context).getString(KEY_SERVER_USER_ID, "") ?: ""

    fun authId(context: Context): String =
        prefs(context).getString(KEY_AUTH_ID, "") ?: ""

    fun hasVpsSession(context: Context): Boolean = jwt(context).isNotBlank()

    fun saveVpsSession(context: Context, resp: AuthResponse) {
        prefs(context).edit()
            .putString(KEY_JWT, resp.token)
            .putString(KEY_SERVER_USER_ID, resp.userId)
            .putString(KEY_AUTH_ID, resp.authId)
            .putString(
                KEY_AUTH_PROVIDER,
                when (resp.authMethod) {
                    "tg" -> AuthProvider.TELEGRAM.wireId
                    "email" -> AuthProvider.EMAIL.wireId
                    else -> AuthProvider.fromWire(resp.authMethod).wireId
                }
            )
            .apply()
    }

    fun clearVpsSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_JWT)
            .remove(KEY_SERVER_USER_ID)
            .remove(KEY_AUTH_ID)
            .apply()
    }

    /**
     * Existing installs that already set a nick skip the new auth screen.
     * Call once at cold start before gating UI.
     */
    fun migrateLegacyIfNeeded(context: Context) {
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
