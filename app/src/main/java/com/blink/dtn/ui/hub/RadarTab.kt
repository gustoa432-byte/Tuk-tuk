package com.blink.dtn.ui.hub

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.blink.dtn.db.UserProfile
import com.blink.dtn.ui.AppLang
import com.blink.dtn.ui.BLinkViewModel
import com.blink.dtn.ui.S
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import kotlin.math.cos
import kotlin.math.sin

private val OledBlack = Color(0xFF000000)
private val SonarRing = Color(0xFF4A7C59)
private val SignalDot = Color(0xFFB8C5A6)

private enum class RadarPhase {
    SEARCHING,
    SIGNAL,
    HANDSHAKE
}

private const val HANDSHAKE_FRESH_MS = 15 * 60_000L

/**
 * Radar driven by [BLinkViewModel.activePeers] (BLE discovery) and
 * [BLinkViewModel.recentKeyedPeers] (Room publicBleKey after identity handshake).
 */
@Composable
fun RadarTab(
    viewModel: BLinkViewModel,
    modifier: Modifier = Modifier
) {
    val lang by AppLang.lang.collectAsState()
    val view = LocalView.current
    val peerCount by viewModel.peerCount.collectAsState()
    val activePeers by viewModel.activePeers.collectAsState()
    val keyedPeers by viewModel.recentKeyedPeers.collectAsState()

    val freshHandshake = remember(keyedPeers) {
        val now = System.currentTimeMillis()
        keyedPeers.firstOrNull { now - it.lastSeen <= HANDSHAKE_FRESH_MS }
    }

    val phase = when {
        freshHandshake != null -> RadarPhase.HANDSHAKE
        peerCount > 0 || activePeers.isNotEmpty() -> RadarPhase.SIGNAL
        else -> RadarPhase.SEARCHING
    }

    var lastPeerCount by remember { mutableStateOf(0) }
    LaunchedEffect(peerCount) {
        if (peerCount > lastPeerCount && peerCount > 0) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        lastPeerCount = peerCount
    }
    var lastHandshakeId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(freshHandshake?.userId) {
        val id = freshHandshake?.userId
        if (id != null && id != lastHandshakeId) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            lastHandshakeId = id
        }
    }

    val pulse = rememberInfiniteTransition(label = "sonar")
    val wave by pulse.animateFloat(
        initialValue = 0.15f,
        targetValue = if (phase == RadarPhase.SEARCHING) 0.35f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAlpha"
    )
    val expand by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveExpand"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(S.hubRadar(lang), style = Typography.titleMedium, color = TextPrimary)
        Text(
            when (phase) {
                RadarPhase.SEARCHING -> S.hubRadarSearching(lang)
                RadarPhase.SIGNAL -> S.hubRadarSignal(lang)
                RadarPhase.HANDSHAKE -> S.hubRadarHandshake(lang)
            },
            style = Typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(280.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f
                for (i in 1..3) {
                    drawCircle(
                        color = SonarRing.copy(alpha = 0.12f * i),
                        radius = maxR * (i / 3f),
                        center = c,
                        style = Stroke(width = 1.5f)
                    )
                }
                drawCircle(
                    color = SonarRing.copy(alpha = wave * (1f - expand * 0.5f)),
                    radius = maxR * expand,
                    center = c,
                    style = Stroke(width = 2f)
                )
                drawCircle(
                    color = SonarRing.copy(alpha = 0.45f),
                    radius = 10f,
                    center = c
                )

                // One edge dot per discovered BLE MAC (capped).
                val dots = activePeers.take(8)
                dots.forEachIndexed { index, _ ->
                    val angle = Math.toRadians(28.0 + index * (320.0 / dots.size.coerceAtLeast(1)))
                    val edge = maxR * 0.82f
                    val dot = Offset(
                        c.x + (cos(angle) * edge).toFloat(),
                        c.y + (sin(angle) * edge).toFloat()
                    )
                    val alpha = if (phase == RadarPhase.SIGNAL) 0.55f else 0.85f
                    drawCircle(color = SignalDot.copy(alpha = alpha), radius = 7f, center = dot)
                }
            }

            if (phase == RadarPhase.HANDSHAKE && freshHandshake != null) {
                HandshakeCard(
                    profile = freshHandshake,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 120.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun HandshakeCard(profile: UserProfile, modifier: Modifier = Modifier) {
    val label = profile.displayLabel(profile.userId.take(8))
    val emoji = dinoEmojiFor(profile.userId)
    Box(
        modifier = modifier
            .background(Color(0xFF121212), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            "$emoji $label",
            style = Typography.titleMedium,
            color = TextPrimary
        )
    }
}

private fun dinoEmojiFor(id: String): String {
    val pool = listOf("🦕", "🦖", "🦎", "🥚", "🦴")
    val idx = (id.hashCode().and(0x7fffffff)) % pool.size
    return pool[idx]
}
