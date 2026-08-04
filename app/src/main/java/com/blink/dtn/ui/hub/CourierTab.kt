package com.blink.dtn.ui.hub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blink.dtn.db.MessagePriority
import com.blink.dtn.ui.AppLang
import com.blink.dtn.ui.S
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography

private val OledBlack = Color(0xFF000000)
private val CapsuleNormal = Color(0xFF3A3A3A)
private val CapsuleMedium = Color(0xFFC9A227)
private val CapsuleCritical = Color(0xFFC0392B)
private val OutlineDino = Color(0xFF6E6E6E)

/**
 * Tab 2 — courier inventory: dino silhouette + backpack grid of parcels by priority.
 */
@Composable
fun CourierTab(
    modifier: Modifier = Modifier,
    parcels: List<ParcelItem> = HubMocks.parcels
) {
    val lang by AppLang.lang.collectAsState()

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
            DinoOutlinePlaceholder(modifier = Modifier.size(96.dp))
        }

        Text(
            S.hubBackpack(lang),
            style = Typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(parcels, key = { it.id }) { parcel ->
                ParcelCapsule(parcel)
            }
        }
    }
}

@Composable
private fun DinoOutlinePlaceholder(modifier: Modifier = Modifier) {
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
        drawCircle(
            color = OutlineDino,
            radius = 3f,
            center = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.3f)
        )
    }
}

@Composable
private fun ParcelCapsule(parcel: ParcelItem) {
    val base = when (parcel.priority) {
        MessagePriority.NORMAL -> CapsuleNormal
        MessagePriority.MEDIUM -> CapsuleMedium
        MessagePriority.CRITICAL -> CapsuleCritical
    }
    val pulse = rememberInfiniteTransition(label = "crit-${parcel.id}")
    val critAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "critAlpha"
    )
    val glow = if (parcel.priority == MessagePriority.CRITICAL) critAlpha else 1f

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
            parcel.title,
            style = Typography.labelSmall,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            parcel.preview,
            style = Typography.labelSmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
