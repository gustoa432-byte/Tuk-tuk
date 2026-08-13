package com.blink.dtn.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blink.dtn.auth.AuthProvider
import com.blink.dtn.auth.AuthResult
import com.blink.dtn.auth.DinoNameGenerator
import com.blink.dtn.auth.EmailOtpAuth
import com.blink.dtn.auth.TelegramAuth
import com.blink.dtn.net.VpsConfig
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import kotlinx.coroutines.launch

private val OledBlack = Color(0xFF000000)
private val FieldFill = Color(0xFF1A1A1A)
private val AccentFill = Color(0xFF1F2A1A)
private val OutlineDino = Color(0xFF6E6E6E)

private enum class AuthStep { Welcome, SignIn, Profile }

/**
 * OLED onboarding: welcome → sign-in (email / TG / offline) → name & nick.
 */
@Composable
fun AuthOnboardingScreen(
    onComplete: (displayName: String, nick: String, provider: AuthProvider) -> Unit
) {
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(AuthStep.Welcome) }
    var provider by remember { mutableStateOf(AuthProvider.OFFLINE) }
    var nameField by remember { mutableStateOf(TextFieldValue("")) }
    var nickField by remember { mutableStateOf(TextFieldValue("")) }
    var emailField by remember { mutableStateOf(TextFieldValue("")) }
    var otpField by remember { mutableStateOf(TextFieldValue("")) }
    var tgInitField by remember { mutableStateOf(TextFieldValue("")) }
    var showTgField by remember { mutableStateOf(false) }
    var otpSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun goProfile(p: AuthProvider) {
        provider = p
        step = AuthStep.Profile
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        when (step) {
            AuthStep.Welcome -> WelcomeStep(
                lang = lang,
                onContinue = { step = AuthStep.SignIn }
            )
            AuthStep.SignIn -> SignInStep(
                lang = lang,
                emailField = emailField,
                onEmail = { emailField = it },
                otpField = otpField,
                onOtp = { if (it.text.length <= 8) otpField = it },
                otpSent = otpSent,
                tgInitField = tgInitField,
                onTgInit = { tgInitField = it },
                showTgField = showTgField,
                busy = busy,
                onGetCode = {
                    if (busy) return@SignInStep
                    VpsConfig.init(context)
                    scope.launch {
                        busy = true
                        when (val r = EmailOtpAuth(context, emailField.text).beginSignIn()) {
                            is AuthResult.Success -> {
                                otpSent = true
                                Toast.makeText(context, S.authCodeSent(lang), Toast.LENGTH_SHORT).show()
                            }
                            is AuthResult.Failed ->
                                Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                            is AuthResult.Deferred -> Unit
                        }
                        busy = false
                    }
                },
                onVerifyEmail = {
                    if (busy) return@SignInStep
                    VpsConfig.init(context)
                    scope.launch {
                        busy = true
                        when (
                            val r = EmailOtpAuth(context, emailField.text, otpField.text).beginSignIn()
                        ) {
                            is AuthResult.Success -> {
                                Toast.makeText(context, S.authEmailOk(lang), Toast.LENGTH_SHORT).show()
                                goProfile(AuthProvider.EMAIL)
                            }
                            is AuthResult.Failed ->
                                Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                            is AuthResult.Deferred -> Unit
                        }
                        busy = false
                    }
                },
                onToggleTelegram = { showTgField = !showTgField },
                onTelegramSignIn = {
                    if (busy) return@SignInStep
                    if (tgInitField.text.isBlank()) {
                        showTgField = true
                        Toast.makeText(context, S.authTgHint(lang), Toast.LENGTH_SHORT).show()
                        return@SignInStep
                    }
                    VpsConfig.init(context)
                    scope.launch {
                        busy = true
                        when (val r = TelegramAuth(context, tgInitField.text).beginSignIn()) {
                            is AuthResult.Success -> goProfile(AuthProvider.TELEGRAM)
                            is AuthResult.Failed ->
                                Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                            is AuthResult.Deferred -> Unit
                        }
                        busy = false
                    }
                },
                onOffline = { goProfile(AuthProvider.OFFLINE) }
            )
            AuthStep.Profile -> ProfileStep(
                lang = lang,
                nameField = nameField,
                onName = { if (it.text.length <= DinoNameGenerator.MAX_LEN) nameField = it },
                nickField = nickField,
                onNick = { if (it.text.length <= DinoNameGenerator.MAX_LEN) nickField = it },
                onContinue = {
                    val name = DinoNameGenerator.resolveDisplayName(nameField.text, lang)
                    val nick = nickField.text.trim().take(DinoNameGenerator.MAX_LEN)
                    onComplete(name, nick, provider)
                }
            )
        }
    }
}

@Composable
private fun WelcomeStep(lang: String, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.35f))
        AuthDinoOutline(modifier = Modifier.size(120.dp))
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            S.authWelcomeBody(lang),
            style = Typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.weight(0.45f))
        AuthFillButton(
            label = S.authContinue(lang),
            accent = true,
            onClick = onContinue
        )
    }
}

@Composable
private fun SignInStep(
    lang: String,
    emailField: TextFieldValue,
    onEmail: (TextFieldValue) -> Unit,
    otpField: TextFieldValue,
    onOtp: (TextFieldValue) -> Unit,
    otpSent: Boolean,
    tgInitField: TextFieldValue,
    onTgInit: (TextFieldValue) -> Unit,
    showTgField: Boolean,
    busy: Boolean,
    onGetCode: () -> Unit,
    onVerifyEmail: () -> Unit,
    onToggleTelegram: () -> Unit,
    onTelegramSignIn: () -> Unit,
    onOffline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Qq", style = Typography.headlineMedium, color = TextPrimary)
        Text(
            S.authSignInTitle(lang),
            style = Typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
        )

        AuthMinimalField(
            placeholder = S.authEmailHint(lang),
            value = emailField,
            onValueChange = onEmail
        )
        Spacer(modifier = Modifier.height(10.dp))
        AuthFillButton(
            label = S.authGetCode(lang),
            onClick = onGetCode,
            enabled = !busy
        )

        if (otpSent) {
            Spacer(modifier = Modifier.height(14.dp))
            AuthMinimalField(
                placeholder = S.authOtpHint(lang),
                value = otpField,
                onValueChange = onOtp
            )
            Spacer(modifier = Modifier.height(10.dp))
            AuthFillButton(
                label = S.authVerifyEmail(lang),
                onClick = onVerifyEmail,
                enabled = !busy
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        AuthFillButton(
            label = S.authTgSignIn(lang),
            onClick = {
                if (showTgField) onTelegramSignIn() else onToggleTelegram()
            },
            enabled = !busy
        )
        if (showTgField) {
            Spacer(modifier = Modifier.height(10.dp))
            AuthMinimalField(
                placeholder = S.authTgHint(lang),
                value = tgInitField,
                onValueChange = onTgInit
            )
            Spacer(modifier = Modifier.height(10.dp))
            AuthFillButton(
                label = S.authTgConfirm(lang),
                onClick = onTelegramSignIn,
                enabled = !busy
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        AuthFillButton(
            label = S.authOfflineContinue(lang),
            accent = true,
            tall = true,
            onClick = onOffline,
            enabled = !busy
        )
    }
}

@Composable
private fun ProfileStep(
    lang: String,
    nameField: TextFieldValue,
    onName: (TextFieldValue) -> Unit,
    nickField: TextFieldValue,
    onNick: (TextFieldValue) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(S.authProfileTitle(lang), style = Typography.titleLarge, color = TextPrimary)
        Text(
            S.authProfileHint(lang),
            style = Typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        AuthMinimalField(
            placeholder = S.authDisplayNameHint(lang),
            value = nameField,
            onValueChange = onName
        )
        Spacer(modifier = Modifier.height(12.dp))
        AuthMinimalField(
            placeholder = S.authNicknameHint(lang),
            value = nickField,
            onValueChange = onNick
        )
        Spacer(modifier = Modifier.height(28.dp))
        AuthFillButton(
            label = S.authContinue(lang),
            accent = true,
            tall = true,
            onClick = onContinue
        )
    }
}

@Composable
fun AuthDinoOutline(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.35f, h * 0.75f)
            cubicTo(w * 0.15f, h * 0.55f, w * 0.2f, h * 0.25f, w * 0.45f, h * 0.2f)
            cubicTo(w * 0.55f, h * 0.08f, w * 0.72f, h * 0.12f, w * 0.78f, h * 0.28f)
            cubicTo(w * 0.9f, h * 0.35f, w * 0.88f, h * 0.55f, w * 0.7f, h * 0.58f)
            cubicTo(w * 0.78f, h * 0.7f, w * 0.65f, h * 0.85f, w * 0.5f, h * 0.78f)
            cubicTo(w * 0.42f, h * 0.9f, w * 0.28f, h * 0.88f, w * 0.35f, h * 0.75f)
            close()
        }
        drawPath(path, color = OutlineDino, style = Stroke(width = 2.5f))
        drawCircle(color = OutlineDino, radius = 3.5f, center = Offset(w * 0.68f, h * 0.3f))
    }
}

@Composable
private fun AuthMinimalField(
    placeholder: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit
) {
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
                    .background(FieldFill, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                if (value.text.isEmpty()) {
                    Text(placeholder, color = TextSecondary.copy(alpha = 0.55f), style = Typography.bodyLarge)
                }
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AuthFillButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    tall: Boolean = false
) {
    val bg = when {
        !enabled -> FieldFill.copy(alpha = 0.5f)
        accent -> AccentFill
        else -> FieldFill
    }
    val fg = when {
        !enabled -> TextSecondary
        accent -> AccentLime
        else -> TextPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(if (tall) 18.dp else 14.dp))
            .then(
                if (enabled) Modifier.bounceClick(onClick)
                else Modifier
            )
            .padding(vertical = if (tall) 18.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, style = if (tall) Typography.titleMedium else Typography.bodyLarge)
    }
}
