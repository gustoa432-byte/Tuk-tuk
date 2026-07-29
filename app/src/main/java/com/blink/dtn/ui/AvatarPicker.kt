package com.blink.dtn.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gallery picker + mesh compressor preview. Calls [onCompressed] with JPEG bytes
 * ready for DB/QR (never full-res).
 */
@Composable
fun rememberAvatarPicker(
    onCompressed: (ByteArray) -> Unit,
    onTooBigForQr: (() -> Unit)? = null
): () -> Unit {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    var compressing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) pendingUri = uri
    }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        compressing = true
        val result = withContext(Dispatchers.IO) {
            AvatarCompressor.compressFromUri(context, uri)
        }
        compressing = false
        if (result == null) {
            Toast.makeText(context, "Не удалось обработать фото", Toast.LENGTH_SHORT).show()
            pendingUri = null
        } else {
            previewBytes = result
            previewBitmap = AvatarCompressor.decodeToBitmap(result)
        }
    }

    if (previewBytes != null && previewBitmap != null) {
        AlertDialog(
            onDismissRequest = {
                previewBytes = null
                previewBitmap = null
                pendingUri = null
            },
            title = { Text("Аватар для сети", color = TextPrimary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Квадрат ${AvatarCompressor.EDGE_PX}×${AvatarCompressor.EDGE_PX}, сжатый JPEG — удобно для QR и mesh.",
                        color = TextSecondary,
                        style = Typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(DividerColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(120.dp).clip(CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "~${previewBytes!!.size} байт",
                        color = TextSecondary,
                        style = Typography.labelSmall
                    )
                    if (compressing) {
                        Text("Сжатие…", color = TextSecondary, style = Typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = previewBytes ?: return@TextButton
                    val qrFit = AvatarCompressor.fitForQr(bytes)
                    if (qrFit == null) {
                        onTooBigForQr?.invoke()
                    }
                    onCompressed(bytes)
                    previewBytes = null
                    previewBitmap = null
                    pendingUri = null
                }) {
                    Text("Сохранить", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    previewBytes = null
                    previewBitmap = null
                    pendingUri = null
                }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
            containerColor = GlassDialogContainer
        )
    }

    return {
        picker.launch("image/*")
    }
}

fun decodeContactQrAvatar(json: org.json.JSONObject): ByteArray? {
    val av = json.optString("av", "")
    if (av.isBlank()) return null
    return try {
        val raw = android.util.Base64.decode(av, android.util.Base64.DEFAULT)
        if (raw.isEmpty() || raw.size > AvatarCompressor.MAX_DB_BYTES * 2) return null
        val bmp = AvatarCompressor.decodeToBitmap(raw) ?: return null
        AvatarCompressor.compressBitmap(bmp) ?: raw.takeIf { it.size <= AvatarCompressor.MAX_DB_BYTES }
    } catch (_: Exception) {
        null
    }
}
