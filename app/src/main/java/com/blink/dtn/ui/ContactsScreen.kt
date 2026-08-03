package com.blink.dtn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult

@Composable
fun ContactsScreen(
    viewModel: BLinkViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onInvite: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val dialogs by viewModel.dialogs.collectAsState()
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var addId by remember { mutableStateOf("") }

    val contacts = remember(dialogs, query) {
        dialogs
            .filter { it.conversationId != "general" && !it.isArchived }
            .filter {
                val q = query.trim().lowercase()
                q.isEmpty() ||
                    it.displayName.orEmpty().lowercase().contains(q) ||
                    it.conversationId.lowercase().contains(q)
            }
            .sortedByDescending { it.lastTimestamp }
    }
    val favorites = contacts.filter { it.isPinned }
    val recent = contacts.take(8)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && scanned.isNotBlank()) {
            val json = try { org.json.JSONObject(scanned) } catch (_: Exception) { null }
            val pk = json?.optString("pk", "")
            if (json != null && !pk.isNullOrEmpty()) {
                val derivedId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pk)
                val claimedId = json.optString("id", "")
                if (derivedId.isEmpty() || (claimedId.isNotEmpty() && claimedId != derivedId)) {
                    Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
                } else {
                    val nick = json.optString("n", "")
                    val avatar = decodeContactQrAvatar(json)
                    viewModel.addScannedContact(derivedId, nick, pk, avatar)
                    onOpenChat(derivedId)
                }
            } else {
                viewModel.ensureContact(scanned)
                onOpenChat(scanned)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .bounceClick(onBack)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(S.contacts(lang), style = Typography.titleLarge, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .glassPanel(corner = 12.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(S.searchDialogs(lang), color = TextSecondary, style = Typography.bodyMedium)
                    }
                    inner()
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (favorites.isNotEmpty()) {
                item { SectionLabel(S.favorites(lang)) }
                items(favorites, key = { "fav-" + it.conversationId }) { d ->
                    ContactRow(d.displayName ?: d.conversationId, d.conversationId) {
                        onOpenChat(d.conversationId)
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionLabel(S.recent(lang)) }
                items(recent, key = { "rec-" + it.conversationId }) { d ->
                    ContactRow(d.displayName ?: d.conversationId, d.conversationId) {
                        onOpenChat(d.conversationId)
                    }
                }
            }
            item { SectionLabel(S.contacts(lang)) }
            items(contacts, key = { "all-" + it.conversationId }) { d ->
                ContactRow(d.displayName ?: d.conversationId, d.conversationId) {
                    onOpenChat(d.conversationId)
                }
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
                SettingsNavRow(S.inviteFriends(lang), onInvite)
                SettingsNavRow(S.addById(lang)) { /* scroll focus via add field below */ }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(S.addById(lang), color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = addId,
                onValueChange = { addId = it },
                singleLine = true,
                textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(corner = 12.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (addId.isEmpty()) {
                            Text(S.enterPeerId(lang), color = TextSecondary, style = Typography.bodySmall)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TukTukButton(onClick = {
                val id = addId.trim()
                if (id.isBlank()) return@TukTukButton
                viewModel.ensureContact(id)
                onOpenChat(id)
                addId = ""
                focusManager.clearFocus()
            }) {
                Text(S.addToContacts(lang), color = TextPrimary, style = Typography.labelSmall)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TukTukButton(onClick = {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setCameraId(0)
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            options.setCaptureActivity(CustomScannerActivity::class.java)
            scanLauncher.launch(options)
        }) {
            Text(S.scanQr(lang), color = TextPrimary)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        style = Typography.labelSmall,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun ContactRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(avatarBlob = null, label = title, size = 40.dp, uid = subtitle)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = Typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle.take(12) + if (subtitle.length > 12) "…" else "",
                color = TextSecondary,
                style = Typography.labelSmall,
                maxLines = 1
            )
        }
    }
}
