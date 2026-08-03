package com.blink.dtn.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Local-only reactions (not Telegram). Keyed by messageId → emoji.
 */
object ReactionStore {
    private const val PREFS = "blink_prefs"
    private const val KEY = "msg_reactions_v1"

    val palette = listOf("👍", "❤️", "😂", "🔥", "🙏", "😮")

    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map.asStateFlow()

    fun init(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        val out = mutableMapOf<String, String>()
        runCatching {
            val o = JSONObject(raw)
            o.keys().forEach { k -> out[k] = o.optString(k) }
        }
        _map.value = out
    }

    fun set(context: Context, messageId: String, emoji: String?) {
        val next = _map.value.toMutableMap()
        if (emoji.isNullOrBlank()) next.remove(messageId) else next[messageId] = emoji
        _map.value = next
        val o = JSONObject()
        next.forEach { (k, v) -> o.put(k, v) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun get(messageId: String): String? = _map.value[messageId]
}

/** Built-in sticker pack — own format: emoji tiles, not TG assets. */
object StickerPack {
    const val FORMAT = "tuktuk.stickers.v1"
    val stickers = listOf("🦕", "🌿", "📦", "🚶", "🚲", "📡", "🤝", "🌙", "☀️", "💧")
}
