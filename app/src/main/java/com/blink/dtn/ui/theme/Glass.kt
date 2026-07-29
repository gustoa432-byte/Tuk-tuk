package com.blink.dtn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val AppBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0A0B0D),
        Color(0xFF10131A),
        Color(0xFF0C1A12)
    )
)

/** Dark translucent surface for AlertDialog / sheet containers. */
val GlassDialogContainer = Color(0xE610131A)

fun Modifier.glassPanel(
    corner: Dp = 20.dp,
    strong: Boolean = false
): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .background(if (strong) GlassFillStrong.copy(alpha = 0.18f) else GlassFill.copy(alpha = 0.12f))
        .border(
            width = 1.dp,
            color = if (strong) GlassBorderStrong.copy(alpha = 0.35f) else GlassBorder,
            shape = shape
        )
}

/** Chat bubble glass — mine gets a stronger fill and lime-tinted border. */
fun Modifier.glassBubble(
    isMine: Boolean,
    corner: Dp = 16.dp,
    tailCorner: Dp = 4.dp
): Modifier {
    val shape = RoundedCornerShape(
        topStart = corner,
        topEnd = corner,
        bottomStart = if (isMine) corner else tailCorner,
        bottomEnd = if (isMine) tailCorner else corner
    )
    return this
        .clip(shape)
        .background(
            if (isMine) GlassFillStrong.copy(alpha = 0.22f) else GlassFill.copy(alpha = 0.12f)
        )
        .border(
            width = 1.dp,
            color = if (isMine) GlassBorderStrong.copy(alpha = 0.42f) else GlassBorder,
            shape = shape
        )
}

@Composable
fun AppGlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush),
        content = content
    )
}
