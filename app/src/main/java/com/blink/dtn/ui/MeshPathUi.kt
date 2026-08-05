package com.blink.dtn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blink.dtn.router.RoutePath
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography

/** Human-readable route path (kept after NetworkTab removal). */
fun humanPathLabel(path: RoutePath, lang: String): String = when (path) {
    RoutePath.INTERNET -> S.pathInternet(lang)
    RoutePath.BLE -> S.pathPeople(lang)
}

@Composable
fun MessageTrackerStrip(
    path: RoutePath,
    statusRu: String,
    modifier: Modifier = Modifier
) {
    val lang by AppLang.lang.collectAsState()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TrackerNode(if (lang == "en") "You" else "Ты")
        TrackerLine()
        TrackerNode(
            when (path) {
                RoutePath.INTERNET -> S.pathInternetShort(lang)
                RoutePath.BLE -> S.pathPeopleShort(lang)
            },
            accent = true
        )
        TrackerLine()
        TrackerNode(if (lang == "en") "Friend" else "Друг")
    }
    Text(
        statusRu,
        color = TextSecondary,
        style = Typography.labelSmall,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun TrackerNode(label: String, accent: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (accent) AccentLime else TextPrimary, CircleShape)
        )
        Text(label, color = TextSecondary, style = Typography.labelSmall)
    }
}

@Composable
private fun TrackerLine() {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(2.dp)
            .background(DividerColor, androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
    )
}
