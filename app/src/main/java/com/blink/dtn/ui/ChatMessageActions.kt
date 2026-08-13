package com.blink.dtn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blink.dtn.db.Conversation
import com.blink.dtn.db.Message
import com.blink.dtn.db.UserProfile
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.DangerColor
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    msg: Message,
    isMine: Boolean,
    canCancel: Boolean,
    canBlockSender: Boolean,
    onDismiss: () -> Unit,
    onReply: (() -> Unit)?,
    onForward: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    onDeleteLocal: (() -> Unit)?,
    onCancelSend: (() -> Unit)?,
    onBlockUser: (() -> Unit)?,
    onReport: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null
) {
    val lang by AppLang.lang.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GlassDialogContainer,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextSecondary.copy(alpha = 0.45f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                S.msgActionTitle(lang),
                color = TextPrimary,
                style = Typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                msg.text,
                color = TextSecondary,
                style = Typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            if (onReact != null && !com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReactionStore.palette.forEach { emoji ->
                        Text(
                            emoji,
                            style = Typography.titleLarge,
                            modifier = Modifier.bounceClick {
                                onReact(emoji)
                                onDismiss()
                            }.padding(4.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StickerPack.stickers.take(6).forEach { sticker ->
                        Text(
                            sticker,
                            style = Typography.titleMedium,
                            modifier = Modifier.bounceClick {
                                onReact(sticker)
                                onDismiss()
                            }.padding(2.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = DividerColor.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            if (onReply != null) {
                SheetActionRow(S.reply(lang)) { onDismiss(); onReply() }
            }
            if (onCopy != null) {
                SheetActionRow(S.copy(lang)) { onDismiss(); onCopy() }
            }
            if (onForward != null) {
                SheetActionRow(S.forward(lang)) { onDismiss(); onForward() }
            }
            if (isMine && onEdit != null) {
                SheetActionRow(S.edit(lang)) { onDismiss(); onEdit() }
            }
            if (onSelect != null) {
                SheetActionRow(S.select(lang)) { onDismiss(); onSelect() }
            }
            if (canCancel && onCancelSend != null) {
                SheetActionRow(S.cancelSend(lang)) { onDismiss(); onCancelSend() }
            }
            if (canBlockSender && onReport != null) {
                SheetActionRow(S.reportMessage(lang), color = DangerColor) {
                    onDismiss(); onReport()
                }
            }
            if (canBlockSender && onBlockUser != null) {
                SheetActionRow(S.blockUser(lang), color = DangerColor) {
                    onDismiss(); onBlockUser()
                }
            }
            if (onDeleteLocal != null && !canBlockSender) {
                SheetActionRow(S.deleteLocal(lang), color = DangerColor) {
                    onDismiss(); onDeleteLocal()
                }
            }
            Text(
                when {
                    canCancel -> S.msgDeleteHintCanCancel(lang)
                    else -> S.msgDeleteHint(lang)
                },
                color = TextSecondary,
                style = Typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    label: String,
    color: Color = TextPrimary,
    onClick: () -> Unit
) {
    Text(
        label,
        color = color,
        style = Typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick)
            .padding(vertical = 12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerSheet(
    dialogs: List<Conversation>,
    excludeId: String?,
    profiles: Map<String, UserProfile?>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val targets = remember(dialogs, excludeId) {
        dialogs.filter {
            it.conversationId != "general" &&
                it.conversationId != excludeId &&
                !it.conversationId.startsWith("__")
        }
    }
    var selected by remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GlassDialogContainer,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextSecondary.copy(alpha = 0.45f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                S.forwardTo(lang),
                color = TextPrimary,
                style = Typography.titleMedium,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            if (targets.isEmpty()) {
                Text(
                    S.noChatsToForward(lang),
                    color = TextSecondary,
                    style = Typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(targets, key = { it.conversationId }) { conv ->
                        val id = conv.conversationId
                        val profile = profiles[id]
                        val title = peerListTitle(profile, conv.displayName, id)
                        val checked = id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(corner = 12.dp, strong = checked)
                                .bounceClick {
                                    selected = if (checked) selected - id else selected + id
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PeerAvatar(
                                avatarBlob = profile?.avatarBlob,
                                label = title,
                                size = 36.dp,
                                uid = id
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                title,
                                color = TextPrimary,
                                style = Typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(if (checked) AccentLime else Color.Transparent)
                                    .then(
                                        if (!checked) Modifier.background(
                                            TextSecondary.copy(alpha = 0.25f),
                                            CircleShape
                                        ) else Modifier
                                    )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        S.cancel(lang),
                        color = TextSecondary,
                        style = Typography.labelLarge,
                        modifier = Modifier
                            .bounceClick(onDismiss)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        S.forward(lang),
                        color = if (selected.isEmpty()) TextSecondary else AccentLime,
                        style = Typography.labelLarge,
                        modifier = Modifier
                            .bounceClick {
                                if (selected.isNotEmpty()) {
                                    onConfirm(selected.toList())
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
