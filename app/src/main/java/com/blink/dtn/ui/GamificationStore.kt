package com.blink.dtn.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gamification = courier contribution (not money / crypto).
 * Cosmetics only. Emotion: "I helped the network."
 */
object GamificationStore {

    private const val PREFS = "blink_prefs"
    private const val KEY_HELPED = "gm_helped"
    private const val KEY_RECEIVED = "gm_received"
    private const val KEY_SAVED = "gm_saved"
    private const val KEY_THEME = "gm_theme"
    private const val KEY_FRAME = "gm_frame"
    private const val KEY_NICK_COLOR = "gm_nick_color"
    private const val KEY_UNLOCKED = "gm_unlocked"
    private const val KEY_DINO = "gm_dino"

    @Volatile
    private var appContext: Context? = null

    data class Snapshot(
        val helped: Int = 0,
        val received: Int = 0,
        val saved: Int = 0,
        val themeId: String = "default",
        val frameId: String = "none",
        val nickColorId: String = "default",
        val dinoId: String = "dino_basic",
        val unlocked: Set<String> = setOf("default", "none", "dino_basic"),
        val lastHelpedAt: Long = 0L
    )

    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val unlocked = p.getStringSet(KEY_UNLOCKED, setOf("default", "none", "dino_basic"))
            ?.toSet()
            ?: setOf("default", "none", "dino_basic")
        _snap.value = Snapshot(
            helped = p.getInt(KEY_HELPED, 0),
            received = p.getInt(KEY_RECEIVED, 0),
            saved = p.getInt(KEY_SAVED, 0),
            themeId = p.getString(KEY_THEME, "default") ?: "default",
            frameId = p.getString(KEY_FRAME, "none") ?: "none",
            nickColorId = p.getString(KEY_NICK_COLOR, "default") ?: "default",
            dinoId = p.getString(KEY_DINO, "dino_basic") ?: "dino_basic",
            unlocked = unlocked
        )
        maybeUnlock(context)
    }

    /** Safe from mesh service when UI may not have opened Expedition yet. */
    fun noteHelpedRelay(context: Context? = appContext) {
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) return
        val ctx = context ?: return
        if (appContext == null) init(ctx)
        bump(ctx, KEY_HELPED) {
            it.copy(helped = it.helped + 1, lastHelpedAt = System.currentTimeMillis())
        }
    }

    fun noteReceived(context: Context? = appContext) {
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) return
        val ctx = context ?: return
        if (appContext == null) init(ctx)
        bump(ctx, KEY_RECEIVED) { it.copy(received = it.received + 1) }
    }

    fun noteSavedDelivery(context: Context? = appContext) {
        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) return
        val ctx = context ?: return
        if (appContext == null) init(ctx)
        bump(ctx, KEY_SAVED) { it.copy(saved = it.saved + 1) }
    }

    fun selectCosmetic(context: Context, kind: String, id: String) {
        if (id !in _snap.value.unlocked) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = when (kind) {
            "theme" -> {
                p.edit().putString(KEY_THEME, id).apply()
                _snap.value.copy(themeId = id)
            }
            "frame" -> {
                p.edit().putString(KEY_FRAME, id).apply()
                _snap.value.copy(frameId = id)
            }
            "nick" -> {
                p.edit().putString(KEY_NICK_COLOR, id).apply()
                _snap.value.copy(nickColorId = id)
            }
            "dino" -> {
                p.edit().putString(KEY_DINO, id).apply()
                _snap.value.copy(dinoId = id)
            }
            else -> _snap.value
        }
        _snap.value = next
    }

    private fun bump(context: Context, key: String, map: (Snapshot) -> Snapshot) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = _snap.value
        val next = map(cur)
        val value = when (key) {
            KEY_HELPED -> next.helped
            KEY_RECEIVED -> next.received
            KEY_SAVED -> next.saved
            else -> 0
        }
        p.edit().putInt(key, value).apply()
        _snap.value = next
        maybeUnlock(context)
    }

    private fun maybeUnlock(context: Context) {
        val s = _snap.value
        val add = mutableSetOf<String>()
        if (s.helped >= 1) add += "frame_lime"
        if (s.helped >= 10) add += "theme_night_road"
        if (s.helped >= 25) add += "nick_lime"
        if (s.helped >= 50) add += "dino_rare"
        if (s.saved >= 5) add += "frame_gold"
        if (add.isEmpty()) return
        val unlocked = s.unlocked + add
        if (unlocked == s.unlocked) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_UNLOCKED, unlocked).apply()
        _snap.value = s.copy(unlocked = unlocked)
    }

    data class Cosmetic(
        val id: String,
        val kind: String,
        val titleRu: String,
        val titleEn: String
    )

    val catalog = listOf(
        Cosmetic("default", "theme", "Обычная", "Default"),
        Cosmetic("theme_night_road", "theme", "Ночная дорога", "Night road"),
        Cosmetic("none", "frame", "Без рамки", "No frame"),
        Cosmetic("frame_lime", "frame", "Лаймовая", "Lime"),
        Cosmetic("frame_gold", "frame", "Спасатель", "Lifesaver"),
        Cosmetic("default", "nick", "Белый", "White"),
        Cosmetic("nick_lime", "nick", "Лаймовый ник", "Lime nick"),
        Cosmetic("dino_basic", "dino", "Обычный дино", "Basic dino"),
        Cosmetic("dino_rare", "dino", "Редкий дино", "Rare dino")
    )
}
