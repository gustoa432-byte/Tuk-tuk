package com.blink.dtn.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.blink.dtn.db.Message
import com.blink.dtn.ui.theme.*
import kotlinx.coroutines.launch
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
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }
    val peerCount by viewModel.peerCount.collectAsState()
    val vkActive by viewModel.vkActive.collectAsState()
    val relayActive by viewModel.relayActive.collectAsState()
    val isConnected = vkActive || peerCount > 0

    if (showDevPanel) {
        DeliveryObservatoryPanel(viewModel = viewModel, onClose = { showDevPanel = false })
        return
    }

    Scaffold(
        containerColor = BackgroundDark,
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
                        // Subtle indicator
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (isConnected) TextPrimary else DividerColor,
                                    shape = CircleShape
                                )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark,
                    titleContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            CustomBottomBar(selectedTab) { selectedTab = it }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
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

@Composable
fun CustomBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Surface(
        color = BackgroundDark,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(Icons.Default.Person, "Диалоги", selectedTab == 0) { onTabSelected(0) }
            BottomBarItem(Icons.Default.Email, "Общий чат", selectedTab == 1) { onTabSelected(1) }
            BottomBarItem(Icons.Default.Person, "Профиль", selectedTab == 2) { onTabSelected(2) }
        }
    }
}

@Composable
fun BottomBarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val contentColor = if (isSelected) BackgroundDark else TextSecondary
    val bgColor = if (isSelected) TextPrimary else Color.Transparent

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
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
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
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark)
                    .bounceClick { showSearch = true }
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Найти или начать диалог...",
                    color = TextSecondary,
                    style = Typography.bodyLarge
                )
            }
            

            if (privateDialogs.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("У вас пока нет диалогов.\nНажмите 'Найти или начать диалог...', чтобы добавить контакт.", color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(privateDialogs) { dialog ->
                        val profile by viewModel.getProfileFlow(dialog.conversationId).collectAsState(initial = null)
                        val formatter = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
                        val timeString = if (dialog.lastTimestamp > 0) formatter.format(java.util.Date(dialog.lastTimestamp)) else ""
                        val title = peerListTitle(profile, dialog.displayName)
                        val isStranger = profile?.isStranger == true
                        val trustBadge = profile?.trustBadgeRu()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .bounceClick { viewModel.setCurrentDialog(dialog.conversationId) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
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
                    .background(BackgroundDark.copy(alpha = 0.9f))
                    .pointerInput(Unit) {
                        detectTapGestures { showSearch = false }
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(SurfaceDark)
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = searchId,
                        onValueChange = { searchId = it },
                        textStyle = Typography.bodyLarge.copy(color = TextPrimary),
                        cursorBrush = SolidColor(TextPrimary),
                        decorationBox = { innerTextField ->
                            if (searchId.isEmpty()) {
                                Text("Введите ID собеседника...", color = TextSecondary, style = Typography.bodyLarge)
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
                            text = "Отмена",
                            color = TextSecondary,
                            modifier = Modifier
                                .bounceClick { showSearch = false }
                                .padding(8.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Начать",
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
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val verifyScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && scanned.isNotBlank()) {
            val json = try { org.json.JSONObject(scanned) } catch (e: Exception) { null }
            val pk = json?.optString("pk", "")
            if (json != null && !pk.isNullOrEmpty()) {
                val derivedId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pk)
                val claimedId = json.optString("id", "")
                if (derivedId.isEmpty() || (claimedId.isNotEmpty() && claimedId != derivedId)) {
                    Toast.makeText(context, "Неверный QR: ключ не совпадает с id", Toast.LENGTH_LONG).show()
                } else if (derivedId != contactId) {
                    Toast.makeText(context, "QR другого человека — не этого диалога", Toast.LENGTH_LONG).show()
                } else {
                    val nick = json.optString("n", "")
                    viewModel.addScannedContact(derivedId, nick, pk)
                    Toast.makeText(context, "Контакт проверен по QR", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Нужен QR контакта TukTuk (с ключом)", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Имя в диалогах", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Локальная подпись только на этом устройстве. Сетевой ник собеседника не меняется (ник — просто метка, не уникальный id).",
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
                                    .background(DividerColor, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                if (renameDraft.isEmpty()) {
                                    Text("Например, Вася с работы", color = TextSecondary, style = Typography.bodyLarge)
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
                    Toast.makeText(context, "Имя сохранено", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Сохранить", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
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
                        text = profile?.trustBadgeRu() ?: "из сети",
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
                            text = { Text("Изменить имя", color = TextPrimary) },
                            onClick = {
                                menuExpanded = false
                                renameDraft = profile?.localAlias.orEmpty()
                                showRename = true
                            }
                        )
                    }
                    if (isStranger) {
                        DropdownMenuItem(
                            text = { Text("В контакты", color = TextPrimary) },
                            onClick = {
                                menuExpanded = false
                                viewModel.acceptContact(contactId)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Игнорировать", color = DangerColor) },
                            onClick = {
                                menuExpanded = false
                                viewModel.ignorePeer(contactId, profile?.nickname.orEmpty())
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Игнорировать", color = DangerColor) },
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .padding(12.dp)
            ) {
                Text(
                    "Запрос сообщения от незнакомца. Ник — просто метка; смотрите id. Примите, игнорируйте или сверьте QR.",
                    color = TextSecondary,
                    style = Typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TukTukButton(onClick = { viewModel.acceptContact(contactId) }) {
                        Text("Принять", color = TextPrimary, style = Typography.labelMedium)
                    }
                    TukTukButton(onClick = {
                        viewModel.ignorePeer(contactId, profile?.nickname.orEmpty())
                    }) {
                        Text("Игнорировать", color = DangerColor, style = Typography.labelMedium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else if (needsQrVerify) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .padding(12.dp)
            ) {
                Text(
                    "Контакт из сети. Для семьи и близких сверьте QR — так вы закрепите ключ («проверен»).",
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
                    Text("Сверить QR", color = TextPrimary, style = Typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Здесь пока нет сообщений.", color = TextSecondary, style = Typography.bodyMedium)
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
    val messages by viewModel.publicMessages.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Общий чат — открытый мегафон: соседние узлы видят текст. Без группового шифрования.",
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("В общем чате пока тихо.\nНапишите что-нибудь!", color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                        showSender = true,
                        onSenderClick = onPrivateChatRequested,
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
                    viewModel.sendPublicMessage(messageText, "general")
                    messageText = ""
                }
            }
        )
    }
}

// 4. ChatInputArea with custom BasicTextField and send button
@Composable
fun ChatInputArea(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
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
                        .weight(1f)
                        .background(SurfaceDark, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    if (text.isEmpty()) {
                        Text("Сообщение...", color = TextSecondary, style = Typography.bodyLarge)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(SurfaceDark)
                .bounceClick { onSend() }
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send", tint = TextPrimary, modifier = Modifier.size(20.dp))
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
    onRetrySend: (() -> Unit)? = null
) {
    val isMine = msg.senderId == myNodeId || msg.isMine
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
            title = { Text("Сообщение", color = TextPrimary) },
            text = {
                Text(
                    if (canCancel) {
                        "Удалить только у себя или отменить отправку (пока не ушло в сеть)."
                    } else {
                        "Удалить сообщение только на этом устройстве. У других оно останется."
                    },
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showActions = false
                    onDeleteLocal?.invoke()
                }) {
                    Text("Удалить у себя", color = DangerColor)
                }
            },
            dismissButton = {
                Row {
                    if (canCancel) {
                        TextButton(onClick = {
                            showActions = false
                            onCancelSend?.invoke()
                        }) {
                            Text("Отменить отправку", color = TextPrimary)
                        }
                    }
                    TextButton(onClick = { showActions = false }) {
                        Text("Закрыть", color = TextSecondary)
                    }
                }
            },
            containerColor = SurfaceDark
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
            Text(
                text = msg.senderNick.ifEmpty { "Аноним" },
                style = Typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier
                    .padding(bottom = 2.dp, start = 4.dp)
                    .bounceClick { onSenderClick?.invoke(msg.senderId) }
            )
        }
        Box(
            modifier = Modifier
                .background(
                    if (isMine) SurfaceDark else DividerColor,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMine) 16.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                Text(text = msg.text, style = Typography.bodyLarge, color = TextPrimary)
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
        context.startActivity(Intent.createChooser(intent, "Поделиться Tuk-Tuk"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ProfileTab(viewModel: BLinkViewModel, onScanSuccess: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var nickname by remember { mutableStateOf(viewModel.myNick) }
    var savedNick by remember { mutableStateOf(viewModel.myNick) }
    var language by remember { mutableStateOf("Русский") }
    var showInfo by remember { mutableStateOf(false) }
    val nickDirty = nickname.trim() != savedNick.trim()
    
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && scanned.isNotBlank()) {
            // Try the self-certifying contact payload first (pins the public key).
            // Fall back to treating the scan as a bare node id for legacy/manual entry.
            val json = try { org.json.JSONObject(scanned) } catch (e: Exception) { null }
            val pk = json?.optString("pk", "")
            if (json != null && !pk.isNullOrEmpty()) {
                val derivedId = com.blink.dtn.crypto.NodeIdentity.deriveNodeId(pk)
                val claimedId = json.optString("id", "")
                if (derivedId.isEmpty() || (claimedId.isNotEmpty() && claimedId != derivedId)) {
                    Toast.makeText(context, "Неверный QR: ключ не совпадает с id", Toast.LENGTH_LONG).show()
                } else {
                    val nick = json.optString("n", "")
                    viewModel.addScannedContact(derivedId, nick, pk)
                    onScanSuccess()
                }
            } else {
                viewModel.ensureContact(scanned)
                viewModel.setCurrentDialog(scanned)
                onScanSuccess()
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("О TukTuk", color = TextPrimary) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    InfoContent(compact = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Закрыть", color = TextPrimary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .bounceClick { showInfo = true }
                    .padding(12.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = "Информация", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .bounceClick { shareApk(context) }
                    .padding(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = "Поделиться приложением", tint = TextPrimary)
            }
        }
        
        BasicTextField(
            value = nickname,
            onValueChange = {
                if (it.length <= 15) {
                    nickname = it
                }
            },
            textStyle = Typography.titleLarge.copy(color = TextPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            cursorBrush = SolidColor(TextPrimary),
            decorationBox = { innerTextField ->
                if (nickname.isEmpty()) {
                    Text("Имя", color = TextSecondary, style = Typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                innerTextField()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        TukTukButton(
            onClick = {
                val trimmed = nickname.trim()
                if (trimmed.isEmpty()) {
                    Toast.makeText(context, "Введите имя", Toast.LENGTH_SHORT).show()
                    return@TukTukButton
                }
                nickname = trimmed
                viewModel.updateMyProfile(trimmed, false)
                savedNick = trimmed
                Toast.makeText(context, "Имя сохранено", Toast.LENGTH_SHORT).show()
            }
        ) {
            Text(
                if (nickDirty) "Сохранить" else "Сохранено",
                color = if (nickDirty) TextPrimary else TextSecondary,
                style = Typography.labelMedium
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        // QR encodes the full contact payload (id + public key + nick) so a scan
        // pins the key out-of-band. The human-readable id below stays as-is.
        val contactQr = viewModel.myContactQr
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
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .bounceClick { 
                    clipboardManager.setText(AnnotatedString(viewModel.myNodeId))
                    Toast.makeText(context, "ID скопирован", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = viewModel.myNodeId, color = TextSecondary, style = Typography.bodyMedium)
            Spacer(modifier = Modifier.width(16.dp))
            Text("📋 Копировать", color = TextSecondary, style = Typography.bodyMedium)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text("Отсканируйте QR другого пользователя", style = Typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        TukTukButton(onClick = { 
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setCameraId(0) // Use a specific camera of the device
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            options.setCaptureActivity(CustomScannerActivity::class.java)
            scanLauncher.launch(options)
        }) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сканировать QR", color = TextPrimary, style = Typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text("Режим сети (батарея)", style = Typography.labelSmall, color = TextSecondary)
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) DividerColor else SurfaceDark)
                        .bounceClick {
                            viewModel.setDutyPreset(p)
                            Toast.makeText(context, "Режим: ${p.labelRu}", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        p.labelRu,
                        color = if (selected) TextPrimary else TextSecondary,
                        style = Typography.labelSmall
                    )
                }
            }
        }
        Text(
            when (dutyPreset) {
                com.blink.dtn.ble.MeshDutyPreset.ECONOMY -> "Редкий скан — сеть живёт, телефон не садится за час."
                com.blink.dtn.ble.MeshDutyPreset.MAX -> "Плотный скан — быстрее соседи, выше расход."
                else -> "Баланс обнаружения и батареи."
            },
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.weight(1f))
        Text("Язык", style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Русский",
                color = if (language == "Русский") TextPrimary else TextSecondary,
                style = Typography.bodyMedium,
                modifier = Modifier.bounceClick { language = "Русский" }.padding(8.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "English",
                color = if (language == "English") TextPrimary else TextSecondary,
                style = Typography.bodyMedium,
                modifier = Modifier.bounceClick { language = "English" }.padding(8.dp)
            )
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
            Toast.makeText(context, "Не удалось открыть Telegram", Toast.LENGTH_SHORT).show()
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
            return nick.ifEmpty { "Незнакомец" }
        }
        if (nick.isNotEmpty()) return nick
        return profile.userId
    }
    return fallback?.takeIf { it.isNotBlank() } ?: "Неизвестный контакт"
}

@Composable
fun InfoContent(compact: Boolean = false) {
    val context = LocalContext.current
    val storyStyle = Typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val quietStyle = Typography.bodySmall.copy(lineHeight = 15.sp)
    val helpItems = listOf(
        "покупать оборудование для тестов",
        "разрабатывать маршрутизацию",
        "тестировать сеть в реальных условиях",
        "развивать дальнюю связь",
        "выпускать новые версии"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .then(if (compact) Modifier else Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp))
    ) {
        if (!compact) {
            Text("О проекте", style = Typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(2.dp))
            Text("TukTuk", style = Typography.titleLarge, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            "Мессенджер, который помогает оставаться на связи, когда пропадает интернет.",
            style = quietStyle,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text("Почему появился TukTuk?", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Во время отключения света я не смог сообщить родному человеку, что со мной всё в порядке. " +
                "Пришлось ехать через весь город только ради одной фразы: «Со мной всё хорошо».",
            style = storyStyle,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "После этого я решил создать TukTuk, чтобы люди могли оставаться на связи даже тогда, " +
                "когда привычный интернет недоступен.",
            style = storyStyle,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("Как помогает", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Общий чат рядом · Личные сообщения · Работает без интернета · Связь через телефоны вокруг",
            style = quietStyle,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("Конфиденциальность (кратко)", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "• Диалоги — личные: шифрование на устройстве, ACK от адресата.\n" +
                "• Общий чат — открытый мегафон: его читают соседи по сети.\n" +
                "• Ник — метка, не уникальный id (смотрите короткий id).\n" +
                "• Для семьи сверяйте QR — так контакт станет «проверен».\n" +
                "Подробнее: docs/THREAT_MODEL.md в исходниках.",
            style = quietStyle,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Сообщить об уязвимости: $FEEDBACK_EMAIL (см. docs/SECURITY.md).",
            style = quietStyle,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("Официальный канал", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Новости и обновления TukTuk.",
            style = quietStyle,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        TukTukButton(onClick = { openOfficialChannel(context) }) {
            Text("t.me/$OFFICIAL_CHANNEL", color = TextPrimary, style = Typography.labelMedium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Поддержать развитие", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Если TukTuk оказался полезным, вы можете помочь развитию сети.",
            style = quietStyle,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("Ваша поддержка помогает:", style = quietStyle, color = TextSecondary)
        helpItems.forEach { item ->
            Text("✔  $item", style = quietStyle, color = TextSecondary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TukTukButton(onClick = { openAuthorTelegram(context) }) {
            Text("❤️ Стать участником сети", color = TextPrimary, style = Typography.labelMedium)
        }
        Spacer(modifier = Modifier.height(6.dp))
        TukTukButton(onClick = { openAuthorTelegram(context) }) {
            Text("☕ Угостить автора кофе", color = TextPrimary, style = Typography.labelMedium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Обратная связь", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Нашли ошибку? Есть идея? Хотите помочь проекту? Напишите — письма приходят на почту проекта.",
            style = quietStyle,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(FEEDBACK_EMAIL, style = Typography.bodyMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        TukTukButton(onClick = {
            com.blink.dtn.telemetry.FeedbackMailer.sendFeedback(
                context,
                subject = "TukTuk feedback",
                body = "Опишите ошибку или идею:\n\n"
            )
        }) {
            Text("Написать", color = TextPrimary, style = Typography.labelMedium)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Я читаю все сообщения, но могу отвечать не сразу.",
            style = quietStyle,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Личный Telegram автора: @$AUTHOR_TELEGRAM",
            style = quietStyle,
            color = TextSecondary,
            modifier = Modifier.clickable { openAuthorTelegram(context) }
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "TukTuk остаётся бесплатным для всех.\nСпасибо каждому, кто помогает делать сеть лучше.",
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
