package com.blink.dtn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blink.dtn.db.Message
import com.blink.dtn.router.MessageRouter
import com.blink.dtn.router.RoutePath
import com.blink.dtn.telemetry.PeerDirectory
import com.blink.dtn.telemetry.TraceAnalyzer
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.DangerColor
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography

/**
 * User-facing delivery crumbs — no engineer jargon (no ACK / GATT / UUID).
 */
object DeliveryVoyageLabels {
    fun label(msg: Message, lang: String = AppLang.lang.value): String {
        val base = when (msg.status) {
            Message.STATUS_PENDING -> if (lang == "en") "waiting" else "ждёт"
            Message.STATUS_PENDING_KEY -> if (lang == "en") "almost" else "почти"
            Message.STATUS_IN_FLIGHT -> if (lang == "en") "with people" else "у людей"
            Message.STATUS_STORED_IN_NEIGHBOR -> if (msg.type == "PRIVATE") {
                if (lang == "en") "with a neighbor" else "у соседа"
            } else {
                if (lang == "en") "passed on" else "передано"
            }
            Message.STATUS_DELIVERED_ACK -> if (lang == "en") "delivered" else "доставлено"
            Message.STATUS_FAILED -> if (lang == "en") "stuck" else "застряло"
            else -> if (lang == "en") "waiting" else "ждёт"
        }
        return base
    }

    fun color(msg: Message): Color = when (msg.status) {
        Message.STATUS_DELIVERED_ACK -> TextPrimary
        Message.STATUS_STORED_IN_NEIGHBOR -> TextSecondary
        Message.STATUS_FAILED -> DangerColor
        else -> DividerColor
    }

    fun subtitle(msg: Message, lang: String = AppLang.lang.value): String {
        val path = MessageRouter.pathFor(msg.id)
        val via = path?.let { " · ${humanPathLabel(it, lang)}" }.orEmpty()
        return when (msg.status) {
            Message.STATUS_PENDING ->
                if (lang == "en") "Looking for a path$via" else "Ищем путь$via"
            Message.STATUS_PENDING_KEY ->
                if (lang == "en") "Need to meet this person once (QR helps)"
                else "Нужна одна встреча с человеком (поможет QR)"
            Message.STATUS_IN_FLIGHT ->
                if (lang == "en") "Neighbors are carrying it$via"
                else "Соседи уже несут$via"
            Message.STATUS_STORED_IN_NEIGHBOR ->
                if (msg.type == "PRIVATE") {
                    if (lang == "en") "On a neighbor's phone — waiting for your friend's ACK"
                    else "На телефоне соседа — ждём подтверждение друга"
                } else {
                    if (lang == "en") "Shared with people nearby$via"
                    else "Передано людям рядом$via"
                }
            Message.STATUS_DELIVERED_ACK ->
                if (lang == "en") "Your friend confirmed receipt$via" else "Друг подтвердил получение$via"
            Message.STATUS_FAILED ->
                if (lang == "en") "Couldn't get through — tap to try again"
                else "Не удалось пробиться — нажмите, чтобы повторить"
            else -> ""
        }
    }
}

data class DeliveryHealthSummary(
    val total: Int,
    val delivered: Int,
    val failed: Int,
    val pending: Int
) {
    val successRatePct: Int
        get() {
            val done = delivered + failed
            if (done <= 0) return 0
            return ((delivered * 100.0) / done).toInt()
        }

    companion object {
        fun fromRecentTraces(limit: Int = 40): DeliveryHealthSummary {
            val traces = TraceStore.listRecent(limit).filter {
                it.messageType == "PRIVATE" || it.messageType == "PUBLIC"
            }
            var delivered = 0
            var failed = 0
            var pending = 0
            for (t in traces) {
                when (t.terminalStatus?.lowercase()) {
                    "deliveredack", "delivered" -> delivered++
                    "failed", "expired", "timeout", "cancelled" -> failed++
                    // StoredInNeighbor / Sent = not e2e success
                    else -> pending++
                }
            }
            return DeliveryHealthSummary(traces.size, delivered, failed, pending)
        }
    }
}

/** Human story of a message — Observatory stays data source only. */
@Composable
fun MessageVoyageDialog(
    msg: Message,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val lang by AppLang.lang.collectAsState()
    val report = remember(msg.id, msg.status) {
        TraceStore.getByMessageId(msg.id)?.let { TraceAnalyzer.analyze(it) }
    }
    val path = MessageRouter.pathFor(msg.id) ?: RoutePath.BLE
    val hops = report?.route?.size?.coerceAtLeast(1) ?: 1
    val elapsed = report?.statistics?.deliveryMs
        ?: report?.journey?.lastOrNull()?.elapsedMs
    val helpers = report?.route
        ?.mapNotNull { hop ->
            hop.nodeId?.let { PeerDirectory.humanLabel(it, lang) }
                ?.takeIf { it.isNotBlank() }
        }
        ?.distinct()
        ?.take(6)
        .orEmpty()
    val accepted = helpers.firstOrNull()
    val storySteps = remember(report, lang) {
        val fromJourney = report?.journey.orEmpty().map { humanizeJourney(it.emojiTitle, lang) }
        val fromEvents = report?.let { r ->
            // Fall back to humanized event stages when journey is thin
            emptyList<String>()
        }.orEmpty()
        (fromJourney + fromEvents).distinct().ifEmpty {
            listOf(
                if (lang == "en") "· Looking for a path" else "· Ищем путь",
                if (lang == "en") "· Waiting for people nearby" else "· Ждём людей рядом"
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.messageTracker(lang), color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    DeliveryVoyageLabels.label(msg, lang),
                    style = Typography.titleMedium,
                    color = DeliveryVoyageLabels.color(msg)
                )
                Text(
                    DeliveryVoyageLabels.subtitle(msg, lang),
                    style = Typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                MessageTrackerStrip(
                    path = path,
                    statusRu = DeliveryVoyageLabels.subtitle(msg, lang)
                )
                Spacer(modifier = Modifier.height(12.dp))
                StoryLine(
                    if (lang == "en") "How it travels" else "Как идёт",
                    humanPathLabel(path, lang)
                )
                StoryLine(
                    if (lang == "en") "Stops on the way" else "Остановок по пути",
                    "$hops"
                )
                if (elapsed != null && elapsed > 0) {
                    StoryLine(
                        if (lang == "en") "Time so far" else "Времени в пути",
                        formatDuration(elapsed, lang)
                    )
                }
                if (accepted != null) {
                    StoryLine(
                        if (lang == "en") "First to take it" else "Кто принял первым",
                        accepted
                    )
                }
                if (helpers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (lang == "en") "Who carried / forwarded" else "Кто нёс / переслал",
                        style = Typography.labelMedium,
                        color = TextPrimary
                    )
                    helpers.forEach { name ->
                        Text("· $name", color = TextSecondary, style = Typography.labelSmall)
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (lang == "en")
                            "When someone nearby carries your message, their name will appear here."
                        else
                            "Когда человек рядом понесёт сообщение — его имя появится здесь.",
                        color = TextSecondary,
                        style = Typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    if (lang == "en") "Full story" else "Полная история",
                    style = Typography.labelMedium,
                    color = TextPrimary
                )
                storySteps.take(12).forEach { step ->
                    Text(step, color = TextSecondary, style = Typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Row {
                if (onRetry != null) {
                    TextButton(onClick = { onRetry(); onDismiss() }) {
                        Text(if (lang == "en") "Try again" else "Повторить", color = AccentLime)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(S.close(lang), color = TextPrimary)
                }
            }
        },
        containerColor = GlassDialogContainer
    )
}

@Composable
private fun StoryLine(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(title, color = TextSecondary, style = Typography.labelSmall, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, style = Typography.labelSmall)
    }
}

private fun formatDuration(ms: Long, lang: String): String {
    val sec = (ms / 1000).coerceAtLeast(1)
    return if (sec < 60) {
        if (lang == "en") "${sec}s" else "$sec с"
    } else {
        val m = sec / 60
        if (lang == "en") "${m} min" else "$m мин"
    }
}

private fun humanizeJourney(raw: String, lang: String): String {
    val s = raw.lowercase()
    return when {
        "send" in s || "отправ" in s || "ui" in s ->
            if (lang == "en") "· You sent the package" else "· Вы отправили посылку"
        "encrypt" in s || "ключ" in s || "prep" in s ->
            if (lang == "en") "· Locked for the journey" else "· Закрыли для дороги"
        "wifi" in s ->
            if (lang == "en") "· Passed via nearby Wi‑Fi" else "· Передали через ближайший Wi‑Fi"
        "internet" in s || "vps" in s || "router" in s ->
            if (lang == "en") "· Went through the internet" else "· Ушло через интернет"
        "peer" in s || "сосед" in s || "ble" in s || "gatt" in s ->
            if (lang == "en") "· A neighbor took it" else "· Сосед принял"
        "ack" in s || "deliver" in s || "достав" in s ->
            if (lang == "en") "· Friend confirmed receipt" else "· Друг подтвердил получение"
        "fail" in s || "error" in s ->
            if (lang == "en") "· Path broke — will retry" else "· Путь оборвался — попробуем ещё"
        else -> "· ${raw.replace(Regex("[📡✅🔀👥🌉]"), "").trim()}"
    }
}
