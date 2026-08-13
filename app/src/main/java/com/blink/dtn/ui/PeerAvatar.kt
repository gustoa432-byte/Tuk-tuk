package com.blink.dtn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.theme.DividerColor

/**
 * Avatar resolution (zero network overhead for defaults):
 * 1. Custom JPEG blob (from QR / local picker) → show photo
 * 2. Else → [AvatarHelper.getDefaultAvatarForUid] — same dino on every node
 */
@Composable
fun PeerAvatar(
    avatarBlob: ByteArray?,
    label: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    /** Stable mesh node id — drives deterministic default dino. */
    uid: String = ""
) {
    val bitmap = remember(avatarBlob) { AvatarCompressor.decodeToBitmap(avatarBlob) }
    val dinoRes = remember(uid.ifBlank { label }) {
        AvatarHelper.getDefaultAvatarForUid(uid.ifBlank { label })
    }
    val frame = if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
        null
    } else {
        val gm by GamificationStore.snap.collectAsState()
        CosmeticApply.frameColor(gm.frameId)
    }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (frame != null) Modifier
                    .clip(CircleShape)
                    .background(frame)
                    .padding(2.dp)
                else Modifier
            )
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
            Image(
                painter = painterResource(id = dinoRes),
                contentDescription = label.ifBlank { "avatar" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        }
    }
}
