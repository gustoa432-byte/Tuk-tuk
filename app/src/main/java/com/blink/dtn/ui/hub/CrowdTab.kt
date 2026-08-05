package com.blink.dtn.ui.hub

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blink.dtn.ble.CrowdFrame
import com.blink.dtn.ble.MeshDutyPreset
import com.blink.dtn.crowd.CrowdFeed
import com.blink.dtn.crowd.EventAnchorServer
import com.blink.dtn.crowd.EventRoomStore
import com.blink.dtn.ui.AppLang
import com.blink.dtn.ui.BLinkViewModel
import com.blink.dtn.ui.S
import com.blink.dtn.ui.TukTukButton
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel

/**
 * Crowd survival surface: short nearby feed, event room, duty Crowd, iPhone PWA anchor.
 */
@Composable
fun CrowdTab(viewModel: BLinkViewModel) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        EventRoomStore.init(context)
        com.blink.dtn.ble.MeshDutyPrefs.init(context)
    }
    val feed by CrowdFeed.feed.collectAsState()
    val room by EventRoomStore.room.collectAsState()
    val duty by com.blink.dtn.ble.MeshDutyPrefs.preset.collectAsState()
    val dense by com.blink.dtn.telemetry.MeshDutyTelemetry.snapshot.collectAsState()
    var draft by remember { mutableStateOf("") }
    var titleDraft by remember { mutableStateOf("") }
    var anchorOn by remember { mutableStateOf(EventAnchorServer.isRunning()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(16.dp)
    ) {
        Text(S.crowdTitle(lang), color = TextPrimary, style = Typography.titleLarge)
        Text(
            S.crowdSubtitle(lang),
            color = TextSecondary,
            style = Typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Text(
            S.crowdDensity(lang, dense.denseWindowPeers.toInt(), dense.scanPeersPeak.toInt()),
            color = TextSecondary,
            style = Typography.labelSmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TukTukButton(
                enabled = duty != MeshDutyPreset.CROWD,
                onClick = {
                    viewModel.setDutyPreset(MeshDutyPreset.CROWD)
                    Toast.makeText(context, S.crowdModeOn(lang), Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(S.crowdEnable(lang), color = TextPrimary)
            }
            TukTukButton(
                enabled = duty == MeshDutyPreset.CROWD,
                onClick = {
                    viewModel.setDutyPreset(MeshDutyPreset.NORMAL)
                    Toast.makeText(context, S.crowdModeOff(lang), Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(S.crowdDisable(lang), color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (room == null) {
            BasicTextField(
                value = titleDraft,
                onValueChange = { if (it.length <= 40) titleDraft = it },
                textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { inner ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glassPanel(corner = 12.dp)
                            .padding(12.dp)
                    ) {
                        if (titleDraft.isEmpty()) {
                            Text(S.crowdRoomHint(lang), color = TextSecondary)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TukTukButton(onClick = {
                EventRoomStore.create(context, titleDraft)
                Toast.makeText(context, S.crowdRoomCreated(lang), Toast.LENGTH_SHORT).show()
            }) {
                Text(S.crowdCreateRoom(lang), color = TextPrimary)
            }
        } else {
            Text(
                S.crowdRoomActive(lang, room!!.title, room!!.id),
                color = AccentLime,
                style = Typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            TukTukButton(onClick = {
                EventRoomStore.leave(context)
            }) {
                Text(S.crowdLeaveRoom(lang), color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TukTukButton(onClick = {
            if (anchorOn) {
                EventAnchorServer.stop()
                anchorOn = false
                Toast.makeText(context, S.crowdAnchorOff(lang), Toast.LENGTH_SHORT).show()
            } else {
                EventAnchorServer.start(context)
                anchorOn = true
                Toast.makeText(
                    context,
                    S.crowdAnchorOn(lang, EventAnchorServer.lastBindHint),
                    Toast.LENGTH_LONG
                ).show()
            }
        }) {
            Text(
                if (anchorOn) S.crowdAnchorStop(lang) else S.crowdAnchorStart(lang),
                color = TextPrimary
            )
        }
        if (anchorOn) {
            Text(
                S.crowdAnchorHint(lang, EventAnchorServer.lastBindHint),
                color = TextSecondary,
                style = Typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TukTukButton(onClick = {
                viewModel.sendCrowd(CrowdFrame.KIND_SOS, draft.ifBlank { "SOS" })
                draft = ""
            }) {
                Text("SOS", color = TextPrimary)
            }
            TukTukButton(onClick = {
                viewModel.sendCrowd(CrowdFrame.KIND_PRESENCE, "ping")
            }) {
                Text(S.crowdPresence(lang), color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        BasicTextField(
            value = draft,
            onValueChange = { if (it.length <= CrowdFrame.MAX_TEXT) draft = it },
            textStyle = Typography.bodyMedium.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            decorationBox = { inner ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassPanel(corner = 12.dp)
                        .padding(12.dp)
                ) {
                    if (draft.isEmpty()) Text(S.crowdComposeHint(lang), color = TextSecondary)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        TukTukButton(
            enabled = draft.isNotBlank(),
            onClick = {
                viewModel.sendCrowd(CrowdFrame.KIND_PUBLIC, draft)
                draft = ""
            }
        ) {
            Text(S.crowdSend(lang), color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(feed, key = { it.id }) { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (item.kind == CrowdFrame.KIND_SOS) Color(0xFF2A1515) else Color(0xFF14161A),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(item.text, color = TextPrimary, style = Typography.bodyMedium)
                }
            }
        }
    }
}
