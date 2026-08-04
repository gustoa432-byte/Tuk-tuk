package com.blink.dtn.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blink.dtn.auth.AuthProvider
import com.blink.dtn.auth.AuthResult
import com.blink.dtn.auth.AuthSessionStore
import com.blink.dtn.auth.DeferredSocialAuth
import com.blink.dtn.auth.DinoNameGenerator
import com.blink.dtn.auth.EmailOtpAuth
import com.blink.dtn.auth.TelegramAuth
import com.blink.dtn.net.VpsConfig
import com.blink.dtn.ui.theme.AccentLilac
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.BackgroundDark
import com.blink.dtn.ui.theme.BackgroundMid
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel
import kotlinx.coroutines.launch

/**
 * First-run auth: Email OTP + Telegram initData against VPS, plus offline path.
 */
@Composable
fun AuthOnboardingScreen(
    onComplete: (displayName: String, nick: String, provider: AuthProvider) -> Unit
) {
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(AuthProvider.OFFLINE) }
    var nameField by remember { mutableStateOf(TextFieldValue("")) }
    var nickField by remember { mutableStateOf(TextFieldValue("")) }
    var emailField by remember { mutableStateOf(TextFieldValue("")) }
    var otpField by remember { mutableStateOf(TextFieldValue("")) }
    var tgInitField by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var emailVerified by remember { mutableStateOf(AuthSessionStore.hasVpsSession(context) && AuthSessionStore.authProvider(context) == AuthProvider.EMAIL) }

    fun finish() {
        val name = DinoNameGenerator.resolveDisplayName(nameField.text, lang)
        val nick = nickField.text.trim().take(DinoNameGenerator.MAX_LEN)
        onComplete(name, nick, provider)
    }

    val bg = remember {
        Brush.verticalGradient(listOf(BackgroundDark, BackgroundMid, BackgroundDark))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Tuk-Tuk", style = Typography.headlineMedium, color = TextPrimary)
            Text(
                S.slogan(lang),
                style = Typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
            )

            Text(
                S.authSignInWith(lang),
                style = Typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // ── Email OTP ────────────────────────────────────────────────
            AuthField(
                label = S.authEmail(lang),
                placeholder = S.authEmailHint(lang),
                value = emailField,
                onValueChange = { emailField = it }
            )
            Spacer(modifier = Modifier.height(8.dp))
            TukTukButton(
                onClick = {
                    if (busy) return@TukTukButton
                    VpsConfig.init(context)
                    scope.launch {
                        busy = true
                        when (val r = EmailOtpAuth(context, emailField.text).beginSignIn()) {
                            is AuthResult.Success -> {
                                provider = AuthProvider.EMAIL
                                Toast.makeText(context, S.authCodeSent(lang), Toast.LENGTH_SHORT).show()
                            }
                            is AuthResult.Failed ->
                                Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                            is AuthResult.Deferred -> Unit
                        }
                        busy = false
                    }
                }
            ) {
                Text(S.authSendOtp(lang), color = TextPrimary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AuthField(
                label = S.authOtp(lang),
                placeholder = S.authOtpHint(lang),
                value = otpField,
                onValueChange = { if (it.text.length <= 8) otpField = it }
            )
            Spacer(modifier = Modifier.height(8.dp))
            TukTukButton(onClick = {
                if (busy) return@TukTukButton
                VpsConfig.init(context)
                scope.launch {
                    busy = true
                    when (
                        val r = EmailOtpAuth(context, emailField.text, otpField.text).beginSignIn()
                    ) {
                        is AuthResult.Success -> {
                            provider = AuthProvider.EMAIL
                            emailVerified = true
                            Toast.makeText(context, S.authEmailOk(lang), Toast.LENGTH_SHORT).show()
                        }
                        is AuthResult.Failed ->
                            Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                        is AuthResult.Deferred -> Unit
                    }
                    busy = false
                }
            }) {
                Text(S.authVerifyEmail(lang), color = if (emailVerified) AccentLime else TextPrimary)
            }

            Spacer(modifier = Modifier.height(16.dp))
            // ── Telegram ─────────────────────────────────────────────────
            AuthField(
                label = S.authTgInitData(lang),
                placeholder = S.authTgHint(lang),
                value = tgInitField,
                onValueChange = { tgInitField = it }
            )
            Spacer(modifier = Modifier.height(8.dp))
            TukTukButton(onClick = {
                if (busy) return@TukTukButton
                VpsConfig.init(context)
                scope.launch {
                    busy = true
                    when (val r = TelegramAuth(context, tgInitField.text).beginSignIn()) {
                        is AuthResult.Success -> {
                            provider = AuthProvider.TELEGRAM
                            Toast.makeText(context, "Telegram OK", Toast.LENGTH_SHORT).show()
                        }
                        is AuthResult.Failed ->
                            Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                        is AuthResult.Deferred -> Unit
                    }
                    busy = false
                }
            }) {
                Text(S.authTgSignIn(lang), color = TextPrimary)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    AuthProvider.VK to "VK",
                    AuthProvider.GOOGLE to "Google",
                    AuthProvider.YANDEX to "Яндекс"
                ).forEach { (p, label) ->
                    AuthProviderChip(
                        label = label,
                        selected = provider == p,
                        modifier = Modifier.weight(1f)
                    ) {
                        scope.launch {
                            DeferredSocialAuth.gateway(p).beginSignIn()
                            Toast.makeText(context, S.authSocialSoon(lang), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(S.authOrOffline(lang), style = Typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            TukTukButton(onClick = {
                provider = AuthProvider.OFFLINE
                finish()
            }) {
                Text(S.authOfflineContinue(lang), color = TextPrimary)
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                S.authProfileTitle(lang),
                style = Typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                S.authProfileHint(lang),
                style = Typography.labelSmall,
                color = AccentLilac,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp)
            )

            AuthField(
                label = S.authDisplayName(lang),
                placeholder = S.authDisplayNameHint(lang),
                value = nameField,
                onValueChange = { if (it.text.length <= DinoNameGenerator.MAX_LEN) nameField = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            AuthField(
                label = S.authNickname(lang),
                placeholder = S.authNicknameHint(lang),
                value = nickField,
                onValueChange = { if (it.text.length <= DinoNameGenerator.MAX_LEN) nickField = it }
            )

            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 16.dp, strong = true)
                    .bounceClick { finish() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(S.authContinue(lang), color = AccentLime, style = Typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AuthProviderChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .glassPanel(corner = 12.dp, strong = selected)
            .bounceClick(onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) AccentLime else TextPrimary,
            style = Typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AuthField(
    label: String,
    placeholder: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DividerColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (value.text.isEmpty()) {
                        Text(placeholder, color = TextSecondary, style = Typography.bodyLarge)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
