package com.blink.dtn.ui.hub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blink.dtn.ble.NetworkPacket
import com.blink.dtn.db.MessagePriority
import com.blink.dtn.ui.AppLang
import com.blink.dtn.ui.AuthDinoOutline
import com.blink.dtn.ui.BLinkViewModel
import com.blink.dtn.ui.S
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography

private val OledBlack = Color(0xFF000000)
private val CapsuleNormal = Color(0xFF3A3A3A)
private val CapsuleMedium = Color(0xFFC9A227)
private val CapsuleCritical = Color(0xFFC0392B)

/**
 * Courier backpack — real [NetworkPacket] stream from Room via ViewModel.
 */
@Composable
fun CourierTab(
    viewModel: BLinkViewModel,
    modifier: Modifier = Modifier
) {
    val lang by AppLang.lang.collectAsState()
    val packets by viewModel.backpackPackets.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(16.dp)
    ) {
        Text(S.hubCourier(lang), style = Typography.titleMedium, color = TextPrimary)
        Text(
            S.hubCourierHint(lang),
            style = Typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            AuthDinoOutline(modifier = Modifier.size(96.dp))
        }

        Text(
            S.hubBackpack(lang),
            style = Typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (packets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    S.hubBackpackEmpty(lang),
                    style = Typography.bodyMedium,
                    color = TextSecondary.copy(alpha = 0.45f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(packets, key = { it.messageId }) { packet ->
                    ParcelCapsule(packet)
                }
            }
        }
    }
}

@Composable
private fun ParcelCapsule(packet: NetworkPacket) {
    val priority = MessagePriority.fromCode(packet.priority)
    val base = when (priority) {
        MessagePriority.NORMAL -> CapsuleNormal
        MessagePriority.MEDIUM -> CapsuleMedium
        MessagePriority.CRITICAL -> CapsuleCritical
    }
    val pulse = rememberInfiniteTransition(label = "crit-${packet.messageId}")
    val critAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "critAlpha"
    )
    val glow = if (priority == MessagePriority.CRITICAL) critAlpha else 1f
    val title = packet.senderNick.ifBlank { packet.targetId ?: packet.messageId.take(8) }
    val preview = packet.payload.take(48).ifBlank { packet.type }

    Column(
        modifier = Modifier
            .aspectRatio(0.85f)
            .alpha(glow)
            .background(base.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .border(1.dp, base.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(base, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            title,
            style = Typography.labelSmall,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            preview,
            style = Typography.labelSmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
