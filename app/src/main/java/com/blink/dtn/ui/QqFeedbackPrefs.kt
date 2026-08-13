package com.blink.dtn.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val FEEDBACK_PREFS = "blink_prefs"
private const val KEY_SOUND = "qq_feedback_sound"
private const val KEY_VIBRATION = "qq_feedback_vibration"

/**
 * User switches for the three delivery feedback signals (incoming message,
 * message passed onward, delivery confirmed).
 *
 * Read from the mesh service thread as well as from Compose, so the values are
 * cached in memory and refreshed lazily from prefs on first access — Settings is
 * not guaranteed to have been opened before a signal fires.
 */
object QqFeedbackPrefs {

    private val _sound = MutableStateFlow(true)
    private val _vibration = MutableStateFlow(true)
    private var loaded = false

    val sound: StateFlow<Boolean> = _sound
    val vibration: StateFlow<Boolean> = _vibration

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext
            .getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
        _sound.value = prefs.getBoolean(KEY_SOUND, true)
        _vibration.value = prefs.getBoolean(KEY_VIBRATION, true)
        loaded = true
    }

    fun soundEnabled(context: Context): Boolean {
        init(context)
        return _sound.value
    }

    fun vibrationEnabled(context: Context): Boolean {
        init(context)
        return _vibration.value
    }

    fun setSound(context: Context, enabled: Boolean) {
        init(context)
        _sound.value = enabled
        context.applicationContext
            .getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    fun setVibration(context: Context, enabled: Boolean) {
        init(context)
        _vibration.value = enabled
        context.applicationContext
            .getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }
}
