package com.blink.dtn.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room converters for Human Layer fields (hop chain as JSON list). */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun hopHistoryToString(value: List<String>?): String =
        json.encodeToString(value ?: emptyList())

    @TypeConverter
    fun hopHistoryFromString(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(value) }
            .getOrDefault(emptyList())
    }
}
