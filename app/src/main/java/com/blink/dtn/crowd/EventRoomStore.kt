package com.blink.dtn.crowd

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Offline event room — join via QR without OTP (BitChat-friction killer).
 * Mesh room id maps to PUBLIC flood channel when in Crowd mode.
 */
object EventRoomStore {
    private const val PREFS = "blink_prefs"
    private const val KEY = "event_room_json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _room = MutableStateFlow<EventRoom?>(null)
    val room: StateFlow<EventRoom?> = _room.asStateFlow()

    fun init(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return
        _room.value = runCatching { json.decodeFromString<EventRoom>(raw) }.getOrNull()
    }

    fun current(): EventRoom? = _room.value

    fun create(context: Context, title: String, passphrase: String = ""): EventRoom {
        val room = EventRoom(
            id = UUID.randomUUID().toString().take(8),
            title = title.trim().ifBlank { "Event" }.take(40),
            passphrase = passphrase.trim().take(32),
            createdAt = System.currentTimeMillis()
        )
        persist(context, room)
        return room
    }

    fun join(context: Context, id: String, title: String = "", passphrase: String = ""): EventRoom {
        val room = EventRoom(
            id = id.trim().take(16),
            title = title.trim().ifBlank { id }.take(40),
            passphrase = passphrase.trim().take(32),
            createdAt = System.currentTimeMillis()
        )
        persist(context, room)
        return room
    }

    fun leave(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
        _room.value = null
    }

    fun qrPayload(): String {
        val r = _room.value ?: return ""
        return json.encodeToString(
            EventRoomQr(v = 1, id = r.id, t = r.title, p = r.passphrase)
        )
    }

    fun parseQr(raw: String): EventRoomQr? =
        runCatching { json.decodeFromString<EventRoomQr>(raw) }.getOrNull()

    private fun persist(context: Context, room: EventRoom) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(room))
            .apply()
        _room.value = room
    }
}

@Serializable
data class EventRoom(
    val id: String,
    val title: String,
    val passphrase: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class EventRoomQr(
    val v: Int = 1,
    val id: String,
    val t: String = "",
    val p: String = ""
)
