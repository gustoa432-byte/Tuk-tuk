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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.filled.Check

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
        DeveloperPanel(viewModel = viewModel, onClose = { showDevPanel = false })
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
                    viewModel.setCurrentDialog(contactId)
                    selectedTab = 0
                }
                2 -> InfoTab()
                3 -> ProfileTab(viewModel, { selectedTab = 0 })
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
            BottomBarItem(Icons.Default.Info, "Информация", selectedTab == 2) { onTabSelected(2) }
            BottomBarItem(Icons.Default.Person, "Профиль", selectedTab == 3) { onTabSelected(3) }
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
                        val formatter = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
                        val timeString = if (dialog.lastTimestamp > 0) formatter.format(java.util.Date(dialog.lastTimestamp)) else ""

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
                                Text(
                                    text = dialog.displayName ?: "Неизвестный контакт", 
                                    color = TextPrimary, 
                                    style = Typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                                        viewModel.setCurrentDialog(searchId)
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
    val displayName = dialogs.find { it.conversationId == contactId }?.displayName ?: "Неизвестный контакт"
    var messageText by remember { mutableStateOf("") }
    
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
            Text(text = displayName, style = Typography.titleMedium, color = TextPrimary)
        }
        

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Здесь пока нет сообщений.", color = TextSecondary, style = Typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                items(messages.reversed()) { msg ->
                    MessageBubble(msg, viewModel.myNodeId)
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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("В общем чате пока тихо.\nНапишите что-нибудь!", color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                items(messages.reversed()) { msg ->
                    MessageBubble(msg, viewModel.myNodeId, showSender = true, onSenderClick = onPrivateChatRequested)
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
fun MessageBubble(msg: Message, myNodeId: String, showSender: Boolean = false, onSenderClick: ((String) -> Unit)? = null) {
    val isMine = msg.senderId == myNodeId || msg.isMine
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = formatter.format(Date(msg.timestamp))

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                        Spacer(modifier = Modifier.width(4.dp))
                        val statusColor = when (msg.status) {
                            Message.STATUS_SENT, Message.STATUS_DELIVERED -> TextSecondary
                            Message.STATUS_FAILED -> DangerColor
                            else -> DividerColor
                        }
                        Box(modifier = Modifier.size(6.dp).background(statusColor, CircleShape))
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
                    Toast.makeText(context, "Invalid QR: key does not match ID", Toast.LENGTH_LONG).show()
                } else {
                    val nick = json.optString("n", "")
                    viewModel.addScannedContact(derivedId, nick, pk)
                    onScanSuccess()
                }
            } else {
                viewModel.setCurrentDialog(scanned)
                onScanSuccess()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                    viewModel.updateMyProfile(it, false)
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

@Composable
fun InfoTab() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var language by remember { mutableStateOf("Русский") }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Что такое Tuk-Tuk", style = Typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Tuk-Tuk — простой мессенджер, который работает без интернета.",
            style = Typography.bodyMedium,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Возможности", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        val features = listOf("Общий чат", "Личные сообщения", "Передача через BLE Mesh", "Работает без интернета")
        features.forEach { feature ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(feature, style = Typography.bodyMedium, color = TextSecondary)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Поддержка проекта", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Поддержка помогает тестировать приложение, развивать BLE Mesh и в будущем добавить поддержку LoRa.",
            style = Typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        TukTukButton(onClick = { uriHandler.openUri("https://boosty.to/tuktuk") }) {
            Text("Поддержать автора", color = TextPrimary, style = Typography.titleMedium)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Обратная связь", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Telegram",
            style = Typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        TukTukButton(onClick = { 
            clipboardManager.setText(AnnotatedString("@tuktuk_dev"))
            Toast.makeText(context, "Ник скопирован", Toast.LENGTH_SHORT).show()
        }) {
            Text("@tuktuk_dev (Скопировать)", color = TextPrimary, style = Typography.titleMedium)
        }
        
        Spacer(modifier = Modifier.height(48.dp))
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
        
        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
fun DeveloperPanel(viewModel: BLinkViewModel, onClose: () -> Unit) {
    val peerCount by viewModel.peerCount.collectAsState()
    val pending by viewModel.pendingCount.collectAsState(0)
    Column(modifier = Modifier.fillMaxSize().background(BackgroundDark).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
            }
            Text("Developer Panel", style = Typography.titleLarge, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("BLE Devices (Active Peers): $peerCount", color = TextSecondary, style = Typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Pending Messages (Queue): $pending", color = TextSecondary, style = Typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Routing Mode: BLE DTN Mesh", color = TextSecondary, style = Typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("InFlight: Managed by TxBatch", color = TextSecondary, style = Typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))
        TukTukButton(onClick = { /* Export mock */ }) {
            Text("Export Logs", color = TextPrimary)
        }
    }
}
