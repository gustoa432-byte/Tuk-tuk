package com.blink.dtn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blink.dtn.router.MessageRouter
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel

/**
 * Expedition / Delivery — gamification as courier work.
 * Emotion: "I helped the network." Cosmetics only.
 */
@Composable
fun ExpeditionTab(viewModel: BLinkViewModel) {
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val snap by GamificationStore.snap.collectAsState()
    val pending by viewModel.pendingCount.collectAsState(0)
    val peerCount by viewModel.peerCount.collectAsState()
    val shipment by MessageRouter.activeShipment.collectAsState()

    LaunchedEffect(Unit) { GamificationStore.init(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(S.expedition(lang), style = Typography.titleLarge, color = TextPrimary)
        Text(S.expeditionTagline(lang), style = Typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(S.helpedFeeling(lang), style = Typography.labelSmall, color = AccentLime)
        if (System.currentTimeMillis() - snap.lastHelpedAt < 60_000L && snap.lastHelpedAt > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (lang == "en") "You just helped the network — thank you."
                else "Ты только что помог сети — спасибо.",
                color = AccentLime,
                style = Typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 12.dp, strong = true)
                    .padding(12.dp)
            )
        }
        Text(
            CosmeticApply.dinoBadge(snap.dinoId),
            style = Typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpStat(S.packagesWaiting(lang), pending.toString(), Modifier.weight(1f))
            ExpStat(S.packagesDelivered(lang), snap.helped.toString(), Modifier.weight(1f))
            ExpStat(S.neighborsHelped(lang), peerCount.toString(), Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpStat(S.messagesReceived(lang), snap.received.toString(), Modifier.weight(1f))
            ExpStat(S.livesSaved(lang), snap.saved.toString(), Modifier.weight(1f))
            ExpStat(S.queueNow(lang), pending.toString(), Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(S.currentMission(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 14.dp)
                .padding(14.dp)
        ) {
            val ship = shipment
            if (ship == null && pending == 0) {
                Text(S.noMission(lang), color = TextSecondary, style = Typography.bodySmall)
            } else if (ship != null) {
                Column {
                    Text(S.packageInFlight(lang), color = TextPrimary, style = Typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(ship.statusLabelRu, color = AccentLime, style = Typography.labelSmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    MessageTrackerStrip(path = ship.path, statusRu = ship.statusLabelRu)
                }
            } else {
                Text(
                    S.packagesWaitingHint(lang, pending),
                    color = TextPrimary,
                    style = Typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(S.yourContribution(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 14.dp)
                .padding(14.dp)
        ) {
            Text(S.contributionBody(lang, snap.helped, snap.saved), color = TextPrimary, style = Typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(S.cosmeticsOnly(lang), color = TextSecondary, style = Typography.labelSmall)
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(S.cosmetics(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        GamificationStore.catalog.forEach { item ->
            val unlocked = item.id in snap.unlocked
            val selected = when (item.kind) {
                "theme" -> snap.themeId == item.id
                "frame" -> snap.frameId == item.id
                "nick" -> snap.nickColorId == item.id
                "dino" -> snap.dinoId == item.id
                else -> false
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .glassPanel(corner = 12.dp, strong = selected)
                    .then(
                        if (unlocked) Modifier.bounceClick {
                            GamificationStore.selectCosmetic(context, item.kind, item.id)
                        } else Modifier
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (unlocked) AccentLime else DividerColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (lang == "en") item.titleEn else item.titleRu,
                        color = if (unlocked) TextPrimary else TextSecondary,
                        style = Typography.bodyMedium
                    )
                    Text(
                        if (unlocked) S.cosmeticReady(lang) else S.cosmeticLocked(lang),
                        color = TextSecondary,
                        style = Typography.labelSmall
                    )
                }
                if (selected) {
                    Text(S.equipped(lang), color = AccentLime, style = Typography.labelSmall)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ExpStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .glassPanel(corner = 12.dp)
            .padding(12.dp)
    ) {
        Text(value, color = AccentLime, style = Typography.titleLarge)
        Text(label, color = TextSecondary, style = Typography.labelSmall)
    }
}
