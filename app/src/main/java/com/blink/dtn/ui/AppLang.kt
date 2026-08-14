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
    fun hub(lang: String) = if (lang == LANG_EN) "Hub" else "Хаб"
    fun hubRadar(lang: String) = if (lang == LANG_EN) "Radar" else "Сонар"
    fun hubCourier(lang: String) = if (lang == LANG_EN) "Courier" else "Курьер"
    fun hubChronicle(lang: String) = if (lang == LANG_EN) "Chronicle" else "Хроника"
    fun hubRadarSearching(lang: String) = if (lang == LANG_EN)
        "Listening for nearby couriers…" else "Слушаю соседних курьеров…"
    fun hubRadarSignal(lang: String) = if (lang == LANG_EN)
        "Signal on the edge…" else "Сигнал на краю радиуса…"
    fun hubRadarHandshake(lang: String) = if (lang == LANG_EN)
        "Handshake complete" else "Рукопожатие успешно"
    fun hubCourierHint(lang: String) = if (lang == LANG_EN)
        "Your dino carries the backpack of parcels" else "Дино несёт рюкзак с посылками"
    fun hubBackpack(lang: String) = if (lang == LANG_EN) "Backpack" else "Рюкзак"
    fun hubChronicleHint(lang: String) = if (lang == LANG_EN)
        "Delivered routes and karma" else "Доставленные маршруты и карма"
    fun hubChainOfCustody(lang: String) = if (lang == LANG_EN)
        "Chain of custody" else "Маршрутный лист"
    fun hubThankChain(lang: String) = if (lang == LANG_EN)
        "❤️ Thank the chain" else "❤️ Поблагодарить цепочку"
    fun hubThanked(lang: String) = if (lang == LANG_EN)
        "Thanks sent (soon)" else "Благодарность отправлена (скоро)"

    fun profile(lang: String) = if (lang == LANG_EN) "Profile" else "Профиль"

    // Network / router
    fun networkActive(lang: String) = if (lang == LANG_EN)
        "Your network is active. Qq helps deliver messages"
    else
        "Ваша сеть активна. Qq помогает доставлять сообщения"
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
    fun noNeighbors(lang: String) = if (lang == LANG_EN)
        "No one nearby yet — come closer or wait"
    else
        "Пока никого рядом — подойдите ближе или подождите"
    fun vpsUrl(lang: String) = if (lang == LANG_EN) "Delivery server (optional)" else "Сервер доставки (необязательно)"
    fun vpsUrlHint(lang: String) = if (lang == LANG_EN)
        "Example: https://your-server:8080 — leave empty; Qq will still find a path through people"
    else
        "Например: https://ваш-сервер:8080 — можно пусто; Qq всё равно найдёт путь через людей"
    fun vpsSaved(lang: String) = if (lang == LANG_EN) "Delivery server saved" else "Сервер доставки сохранён"
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
    fun reportMessage(lang: String) = if (lang == LANG_EN) "Report" else "Пожаловаться"
    fun reportSent(lang: String) = if (lang == LANG_EN) "Report sent" else "Жалоба отправлена"
    fun reportFailed(lang: String) = if (lang == LANG_EN) "Report failed — sign in online" else "Жалоба не отправлена — нужен вход"
    fun shareTukTuk(lang: String) = if (lang == LANG_EN) "Share Qq" else "Поделиться Qq"

    // Inputs / placeholders
    fun message(lang: String) = if (lang == LANG_EN) "Message..." else "Сообщение..."
    fun enterName(lang: String) = if (lang == LANG_EN) "Your nickname..." else "Ваш никнейм..."
    fun enterNameHint(lang: String) = if (lang == LANG_EN) "Enter nickname..." else "Введите никнейм..."
    fun enterPeerId(lang: String) = if (lang == LANG_EN)
        "Their Qq ID..." else "Qq ID собеседника..."
    fun findOrStartDialog(lang: String) = if (lang == LANG_EN) "Find or start a dialog..." else "Найти или начать диалог..."
    fun renameHint(lang: String) = if (lang == LANG_EN) "E.g. Vasya from work" else "Например, Вася с работы"

    // Auth / onboarding
    fun authSignInWith(lang: String) = if (lang == LANG_EN) "Sign in with" else "Войти через"
    fun authSignInTitle(lang: String) = if (lang == LANG_EN)
        "Email, Telegram, or go fully offline" else "Email, Telegram или полностью офлайн"
    fun authWelcomeBody(lang: String) = if (lang == LANG_EN)
        "Looks like you're heading on a small journey through time. Somewhere with no light, no internet, no cell towers. Welcome to the age of dinosaurs. Luckily, messages still know how to find a path."
    else
        "Похоже, вы собираетесь в небольшое путешествие во времени. Туда, где нет света, нет интернета, нет вышек связи. Добро пожаловать в эпоху динозавров. К счастью, сообщения всё ещё умеют находить путь."
    fun authEmail(lang: String) = "Email"
    fun authEmailHint(lang: String) = "you@example.com"
    fun authSendOtp(lang: String) = if (lang == LANG_EN) "Send code" else "Отправить код"
    fun authGetCode(lang: String) = if (lang == LANG_EN) "Get code" else "Получить код"
    fun authOtp(lang: String) = if (lang == LANG_EN) "Code from email" else "Код из письма"
    fun authOtpHint(lang: String) = if (lang == LANG_EN) "6 digits" else "6 цифр"
    fun authVerifyEmail(lang: String) = if (lang == LANG_EN) "Verify email" else "Подтвердить email"
    fun authEmailOk(lang: String) = if (lang == LANG_EN) "Email verified" else "Email подтверждён"
    fun authCodeSent(lang: String) = if (lang == LANG_EN) "Code sent" else "Код отправлен"
    fun authTgInitData(lang: String) = "Telegram initData"
    fun authTgHint(lang: String) = if (lang == LANG_EN)
        "Paste WebApp initData" else "Вставь initData из Mini App"
    fun authTgSignIn(lang: String) = if (lang == LANG_EN) "Sign in with Telegram" else "Войти через Telegram"
    fun authTgConfirm(lang: String) = if (lang == LANG_EN) "Confirm Telegram" else "Подтвердить Telegram"
    fun authOrOffline(lang: String) = if (lang == LANG_EN) "or continue offline" else "или продолжить офлайн"
    fun authOfflineContinue(lang: String) = if (lang == LANG_EN) "Continue offline" else "Продолжить офлайн"
    fun vpsSignIn(lang: String) = if (lang == LANG_EN) "Sign in to delivery server" else "Вход на сервер доставки"
    fun vpsSignInOk(lang: String) = if (lang == LANG_EN) "Delivery server session saved" else "Сессия сервера доставки сохранена"
    fun vpsSessionLabel(lang: String) = if (lang == LANG_EN) "Delivery server session" else "Сессия сервера доставки"
    fun vpsSessionOn(lang: String) = if (lang == LANG_EN)
        "Signed in — messages can also go over the internet when there is any"
    else
        "Вход выполнен — сообщения могут идти ещё и через интернет, когда он есть"
    fun vpsSessionOff(lang: String) = if (lang == LANG_EN)
        "Not signed in — messages go only through people nearby"
    else
        "Входа нет — сообщения идут только через людей рядом"
    fun authSocialSoon(lang: String) = if (lang == LANG_EN)
        "Coming soon" else "Скоро"
    fun authNeedOnline(lang: String) = if (lang == LANG_EN)
        "Need internet + VPS" else "Нужен интернет и VPS"
    fun hubBackpackEmpty(lang: String) = if (lang == LANG_EN)
        "Backpack is empty" else "Рюкзак пуст"
    fun hubChronicleEmpty(lang: String) = if (lang == LANG_EN)
        "No completed routes yet" else "Пока нет завершённых маршрутов"
    fun handshakeOk(lang: String) = if (lang == LANG_EN)
        "Contact saved — you can write without the internet" else "Контакт сохранён — можно писать без интернета"
    fun handshakeFail(lang: String) = if (lang == LANG_EN)
        "Could not add contact online" else "Не удалось добавить контакт онлайн"
    fun authProfileTitle(lang: String) = if (lang == LANG_EN) "Your profile" else "Твой профиль"
    fun authProfileHint(lang: String) = if (lang == LANG_EN)
        "Name and nickname are optional. Empty name becomes a random dinosaur."
    else
        "Имя и ник необязательны. Пустое имя — случайный динозавр."
    fun authDisplayName(lang: String) = if (lang == LANG_EN) "Name" else "Имя"
    fun authDisplayNameHint(lang: String) = if (lang == LANG_EN) "Up to 20 characters" else "До 20 символов"
    fun authNickname(lang: String) = if (lang == LANG_EN) "Nickname" else "Никнейм"
    fun authNicknameHint(lang: String) = if (lang == LANG_EN) "Optional @handle" else "Необязательный @ник"
    fun authContinue(lang: String) = if (lang == LANG_EN) "Continue" else "Продолжить"

    // Permission rationale — product terms, not Android terms.
    fun permTitle(lang: String) = if (lang == LANG_EN)
        "Let Qq see phones nearby"
    else
        "Разрешите Qq видеть телефоны рядом"
    fun permBody(lang: String) = if (lang == LANG_EN)
        "Qq delivers your messages through the phones of people around you, so it has to " +
            "notice when someone is close. That is what Bluetooth is for here.\n\n" +
            "Your phone will also ask for location access: on this version of Android, " +
            "looking for phones nearby is only allowed together with it. Qq never reads, " +
            "stores or sends where you are.\n\n" +
            "The camera is asked for separately, only at the moment you scan someone's QR code."
    else
        "Qq доставляет ваши сообщения через телефоны людей вокруг, поэтому ему нужно " +
            "замечать, когда кто-то оказался рядом. Именно для этого здесь Bluetooth.\n\n" +
            "Телефон попросит ещё и доступ к местоположению: на этой версии Android искать " +
            "телефоны рядом разрешено только вместе с ним. Qq никогда не читает, не хранит " +
            "и не отправляет, где вы находитесь.\n\n" +
            "Камера запрашивается отдельно — только в момент, когда вы сканируете чужой QR-код."
    fun permGrant(lang: String) = if (lang == LANG_EN) "Allow" else "Разрешить"

    // First run — the whole product in three lines.
    fun welcomeTitle(lang: String) = if (lang == LANG_EN) "Qq" else "Qq"
    fun welcomeTagline(lang: String) = if (lang == LANG_EN)
        "Messages without the internet."
    else
        "Сообщения без интернета."
    fun welcomeWhat(lang: String) = if (lang == LANG_EN)
        "When there is no internet and no mobile network, your message travels through the " +
            "phones of people around you until it reaches the person you wrote to."
    else
        "Когда нет интернета и не ловит сеть, ваше сообщение идёт через телефоны людей " +
            "вокруг, пока не дойдёт до того, кому вы написали."
    fun welcomeHow(lang: String) = if (lang == LANG_EN)
        "To add someone, tap + and find them by Qq address, or scan their QR. QR marks them as verified."
    else
        "Чтобы добавить человека, нажмите + и найдите его по адресу Qq — или отсканируйте QR. QR помечает контакт как проверенный."
    fun welcomeTime(lang: String) = if (lang == LANG_EN)
        "Delivery can take minutes or hours: the message waits until someone walks close " +
            "enough to carry it further. You can close the app — it keeps working."
    else
        "Доставка может занять минуты или часы: сообщение ждёт, пока кто-нибудь подойдёт " +
            "достаточно близко, чтобы понести его дальше. Приложение можно закрыть — оно продолжит работать."
    fun welcomeStart(lang: String) = if (lang == LANG_EN) "Got it" else "Понятно"

    // Notifications / toasts
    fun idCopied(lang: String) = if (lang == LANG_EN) "ID copied" else "ID скопирован"
    fun contactAccepted(lang: String) = if (lang == LANG_EN) "Contact accepted" else "Контакт принят"
    fun nameSaved(lang: String) = if (lang == LANG_EN) "Name saved" else "Имя сохранено"
    fun modeSet(lang: String, label: String) = if (lang == LANG_EN) "Mode: $label" else "Режим: $label"
    fun qrVerified(lang: String) = if (lang == LANG_EN) "Contact verified via QR" else "Контакт проверен по QR"
    fun qrKeyMismatch(lang: String) = if (lang == LANG_EN)
        "This QR does not match that person — ask them to show it again"
    else
        "Этот QR не совпадает с человеком — попросите показать код ещё раз"
    fun qrNotContact(lang: String) = if (lang == LANG_EN)
        "That is not a Qq contact code — ask them to open Qq and show their QR"
    else
        "Это не код контакта Qq — попросите открыть Qq и показать свой QR"
    fun qrSelf(lang: String) = if (lang == LANG_EN) "That is your own QR" else "Это ваш собственный QR"
    fun telegramError(lang: String) = if (lang == LANG_EN) "Could not open Telegram" else "Не удалось открыть Telegram"
    fun avatarSaved(lang: String) = if (lang == LANG_EN) "Avatar saved" else "Аватар сохранён"
    fun avatarTooBig(lang: String) = if (lang == LANG_EN) "Avatar too large for QR, saved locally" else "Аватар слишком большой для QR, сохранён локально"
    fun avatarSaveError(lang: String) = if (lang == LANG_EN) "Could not save avatar" else "Не удалось сохранить аватар"
    fun userBlocked(lang: String) = if (lang == LANG_EN) "User blocked" else "Пользователь заблокирован"

    // Empty states
    fun noDialogs(lang: String) = if (lang == LANG_EN)
        "No dialogs yet.\nTap + to add a contact."
    else
        "У вас пока нет диалогов.\nНажмите +, чтобы добавить контакт."
    fun noMessages(lang: String) = if (lang == LANG_EN)
        "Send the first message — Qq will deliver it when it finds a way."
    else
        "Напишите первым — Qq доставит, когда найдёт путь."
    fun publicChatEmpty(lang: String) = if (lang == LANG_EN) "Public chat is quiet.\nSay something!" else "В общем чате пока тихо.\nНапишите что-нибудь!"
    fun anonymous(lang: String) = if (lang == LANG_EN) "Anonymous" else "Аноним"
    fun unknownContact(lang: String) = if (lang == LANG_EN) "Unknown contact" else "Неизвестный контакт"
    fun stranger(lang: String) = if (lang == LANG_EN) "Stranger" else "Незнакомец"
    fun fromNetwork(lang: String) = if (lang == LANG_EN) "not verified" else "не сверен"
    fun qrAlreadyVerified(lang: String) = if (lang == LANG_EN) "QR already verified" else "QR уже проверен"

    // Banners / hints
    fun strangerBanner(lang: String) = if (lang == LANG_EN)
        "Someone you have not added is writing to you. Anyone can pick any name, so trust the " +
            "ID, not the name. Accept, ignore, or scan their QR to be sure."
    else
        "Вам пишет человек, которого вы не добавляли. Имя может выбрать кто угодно — верьте " +
            "не имени, а ID. Примите, игнорируйте или отсканируйте QR, чтобы убедиться."
    fun verifyQrHint(lang: String) = if (lang == LANG_EN)
        "You have not checked in person that this is really them. Scan their QR once when you " +
            "meet — after that Qq will warn you if anyone tries to take their place."
    else
        "Вы ещё не убедились лично, что это действительно он. Отсканируйте его QR при встрече — " +
            "после этого Qq предупредит, если кто-то попробует занять его место."
    fun publicChatHint(lang: String) = if (lang == LANG_EN)
        "Public chat is an open megaphone: every phone nearby can read it. Not private."
    else
        "Общий чат — открытый мегафон: его читает любой телефон рядом. Это не личная переписка."
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
        "From $nick · faster nearby transfer (experimental)"
    else
        "У $nick · быстрая передача рядом (экспериментально)"

    // Network mode (battery)
    fun networkMode(lang: String) = if (lang == LANG_EN) "Battery" else "Батарея"
    fun networkModeHint(lang: String) = if (lang == LANG_EN)
        "How often Qq looks for people around you."
    else
        "Как часто Qq ищет людей вокруг."
    fun modeEconomy(lang: String) = if (lang == LANG_EN)
        "Looks around rarely. Delivery is slower, the battery lasts much longer."
    else
        "Смотрит вокруг редко. Доставка медленнее, зато телефон живёт дольше."
    fun modeMax(lang: String) = if (lang == LANG_EN)
        "Looks around constantly. People are found fastest, the battery drains fastest."
    else
        "Смотрит вокруг постоянно. Людей находит быстрее всего, батарея садится быстрее всего."
    fun modeBalance(lang: String) = if (lang == LANG_EN)
        "The usual choice — decent speed without eating the battery."
    else
        "Обычный выбор — нормальная скорость и батарея не тает."
    fun modeCrowd(lang: String) = if (lang == LANG_EN)
        "For a crowd — a stadium, a square, a queue. Many people at once, short messages."
    else
        "Для толпы — стадион, площадь, очередь. Много людей сразу, короткие сообщения."

    fun crowdTitle(lang: String) = if (lang == LANG_EN) "Nearby / Crowd" else "Рядом / Толпа"
    fun crowdSubtitle(lang: String) = if (lang == LANG_EN)
        "Short messages in dense air. VPS still builds contacts & Oracle hints."
    else
        "Короткие сообщения в плотном эфире. VPS по-прежнему для контактов и Оракула."
    fun crowdDensity(lang: String, window: Int, peak: Int) = if (lang == LANG_EN)
        "Dense window: $window advertisers · peak $peak"
    else
        "Плотность: $window реклам в окне · пик $peak"
    fun crowdEnable(lang: String) = if (lang == LANG_EN) "Crowd on" else "Толпа вкл"
    fun crowdDisable(lang: String) = if (lang == LANG_EN) "Crowd off" else "Толпа выкл"
    fun crowdModeOn(lang: String) = if (lang == LANG_EN) "Crowd radio on (4h)" else "Режим Толпа включён (4ч)"
    fun crowdModeOff(lang: String) = if (lang == LANG_EN) "Back to Normal duty" else "Снова обычный режим"
    fun crowdRoomHint(lang: String) = if (lang == LANG_EN) "Event title…" else "Название события…"
    fun crowdCreateRoom(lang: String) = if (lang == LANG_EN) "Create offline room" else "Создать офлайн-комнату"
    fun crowdRoomCreated(lang: String) = if (lang == LANG_EN) "Room ready — share QR later" else "Комната готова"
    fun crowdRoomActive(lang: String, title: String, id: String) =
        if (lang == LANG_EN) "Room «$title» · $id" else "Комната «$title» · $id"
    fun crowdLeaveRoom(lang: String) = if (lang == LANG_EN) "Leave room" else "Выйти из комнаты"
    fun crowdAnchorStart(lang: String) = if (lang == LANG_EN) "iPhone Wi‑Fi anchor" else "Якорь Wi‑Fi для iPhone"
    fun crowdAnchorStop(lang: String) = if (lang == LANG_EN) "Stop anchor" else "Остановить якорь"
    fun crowdAnchorOn(lang: String, url: String) =
        if (lang == LANG_EN) "Anchor on · $url" else "Якорь включён · $url"
    fun crowdAnchorOff(lang: String) = if (lang == LANG_EN) "Anchor stopped" else "Якорь выключен"
    fun crowdAnchorHint(lang: String, url: String) = if (lang == LANG_EN)
        "Hotspot this phone → open $url in Safari (PWA, no App Store)."
    else
        "Раздайте Wi‑Fi с этого телефона → в Safari откройте $url (PWA, без App Store)."
    fun crowdPresence(lang: String) = if (lang == LANG_EN) "Ping" else "Пинг"
    fun crowdComposeHint(lang: String) = if (lang == LANG_EN) "Short nearby text…" else "Короткий текст рядом…"
    fun crowdSend(lang: String) = if (lang == LANG_EN) "Send nearby" else "Отправить рядом"
    fun hubCrowd(lang: String) = if (lang == LANG_EN) "Crowd" else "Толпа"

    fun langLabel(lang: String) = if (lang == LANG_EN) "Language" else "Язык"

    // Settings
    fun settings(lang: String) = if (lang == LANG_EN) "Settings" else "Настройки"
    fun wallpaper(lang: String) = if (lang == LANG_EN) "Wallpaper" else "Обои"
    fun wallpaperHint(lang: String) = if (lang == LANG_EN)
        "Built-in pack starts at 25% over black. You can pick a photo or turn the strength."
    else
        "Встроенный набор сразу ставится на 25% поверх чёрного. Можно своё фото или подвинуть силу."
    fun chooseWallpaper(lang: String) = if (lang == LANG_EN) "Choose photo" else "Выбрать фото"
    fun wallpaperPack(lang: String) = if (lang == LANG_EN) "Qq wallpapers" else "Обои Qq"
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
    fun infoTitle(lang: String) = if (lang == LANG_EN) "About Qq" else "О Qq"
    fun infoTagline(lang: String) = if (lang == LANG_EN) "Connection where there are people." else "Связь там где есть люди."
    fun infoBody(lang: String) = if (lang == LANG_EN)
        "Qq works even when the internet is down and cell towers are silent. Your phones become the network, passing messages from person to person."
    else
        "Qq работает, даже когда падает интернет и молчат вышки сотовой связи. Ваши телефоны сами становятся сетью, передавая сообщения от человека к человеку."
    fun infoContacts(lang: String) = if (lang == LANG_EN) "Contacts" else "Контакты"
    fun infoChannel(lang: String) = if (lang == LANG_EN) "• Project channel:" else "• Канал проекта:"
    fun infoBugs(lang: String) = if (lang == LANG_EN) "• Ideas and bug reports:" else "• Идеи и баг-репорты:"
    fun errorReportButton(lang: String) = if (lang == LANG_EN) "Error report" else "Отчет об ошибках"
    fun errorReportHint(lang: String) = if (lang == LANG_EN)
        "Opens Telegram with a ZIP (error journal + telemetry). Send it to @b6dmachine."
    else
        "Откроет Telegram с ZIP (журнал ошибок + телеметрия). Отправьте @b6dmachine."
    fun infoFooter(lang: String) = if (lang == LANG_EN)
        "® Built in Crimea. Works under any conditions."
    else
        "® Разработано в Крыму. Для работы в любых условиях"
    fun feedbackBody(lang: String) = if (lang == LANG_EN) "Describe the bug or idea:\n\n" else "Опишите ошибку или идею:\n\n"
    fun attachPhoto(lang: String) = if (lang == LANG_EN) "Photo" else "Фото"
    fun photo(lang: String) = if (lang == LANG_EN) "Photo" else "Фото"

    // Expedition / gamification
    fun expedition(lang: String) = if (lang == LANG_EN) "Expedition" else "Экспедиция"
    fun expeditionTagline(lang: String) = if (lang == LANG_EN)
        "You’re a courier. Deliver the package. Help the network."
    else
        "Ты курьер. Отнеси посылку. Помоги сети."
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
    fun unarchive(lang: String) = if (lang == LANG_EN) "Unarchive" else "Вернуть"
    fun pinned(lang: String) = if (lang == LANG_EN) "Pinned" else "Закрепы"
    fun pin(lang: String) = if (lang == LANG_EN) "Pin" else "Закрепить"
    fun unpin(lang: String) = if (lang == LANG_EN) "Unpin" else "Открепить"
    fun unreadOnly(lang: String) = if (lang == LANG_EN) "Unread" else "Непрочитанные"
    fun searchDialogs(lang: String) = if (lang == LANG_EN) "Search dialogs..." else "Поиск диалогов..."
    fun startDialog(lang: String) = if (lang == LANG_EN) "Start" else "Начать"
    fun noArchivedDialogs(lang: String) = if (lang == LANG_EN)
        "Archive is empty."
    else
        "В архиве пусто."
    fun noUnreadDialogs(lang: String) = if (lang == LANG_EN)
        "No unread dialogs."
    else
        "Нет непрочитанных."
    fun noPinnedDialogs(lang: String) = if (lang == LANG_EN)
        "No pinned dialogs."
    else
        "Нет закрепов."
    fun reply(lang: String) = if (lang == LANG_EN) "Reply" else "Ответить"
    fun forward(lang: String) = if (lang == LANG_EN) "Forward" else "Переслать"
    fun forwardTo(lang: String) = if (lang == LANG_EN) "Forward to…" else "Переслать…"
    fun edit(lang: String) = if (lang == LANG_EN) "Edit" else "Изменить"
    fun edited(lang: String) = if (lang == LANG_EN) "edited" else "изм."
    fun select(lang: String) = if (lang == LANG_EN) "Select" else "Выбрать"
    fun selectedCount(lang: String, n: Int) = if (lang == LANG_EN) "$n selected" else "Выбрано: $n"
    fun noChatsToForward(lang: String) = if (lang == LANG_EN)
        "No other chats yet — open a dialog first."
    else
        "Пока нет других чатов — сначала открой диалог."
    fun forwarded(lang: String) = if (lang == LANG_EN) "Forwarded" else "Переслано"
    fun copied(lang: String) = if (lang == LANG_EN) "Copied" else "Скопировано"
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

    // Settings hub / invite / about / contacts (product UX)
    fun settingsAccount(lang: String) = if (lang == LANG_EN) "Account" else "Аккаунт"
    fun settingsPrivacy(lang: String) = if (lang == LANG_EN) "Privacy" else "Конфиденциальность"
    fun settingsNotifications(lang: String) = if (lang == LANG_EN) "Notifications" else "Уведомления"
    fun settingsAppearance(lang: String) = if (lang == LANG_EN) "Appearance" else "Внешний вид"
    fun settingsNetwork(lang: String) = if (lang == LANG_EN) "Connection" else "Соединение"
    fun settingsAbout(lang: String) = if (lang == LANG_EN) "About" else "О приложении"
    fun settingsAccountBody(lang: String) = if (lang == LANG_EN)
        "Language and your identity on this device."
    else
        "Язык и ваша личность на этом устройстве."
    fun settingsPrivacyBody(lang: String) = if (lang == LANG_EN)
        "Chats in Qq are encrypted end-to-end. Only you and the recipient can read them — " +
            "phones that carry the message along the way cannot."
    else
        "Диалоги в Qq шифруются на концах. Прочитать их можете только вы и получатель — " +
            "телефоны, которые несут сообщение по пути, не могут."
    fun settingsNotificationsBody(lang: String) = if (lang == LANG_EN)
        "System notifications for new messages. Fine-grained mute comes later."
    else
        "Системные уведомления о новых сообщениях. Тонкая настройка тишины — позже."
    fun settingsNetworkHint(lang: String) = if (lang == LANG_EN)
        "Optional delivery server and how actively this phone helps people nearby. " +
            "Qq works without a server."
    else
        "Необязательный сервер доставки и то, насколько активно телефон помогает людям рядом. " +
            "Qq работает и без сервера."
    fun deliveryServer(lang: String) = if (lang == LANG_EN) "Delivery server (optional)" else "Сервер доставки (необязательно)"
    fun deliveryServerHint(lang: String) = if (lang == LANG_EN)
        "Leave empty — Qq still finds a path through people"
    else
        "Можно пусто — Qq всё равно найдёт путь через людей"
    fun deliveryServerSaved(lang: String) = if (lang == LANG_EN) "Delivery server saved" else "Сервер доставки сохранён"
    fun aboutProject(lang: String) = if (lang == LANG_EN) "About the project" else "О проекте"
    fun versionLabel(lang: String) = if (lang == LANG_EN) "Version" else "Версия"
    fun openGithub(lang: String) = if (lang == LANG_EN) "GitHub" else "GitHub"
    fun openSource(lang: String) = if (lang == LANG_EN) "Open source" else "Открытый код"
    fun supportProject(lang: String) = if (lang == LANG_EN) "Support the project" else "Поддержать проект"
    fun projectHistory(lang: String) = if (lang == LANG_EN) "Project history" else "История проекта"
    fun licenseHint(lang: String) = if (lang == LANG_EN)
        "License and source are on GitHub."
    else
        "Лицензия и исходники — на GitHub."
    fun inviteFriends(lang: String) = if (lang == LANG_EN) "Invite friends" else "Пригласить друзей"
    fun inviteFriendsHint(lang: String) = if (lang == LANG_EN)
        "Share the app — more people nearby means stronger delivery."
    else
        "Поделитесь приложением — больше людей рядом значит надёжнее доставка."
    fun shareApk(lang: String) = if (lang == LANG_EN) "Share APK" else "Поделиться APK"
    fun shareLink(lang: String) = if (lang == LANG_EN) "Share link" else "Поделиться ссылкой"
    fun showQr(lang: String) = if (lang == LANG_EN) "Show QR" else "Показать QR"
    fun copyLink(lang: String) = if (lang == LANG_EN) "Copy link" else "Скопировать ссылку"
    fun linkCopied(lang: String) = if (lang == LANG_EN) "Link copied" else "Ссылка скопирована"
    fun sendViaTelegram(lang: String) = if (lang == LANG_EN) "Send via Telegram" else "Отправить через Telegram"
    fun sendViaVk(lang: String) = if (lang == LANG_EN) "Send via VK" else "Отправить через VK"
    fun sendViaWhatsApp(lang: String) = if (lang == LANG_EN) "Send via WhatsApp" else "Отправить через WhatsApp"
    fun yourId(lang: String) = if (lang == LANG_EN) "Your ID" else "Ваш ID"
    fun tapCopyId(lang: String) = if (lang == LANG_EN) "Tap to copy ID" else "Нажми, чтобы скопировать ID"
    fun contacts(lang: String) = if (lang == LANG_EN) "Contacts" else "Контакты"
    fun favorites(lang: String) = if (lang == LANG_EN) "Favorites" else "Избранные"
    fun recent(lang: String) = if (lang == LANG_EN) "Recent" else "Недавние"
    fun addById(lang: String) = if (lang == LANG_EN) "Add by ID" else "Добавить по ID"
    fun connectionStatus(lang: String) = if (lang == LANG_EN) "Status" else "Статус"
    fun lastRoute(lang: String) = if (lang == LANG_EN) "Last route" else "Последний маршрут"
    fun pathInternet(lang: String) = if (lang == LANG_EN) "Through the internet" else "Через интернет"
    fun pathNearbyGroup(lang: String) = if (lang == LANG_EN) "Through a nearby group" else "Через ближнюю группу"
    fun pathPeople(lang: String) = if (lang == LANG_EN) "Through people nearby" else "Через людей рядом"
    fun pathInternetShort(lang: String) = if (lang == LANG_EN) "Internet" else "Интернет"
    fun pathNearbyShort(lang: String) = if (lang == LANG_EN) "Nearby" else "Рядом"
    fun pathPeopleShort(lang: String) = if (lang == LANG_EN) "People" else "Люди"
    fun connectionInternet(lang: String) = if (lang == LANG_EN) "Internet" else "Интернет"
    fun connectionNearby(lang: String) = if (lang == LANG_EN) "Nearby link" else "Ближняя связь"
    fun connectionPeople(lang: String) = if (lang == LANG_EN) "People nearby" else "Люди рядом"
    fun connectionOn(lang: String) = if (lang == LANG_EN) "Available" else "Есть"
    fun connectionOff(lang: String) = if (lang == LANG_EN) "Not now" else "Сейчас нет"
    fun typeToSend(lang: String) = if (lang == LANG_EN) "Type a message first" else "Сначала введите сообщение"
    fun zoomQr(lang: String) = if (lang == LANG_EN) "Enlarge QR" else "Увеличить QR"
    fun closeScanner(lang: String) = if (lang == LANG_EN) "Close" else "Закрыть"
    fun updateHint(lang: String) = if (lang == LANG_EN)
        "1) Stay next to each other  2) Tap Download  3) Confirm install when the package arrives"
    else
        "1) Будьте рядом  2) Нажмите «Получить»  3) Подтвердите установку, когда придёт файл"
    fun updateRequestSent(lang: String) = if (lang == LANG_EN)
        "Update requested. Keep phones nearby — the install screen opens when the file arrives."
    else
        "Запрос отправлен. Держите телефоны рядом — откроется установка, когда файл придёт."
    fun updateNeedNearby(lang: String) = if (lang == LANG_EN)
        "Need a nearby link. Open Network on both phones and stay close, then try again."
    else
        "Нужна ближняя связь. Откройте «Сеть» на обоих телефонах, подойдите ближе и повторите."
    fun emoji(lang: String) = if (lang == LANG_EN) "Emoji" else "Эмодзи"
    fun attach(lang: String) = if (lang == LANG_EN) "Attach" else "Вложение"
    fun voiceSoon(lang: String) = if (lang == LANG_EN) "Voice messages soon" else "Голосовые — скоро"

    // "Why is it taking so long" — plain language, reachable from delivery status and About.
    fun deliveryWhyLink(lang: String) = if (lang == LANG_EN)
        "Why is it taking so long?" else "Почему так долго?"
    fun deliveryHelpTitle(lang: String) = if (lang == LANG_EN)
        "How delivery works" else "Как идёт доставка"
    fun deliveryHelpWaiting(lang: String) = if (lang == LANG_EN)
        "Why isn't it delivered yet?\n" +
            "Because nobody suitable has been close enough yet. The message is saved on your " +
            "phone and leaves on its own the moment someone appears."
    else
        "Почему ещё не доставлено?\n" +
            "Потому что рядом пока не было подходящего человека. Сообщение лежит в вашем " +
            "телефоне и уйдёт само, как только кто-то появится."
    fun deliveryHelpHandedOn(lang: String) = if (lang == LANG_EN)
        "What does \"at another Qq\" mean?\n" +
            "Someone nearby took your message and is carrying it further. They cannot read it. " +
            "It is not delivered yet — that word is only used when the recipient's phone confirms."
    else
        "Что значит «у другого Qq»?\n" +
            "Кто-то рядом взял ваше сообщение и несёт его дальше. Прочитать он его не может. " +
            "Это ещё не доставка — «доставлено» появится, только когда подтвердит телефон получателя."
    fun deliveryHelpNearby(lang: String) = if (lang == LANG_EN)
        "What happens when another Qq comes near?\n" +
            "The phones notice each other by themselves and swap the messages they are carrying. " +
            "You do not need to press anything, and the app may be closed."
    else
        "Что происходит, когда рядом появляется другой Qq?\n" +
            "Телефоны сами замечают друг друга и обмениваются сообщениями, которые несут. " +
            "Нажимать ничего не нужно, приложение может быть закрыто."
    fun deliveryHelpSlow(lang: String) = if (lang == LANG_EN)
        "Why can it take long?\n" +
            "A message moves only as fast as people walk. In an empty place it waits; in a busy " +
            "one it can arrive in seconds. Nothing is lost while it waits."
    else
        "Почему это может быть долго?\n" +
            "Сообщение движется со скоростью людей. В пустом месте оно ждёт, в людном может " +
            "дойти за секунды. Пока оно ждёт, ничего не теряется."

    // Sound & vibration
    fun settingsSound(lang: String) = if (lang == LANG_EN) "Sound and vibration" else "Звук и вибрация"
    fun settingsSoundHint(lang: String) = if (lang == LANG_EN)
        "Qq gives three quiet signals: a new message for you, your phone passing someone " +
            "else's message on, and your own message reaching its recipient."
    else
        "Qq подаёт три тихих сигнала: новое сообщение вам, ваш телефон передал чужое " +
            "сообщение дальше и ваше сообщение дошло до получателя."
    fun soundToggle(lang: String) = if (lang == LANG_EN) "Sound" else "Звук"
    fun vibrationToggle(lang: String) = if (lang == LANG_EN) "Vibration" else "Вибрация"
    fun toggleOn(lang: String) = if (lang == LANG_EN) "On" else "Вкл"
    fun toggleOff(lang: String) = if (lang == LANG_EN) "Off" else "Выкл"

    // Advanced (gateway account lives one level down)
    fun settingsAdvanced(lang: String) = if (lang == LANG_EN) "Advanced" else "Дополнительно"
    fun deliveryServerBody(lang: String) = if (lang == LANG_EN)
        "If you have an address of a Qq delivery server, messages can also travel over the " +
            "internet when there is any. Leave it empty and Qq works exactly the same through people."
    else
        "Если у вас есть адрес сервера доставки Qq, сообщения смогут идти ещё и через " +
            "интернет, когда он есть. Оставьте пусто — Qq так же работает через людей."
    /*
     * Toast copy for BLinkViewModel (owned by another agent — see report).
     * Kept here so both languages live in one table.
     */
    fun sendFailedToast(lang: String) = if (lang == LANG_EN)
        "Could not send — the message stays in the chat, tap it to try again"
    else
        "Не удалось отправить — сообщение осталось в чате, нажмите на него, чтобы повторить"
    fun recipientBlockedToast(lang: String) = if (lang == LANG_EN)
        "This person cannot receive messages" else "Этот человек не может получать сообщения"
    fun needTheirQrToast(lang: String) = if (lang == LANG_EN)
        "Qq does not know this person yet — the message will go as soon as you scan their QR"
    else
        "Qq пока не знает этого человека — сообщение уйдёт, как только вы отсканируете его QR"
    fun sendCancelledToast(lang: String) = if (lang == LANG_EN)
        "Sending cancelled" else "Отправка отменена"
    fun alreadySentToast(lang: String) = if (lang == LANG_EN)
        "Already on its way — you can only delete it here" else "Уже в пути — можно только удалить у себя"
    fun retryingToast(lang: String) = if (lang == LANG_EN) "Sending again…" else "Отправляем ещё раз…"
    fun photoNeedsInternetToast(lang: String) = if (lang == LANG_EN)
        "Photos are not sent yet — no end-to-end encryption for media. Saved on this phone only."
    else
        "Фото пока не отправляются — для медиа нет сквозного шифрования. Сохранено только на этом телефоне."
    fun photoFailedToast(lang: String) = if (lang == LANG_EN)
        "Could not save the photo" else "Не удалось сохранить фото"
    fun photoCompressFailedToast(lang: String) = if (lang == LANG_EN)
        "Could not prepare the photo" else "Не удалось подготовить фото"
    fun authGatewayHint(lang: String) = if (lang == LANG_EN)
        "Delivery server address (optional)"
    else
        "Адрес сервера доставки (необязательно)"
    fun authGatewayBody(lang: String) = if (lang == LANG_EN)
        "Needed only for email or Telegram sign-in. Leave empty — Qq still works through people nearby."
    else
        "Нужен только для входа по почте или Telegram. Оставьте пусто — Qq работает через людей рядом."
    fun authRebindPrimary(lang: String) = if (lang == LANG_EN)
        "This is my new phone — make it primary"
    else
        "Это мой новый телефон — сделать его основным"
    fun authRebindPrimaryHint(lang: String) = if (lang == LANG_EN)
        "After a reinstall, contacts added by ID may otherwise keep an old key."
    else
        "После переустановки контакты, добавленные по ID, иначе могут получить старый ключ."
    fun carryExplainTitle(lang: String) = if (lang == LANG_EN) "Carrying" else "Вы несёте"
    fun carryExplainBody(count: Int, lang: String) = if (lang == LANG_EN) {
        if (count <= 0)
            "Your phone is not carrying anyone else's messages right now. When people nearby use Qq, encrypted messages may rest here until the next phone can take them."
        else
            "Your phone is carrying $count encrypted messages that belong to other people. They will move on when another Qq is close enough. Qq never shows whose they are."
    } else {
        if (count <= 0)
            "Сейчас ваш телефон не несёт чужих сообщений. Когда рядом окажутся люди с Qq, чужие зашифрованные сообщения могут полежать здесь, пока следующий телефон не заберёт их дальше."
        else
            "Ваш телефон несёт $count чужих зашифрованных сообщений. Они уйдут дальше, когда рядом окажется другой Qq. Чьи они — Qq не показывает."
    }
    fun keyChangedTitle(lang: String) = if (lang == LANG_EN)
        "This person's code changed"
    else
        "Код этого человека изменился"
    fun keyChangedBody(lang: String) = if (lang == LANG_EN)
        "Qq will keep writing with the key you already have. Scan their QR to confirm the new one — otherwise someone else could be pretending to be them."
    else
        "Qq продолжит писать со старым ключом. Отсканируйте их QR, чтобы подтвердить новый — иначе это может быть кто-то другой."
    fun findViaQq(lang: String) = if (lang == LANG_EN) "Find by Qq address" else "Найти по адресу Qq"
    fun usernameHint(lang: String) = if (lang == LANG_EN) "@username" else "@имя"
    fun usernameNeedServer(lang: String) = if (lang == LANG_EN)
        "Set a delivery server in Settings to find people by address"
    else
        "Укажите сервер доставки в настройках, чтобы искать людей по адресу"
    fun usernameNeedSignIn(lang: String) = if (lang == LANG_EN)
        "Sign in to the delivery server to find people by address"
    else
        "Войдите на сервер доставки, чтобы искать людей по адресу"
    fun usernameNotFound(lang: String) = if (lang == LANG_EN)
        "No one with that address" else "Такого адреса нет"
    fun usernameInvalid(lang: String) = if (lang == LANG_EN)
        "Address is 3–20 characters: latin letters, digits, underscore"
    else
        "Адрес — 3–20 символов: латиница, цифры, подчёркивание"
    fun usernameClaim(lang: String) = if (lang == LANG_EN) "Your Qq address" else "Ваш адрес Qq"
    fun usernameClaimHint(lang: String) = if (lang == LANG_EN)
        "Optional. Others can find you by this exact address. It is not a public profile."
    else
        "Необязательно. Другие смогут найти вас по этому точному адресу. Это не публичный профиль."
    fun usernameSaved(lang: String) = if (lang == LANG_EN) "Address saved" else "Адрес сохранён"
    fun usernameTaken(lang: String) = if (lang == LANG_EN) "That address is taken" else "Этот адрес занят"
    fun usernameCooldown(lang: String) = if (lang == LANG_EN)
        "You can change the address once every 30 days"
    else
        "Адрес можно сменить раз в 30 дней"
    fun usernameFind(lang: String) = if (lang == LANG_EN) "Find" else "Найти"
    fun usernameSelf(lang: String) = if (lang == LANG_EN) "That is your own address" else "Это ваш собственный адрес"

    fun phoneContacts(lang: String) = if (lang == LANG_EN) "Phone contacts" else "Контакты телефона"
    fun phoneContactsFindTitle(lang: String) = if (lang == LANG_EN)
        "Find friends on Qq"
    else
        "Найдём друзей в Qq"
    fun phoneContactsFindBody(lang: String) = if (lang == LANG_EN)
        "Allow Qq to check your contacts. Numbers stay on this phone — Qq only sends a one-way hash to see who is already here. The full address book is never uploaded."
    else
        "Разрешите Qq проверить ваши контакты. Номера остаются на телефоне — Qq отправляет только односторонний хеш, чтобы понять, кто уже в Qq. Записная книжка целиком не загружается."
    fun phoneContactsContinue(lang: String) = if (lang == LANG_EN) "Continue" else "Продолжить"
    fun phoneContactsDeniedTitle(lang: String) = if (lang == LANG_EN)
        "Contacts are closed"
    else
        "Контакты закрыты"
    fun phoneContactsDeniedBody(lang: String) = if (lang == LANG_EN)
        "Qq will not read your address book. You can still add people by QR or @username."
    else
        "Qq не будет читать записную книжку. По-прежнему можно добавить человека по QR или @имени."
    fun phoneContactsOpenSettings(lang: String) = if (lang == LANG_EN)
        "Open system settings"
    else
        "Открыть системные настройки"
    fun phoneContactsInQq(lang: String) = if (lang == LANG_EN) "On Qq" else "В Qq"
    fun phoneContactsInviteSection(lang: String) = if (lang == LANG_EN)
        "Invite to Qq"
    else
        "Пригласить в Qq"
    fun phoneContactsWrite(lang: String) = if (lang == LANG_EN) "Message" else "Написать"
    fun phoneContactsInvite(lang: String) = if (lang == LANG_EN) "Invite" else "Пригласить"
    fun phoneContactsLookupFailed(lang: String) = if (lang == LANG_EN)
        "Could not check contacts"
    else
        "Не удалось проверить контакты"
    fun phoneContactsRefresh(lang: String) = if (lang == LANG_EN) "Refresh" else "Обновить"
    fun phoneContactsEmpty(lang: String) = if (lang == LANG_EN)
        "No numbers on this phone to check"
    else
        "На телефоне нет номеров для проверки"
    fun phoneDiscoverable(lang: String) = if (lang == LANG_EN)
        "People can find me by number"
    else
        "Меня можно найти по номеру"
    fun phoneDiscoverableHint(lang: String) = if (lang == LANG_EN)
        "Optional. Qq stores only a hash of the number, never the number itself. Turn off to disappear from phone lookup. Username search stays separate."
    else
        "Необязательно. Qq хранит только хеш номера, не сам номер. Выключите — по номеру вас не найдут. Поиск по адресу Qq от этого не зависит."
    fun phoneNumberHint(lang: String) = if (lang == LANG_EN) "Your number" else "Ваш номер"
    fun phoneNumberInvalid(lang: String) = if (lang == LANG_EN)
        "Enter a valid phone number"
    else
        "Введите настоящий номер телефона"
    fun phoneDiscoverableSaved(lang: String) = if (lang == LANG_EN)
        "You can be found by this number"
    else
        "Вас можно найти по этому номеру"
    fun phoneDiscoverableOff(lang: String) = if (lang == LANG_EN)
        "You cannot be found by number"
    else
        "По номеру вас найти нельзя"
    fun qqInviteShareText(lang: String): String {
        val line = if (lang == LANG_EN)
            "Join Qq — a link that finds a path."
        else
            "Присоединяйся к Qq — связь, которая ищет путь."
        return "$line\n$TUKTUK_DOWNLOAD_URL"
    }
}

