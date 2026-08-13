package com.blink.dtn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import java.io.File

const val TUKTUK_DOWNLOAD_URL =
    "https://github.com/gustoa432-byte/Tuk-tuk/releases/latest"
const val TUKTUK_GITHUB_URL =
    "https://github.com/gustoa432-byte/Tuk-tuk"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendsSheet(
    contactQrPayload: String,
    onDismiss: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val qrBitmap = remember(contactQrPayload) {
        generateQrCode(contactQrPayload, 512)?.asImageBitmap()
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
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(S.inviteFriends(lang), style = Typography.titleLarge, color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(S.inviteFriendsHint(lang), style = Typography.bodySmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(16.dp))

            InviteRow(S.shareApk(lang)) { shareApk(context) }
            InviteRow(S.shareLink(lang)) {
                shareText(context, TUKTUK_DOWNLOAD_URL, S.shareTukTuk(lang))
            }
            InviteRow(S.copyLink(lang)) {
                copyText(context, TUKTUK_DOWNLOAD_URL)
                Toast.makeText(context, S.linkCopied(lang), Toast.LENGTH_SHORT).show()
            }
            InviteRow(S.sendViaTelegram(lang)) {
                shareToPackage(context, TUKTUK_DOWNLOAD_URL, "org.telegram.messenger", "org.telegram.messenger.web")
            }
            InviteRow(S.sendViaVk(lang)) {
                shareToPackage(context, TUKTUK_DOWNLOAD_URL, "com.vkontakte.android", "com.vk.im")
            }
            InviteRow(S.sendViaWhatsApp(lang)) {
                shareToPackage(context, TUKTUK_DOWNLOAD_URL, "com.whatsapp", "com.whatsapp.w4b")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(S.showQr(lang), style = Typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            if (qrBitmap != null) {
                BoxWhiteQr(qrBitmap)
            }
        }
    }
}

@Composable
private fun InviteRow(label: String, onClick: () -> Unit) {
    TukTukButton(onClick = onClick) {
        Text(label, color = TextPrimary, style = Typography.bodyMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun BoxWhiteQr(bitmap: androidx.compose.ui.graphics.ImageBitmap) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(180.dp)
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(152.dp))
    }
}

fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareToPackage(context: Context, text: String, vararg packages: String) {
    for (pkg in packages) {
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // try next
        }
    }
    shareText(context, text, S.shareTukTuk(AppLang.lang.value))
}

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Qq", text))
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

fun shareApkFile(context: Context) {
    shareApk(context)
}
