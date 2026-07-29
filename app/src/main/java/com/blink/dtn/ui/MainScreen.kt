@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.blink.dtn.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.blink.dtn.db.Message
import com.blink.dtn.db.UserProfile
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.AppRootBackdrop
import com.blink.dtn.ui.theme.BackgroundDark
import com.blink.dtn.ui.theme.DangerColor
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.GlassDialogContainer
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassBubble
import com.blink.dtn.ui.theme.glassPanel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 1. Custom bounceClick modifier
fun Modifier.bounceClick(onClick: () -> Unit) = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "bounce"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        isPressed = false
                    }
                },
                onTap = { onClick() }
            )
        }
}

// 2. Custom Back Icon (Canvas)
@Composable
fun CustomBackIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.65f, size.height * 0.2f)
            lineTo(size.width * 0.35f, size.height * 0.5f)
            lineTo(size.width * 0.65f, size.height * 0.8f)
        }
        drawPath(
            path = path,
            color = TextPrimary,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BLinkViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var showDevPanel by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }
    val peerCount by viewModel.peerCount.collectAsState()
    val vkActive by viewModel.vkActive.collectAsState()
    val nearbyUpdate by viewModel.nearbyUpdate.collectAsState()
    val isConnected = vkActive || peerCount > 0
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    LaunchedEffect(Unit) {
        AppLang.init(context)
        AppWallpaper.init(context)
    }

    if (showDevPanel) {
        DeliveryObservatoryPanel(viewModel = viewModel, onClose = { showDevPanel = false })
        return
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(S.infoTitle(lang), color = TextPrimary) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    InfoContent(compact = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(S.close(lang), color = TextPrimary)
                }
            },
            containerColor = GlassDialogContainer
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppRootBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        val now = System.currentTimeMillis()
                                        if (now - lastClickTime < 500) {
                                            clickCount++
                                            if (clickCount >= 4) {
                                                showDevPanel = true
                                                clickCount = 0
                                            }
                                        } else {
                                            clickCount = 1
                                        }
                                        lastClickTime = now
                                    }
                                )
                            }
                        ) {
                            Text("Tuk-Tuk", style = Typography.titleLarge, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        color = if (isConnected) AccentLime else DividerColor,
                                        shape = CircleShape
                                    )
                            )
                        }
                    },
                    actions = {
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(40.dp)
                                .glassPanel(corner = 20.dp)
                                .bounceClick { showInfo = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Информация", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(40.dp)
                                .glassPanel(corner = 20.dp)
                                .bounceClick { shareApk(context) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextPrimary,
                        actionIconContentColor = TextPrimary
                    )
                )
            },
            bottomBar = {
                CustomBottomBar(selectedTab) { selectedTab = it }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                nearbyUpdate?.let { offer ->
                    NearbyUpdateBanner(
                        versionName = offer.versionName,
                        peerNick = offer.peerNick,
                        onRequest = { viewModel.requestNearbyApkUpdate(offer.peerId) },
                        onDismiss = { viewModel.dismissNearbyUpdate() }
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        0 -> PrivateTab(viewModel)
                        1 -> PublicTab(viewModel) { contactId ->
                            viewModel.ensureContact(contactId)
                            viewModel.setCurrentDialog(contactId)
                            selectedTab = 0
                        }
                        2 -> ProfileTab(viewModel, { selectedTab = 0 })
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyUpdateBanner(
    versionName: String,
    peerNick: String,
    onRequest: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .glassPanel(corner = 12.dp, strong = true)
            .padding(12.dp)
    ) {
        val lang by AppLang.lang.collectAsState()
        Text(
            S.updateAvailable(lang, versionName),
            color = TextPrimary,
            style = Typography.labelLarge
        )
        Text(
            S.updatePeer(lang, peerNick),
            color = TextSecondary,
            style = Typography.labelSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                S.get(lang),
                color = TextPrimary,
                style = Typography.labelMedium,
                modifier = Modifier.bounceClick { onRequest() }
            )
            Text(
                S.hide(lang),
                color = TextSecondary,
                style = Typography.labelMedium,
                modifier = Modifier.bounceClick { onDismiss() }
            )
        }
    }
}

@Composable
fun CustomBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val lang by AppLang.lang.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 24.dp, strong = true)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(Icons.Default.Person, S.dialogs(lang), selectedTab == 0) { onTabSelected(0) }
            BottomBarItem(Icons.Default.Email, S.groupChat(lang), selectedTab == 1) { onTabSelected(1) }
            BottomBarItem(Icons.Default.Person, S.profile(lang), selectedTab == 2) { onTabSelected(2) }
        }
    }
}

@Composable
fun BottomBarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val contentColor = if (isSelected) BackgroundDark else TextSecondary
    val bgColor = if (isSelected) AccentLime else Color.Transparent

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .bounceClick { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(20.dp))
            if (isSelected) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, color = contentColor, style = Typography.labelMedium)
            }
        }
    }
}

@Composable
fun TukTukButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Box(
        modifier = Modifier
            .glassPanel(corner = 16.dp)
            .bounceClick(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun PrivateTab(viewModel: BLinkViewModel) {
    val currentDialogId by viewModel.currentDialogId.collectAsState()
    
    if (currentDialogId == null) {
        ChatListScreen(viewModel)
    } else {
        ConversationScreen(
            viewModel = viewModel,
            contactId = currentDialogId!!,
            onBack = { viewModel.setCurrentDialog(null) }
        )
    }
}

@Composable
fun ChatListScreen(viewModel: BLinkViewModel) {
    val lang by AppLang.lang.collectAsState()
    val dialogs by viewModel.dialogs.collectAsState()
    val privateDialogs = dialogs.filter { it.conversationId != "general" }
    var showSearch by remember { mutableStateOf(false) }
    var searchId by remember { mutableStateOf("") }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Search / New Chat Capsule
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp)
                    .glassPanel(corner = 24.dp)
                    .bounceClick { showSearch = true }
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = S.findOrStartDialog(lang),
                    color = TextSecondary,
                    style = Typography.bodyLarge
                )
            }
            

            if (privateDialogs.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(S.noDialogs(lang), color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(privateDialogs) { dialog ->
                        val profile by viewModel.getProfileFlow(dialog.conversationId).collectAsState(initial = null)
                        val formatter = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
                        val timeString = if (dialog.lastTimestamp > 0) formatter.format(java.util.Date(dialog.lastTimestamp)) else ""
                        val title = peerListTitle(profile, dialog.displayName)
                        val trustBadge = profile?.trustBadge(lang)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .glassPanel(corner = 16.dp)
                                .bounceClick { viewModel.setCurrentDialog(dialog.conversationId) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            PeerAvatar(
                                avatarBlob = profile?.avatarBlob,
                                label = title,
                                size = 44.dp,
                                uid = dialog.conversationId
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = title,
                                        color = TextPrimary,
                                        style = Typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (trustBadge != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = trustBadge,
                                            color = TextSecondary,
                                            style = Typography.labelSmall,
                                            modifier = Modifier
                                                .background(DividerColor, RoundedCornerShape(8.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "id ${profile?.shortId() ?: dialog.conversationId.take(8)}",
                                    color = TextSecondary,
                                    style = Typography.labelSmall,
                                    maxLines = 1
                                )
                                if (!dialog.lastMessage.isNullOrEmpty()) {
                                    Text(
                                        text = dialog.lastMessage,
                                        color = TextSecondary,
                                        style = Typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (timeString.isNotEmpty()) {
                                    Text(text = timeString, color = TextSecondary, style = Typography.labelSmall)
                                }
                                if (dialog.unreadCount > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(TextPrimary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = dialog.unreadCount.toString(), color = BackgroundDark, style = Typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
        
        // 3. Custom Search Overlay
        if (showSearch) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundDark.copy(alpha = 0.72f))
                    .pointerInput(Unit) {
                        detectTapGestures { showSearch = false }
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .glassPanel(corner = 24.dp, strong = true)
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = searchId,
                        onValueChange = { searchId = it },
                        textStyle = Typography.bodyLarge.copy(color = TextPrimary),
                        cursorBrush = SolidColor(TextPrimary),
                        decorationBox = { innerTextField ->
                            if (searchId.isEmpty()) {
                                Text(S.enterPeerId(lang), color = TextSecondary, style = Typography.bodyLarge)
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = S.cancel(lang),
                            color = TextSecondary,
                            modifier = Modifier
                                .bounceClick { showSearch = false }
                                .padding(8.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (lang == "en") "Start" else "Начать",
                            color = TextPrimary,
                            modifier = Modifier
                                .bounceClick {
                                    if (searchId.isNotBlank()) {
                                        viewModel.ensureContact(searchId.trim())
                                        viewModel.setCurrentDialog(searchId.trim())
                                        showSearch = false
                                        searchId = ""
                                    }
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationScreen(viewModel: BLinkViewModel, contactId: String, onBack: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    val messages by viewModel.currentDialogMessages.collectAsState()
    val dialogs by viewModel.dialogs.collectAsState()
    val profile by viewModel.getProfileFlow(contactId).collectAsState(initial = null)
    val fallbackName = dialogs.find { it.conversationId == contactId }?.displayName
    val displayName = peerListTitle(profile, fallbackName)
    val isStranger = profile?.isStranger == true
    val isContact = profile == null || profile?.isContact == true
    val needsQrVerify = profile?.isVerified != true
    var messageText by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showPeerProfile by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val verifyScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && scanned.isNotBlank()) {
            val json = try { org.json.JSONObject(scanned) } catch (e: Exception) { null }
            val pk = json?.optString("pk", "")
            if (json != null && !pk.isNullOrEmpty()) {
                val derivedId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pk)
                val claimedId = json.optString("id", "")
                if (derivedId.isEmpty() || (claimedId.isNotEmpty() && claimedId != derivedId)) {
                    Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
                } else if (derivedId != contactId) {
                    Toast.makeText(context, if (lang == "en") "QR is for a different person" else "QR другого человека — не этого диалога", Toast.LENGTH_LONG).show()
                } else {
                    val nick = json.optString("n", "")
                    val avatar = decodeContactQrAvatar(json)
                    viewModel.addScannedContact(derivedId, nick, pk, avatar)
                    Toast.makeText(context, S.qrVerified(lang), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, S.qrNotContact(lang), Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showPeerProfile) {
        PeerProfileSheet(
            contactId = contactId,
            displayName = displayName,
            profile = profile,
            onDismiss = { showPeerProfile = false },
            onRename = {
                showPeerProfile = false
                renameDraft = profile?.localAlias?.ifBlank { displayName } ?: displayName
                showRename = true
            },
            onAccept = {
                viewModel.acceptContact(contactId)
                Toast.makeText(context, S.contactAccepted(lang), Toast.LENGTH_SHORT).show()
            },
            onCopyId = {
                clipboardManager.setText(AnnotatedString(contactId))
                Toast.makeText(context, "ID скопирован", Toast.LENGTH_SHORT).show()
            }
        )
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(S.renameDlgTitle(lang), color = TextPrimary) },
            text = {
                Column {
                    Text(
                        S.renameTip(lang),
                        color = TextSecondary,
                        style = Typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BasicTextField(
                        value = renameDraft,
                        onValueChange = { if (it.length <= 32) renameDraft = it },
                        textStyle = Typography.bodyLarge.copy(color = TextPrimary),
                        cursorBrush = SolidColor(TextPrimary),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassPanel(corner = 12.dp)
                                    .padding(12.dp)
                            ) {
                                if (renameDraft.isEmpty()) {
                                    Text(S.renameHint(lang), color = TextSecondary, style = Typography.bodyLarge)
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setLocalAlias(contactId, renameDraft)
                    showRename = false
                    Toast.makeText(context, S.nameSaved(lang), Toast.LENGTH_SHORT).show()
                }) {
                    Text(S.save(lang), color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(S.cancel(lang), color = TextSecondary)
                }
            },
            containerColor = GlassDialogContainer
        )
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .bounceClick { onBack() }
                    .padding(8.dp)
            ) {
                CustomBackIcon()
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .bounceClick { showPeerProfile = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PeerAvatar(
                    avatarBlob = profile?.avatarBlob,
                    label = displayName,
                    size = 40.dp,
                    uid = contactId
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = Typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = profile?.trustBadge(lang) ?: S.fromNetwork(lang),
                            style = Typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier
                                .background(DividerColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "id ${(profile?.shortId() ?: contactId.take(8))}",
                            style = Typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            Box {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Меню",
                    tint = TextPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .bounceClick { menuExpanded = true }
                        .padding(8.dp)
                        .size(22.dp)
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (isContact || profile?.localAlias?.isNotBlank() == true) {
                        DropdownMenuItem(
                            text = { Text(S.rename(lang), color = TextPrimary) },
                            onClick = {
                                menuExpanded = false
                                renameDraft = profile?.localAlias.orEmpty()
                                showRename = true
                            }
                        )
                    }
                    if (isStranger) {
                        DropdownMenuItem(
                            text = { Text(S.addToContacts(lang), color = TextPrimary) },
                            onClick = {
                                menuExpanded = false
                                viewModel.acceptContact(contactId)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(S.ignore(lang), color = DangerColor) },
                            onClick = {
                                menuExpanded = false
                                viewModel.ignorePeer(contactId, profile?.nickname.orEmpty())
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(S.ignore(lang), color = DangerColor) },
                            onClick = {
                                menuExpanded = false
                                viewModel.ignorePeer(contactId, profile?.nickname.orEmpty())
                            }
                        )
                    }
                }
            }
        }

        if (isStranger) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 16.dp, strong = true)
                    .padding(12.dp)
            ) {
                Text(
                    S.strangerBanner(lang),
                    color = TextSecondary,
                    style = Typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TukTukButton(onClick = { viewModel.acceptContact(contactId) }) {
                        Text(S.accept(lang), color = TextPrimary, style = Typography.labelMedium)
                    }
                    TukTukButton(onClick = {
                        viewModel.ignorePeer(contactId, profile?.nickname.orEmpty())
                    }) {
                        Text(S.ignore(lang), color = DangerColor, style = Typography.labelMedium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else if (needsQrVerify) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 16.dp, strong = true)
                    .padding(12.dp)
            ) {
                Text(
                    S.verifyQrHint(lang),
                    color = TextSecondary,
                    style = Typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                TukTukButton(onClick = {
                    val options = ScanOptions()
                    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    options.setCameraId(0)
                    options.setBeepEnabled(false)
                    options.setBarcodeImageEnabled(true)
                    options.setCaptureActivity(CustomScannerActivity::class.java)
                    verifyScanLauncher.launch(options)
                }) {
                    Text(S.verifyQr(lang), color = TextPrimary, style = Typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(S.noMessages(lang), color = TextSecondary, style = Typography.bodyMedium)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                reverseLayout = false
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        myNodeId = viewModel.myNodeId,
                        onDeleteLocal = { viewModel.deleteMessageLocally(msg.id) },
                        onCancelSend = { viewModel.cancelOutgoingMessage(msg.id) },
                        onRetrySend = { viewModel.retryOutgoingMessage(msg.id) }
                    )
                }
            }
        }

        
        ChatInputArea(
            text = messageText,
            onTextChange = { if (it.length <= 140) messageText = it },
            onSend = {
                if (messageText.isNotBlank()) {
                    viewModel.sendPrivateMessage(messageText, contactId)
                    messageText = ""
                }
            }
        )
    }
}


@Composable
fun PublicTab(viewModel: BLinkViewModel, onPrivateChatRequested: (String) -> Unit) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val selectedRoom by viewModel.selectedRoom.collectAsState()
    val messages by viewModel.publicMessages.collectAsState()
    val roomMeta = remember(selectedRoom) {
        com.blink.dtn.ble.MeshRoom.ALL.firstOrNull { it.id == com.blink.dtn.ble.MeshRoom.normalise(selectedRoom) }
    }
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Room selector — horizontal scroll strip
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(com.blink.dtn.ble.MeshRoom.ALL) { meta ->
                val isSelected = selectedRoom == meta.id
                Box(
                    modifier = Modifier
                        .glassPanel(corner = 16.dp, strong = isSelected)
                        .background(
                            if (isSelected) com.blink.dtn.ui.theme.AccentLime.copy(alpha = 0.22f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .bounceClick { viewModel.selectRoom(meta.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (lang == "en") meta.titleEn else meta.titleRu,
                        color = if (isSelected) com.blink.dtn.ui.theme.AccentLime else TextSecondary,
                        style = Typography.labelSmall
                    )
                }
            }
        }
        Text(
            if (lang == "en") roomMeta?.subtitleEn ?: "" else roomMeta?.subtitleRu ?: "",
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(S.publicChatEmpty(lang), color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                reverseLayout = false
            ) {
                items(messages, key = { it.id }) { msg ->
                    val senderProfile by viewModel.getProfileFlow(msg.senderId).collectAsState(initial = null)
                    MessageBubble(
                        msg = msg,
                        myNodeId = viewModel.myNodeId,
                        showSender = true,
                        onSenderClick = onPrivateChatRequested,
                        onDeleteLocal = { viewModel.deleteMessageLocally(msg.id) },
                        onCancelSend = { viewModel.cancelOutgoingMessage(msg.id) },
                        onRetrySend = { viewModel.retryOutgoingMessage(msg.id) },
                        onBlockUser = {
                            viewModel.blockUser(msg.senderId, msg.senderNick)
                            Toast.makeText(context, S.userBlocked(lang), Toast.LENGTH_SHORT).show()
                        },
                        senderAvatarBlob = senderProfile?.avatarBlob
                    )
                }
            }
        }


        ChatInputArea(
            text = messageText,
            onTextChange = { if (it.length <= 140) messageText = it },
            onSend = {
                if (messageText.isNotBlank()) {
                    viewModel.sendPublicMessage(messageText, selectedRoom)
                    messageText = ""
                }
            }
        )
    }
}

// 4. ChatInputArea with custom BasicTextField and send button
@Composable
fun ChatInputArea(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = Typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(corner = 24.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(S.message(lang), color = TextSecondary, style = Typography.bodyLarge)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .glassPanel(corner = 22.dp)
                .bounceClick { onSend() }
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = S.send(lang), tint = TextPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun MessageBubble(
    msg: Message,
    myNodeId: String,
    showSender: Boolean = false,
    onSenderClick: ((String) -> Unit)? = null,
    onDeleteLocal: (() -> Unit)? = null,
    onCancelSend: (() -> Unit)? = null,
    onRetrySend: (() -> Unit)? = null,
    onBlockUser: (() -> Unit)? = null,
    senderAvatarBlob: ByteArray? = null
) {
    val lang by AppLang.lang.collectAsState()
    val isMine = msg.senderId == myNodeId || msg.isMine
    val canBlockSender = !isMine && msg.type == "PUBLIC" && onBlockUser != null
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = formatter.format(Date(msg.timestamp))
    var showActions by remember { mutableStateOf(false) }
    var showVoyage by remember { mutableStateOf(false) }
    val canCancel = isMine && (
        msg.status == Message.STATUS_PENDING ||
            msg.status == Message.STATUS_IN_FLIGHT ||
            msg.status == Message.STATUS_PENDING_KEY ||
            msg.status == Message.STATUS_FAILED
        )

    if (showVoyage) {
        MessageVoyageDialog(
            msg = msg,
            onDismiss = { showVoyage = false },
            onRetry = if (msg.status == Message.STATUS_FAILED) onRetrySend else null
        )
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text(S.msgActionTitle(lang), color = TextPrimary) },
            text = {
                Text(
                    when {
                        canBlockSender -> S.blockUser(lang)
                        canCancel -> S.msgDeleteHintCanCancel(lang)
                        else -> S.msgDeleteHint(lang)
                    },
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showActions = false
                    if (canBlockSender) onBlockUser?.invoke() else onDeleteLocal?.invoke()
                }) {
                    Text(
                        if (canBlockSender) S.blockUser(lang) else S.deleteLocal(lang),
                        color = DangerColor
                    )
                }
            },
            dismissButton = {
                Row {
                    if (canCancel && !canBlockSender) {
                        TextButton(onClick = {
                            showActions = false
                            onCancelSend?.invoke()
                        }) {
                            Text(S.cancelSend(lang), color = TextPrimary)
                        }
                    }
                    TextButton(onClick = { showActions = false }) {
                        Text(S.close(lang), color = TextSecondary)
                    }
                }
            },
            containerColor = GlassDialogContainer
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(msg.id) {
                detectTapGestures(
                    onLongPress = { showActions = true },
                    onTap = {
                        if (isMine) showVoyage = true
                    }
                )
            },
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        if (showSender && !isMine) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 4.dp, start = 2.dp)
                    .bounceClick { onSenderClick?.invoke(msg.senderId) }
            ) {
                PeerAvatar(
                    avatarBlob = senderAvatarBlob,
                    label = msg.senderNick.ifEmpty { S.anonymous(lang) },
                    size = 22.dp,
                    uid = msg.senderId
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = msg.senderNick.ifEmpty { S.anonymous(lang) },
                    style = Typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
        Box(
            modifier = Modifier
                .glassBubble(isMine = isMine)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                EmojiText(
                    text = msg.text,
                    style = Typography.bodyLarge,
                    color = TextPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp).align(Alignment.End)
                ) {
                    Text(text = timeString, style = Typography.labelSmall, color = TextSecondary)
                    if (isMine) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val statusColor = DeliveryVoyageLabels.color(msg)
                        Box(modifier = Modifier.size(6.dp).background(statusColor, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = DeliveryVoyageLabels.label(msg),
                            style = Typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }
}



fun shareApk(context: Context) {
    try {
        val appInfo = context.applicationInfo
        val srcFile = File(appInfo.sourceDir)
        val destFile = File(context.externalCacheDir, "TukTuk_setup.apk")
        srcFile.copyTo(destFile, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, S.shareTukTuk(AppLang.lang.value)))
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ProfileTab(viewModel: BLinkViewModel, onScanSuccess: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    // Use TextFieldValue to preserve cursor position correctly
    var nickFieldValue by remember {
        mutableStateOf(TextFieldValue(viewModel.myNick))
    }
    var savedNick by remember { mutableStateOf(viewModel.myNick) }
    val nickDirty = nickFieldValue.text.trim() != savedNick.trim()
    var showSavedFlash by remember { mutableStateOf(false) }
    val myProfile by viewModel.getProfileFlow(viewModel.myNodeId).collectAsState(initial = null)
    var contactQr by remember { mutableStateOf(viewModel.myContactQr) }

    LaunchedEffect(showSavedFlash) {
        if (showSavedFlash) {
            delay(3000)
            showSavedFlash = false
        }
    }

    LaunchedEffect(myProfile?.avatarBlob?.size, myProfile?.nickname, viewModel.myNick) {
        contactQr = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.buildContactQr()
        }
    }

    val openAvatarPicker = rememberAvatarPicker(
        onCompressed = { bytes ->
            viewModel.setAvatarBlob(viewModel.myNodeId, bytes) { ok ->
                if (ok) {
                    val qrOk = AvatarCompressor.fitForQr(bytes) != null
                    Toast.makeText(
                        context,
                        if (qrOk) S.avatarSaved(lang) else S.avatarTooBig(lang),
                        if (qrOk) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(context, S.avatarSaveError(lang), Toast.LENGTH_SHORT).show()
                }
            }
        }
    )
    
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && scanned.isNotBlank()) {
            val json = try { org.json.JSONObject(scanned) } catch (e: Exception) { null }
            val pk = json?.optString("pk", "")
            if (json != null && !pk.isNullOrEmpty()) {
                val derivedId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pk)
                val claimedId = json.optString("id", "")
                if (derivedId.isEmpty() || (claimedId.isNotEmpty() && claimedId != derivedId)) {
                    Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
                } else {
                    val nick = json.optString("n", "")
                    val avatar = decodeContactQrAvatar(json)
                    viewModel.addScannedContact(derivedId, nick, pk, avatar)
                    onScanSuccess()
                }
            } else {
                viewModel.ensureContact(scanned)
                viewModel.setCurrentDialog(scanned)
                onScanSuccess()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Fix #3: horizontal row — avatar left, nickname+ID right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar: 72dp, tappable
            PeerAvatar(
                avatarBlob = myProfile?.avatarBlob,
                label = nickFieldValue.text.ifBlank { viewModel.myNodeId.take(4) },
                size = 72.dp,
                uid = viewModel.myNodeId,
                modifier = Modifier.bounceClick { openAvatarPicker() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            // Right column: nickname field + short ID
            Column(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = nickFieldValue,
                    onValueChange = { newVal ->
                        if (newVal.text.length <= 20) {
                            nickFieldValue = newVal
                            showSavedFlash = false
                        }
                    },
                    textStyle = Typography.titleMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(TextPrimary),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (nickFieldValue.text.isEmpty()) {
                                Text(S.enterName(lang), color = TextSecondary, style = Typography.titleMedium)
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Full ID — tap to copy
                Text(
                    text = viewModel.myNodeId,
                    color = TextSecondary,
                    style = Typography.labelSmall,
                    maxLines = 2,
                    modifier = Modifier
                        .bounceClick {
                            clipboardManager.setText(AnnotatedString(viewModel.myNodeId))
                            Toast.makeText(context, S.idCopied(lang), Toast.LENGTH_SHORT).show()
                        }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        // Checkmark save button — visible when editing or briefly after save
        if (nickDirty || showSavedFlash) {
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .bounceClick {
                        if (!nickDirty) return@bounceClick
                        val trimmed = nickFieldValue.text.trim()
                        if (trimmed.isEmpty()) {
                            Toast.makeText(context, S.enterNameHint(lang), Toast.LENGTH_SHORT).show()
                            return@bounceClick
                        }
                        nickFieldValue = TextFieldValue(
                            text = trimmed,
                            selection = TextRange(trimmed.length)
                        )
                        viewModel.updateMyProfile(trimmed, false)
                        savedNick = trimmed
                        showSavedFlash = true
                    }
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Сохранить",
                    tint = if (nickDirty) AccentLime else TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        // QR encodes contact payload (id + public key + nick + optional av)
        val qrBitmap = remember(contactQr) {
            generateQrCode(contactQr, 512)?.asImageBitmap()
        }
        
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    bitmap = qrBitmap,
                    contentDescription = "QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(S.scanContact(lang), style = Typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        TukTukButton(onClick = { 
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setCameraId(0)
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            options.setCaptureActivity(CustomScannerActivity::class.java)
            scanLauncher.launch(options)
        }) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(S.scanQr(lang), color = TextPrimary, style = Typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(S.networkMode(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        val dutyPreset by com.blink.dtn.ble.MeshDutyPrefs.preset.collectAsState()
        LaunchedEffect(Unit) {
            com.blink.dtn.ble.MeshDutyPrefs.init(context)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            com.blink.dtn.ble.MeshDutyPreset.entries.forEach { p ->
                val selected = dutyPreset == p
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .glassPanel(corner = 12.dp, strong = selected)
                        .background(if (selected) AccentLime.copy(alpha = 0.22f) else Color.Transparent)
                        .bounceClick {
                            viewModel.setDutyPreset(p)
                            Toast.makeText(context, S.modeSet(lang, if (lang == "en") p.labelEn else p.labelRu), Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (lang == "en") p.labelEn else p.labelRu,
                        color = if (selected) AccentLime else TextSecondary,
                        style = Typography.labelSmall
                    )
                }
            }
        }
        Text(
            when (dutyPreset) {
                com.blink.dtn.ble.MeshDutyPreset.ECONOMY -> S.modeEconomy(lang)
                com.blink.dtn.ble.MeshDutyPreset.MAX -> S.modeMax(lang)
                else -> S.modeBalance(lang)
            },
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))
        TukTukButton(onClick = { showSettings = true }) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(S.settings(lang), color = TextPrimary, style = Typography.titleMedium)
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val revision by AppWallpaper.revision.collectAsState()
    val opacity by AppWallpaper.opacity.collectAsState()
    val scope = rememberCoroutineScope()
    val preview = remember(revision) { AppWallpaper.loadBitmap(context) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AppWallpaper.setFromUri(context, uri) }
            saving = false
            Toast.makeText(
                context,
                if (ok) S.wallpaperSaved(lang) else S.wallpaperError(lang),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            picker.launch("image/*")
        } else {
            Toast.makeText(context, S.galleryDenied(lang), Toast.LENGTH_SHORT).show()
        }
    }

    fun openGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            picker.launch("image/*")
        } else {
            permissionLauncher.launch(mediaPermission)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                CustomBackIcon()
            }
            Text(
                S.settings(lang),
                color = TextPrimary,
                style = Typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(S.langLabel(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Русский",
                color = if (lang == "ru") AccentLime else TextSecondary,
                style = Typography.bodyMedium,
                modifier = Modifier.bounceClick { AppLang.set(context, "ru") }.padding(12.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "English",
                color = if (lang == "en") AccentLime else TextSecondary,
                style = Typography.bodyMedium,
                modifier = Modifier.bounceClick { AppLang.set(context, "en") }.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(S.wallpaper(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(S.wallpaperHint(lang), style = Typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = opacity.coerceIn(0f, 1f) }
                )
            } else {
                Text(S.wallpaperNone(lang), color = TextSecondary, style = Typography.bodySmall)
            }
        }

        if (preview != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(S.wallpaperOpacity(lang), style = Typography.labelSmall, color = TextSecondary)
                Text(
                    "${(opacity * 100).toInt()}%",
                    style = Typography.labelSmall,
                    color = AccentLime
                )
            }
            Slider(
                value = opacity,
                onValueChange = { AppWallpaper.setOpacity(context, it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentLime,
                    activeTrackColor = AccentLime,
                    inactiveTrackColor = DividerColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        TukTukButton(onClick = { if (!saving) openGallery() }) {
            Text(
                if (saving) "…" else S.chooseWallpaper(lang),
                color = TextPrimary,
                style = Typography.titleMedium
            )
        }
        if (preview != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TukTukButton(onClick = {
                AppWallpaper.clear(context)
                Toast.makeText(context, S.wallpaperReset(lang), Toast.LENGTH_SHORT).show()
            }) {
                Text(S.resetWallpaper(lang), color = TextPrimary, style = Typography.titleMedium)
            }
        }
    }
}

fun generateQrCode(text: String, size: Int = 512): android.graphics.Bitmap? {
    return try {
        val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text,
            com.google.zxing.BarcodeFormat.QR_CODE,
            size,
            size
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private const val AUTHOR_TELEGRAM = "b6dmachine"
private const val OFFICIAL_CHANNEL = "tuk_tuk_official"
private const val FEEDBACK_EMAIL = "tuktukfb@internet.ru"

private fun openTelegramLink(context: Context, pathOrUsername: String) {
    val web = if (pathOrUsername.startsWith("http")) pathOrUsername else "https://t.me/$pathOrUsername"
    val domain = pathOrUsername.removePrefix("https://t.me/").removePrefix("http://t.me/")
    val appIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=$domain"))
    val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web))
    try {
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {
                Toast.makeText(context, S.telegramError(AppLang.lang.value), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openAuthorTelegram(context: Context) {
    openTelegramLink(context, AUTHOR_TELEGRAM)
}

private fun openOfficialChannel(context: Context) {
    openTelegramLink(context, OFFICIAL_CHANNEL)
}

private fun peerListTitle(profile: com.blink.dtn.db.UserProfile?, fallback: String?): String {
    if (profile != null) {
        val alias = profile.localAlias.trim()
        if (alias.isNotEmpty()) return alias
        val nick = profile.nickname.trim()
        if (profile.isStranger) {
            return nick.ifEmpty { S.stranger(AppLang.lang.value) }
        }
        if (nick.isNotEmpty()) return nick
        return profile.userId
    }
    return fallback?.takeIf { it.isNotBlank() } ?: S.unknownContact(AppLang.lang.value)
}

@Composable
private fun PeerProfileSheet(
    contactId: String,
    displayName: String,
    profile: UserProfile?,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onAccept: () -> Unit,
    onCopyId: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.profileDlgTitle(lang), color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PeerAvatar(
                    avatarBlob = profile?.avatarBlob,
                    label = displayName,
                    size = 96.dp,
                    uid = contactId
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    displayName,
                    color = TextPrimary,
                    style = Typography.titleMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    profile?.trustBadge(lang) ?: S.fromNetwork(lang),
                    color = TextSecondary,
                    style = Typography.labelSmall,
                    modifier = Modifier
                        .background(DividerColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                if (profile?.isVerified == true) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(S.qrAlreadyVerified(lang), color = TextSecondary, style = Typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .glassPanel(corner = 12.dp)
                        .bounceClick { onCopyId() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = contactId,
                        color = TextSecondary,
                        style = Typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(S.copy(lang), color = TextPrimary, style = Typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (profile?.isStranger == true) {
                    TukTukButton(onClick = {
                        onAccept()
                        onDismiss()
                    }) {
                        Text(S.addToContacts(lang), color = TextPrimary, style = Typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TukTukButton(onClick = onRename) {
                    Text(S.rename(lang), color = TextPrimary, style = Typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(S.close(lang), color = TextPrimary)
            }
        },
        containerColor = GlassDialogContainer
    )
}

@Composable
fun InfoContent(compact: Boolean = false) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val bodyStyle = Typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val quietStyle = Typography.bodySmall.copy(lineHeight = 18.sp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .then(if (compact) Modifier else Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp))
    ) {
        if (!compact) {
            Text(S.infoTitle(lang), style = Typography.titleLarge, color = TextPrimary)
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            S.infoTagline(lang),
            style = Typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            S.infoBody(lang),
            style = bodyStyle,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(S.infoContacts(lang), style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(S.infoChannel(lang), style = quietStyle, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "t.me/$OFFICIAL_CHANNEL",
            style = Typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.clickable { openOfficialChannel(context) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(S.infoBugs(lang), style = quietStyle, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            FEEDBACK_EMAIL,
            style = Typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.clickable {
                com.blink.dtn.telemetry.FeedbackMailer.sendFeedback(
                    context,
                    subject = "Tuk-Tuk feedback",
                    body = S.feedbackBody(lang)
                )
            }
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            S.infoFooter(lang),
            style = quietStyle,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
    }
}

@Composable
fun InfoTab() {
    InfoContent(compact = false)
}

@Composable
fun DeveloperPanel(viewModel: BLinkViewModel, onClose: () -> Unit) {
    // Kept as alias — Delivery Observatory is the Phase-2 black-box UI.
    DeliveryObservatoryPanel(viewModel, onClose)
}
