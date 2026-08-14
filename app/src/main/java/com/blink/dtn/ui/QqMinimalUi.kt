package com.blink.dtn.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blink.dtn.ui.theme.AppRootBackdrop
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One-time flag for the first-run explanation. */
object QqFirstRun {
    private const val PREFS = "blink_prefs"
    private const val KEY = "qq_intro_seen"

    fun seen(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun markSeen(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
    }
}

/**
 * The only explanation Qq ever shows on its own: what it is, how to add someone,
 * and that delivery takes time. Shown once, before the empty dialog list.
 */
@Composable
fun QqIntroScreen(onDone: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    BackHandler(onBack = onDone)

    Box(modifier = Modifier.fillMaxSize()) {
        AppRootBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .padding(top = 48.dp, bottom = 40.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.qq_logo),
                contentDescription = "Qq",
                modifier = Modifier.size(88.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Qq",
                style = Typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 34.sp,
                    letterSpacing = 2.sp
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                S.welcomeTagline(lang),
                style = Typography.titleMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(40.dp))
            Text(S.welcomeWhat(lang), style = Typography.bodyLarge, color = TextPrimary)
            Spacer(modifier = Modifier.height(24.dp))
            Text(S.welcomeHow(lang), style = Typography.bodyLarge, color = TextPrimary)
            Spacer(modifier = Modifier.height(24.dp))
            Text(S.welcomeTime(lang), style = Typography.bodyMedium, color = TextSecondary)

            Spacer(modifier = Modifier.height(48.dp))
            Text(
                S.welcomeStart(lang),
                style = Typography.titleMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onDone)
                    .padding(vertical = 14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactSheet(
    viewModel: BLinkViewModel,
    onDismiss: () -> Unit,
    onOpenedChat: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var contactQr by remember { mutableStateOf(viewModel.myContactQr) }
    var manualId by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var finding by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    var showManualId by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        contactQr = withContext(Dispatchers.IO) { viewModel.buildContactQr() }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned.isNullOrBlank()) return@rememberLauncherForActivityResult
        when (val parsed = com.blink.dtn.crypto.ContactQr.parse(scanned, viewModel.myNodeId)) {
            is com.blink.dtn.crypto.ContactQr.Result.Ok -> {
                val p = parsed.parsed
                val avatar = p.avatarAvBase64?.let { av ->
                    decodeContactQrAvatar(org.json.JSONObject().put("av", av))
                }
                if (p.hasPinnedKey) {
                    viewModel.addScannedContact(p.nodeId, p.nick, p.publicKeyBase64, avatar)
                } else {
                    viewModel.ensureContact(p.nodeId, p.nick)
                    viewModel.setCurrentDialog(p.nodeId)
                }
                onOpenedChat()
            }
            com.blink.dtn.crypto.ContactQr.Result.KeyMismatch ->
                Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
            com.blink.dtn.crypto.ContactQr.Result.Self ->
                Toast.makeText(context, S.qrSelf(lang), Toast.LENGTH_SHORT).show()
            com.blink.dtn.crypto.ContactQr.Result.NotContact ->
                Toast.makeText(context, S.qrNotContact(lang), Toast.LENGTH_LONG).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GlassDialogContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                if (lang == "en") "Add contact" else "Добавить контакт",
                style = Typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(S.findViaQq(lang), color = TextSecondary, style = Typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                enabled = !finding,
                textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        if (username.isEmpty()) {
                            Text(S.usernameHint(lang), color = TextSecondary, style = Typography.bodyMedium)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            QqButton(
                onClick = {
                    val raw = username.trim()
                    if (raw.isBlank() || finding) return@QqButton
                    finding = true
                    viewModel.findByUsername(raw) { ok, meshId, msg ->
                        finding = false
                        if (ok) {
                            viewModel.setCurrentDialog(meshId)
                            onOpenedChat()
                        } else {
                            val text = when (msg) {
                                "need_server" -> S.usernameNeedServer(lang)
                                "need_session" -> S.usernameNeedSignIn(lang)
                                "username_invalid" -> S.usernameInvalid(lang)
                                "self" -> S.usernameSelf(lang)
                                "key_changed" -> S.keyChangedBody(lang)
                                else -> S.usernameNotFound(lang)
                            }
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                            if (msg == "key_changed" && meshId.isNotBlank()) {
                                viewModel.setCurrentDialog(meshId)
                                onOpenedChat()
                            }
                        }
                    }
                }
            ) {
                Text(S.usernameFind(lang), color = TextPrimary)
            }
            Spacer(modifier = Modifier.height(20.dp))
            QuietActionRow(S.scanQr(lang)) {
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                options.setCameraId(0)
                options.setBeepEnabled(false)
                options.setBarcodeImageEnabled(true)
                options.setCaptureActivity(CustomScannerActivity::class.java)
                scanLauncher.launch(options)
            }
            QuietActionRow(S.showQr(lang)) { showMyQr = !showMyQr }
            if (showMyQr) {
                val qrBitmap = remember(contactQr) {
                    generateQrCode(contactQr, 512)?.asImageBitmap()
                }
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = S.showQr(lang),
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .size(200.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Raw ID entry is the only advanced path — collapsed so scanning stays the answer.
            Text(
                if (lang == "en") "Enter ID manually" else "Ввести ID вручную",
                color = TextSecondary.copy(alpha = 0.7f),
                style = Typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick { showManualId = !showManualId }
                    .padding(vertical = 10.dp)
            )
            if (showManualId) {
                Spacer(modifier = Modifier.height(8.dp))
                BasicTextField(
                    value = manualId,
                    onValueChange = { manualId = it },
                    singleLine = true,
                    textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(TextPrimary),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                        ) {
                            if (manualId.isEmpty()) {
                                Text("Qq ID", color = TextSecondary, style = Typography.bodyMedium)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                QqButton(
                    onClick = {
                        val id = manualId.trim()
                        if (id.isBlank()) return@QqButton
                        viewModel.addContactOnlineOrLocal(id) { ok, meshId, _ ->
                            if (ok || meshId.isNotBlank()) {
                                viewModel.setCurrentDialog(meshId.ifBlank { id })
                                onOpenedChat()
                            }
                        }
                    }
                ) {
                    Text(if (lang == "en") "Add" else "Добавить", color = TextPrimary)
                }
            }
        }
    }
}

/**
 * Own profile — Jobs-like: one composition, dark canvas, name + ID + QR, quiet links.
 * Must paint [AppRootBackdrop]: early-exit from MainScreen has no Scaffold backdrop
 * (white Activity window + light TextPrimary = blank / invisible screen).
 */
@Composable
fun QqMinimalProfile(
    viewModel: BLinkViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var contactQr by remember { mutableStateOf(viewModel.myContactQr) }
    var nameField by remember { mutableStateOf(viewModel.displayName()) }
    var nickField by remember { mutableStateOf(viewModel.myNick) }
    var savedName by remember { mutableStateOf(viewModel.displayName()) }
    var savedNick by remember { mutableStateOf(viewModel.myNick) }
    val dirty = nameField != savedName || nickField != savedNick

    LaunchedEffect(Unit) {
        contactQr = withContext(Dispatchers.IO) { viewModel.buildContactQr() }
    }

    BackHandler(onBack = onBack)

    Box(modifier = Modifier.fillMaxSize()) {
        AppRootBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 4.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SettingsBackRow("", onBack)

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                "Qq",
                style = Typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                    fontSize = 15.sp
                ),
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Focus: display name
            BasicTextField(
                value = nameField,
                onValueChange = { if (it.length <= 20) nameField = it },
                singleLine = true,
                textStyle = Typography.titleLarge.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (nameField.isEmpty()) {
                            Text(
                                if (lang == "en") "Name" else "Имя",
                                style = Typography.titleLarge.copy(
                                    fontSize = 28.sp,
                                    textAlign = TextAlign.Center
                                ),
                                color = TextSecondary.copy(alpha = 0.45f)
                            )
                        }
                        inner()
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary: nick
            BasicTextField(
                value = nickField,
                onValueChange = { if (it.length <= 20) nickField = it },
                singleLine = true,
                textStyle = Typography.bodyMedium.copy(
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(TextSecondary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (nickField.isEmpty()) {
                            Text(
                                if (lang == "en") "Nickname" else "Ник",
                                style = Typography.bodyMedium,
                                color = TextSecondary.copy(alpha = 0.4f)
                            )
                        }
                        inner()
                    }
                }
            )

            if (dirty) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    S.save(lang),
                    color = TextPrimary,
                    style = Typography.labelMedium.copy(letterSpacing = 1.sp),
                    modifier = Modifier
                        .bounceClick {
                            viewModel.updateMyNameAndNick(nameField, nickField)
                            savedName = nameField
                            savedNick = nickField
                            Toast.makeText(context, S.nameSaved(lang), Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                S.yourId(lang).uppercase(),
                color = TextSecondary.copy(alpha = 0.7f),
                style = Typography.labelSmall.copy(letterSpacing = 1.5.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                viewModel.myNodeId,
                color = TextPrimary,
                style = Typography.bodyMedium.copy(letterSpacing = 0.3.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .bounceClick {
                        clipboardManager.setText(AnnotatedString(viewModel.myNodeId))
                        Toast.makeText(context, S.idCopied(lang), Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Text(
                if (lang == "en") "Tap to copy" else "Нажмите, чтобы скопировать",
                color = TextSecondary.copy(alpha = 0.5f),
                style = Typography.labelSmall
            )

            Spacer(modifier = Modifier.height(40.dp))

            val qrBitmap = remember(contactQr) {
                generateQrCode(contactQr, 512)?.asImageBitmap()
            }
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = S.showQr(lang),
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Scanning lives behind ＋ only — one entry point for adding people.
            QuietActionRow(S.settings(lang), onOpenSettings)
            QuietActionRow(S.aboutProject(lang), onOpenAbout)
        }
    }
}

/** Secondary profile / sheet action — text only, no glass chrome. */
@Composable
private fun QuietActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = TextSecondary,
        style = Typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick)
            .padding(vertical = 16.dp)
    )
}
