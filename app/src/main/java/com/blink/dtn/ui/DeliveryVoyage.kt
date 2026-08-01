package com.blink.dtn.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blink.dtn.db.Message
import com.blink.dtn.telemetry.TraceAnalyzer
import com.blink.dtn.telemetry.TraceStore
import com.blink.dtn.ui.theme.DangerColor
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel

/**
 * User-facing Russian delivery labels mapped from DB status + message type.
 * Not a second Observatory — short «voyage» crumbs only.
 */
object DeliveryVoyageLabels {
    fun label(msg: Message): String {
        val base = when (msg.status) {
            Message.STATUS_PENDING -> "в очереди"
            Message.STATUS_PENDING_KEY -> "ждём ключ"
            Message.STATUS_IN_FLIGHT -> "у соседей"
            Message.STATUS_SENT -> if (msg.type == "PRIVATE") "в пути" else "у соседей"
            Message.STATUS_DELIVERED -> "доставлено"
            Message.STATUS_FAILED -> "ошибка"
            else -> "в очереди"
        }
        val path = com.blink.dtn.router.MessageRouter.pathFor(msg.id)
        return if (path != null && msg.status != Message.STATUS_PENDING_KEY) {
            "$base · ${path.labelRu()}"
        } else base
    }

    fun color(msg: Message): Color = when (msg.status) {
        Message.STATUS_DELIVERED -> TextPrimary
        Message.STATUS_SENT -> TextSecondary
        Message.STATUS_FAILED -> DangerColor
        Message.STATUS_PENDING, Message.STATUS_IN_FLIGHT, Message.STATUS_PENDING_KEY -> DividerColor
        else -> DividerColor
    }

    fun subtitle(msg: Message): String {
        val path = com.blink.dtn.router.MessageRouter.pathFor(msg.id)
        val via = path?.let { " (${it.labelRu()})" }.orEmpty()
        return when (msg.status) {
            Message.STATUS_PENDING -> "В очереди на отправку$via"
            Message.STATUS_PENDING_KEY -> "Ждём ключ собеседника (лучше сверить QR)"
            Message.STATUS_IN_FLIGHT -> "Пишем соседям$via"
            Message.STATUS_SENT ->
                if (msg.type == "PRIVATE") "Ушло в сеть$via, ждём подтверждение (ACK)"
                else "Передано соседям$via (общий чат — без личного ACK)"
            Message.STATUS_DELIVERED -> "Получено подтверждение от адресата$via"
            Message.STATUS_FAILED -> "Не удалось доставить после повторов — нажмите, чтобы повторить"
            else -> ""
        }
    }
}

/** Session delivery health from recent traces (Observatory summary). */
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
                    "delivered", "sent" -> delivered++
                    "failed", "expired", "timeout", "cancelled" -> failed++
                    else -> pending++
                }
            }
            return DeliveryHealthSummary(traces.size, delivered, failed, pending)
        }
    }
}

@Composable
fun MessageVoyageDialog(
    msg: Message,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val report = remember(msg.id, msg.status) {
        TraceStore.getByMessageId(msg.id)?.let { TraceAnalyzer.analyze(it) }
    }
    val terminal = report?.terminalStatus?.lowercase()
    val showErrorReport = msg.status == Message.STATUS_FAILED ||
        terminal in setOf("failed", "expired", "timeout", "dropped", "retrylimit", "cancelled")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.messageTracker(AppLang.lang.value), color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    DeliveryVoyageLabels.label(msg),
                    style = Typography.titleMedium,
                    color = DeliveryVoyageLabels.color(msg)
                )
                Text(
                    DeliveryVoyageLabels.subtitle(msg),
                    style = Typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                val path = com.blink.dtn.router.MessageRouter.pathFor(msg.id)
                    ?: com.blink.dtn.router.RoutePath.BLE
                MessageTrackerStrip(
                    path = path,
                    statusRu = DeliveryVoyageLabels.subtitle(msg)
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (report == null) {
                    Text(
                        "Подробный след ещё не записан или уже очищен.",
                        color = TextSecondary,
                        style = Typography.bodySmall
                    )
                } else {
                    if (report.route.isNotEmpty()) {
                        Text("Маршрут", style = Typography.labelMedium, color = TextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        report.route.take(6).forEachIndexed { i, hop ->
                            Text(
                                hop.label + (hop.detail?.let { " · $it" } ?: ""),
                                color = TextSecondary,
                                style = Typography.labelSmall
                            )
                            if (i < minOf(report.route.lastIndex, 5)) {
                                Text("↓", color = TextSecondary, style = Typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text("Этапы", style = Typography.labelMedium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    report.journey.takeLast(8).forEach { step ->
                        Text(
                            step.emojiTitle,
                            color = TextPrimary,
                            style = Typography.bodySmall,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .fillMaxWidth()
                                .glassPanel(corner = 8.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text("+${step.elapsedMs} мс", color = TextSecondary, style = Typography.labelSmall)
                    }
                    report.terminalStatus?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Итог: $it", color = TextSecondary, style = Typography.labelSmall)
                    }
                }
                if (showErrorReport) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            TraceStore.shareMessageErrorReport(context, msg.id, msg.status)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отправить ошибку разработчику", color = DangerColor)
                    }
                    Text(
                        "Соберёт ZIP (msgId, этапы, device) и откроет почту → tuktukfb@internet.ru",
                        color = TextSecondary,
                        style = Typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            if (msg.status == Message.STATUS_FAILED && onRetry != null) {
                TextButton(onClick = {
                    onRetry()
                    onDismiss()
                }) {
                    Text("Повторить", color = TextPrimary)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Закрыть", color = TextSecondary)
                }
            }
        },
        dismissButton = {
            if (msg.status == Message.STATUS_FAILED && onRetry != null) {
                TextButton(onClick = onDismiss) {
                    Text("Закрыть", color = TextSecondary)
                }
            }
        },
        containerColor = GlassDialogContainer
    )
}
