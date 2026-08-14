package com.blink.dtn.ui

import android.content.Context

/** First-run copy + the user's own opt-in number (not the address book). */
object PhoneContactsPrefs {
    private const val PREFS = "blink_prefs"
    private const val KEY_EXPLAINED = "qq_phone_contacts_explained"
    private const val KEY_MY_E164 = "qq_my_phone_e164"

    fun explained(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPLAINED, false)

    fun markExplained(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPLAINED, true).apply()
    }

    fun myE164(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MY_E164, "").orEmpty()

    fun setMyE164(context: Context, e164: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MY_E164, e164).apply()
    }

    fun clearMyE164(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_MY_E164).apply()
    }
}
