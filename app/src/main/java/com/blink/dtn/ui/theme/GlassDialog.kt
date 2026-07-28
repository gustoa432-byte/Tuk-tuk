package com.blink.dtn.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val GlassShape = RoundedCornerShape(24.dp)

/**
 * Glassmorphism modal: distant blurred ambient light under a frosted panel
 * with a top-edge highlight stroke.
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmLabel: String = "Закрыть",
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.Center
        ) {
            // Dim scrim so the glow reads as a light source, not a dirty blob.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )

            // 1) Background lighting — soft lunar radial; blur on API 31+.
            val glowBlur =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(110.dp)
                else Modifier
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 340.dp else 480.dp)
                    .then(glowBlur)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                GlassAmbientGlow.copy(alpha = 0.55f),
                                GlassAmbientGlow.copy(alpha = 0.22f),
                                GlassAmbientGlow.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // 2) Frosted glass panel
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(max = 520.dp)
                    .clip(GlassShape)
                    .background(GlassPanelFill)
                    .background(Color.White.copy(alpha = 0.06f))
                    .glassTopStroke(GlassStroke)
                    .border(1.dp, GlassStroke.copy(alpha = 0.12f), GlassShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { /* consume — don't dismiss when tapping panel */ }
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(title, style = Typography.titleLarge, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(confirmLabel, color = TextPrimary)
                }
            }
        }
    }
}

/** 1 dp highlight along the top edge / top corners of the glass. */
private fun Modifier.glassTopStroke(strokeColor: Color): Modifier = drawWithContent {
    drawContent()
    val y = 0.5.dp.toPx()
    val inset = 20.dp.toPx()
    drawLine(
        color = strokeColor,
        start = Offset(inset, y),
        end = Offset(size.width - inset, y),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round
    )
}
