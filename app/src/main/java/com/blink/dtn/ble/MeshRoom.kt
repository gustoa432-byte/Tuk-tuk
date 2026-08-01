package com.blink.dtn.ble

/**
 * On-wire room identifiers are single-char digit strings ("0".."9").
 * Short IDs keep JSON payload minimal — room field costs 5 bytes total
 * ("room":"0") vs e.g. 23 bytes for "emergency_sos".
 *
 * QoS priority:
 *   PRIORITY 0 — system/ACK (handled separately in BleRelayEngine)
 *   PRIORITY 1 — SOS room "0"
 *   PRIORITY 2 — base rooms "1".."8"
 *   PRIORITY 3 — flood room "9" (lowest, dropped first on buffer overflow)
 */
object MeshRoom {

    // ── Wire IDs ────────────────────────────────────────────────────────────
    const val IMPORTANT = "0"
    const val NEWS      = "1"   // was "general" — migrated in v16
    const val MARKET    = "2"
    const val NEIGHBORS = "3"
    const val HELP      = "4"
    const val MEETUPS   = "5"
    const val DATING    = "6"
    const val HUMOR     = "7"
    const val GAMES     = "8"
    const val SMOKING   = "9"

    /** Legacy value stored by older versions — treated as GENERAL on read. */
    const val GENERAL = NEWS
    const val FLOOD = SMOKING
    const val LEGACY_GENERAL = "general"

    /** Normalise any legacy "general" value coming off the wire or from DB. */
    fun normalise(raw: String): String = if (raw == LEGACY_GENERAL) GENERAL else raw

    // ── QoS priority (lower = more urgent) ──────────────────────────────────
    /**
     * Priority for relay queue sorting.
     * System/ACK packets are pre-filtered before this is called.
     */
    fun priority(room: String): Int = when (normalise(room)) {
        IMPORTANT, NEWS -> 1
        MARKET, NEIGHBORS, HELP, MEETUPS -> 2
        DATING, HUMOR, GAMES -> 3
        SMOKING -> 4
        else -> 2
    }

    /**
     * True for rooms whose messages must NEVER be dropped by the Drop Policy,
     * regardless of queue depth.
     */
    fun isProtected(room: String): Boolean = normalise(room) == IMPORTANT

    // ── UI labels (RU / EN) ─────────────────────────────────────────────────
    data class RoomMeta(
        val id: String,
        val titleRu: String,
        val titleEn: String,
        val subtitleRu: String,
        val subtitleEn: String
    )

    val ALL: List<RoomMeta> = listOf(
        RoomMeta(IMPORTANT, "Важное", "Important", "Свет, вода, связь, экстренные ситуации", "Power, water, connectivity, emergencies"),
        RoomMeta(NEWS, "Новости", "News", "Сводки, слухи и инфа из внешнего мира", "Updates, rumors and outside-world info"),
        RoomMeta(MARKET, "Рынок", "Market", "Обмен, покупка, продажа", "Exchange, buying and selling"),
        RoomMeta(NEIGHBORS, "Соседи", "Neighbors", "Общение двором или районом", "Talk by courtyard or neighborhood"),
        RoomMeta(HELP, "Помощь", "Help", "Вопросы, советы и взаимовыручка", "Questions, advice and mutual aid"),
        RoomMeta(MEETUPS, "Сходки", "Meetups", "Где собираемся, где работает генератор и варят кофе", "Where to gather, where the generator works and coffee is brewing"),
        RoomMeta(DATING, "Знакомства", "Dating", "Найти компанию на вечер без интернета", "Find company for the evening without internet"),
        RoomMeta(HUMOR, "Юмор", "Humor", "Мемы, анекдоты, смех сквозь слезы", "Memes, jokes, laughter through tears"),
        RoomMeta(GAMES, "Игры", "Games", "Словесные игры, поиск компании для настолок", "Word games and finding people for board games"),
        RoomMeta(SMOKING, "Курилка", "Offtopic", "Общение обо всем. Удаляется первым при нехватке памяти", "Talk about anything. Dropped first when memory is tight"),
    )

    fun label(id: String, lang: String = "ru"): String {
        val meta = ALL.firstOrNull { it.id == normalise(id) } ?: return id
        return if (lang == "en") meta.titleEn else meta.titleRu
    }

    fun subtitle(id: String, lang: String = "ru"): String {
        val meta = ALL.firstOrNull { it.id == normalise(id) } ?: return ""
        return if (lang == "en") meta.subtitleEn else meta.subtitleRu
    }
}
