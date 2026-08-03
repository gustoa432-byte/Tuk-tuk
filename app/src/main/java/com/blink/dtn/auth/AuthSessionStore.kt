package com.blink.dtn.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Local onboarding / auth session on top of `blink_prefs`.
 * Mesh identity remains RSA nodeId; this only tracks first-run profile completion.
 */
object AuthSessionStore {
    private const val PREFS = "blink_prefs"
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    const val KEY_DISPLAY_NAME = "display_name"
    const val KEY_AUTH_PROVIDER = "auth_provider"
    const val KEY_NICK = "nick"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun displayName(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_NAME, "") ?: ""

    fun authProvider(context: Context): AuthProvider =
        AuthProvider.fromWire(prefs(context).getString(KEY_AUTH_PROVIDER, null))

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
