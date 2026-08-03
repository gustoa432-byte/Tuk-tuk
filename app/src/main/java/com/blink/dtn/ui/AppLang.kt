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
    // Tabs
    fun dialogs(lang: String) = if (lang == LANG_EN) "Dialogs" else "Диалоги"
    fun groupChat(lang: String) = if (lang == LANG_EN) "Channels" else "Каналы"
    fun network(lang: String) = if (lang == LANG_EN) "Network" else "Сеть"
    fun profile(lang: String) = if (lang == LANG_EN) "Profile" else "Профиль"

    // Network / router
    fun networkActive(lang: String) = if (lang == LANG_EN)
        "Your network is active. Tuk-Tuk helps deliver messages"
    else
        "Ваша сеть активна. Tuk-Tuk помогает доставлять сообщения"
    fun networkWaiting(lang: String) = if (lang == LANG_EN)
        "Waiting for people nearby — connection where there are people"
    else
        "Ждём людей рядом — связь там где есть люди"
    fun slogan(lang: String) = if (lang == LANG_EN)
        "Connection where there are people"
    else
        "Связь там где есть люди"
    fun routeVia(lang: String, path: String) = if (lang == LANG_EN) "via $path" else "через $path"
    fun transferred(lang: String) = if (lang == LANG_EN) "Queued" else "В очереди"
    fun hopsToday(lang: String) = if (lang == LANG_EN) "Carries" else "Передач"
    fun devicesNearby(lang: String) = if (lang == LANG_EN) "Devices" else "Устройств"
    fun preferredRoute(lang: String) = if (lang == LANG_EN) "Preferred route" else "Предпочтительный путь"
    fun currentShipment(lang: String) = if (lang == LANG_EN) "Current message" else "Текущее сообщение"
    fun noShipment(lang: String) = if (lang == LANG_EN) "No message in flight" else "Сейчас ничего не летит"
    fun neighbors(lang: String) = if (lang == LANG_EN) "Neighbors" else "Соседи"
    fun noNeighbors(lang: String) = if (lang == LANG_EN) "No peers yet — walk, wait, or open Wi‑Fi Direct" else "Пока нет соседей — подойдите ближе или откройте Wi‑Fi Direct"
    fun vpsUrl(lang: String) = if (lang == LANG_EN) "VPS URL (online path)" else "VPS URL (онлайн-путь)"
    fun vpsUrlHint(lang: String) = if (lang == LANG_EN)
        "Example: https://your-server:8080 — leave empty to use only mesh"
    else
        "Например: https://ваш-сервер:8080 — пусто = только mesh"
    fun vpsSaved(lang: String) = if (lang == LANG_EN) "VPS URL saved" else "VPS URL сохранён"
    fun messageTracker(lang: String) = if (lang == LANG_EN) "Delivery tracker" else "Трекер доставки"

    // Actions
    fun save(lang: String) = if (lang == LANG_EN) "Save" else "Сохранить"
    fun saved(lang: String) = if (lang == LANG_EN) "Saved" else "Сохранено"
    fun copy(lang: String) = if (lang == LANG_EN) "Copy" else "Копировать"
    fun cancel(lang: String) = if (lang == LANG_EN) "Cancel" else "Отмена"
    fun close(lang: String) = if (lang == LANG_EN) "Close" else "Закрыть"
    fun send(lang: String) = if (lang == LANG_EN) "Send" else "Отправить"
    fun accept(lang: String) = if (lang == LANG_EN) "Accept" else "Принять"
    fun ignore(lang: String) = if (lang == LANG_EN) "Ignore" else "Игнорировать"
    fun addToContacts(lang: String) = if (lang == LANG_EN) "Add contact" else "В контакты"
    fun rename(lang: String) = if (lang == LANG_EN) "Rename" else "Изменить имя"
    fun get(lang: String) = if (lang == LANG_EN) "Download" else "Получить"
    fun hide(lang: String) = if (lang == LANG_EN) "Hide" else "Скрыть"
    fun scanQr(lang: String) = if (lang == LANG_EN) "Scan QR" else "Сканировать QR"
    fun verifyQr(lang: String) = if (lang == LANG_EN) "Verify QR" else "Сверить QR"
    fun deleteLocal(lang: String) = if (lang == LANG_EN) "Delete for me" else "Удалить у себя"
    fun cancelSend(lang: String) = if (lang == LANG_EN) "Cancel sending" else "Отменить отправку"
    fun blockUser(lang: String) = if (lang == LANG_EN) "Block user" else "Заблокировать пользователя"
    fun shareTukTuk(lang: String) = if (lang == LANG_EN) "Share Tuk-Tuk" else "Поделиться Tuk-Tuk"

    // Inputs / placeholders
    fun message(lang: String) = if (lang == LANG_EN) "Message..." else "Сообщение..."
    fun enterName(lang: String) = if (lang == LANG_EN) "Your nickname..." else "Ваш никнейм..."
    fun enterNameHint(lang: String) = if (lang == LANG_EN) "Enter nickname..." else "Введите никнейм..."
    fun enterPeerId(lang: String) = if (lang == LANG_EN) "Enter contact ID..." else "Введите ID собеседника..."
    fun findOrStartDialog(lang: String) = if (lang == LANG_EN) "Find or start a dialog..." else "Найти или начать диалог..."
    fun renameHint(lang: String) = if (lang == LANG_EN) "E.g. Vasya from work" else "Например, Вася с работы"

    // Notifications / toasts
    fun idCopied(lang: String) = if (lang == LANG_EN) "ID copied" else "ID скопирован"
    fun contactAccepted(lang: String) = if (lang == LANG_EN) "Contact accepted" else "Контакт принят"
    fun nameSaved(lang: String) = if (lang == LANG_EN) "Name saved" else "Имя сохранено"
    fun modeSet(lang: String, label: String) = if (lang == LANG_EN) "Mode: $label" else "Режим: $label"
    fun qrVerified(lang: String) = if (lang == LANG_EN) "Contact verified via QR" else "Контакт проверен по QR"
    fun qrKeyMismatch(lang: String) = if (lang == LANG_EN) "Invalid QR: key does not match ID" else "Неверный QR: ключ не совпадает с id"
    fun qrNotContact(lang: String) = if (lang == LANG_EN) "Need a TukTuk contact QR (with key)" else "Нужен QR контакта TukTuk (с ключом)"
    fun telegramError(lang: String) = if (lang == LANG_EN) "Could not open Telegram" else "Не удалось открыть Telegram"
    fun avatarSaved(lang: String) = if (lang == LANG_EN) "Avatar saved" else "Аватар сохранён"
    fun avatarTooBig(lang: String) = if (lang == LANG_EN) "Avatar too large for QR, saved locally" else "Аватар слишком большой для QR, сохранён локально"
    fun avatarSaveError(lang: String) = if (lang == LANG_EN) "Could not save avatar" else "Не удалось сохранить аватар"
    fun userBlocked(lang: String) = if (lang == LANG_EN) "User blocked" else "Пользователь заблокирован"

    // Empty states
    fun noDialogs(lang: String) = if (lang == LANG_EN)
        "No dialogs yet.\nTap 'Find or start a dialog...' to add a contact."
    else
        "У вас пока нет диалогов.\nНажмите 'Найти или начать диалог...', чтобы добавить контакт."
    fun noMessages(lang: String) = if (lang == LANG_EN) "No messages yet." else "Здесь пока нет сообщений."
    fun publicChatEmpty(lang: String) = if (lang == LANG_EN) "Public chat is quiet.\nSay something!" else "В общем чате пока тихо.\nНапишите что-нибудь!"
    fun anonymous(lang: String) = if (lang == LANG_EN) "Anonymous" else "Аноним"
    fun unknownContact(lang: String) = if (lang == LANG_EN) "Unknown contact" else "Неизвестный контакт"
    fun stranger(lang: String) = if (lang == LANG_EN) "Stranger" else "Незнакомец"
    fun fromNetwork(lang: String) = if (lang == LANG_EN) "from network" else "из сети"
    fun qrAlreadyVerified(lang: String) = if (lang == LANG_EN) "QR already verified" else "QR уже проверен"

    // Banners / hints
    fun strangerBanner(lang: String) = if (lang == LANG_EN)
        "Message request from a stranger. Nickname is just a label — check the ID. Accept, ignore or verify QR."
    else
        "Запрос сообщения от незнакомца. Ник — просто метка; смотрите id. Примите, игнорируйте или сверьте QR."
    fun verifyQrHint(lang: String) = if (lang == LANG_EN)
        "Contact from network. For family and close ones verify QR — this locks the key (\"verified\")."
    else
        "Контакт из сети. Для семьи и близких сверьте QR — так вы закрепите ключ («проверен»)."
    fun publicChatHint(lang: String) = if (lang == LANG_EN)
        "Public chat — open megaphone: nearby nodes see the text. No group encryption."
    else
        "Общий чат — открытый мегафон: соседние узлы видят текст. Без группового шифрования."
    fun renameTip(lang: String) = if (lang == LANG_EN)
        "Local label on this device only. The contact's network nick does not change."
    else
        "Локальная подпись только на этом устройстве. Сетевой ник собеседника не меняется (ник — просто метка, не уникальный id)."

    // Message actions dialog
    fun msgActionTitle(lang: String) = if (lang == LANG_EN) "Message" else "Сообщение"
    fun msgDeleteHintCanCancel(lang: String) = if (lang == LANG_EN)
        "Delete for yourself or cancel sending (while not yet sent to network)."
    else
        "Удалить только у себя или отменить отправку (пока не ушло в сеть)."
    fun msgDeleteHint(lang: String) = if (lang == LANG_EN)
        "Delete message on this device only. Others will still have it."
    else
        "Удалить сообщение только на этом устройстве. У других оно останется."

    // Profile & dialog rename
    fun renameDlgTitle(lang: String) = if (lang == LANG_EN) "Name in dialogs" else "Имя в диалогах"
    fun profileDlgTitle(lang: String) = if (lang == LANG_EN) "Profile" else "Профиль"

    // Update banner
    fun updateAvailable(lang: String, version: String) = if (lang == LANG_EN)
        "Version $version available nearby"
    else
        "Доступна версия $version рядом"
    fun updatePeer(lang: String, nick: String) = if (lang == LANG_EN)
        "From $nick · fast transfer via Wi-Fi Direct (experimental)"
    else
        "У $nick · быстрая передача по Wi‑Fi Direct (экспериментально)"

    // Network mode (battery)
    fun networkMode(lang: String) = if (lang == LANG_EN) "Network mode (battery)" else "Режим сети (батарея)"
    fun modeEconomy(lang: String) = if (lang == LANG_EN)
        "Rare scan — network stays up, battery lasts."
    else
        "Редкий скан — сеть живёт, телефон не садится за час."
    fun modeMax(lang: String) = if (lang == LANG_EN)
        "Dense scan — faster neighbors, higher drain."
    else
        "Плотный скан — быстрее соседи, выше расход."
    fun modeBalance(lang: String) = if (lang == LANG_EN)
        "Balance between discovery and battery."
    else
        "Баланс обнаружения и батареи."
    fun langLabel(lang: String) = if (lang == LANG_EN) "Language" else "Язык"

    // Settings
    fun settings(lang: String) = if (lang == LANG_EN) "Settings" else "Настройки"
    fun wallpaper(lang: String) = if (lang == LANG_EN) "Wallpaper" else "Обои"
    fun wallpaperHint(lang: String) = if (lang == LANG_EN)
        "Photo sits on a black background — dial how strong it shows."
    else
        "Фото поверх чёрного фона — ползунком настройте, насколько оно видно."
    fun chooseWallpaper(lang: String) = if (lang == LANG_EN) "Choose photo" else "Выбрать фото"
    fun resetWallpaper(lang: String) = if (lang == LANG_EN) "Remove wallpaper" else "Убрать обои"
    fun wallpaperNone(lang: String) = if (lang == LANG_EN) "Black background" else "Чёрный фон"
    fun wallpaperOpacity(lang: String) = if (lang == LANG_EN) "Wallpaper strength" else "Прозрачность обоев"
    fun wallpaperSaved(lang: String) = if (lang == LANG_EN) "Wallpaper saved" else "Обои сохранены"
    fun wallpaperReset(lang: String) = if (lang == LANG_EN) "Black background restored" else "Чёрный фон восстановлен"
    fun wallpaperError(lang: String) = if (lang == LANG_EN) "Could not set wallpaper" else "Не удалось установить обои"
    fun galleryDenied(lang: String) = if (lang == LANG_EN) "Gallery access denied" else "Нет доступа к галерее"

    // Scan contact
    fun scanContact(lang: String) = if (lang == LANG_EN) "Scan contact QR" else "Отсканируйте QR другого пользователя"

    // Info screen
    fun infoTitle(lang: String) = if (lang == LANG_EN) "About Tuk-Tuk" else "О Tuk-Tuk"
    fun infoTagline(lang: String) = if (lang == LANG_EN) "Connection where there are people." else "Связь там где есть люди."
    fun infoBody(lang: String) = if (lang == LANG_EN)
        "Tuk-Tuk works even when the internet is down and cell towers are silent. Your phones become the network, relaying messages from person to person."
    else
        "Tuk-Tuk работает, даже когда падает интернет и молчат вышки сотовой связи. Ваши телефоны сами становятся сетью, передавая сообщения от человека к человеку."
    fun infoContacts(lang: String) = if (lang == LANG_EN) "Contacts" else "Контакты"
    fun infoChannel(lang: String) = if (lang == LANG_EN) "• Project channel:" else "• Канал проекта:"
    fun infoBugs(lang: String) = if (lang == LANG_EN) "• Ideas and bug reports:" else "• Идеи и баг-репорты:"
    fun infoFooter(lang: String) = if (lang == LANG_EN)
        "® Built in Crimea. Works under any conditions."
    else
        "® Разработано в Крыму. Для работы в любых условиях"
    fun feedbackBody(lang: String) = if (lang == LANG_EN) "Describe the bug or idea:\n\n" else "Опишите ошибку или идею:\n\n"

    // Expedition / gamification
    fun expedition(lang: String) = if (lang == LANG_EN) "Expedition" else "Экспедиция"
    fun expeditionTagline(lang: String) = if (lang == LANG_EN)
        "You’re a courier. A message is a package. Your phone is a network node."
    else
        "Ты курьер. Сообщение — посылка. Телефон — узел сети."
    fun helpedFeeling(lang: String) = if (lang == LANG_EN)
        "After a relay you should feel: I helped the network."
    else
        "После передачи должно быть чувство: я помог сети."
    fun packagesWaiting(lang: String) = if (lang == LANG_EN) "Waiting" else "Ждут"
    fun packagesDelivered(lang: String) = if (lang == LANG_EN) "Helped" else "Передал"
    fun neighborsHelped(lang: String) = if (lang == LANG_EN) "Neighbors" else "Соседи"
    fun messagesReceived(lang: String) = if (lang == LANG_EN) "Received" else "Получено"
    fun livesSaved(lang: String) = if (lang == LANG_EN) "Saved" else "Спасено"
    fun queueNow(lang: String) = if (lang == LANG_EN) "Queue" else "Очередь"
    fun currentMission(lang: String) = if (lang == LANG_EN) "Current mission" else "Текущая миссия"
    fun noMission(lang: String) = if (lang == LANG_EN)
        "No packages waiting — rest or find people nearby."
    else
        "Нет посылок — можно отдыхать или искать людей рядом."
    fun packageInFlight(lang: String) = if (lang == LANG_EN) "Package on the way" else "Посылка в пути"
    fun packagesWaitingHint(lang: String, n: Int) = if (lang == LANG_EN)
        "$n packages waiting. Keep the phone near people."
    else
        "$n посылок ждут передачи. Держи телефон рядом с людьми."
    fun yourContribution(lang: String) = if (lang == LANG_EN) "Your contribution" else "Твой вклад"
    fun contributionBody(lang: String, helped: Int, saved: Int) = if (lang == LANG_EN)
        "You carried $helped packages. $saved reached someone who needed them."
    else
        "Ты передал $helped посылок. $saved дошли до тех, кому были нужны."
    fun cosmeticsOnly(lang: String) = if (lang == LANG_EN)
        "Reward is cosmetics only — themes, frames, nick color, rare dinosaurs."
    else
        "Награда — только косметика: темы, рамки, цвет ника, редкие динозавры."
    fun cosmetics(lang: String) = if (lang == LANG_EN) "Cosmetics" else "Украшения"
    fun cosmeticReady(lang: String) = if (lang == LANG_EN) "Unlocked — tap to equip" else "Открыто — нажми, чтобы надеть"
    fun cosmeticLocked(lang: String) = if (lang == LANG_EN) "Help the network to unlock" else "Помоги сети, чтобы открыть"
    fun equipped(lang: String) = if (lang == LANG_EN) "On" else "Надето"
    fun networkHelpStats(lang: String) = if (lang == LANG_EN) "Network help" else "Помощь сети"
    fun archive(lang: String) = if (lang == LANG_EN) "Archive" else "Архив"
    fun pinned(lang: String) = if (lang == LANG_EN) "Pinned" else "Закрепы"
    fun unreadOnly(lang: String) = if (lang == LANG_EN) "Unread" else "Непрочитанные"
    fun reply(lang: String) = if (lang == LANG_EN) "Reply" else "Ответить"
    fun forward(lang: String) = if (lang == LANG_EN) "Forward" else "Переслать"
    fun edit(lang: String) = if (lang == LANG_EN) "Edit" else "Изменить"
    fun select(lang: String) = if (lang == LANG_EN) "Select" else "Выбрать"
    fun selectedCount(lang: String, n: Int) = if (lang == LANG_EN) "$n selected" else "Выбрано: $n"
    fun channelsDistrict(lang: String) = if (lang == LANG_EN) "District" else "Район"
    fun channelsCity(lang: String) = if (lang == LANG_EN) "City" else "Город"
    fun channelsEmergency(lang: String) = if (lang == LANG_EN) "Emergency" else "Экстренные"
    fun channelsLocal(lang: String) = if (lang == LANG_EN) "Local" else "Локальные"
    fun channelsService(lang: String) = if (lang == LANG_EN) "Service" else "Служебные"

    // Living network (human)
    fun peopleNearby(lang: String) = if (lang == LANG_EN) "People nearby" else "Люди рядом"
    fun noPeopleNearby(lang: String) = if (lang == LANG_EN)
        "Nobody nearby yet — walk closer or wait."
    else
        "Пока никого рядом — подойди ближе или подожди."
    fun morePeopleNearby(lang: String, n: Int) = if (lang == LANG_EN)
        "And $n more nearby"
    else
        "И ещё $n рядом"
    fun howMessagesTravel(lang: String) = if (lang == LANG_EN) "How messages travel now" else "Как сейчас идут сообщения"
    fun autoRouteHint(lang: String) = if (lang == LANG_EN)
        "Chosen automatically — you only write the message."
    else
        "Выбирается само — ты только пишешь сообщение."
    fun helpedRelayCount(lang: String, n: Int) = if (lang == LANG_EN)
        "Helped carry $n"
    else
        "Помог передать $n"
    fun nearbyNow(lang: String) = if (lang == LANG_EN) "Nearby now" else "Сейчас рядом"
}

