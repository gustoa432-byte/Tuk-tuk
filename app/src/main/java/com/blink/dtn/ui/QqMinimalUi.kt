package com.blink.dtn.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var showMyQr by remember { mutableStateOf(false) }

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
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                if (lang == "en") "Add contact" else "Добавить контакт",
                style = Typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsNavRow(S.scanQr(lang)) {
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                options.setCameraId(0)
                options.setBeepEnabled(false)
                options.setBarcodeImageEnabled(true)
                options.setCaptureActivity(CustomScannerActivity::class.java)
                scanLauncher.launch(options)
            }
            SettingsNavRow(S.showQr(lang)) { showMyQr = !showMyQr }
            if (showMyQr) {
                val qrBitmap = remember(contactQr) {
                    generateQrCode(contactQr, 512)?.asImageBitmap()
                }
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = S.showQr(lang),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .size(200.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (lang == "en") "Or enter ID" else "Или введите ID",
                color = TextSecondary,
                style = Typography.labelSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
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
                            .glassPanel(corner = 12.dp)
                            .padding(12.dp)
                    ) {
                        if (manualId.isEmpty()) {
                            Text("Qq ID", color = TextSecondary, style = Typography.bodyMedium)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            TukTukButton(
                onClick = {
                    val id = manualId.trim()
                    if (id.isBlank()) return@TukTukButton
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

@Composable
fun QqMinimalProfile(
    viewModel: BLinkViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onScanSuccess: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var contactQr by remember { mutableStateOf(viewModel.myContactQr) }
    var nameField by remember { mutableStateOf(viewModel.displayName()) }
    var nickField by remember { mutableStateOf(viewModel.myNick) }

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
                onScanSuccess()
            }
            com.blink.dtn.crypto.ContactQr.Result.KeyMismatch ->
                Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
            com.blink.dtn.crypto.ContactQr.Result.Self ->
                Toast.makeText(context, S.qrSelf(lang), Toast.LENGTH_SHORT).show()
            com.blink.dtn.crypto.ContactQr.Result.NotContact ->
                Toast.makeText(context, S.qrNotContact(lang), Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.profile(lang), onBack)
        Spacer(modifier = Modifier.height(16.dp))
        Text(if (lang == "en") "Name" else "Имя", color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(6.dp))
        BasicTextField(
            value = nameField,
            onValueChange = { if (it.length <= 20) nameField = it },
            singleLine = true,
            textStyle = Typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 12.dp)
                .padding(12.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(if (lang == "en") "Nickname" else "Ник", color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(6.dp))
        BasicTextField(
            value = nickField,
            onValueChange = { if (it.length <= 20) nickField = it },
            singleLine = true,
            textStyle = Typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 12.dp)
                .padding(12.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        TukTukButton(
            onClick = {
                viewModel.updateMyNameAndNick(nameField, nickField)
                Toast.makeText(context, S.nameSaved(lang), Toast.LENGTH_SHORT).show()
            }
        ) {
            Text(S.save(lang), color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(S.yourId(lang), color = TextSecondary, style = Typography.labelSmall)
        Text(
            viewModel.myNodeId,
            color = TextPrimary,
            style = Typography.bodySmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .bounceClick {
                    clipboardManager.setText(AnnotatedString(viewModel.myNodeId))
                    Toast.makeText(context, S.idCopied(lang), Toast.LENGTH_SHORT).show()
                }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(S.showQr(lang), color = TextSecondary, style = Typography.labelSmall)
        val qrBitmap = remember(contactQr) {
            generateQrCode(contactQr, 512)?.asImageBitmap()
        }
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap,
                contentDescription = S.showQr(lang),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(180.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        SettingsNavRow(S.settings(lang), onOpenSettings)
        SettingsNavRow(S.aboutProject(lang), onOpenAbout)
        SettingsNavRow(S.scanQr(lang)) {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setCameraId(0)
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            options.setCaptureActivity(CustomScannerActivity::class.java)
            scanLauncher.launch(options)
        }
    }
}
