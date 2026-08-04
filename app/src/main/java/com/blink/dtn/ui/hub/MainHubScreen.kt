package com.blink.dtn.ui.hub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.AppLang
import com.blink.dtn.ui.S
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import kotlinx.coroutines.launch

private val OledBlack = Color(0xFF000000)
private val PillIdle = Color(0xFF161616)
private val PillActive = Color(0xFF2A2A2A)

enum class HubPage {
    Radar,
    Courier,
    Chronicle
}

/**
 * Human Layer root: three product tabs on pure OLED black.
 * HorizontalPager for swipe + compact pill bar (light, no Material card chrome).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainHubScreen(
    modifier: Modifier = Modifier,
    initialPage: HubPage = HubPage.Radar
) {
    val lang by AppLang.lang.collectAsState()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialPage.ordinal,
        pageCount = { HubPage.entries.size }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (HubPage.entries[page]) {
                HubPage.Radar -> RadarTab()
                HubPage.Courier -> CourierTab()
                HubPage.Chronicle -> ChronicleTab()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OledBlack)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HubPage.entries.forEach { page ->
                val selected = pagerState.currentPage == page.ordinal
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) PillActive else PillIdle,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(page.ordinal) }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (page) {
                            HubPage.Radar -> S.hubRadar(lang)
                            HubPage.Courier -> S.hubCourier(lang)
                            HubPage.Chronicle -> S.hubChronicle(lang)
                        },
                        style = Typography.labelSmall,
                        color = if (selected) TextPrimary else TextSecondary
                    )
                }
            }
        }
    }
}
