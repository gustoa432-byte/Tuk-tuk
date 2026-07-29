package com.blink.dtn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.Typography

@Composable
fun PeerAvatar(
    avatarBlob: ByteArray?,
    label: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(avatarBlob) { AvatarCompressor.decodeToBitmap(avatarBlob) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(DividerColor),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Text(
                text = initialsOf(label),
                color = TextPrimary,
                style = Typography.labelMedium.copy(fontSize = (size.value * 0.32f).sp)
            )
        }
    }
}

private fun initialsOf(label: String): String {
    val cleaned = label.trim()
    if (cleaned.isEmpty()) return "?"
    val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        else -> cleaned.take(2).uppercase()
    }
}
