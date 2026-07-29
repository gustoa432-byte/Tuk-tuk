package com.blink.dtn.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "blink_prefs"
private const val KEY_LANG = "lang"
private const val LANG_RU = "ru"
private const val LANG_EN = "en"

object AppLang {
    private val _lang = MutableStateFlow(LANG_RU)
    val lang: StateFlow<String> = _lang

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _lang.value = prefs.getString(KEY_LANG, LANG_RU) ?: LANG_RU
    }

    fun set(context: Context, lang: String) {
        val normalized = if (lang == LANG_EN || lang == "English") LANG_EN else LANG_RU
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, normalized).apply()
        _lang.value = normalized
    }

    fun isEn() = _lang.value == LANG_EN
}

/** Provides UI strings in the current language. */
object S {
    fun dialogs(lang: String) = if (lang == LANG_EN) "Dialogs" else "Диалоги"
    fun groupChat(lang: String) = if (lang == LANG_EN) "Group chat" else "Общий чат"
    fun profile(lang: String) = if (lang == LANG_EN) "Profile" else "Профиль"
    fun save(lang: String) = if (lang == LANG_EN) "Save" else "Сохранить"
    fun saved(lang: String) = if (lang == LANG_EN) "Saved" else "Сохранено"
    fun copy(lang: String) = if (lang == LANG_EN) "Copy" else "Копировать"
    fun scanQr(lang: String) = if (lang == LANG_EN) "Scan QR" else "Сканировать QR"
    fun send(lang: String) = if (lang == LANG_EN) "Send" else "Отправить"
    fun message(lang: String) = if (lang == LANG_EN) "Message..." else "Сообщение..."
    fun infoTitle(lang: String) = if (lang == LANG_EN) "About Tuk-Tuk" else "О Tuk-Tuk"
    fun close(lang: String) = if (lang == LANG_EN) "Close" else "Закрыть"
    fun enterName(lang: String) = if (lang == LANG_EN) "Name" else "Имя"
    fun enterNameHint(lang: String) = if (lang == LANG_EN) "Enter name..." else "Введите имя..."
    fun idCopied(lang: String) = if (lang == LANG_EN) "ID copied" else "ID скопирован"
    fun scanContact(lang: String) = if (lang == LANG_EN) "Scan contact QR" else "Отсканируйте QR другого пользователя"
}
