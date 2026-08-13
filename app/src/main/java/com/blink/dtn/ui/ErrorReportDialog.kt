package com.blink.dtn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.blink.dtn.net.TelemetryApi
import com.blink.dtn.telemetry.ErrorJournal
import com.blink.dtn.telemetry.PeerDirectory
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ReportPhase { Compose, Sending, Thanks, Failed }

@Composable
fun ErrorReportDialog(
    lang: String,
    peerCount: Int = 0,
    peers: List<String> = emptyList(),
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(ReportPhase.Compose) }
    var note by remember { mutableStateOf("") }
    var errorHint by remember { mutableStateOf<String?>(null) }
    val en = lang == "en"

    Dialog(onDismissRequest = {
        if (phase != ReportPhase.Sending) onDismiss()
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(GlassDialogContainer, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (phase) {
                ReportPhase.Compose, ReportPhase.Failed -> {
                    Text(
                        if (en) "Send error report" else "Отчёт об ошибке",
                        style = Typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        if (en)
                            "A sanitized diagnostics ZIP (no private keys, no message bodies)."
                        else
                            "ZIP с диагностикой без приватных ключей и текстов сообщений.",
                        style = Typography.bodySmall,
                        color = TextSecondary
                    )
                    if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY &&
                        !com.blink.dtn.BuildConfig.QQ_ALLOW_TELEMETRY_UPLOAD
                    ) {
                        Text(
                            if (en)
                                "Core mode: share locally (email / Files). No upload to the server."
                            else
                                "Режим Core: только локальная отправка (почта / Файлы). Без загрузки на сервер.",
                            style = Typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 500) note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (en) "What happened? (optional)" else "Что случилось? (необязательно)")
                        },
                        minLines = 3,
                        maxLines = 6
                    )
                    if (phase == ReportPhase.Failed) {
                        Text(
                            errorHint ?: if (en) "Send failed" else "Не удалось отправить",
                            color = TextSecondary,
                            style = Typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TukTukButton(
                        onClick = {
                            phase = ReportPhase.Sending
                            errorHint = null
                            scope.launch {
                                val localOnly = com.blink.dtn.BuildConfig.QQ_CORE_ONLY &&
                                    !com.blink.dtn.BuildConfig.QQ_ALLOW_TELEMETRY_UPLOAD
                                val peerSnap = if (peers.isEmpty()) {
                                    PeerDirectory.snapshot().map { it.nodeId }
                                } else {
                                    peers
                                }
                                val count = if (peerCount > 0) peerCount else peerSnap.size
                                val zip = withContext(Dispatchers.IO) {
                                    ErrorJournal.buildReportZip(context, count, peerSnap)
                                }
                                if (zip == null) {
                                    errorHint = "zip_build_failed"
                                    phase = ReportPhase.Failed
                                    return@launch
                                }
                                if (localOnly) {
                                    com.blink.dtn.telemetry.FeedbackMailer.sendTraceZip(
                                        context,
                                        zip,
                                        subject = if (note.isBlank()) "TukTuk diagnostics"
                                        else "TukTuk diagnostics: ${note.take(80)}"
                                    )
                                    phase = ReportPhase.Thanks
                                } else {
                                    val result = withContext(Dispatchers.IO) {
                                        TelemetryApi.uploadZip(context, zip, note = note)
                                    }
                                    if (result.ok) {
                                        phase = ReportPhase.Thanks
                                    } else {
                                        errorHint = when (result.error) {
                                            "not_authenticated" ->
                                                if (en) "Sign in to delivery server first (Settings → Account)."
                                                else "Сначала войдите на сервер доставки (Настройки → Аккаунт)."
                                            "telemetry_upload_disabled" ->
                                                if (en) "Server upload disabled — use local share."
                                                else "Загрузка на сервер отключена — используйте локальную отправку."
                                            "rate_limited" ->
                                                if (en) "Too many reports — try later."
                                                else "Слишком много отчётов — позже."
                                            else -> result.error
                                        }
                                        phase = ReportPhase.Failed
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY &&
                                !com.blink.dtn.BuildConfig.QQ_ALLOW_TELEMETRY_UPLOAD
                            ) {
                                if (en) "Share ZIP" else "Поделиться ZIP"
                            } else {
                                if (en) "Send" else "Отправить"
                            },
                            color = TextPrimary
                        )
                    }
                    TukTukButton(onClick = onDismiss) {
                        Text(if (en) "Cancel" else "Отмена", color = TextPrimary)
                    }
                }
                ReportPhase.Sending -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(if (en) "Sending..." else "Отправка…", color = TextSecondary)
                    }
                }
                ReportPhase.Thanks -> {
                    Text(
                        if (en) "Thank you for helping!" else "Спасибо за помощь!",
                        style = Typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY &&
                            !com.blink.dtn.BuildConfig.QQ_ALLOW_TELEMETRY_UPLOAD
                        ) {
                            if (en) "Share the ZIP from your mail / files app."
                            else "Отправьте ZIP из почты или файлов."
                        } else {
                            if (en) "Your report reached the developers."
                            else "Отчёт дошёл до разработчиков."
                        },
                        style = Typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TukTukButton(onClick = onDismiss) {
                        Text(if (en) "Close" else "Закрыть", color = TextPrimary)
                    }
                }
            }
        }
    }
}