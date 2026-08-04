package com.blink.dtn.ui.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.AppLang
import com.blink.dtn.ui.S
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography

private val OledBlack = Color(0xFF000000)
private val Panel = Color(0xFF101010)

/**
 * Tab 3 — completed routes + chain of custody + thank-you placeholder.
 */
@Composable
fun ChronicleTab(
    modifier: Modifier = Modifier,
    entries: List<ChronicleEntry> = HubMocks.chronicle
) {
    val lang by AppLang.lang.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var thanked by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(16.dp)
    ) {
        Text(S.hubChronicle(lang), style = Typography.titleMedium, color = TextPrimary)
        Text(
            S.hubChronicleHint(lang),
            style = Typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(entries, key = { it.id }) { entry ->
                val open = expandedId == entry.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(14.dp))
                        .clickable {
                            expandedId = if (open) null else entry.id
                        }
                        .padding(14.dp)
                ) {
                    Text(entry.title, style = Typography.bodyMedium, color = TextPrimary)
                    Text(
                        entry.deliveredAtLabel,
                        style = Typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    AnimatedVisibility(visible = open) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                S.hubChainOfCustody(lang),
                                style = Typography.labelSmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                formatHopChain(entry.hopHistory),
                                style = Typography.bodySmall,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            ThankChainButton(
                                enabled = entry.id !in thanked,
                                label = if (entry.id in thanked) {
                                    S.hubThanked(lang)
                                } else {
                                    S.hubThankChain(lang)
                                },
                                onClick = {
                                    if (entry.id !in thanked) {
                                        thanked = thanked + entry.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatHopChain(hops: List<String>): String =
    hops.joinToString(separator = " → ")

@Composable
private fun ThankChainButton(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (enabled) Color(0xFF1A1A1A) else Color(0xFF0A0A0A),
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(label, style = Typography.labelMedium, color = if (enabled) TextPrimary else TextSecondary)
    }
}
