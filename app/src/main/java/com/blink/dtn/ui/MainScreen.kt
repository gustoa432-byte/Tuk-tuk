@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.blink.dtn.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.blink.dtn.db.Conversation
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
        // Key on onClick so Send/emoji/nav rows always use the latest lambda
        // (Unit froze the first composition — empty field kept "voice soon").
        .pointerInput(onClick) {
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

/**
 * Minimal Qq shell: dialogs list + profile + add-contact.
 * Hub / Channels / bottom bar removed from product UI (legacy code remains gated).
 */
enum class MainTab {
    Dialogs,
    Hub,
    Channels,
    Profile
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: BLinkViewModel) {
    var showProfile by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }
    var showCarrySheet by remember { mutableStateOf(false) }
    var keyChangePeerState by remember { mutableStateOf<String?>(null) }
    var showDevPanel by remember { mutableStateOf(false) }
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    var showIntro by remember { mutableStateOf(!QqFirstRun.seen(context)) }
    val lang by AppLang.lang.collectAsState()
    val carryCount by viewModel.meshCarryCount.collectAsState()
    val load = remember(carryCount) { DeliveryLoad.fromQueuedCount(carryCount) }
    val currentDialogId by viewModel.currentDialogId.collectAsState()
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(Unit) {
        AppLang.init(context)
        AppWallpaper.init(context)
        QqFeedbackPrefs.init(context)
        // Legacy gamification / reactions stay out of Core: old prefs must not
        // resurface as cosmetics or emoji on a plain text messenger.
        if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            GamificationStore.init(context)
            ReactionStore.init(context)
        }
        com.blink.dtn.ble.KeyChangeAlerts.events.collect { id ->
            keyChangePeerState = id
        }
    }

    if (showDevPanel && com.blink.dtn.BuildConfig.DEBUG) {
        DeliveryObservatoryPanel(viewModel = viewModel, onClose = { showDevPanel = false })
        return
    }

    // Once, before anything else: what Qq is, how to add someone, how long it takes.
    if (showIntro) {
        QqIntroScreen(
            onDone = {
                QqFirstRun.markSeen(context)
                showIntro = false
            }
        )
        return
    }

    // Early-exit screens have no Scaffold — must paint dark backdrop or light text
    // lands on the Activity's white window (blank / invisible UI).
    if (showSettings) {
        Box(modifier = Modifier.fillMaxSize()) {
            AppRootBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                SettingsHub(
                    onBack = {
                        AppWallpaper.discardDraft()
                        showSettings = false
                    },
                    viewModel = viewModel
                )
            }
        }
        return
    }

    if (showAbout) {
        Box(modifier = Modifier.fillMaxSize()) {
            AppRootBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                AboutQqScreen(onBack = { showAbout = false })
            }
        }
        return
    }

    if (showProfile && currentDialogId == null) {
        QqMinimalProfile(
            viewModel = viewModel,
            onBack = { showProfile = false },
            onOpenSettings = {
                showProfile = false
                showSettings = true
            },
            onOpenAbout = {
                showProfile = false
                showAbout = true
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppRootBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (currentDialogId == null) {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // Dev panel is a debug-only tool: no hidden entry point in release.
                                modifier = if (com.blink.dtn.BuildConfig.DEBUG) {
                                    Modifier.pointerInput(Unit) {
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
                                } else {
                                    Modifier
                                }
                            ) {
                                Text(
                                    "Qq",
                                    style = Typography.titleLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.sp
                                    ),
                                    color = TextPrimary
                                )
                                if (load != DeliveryLoad.Calm) {
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                DeliveryLoadDot(
                                    load = load,
                                    count = carryCount,
                                    onOpen = { showCarrySheet = true }
                                )
                            }
                        },
                        actions = {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = S.profile(lang),
                                tint = TextSecondary,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(40.dp)
                                    .bounceClick { showProfile = true }
                                    .padding(10.dp)
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = TextPrimary,
                            actionIconContentColor = TextPrimary
                        )
                    )
                }
            },
            floatingActionButton = {
                if (currentDialogId == null && !imeVisible) {
                    Box(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(end = 8.dp, bottom = 8.dp)
                            .size(52.dp)
                            .background(TextPrimary, CircleShape)
                            .bounceClick { showAddContact = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "+",
                            color = BackgroundDark,
                            style = Typography.titleLarge.copy(fontWeight = FontWeight.Light)
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .then(if (imeVisible) Modifier.imePadding() else Modifier)
            ) {
                PrivateTab(
                    viewModel = viewModel,
                    onOpenAddContact = { showAddContact = true }
                )
            }
        }
    }

    if (showAddContact) {
        AddContactSheet(
            viewModel = viewModel,
            onDismiss = { showAddContact = false },
            onOpenedChat = { showAddContact = false }
        )
    }
    if (showCarrySheet) {
        CarryExplainSheet(
            count = carryCount,
            onDismiss = { showCarrySheet = false }
        )
    }
    val keyChangePeer = keyChangePeerState
    if (keyChangePeer != null) {
        AlertDialog(
            onDismissRequest = { keyChangePeerState = null },
            title = { Text(S.keyChangedTitle(lang), color = TextPrimary) },
            text = { Text(S.keyChangedBody(lang), color = TextSecondary) },
            confirmButton = {
                Text(
                    S.welcomeStart(lang),
                    color = AccentLime,
                    modifier = Modifier
                        .bounceClick {
                            showAddContact = true
                            keyChangePeerState = null
                        }
                        .padding(12.dp)
                )
            },
            dismissButton = {
                Text(
                    if (lang == "en") "Later" else "Позже",
                    color = TextSecondary,
                    modifier = Modifier
                        .bounceClick { keyChangePeerState = null }
                        .padding(12.dp)
                )
            },
            containerColor = GlassDialogContainer
        )
    }
}

@Composable
private fun DeliveryLoadDot(load: DeliveryLoad, count: Int, onOpen: () -> Unit) {
    // Intensity is size + opacity of one neutral color. Red is reserved for send-failed.
    // Calm still has a hit target so the explanation is reachable when carrying nothing.
    val sizeDp = when (load) {
        DeliveryLoad.Calm -> 6.dp
        DeliveryLoad.Light -> 6.dp
        DeliveryLoad.Medium -> 7.dp
        DeliveryLoad.Heavy -> 8.dp
    }
    val alpha = when (load) {
        DeliveryLoad.Calm -> 0.12f
        DeliveryLoad.Light -> 0.35f
        DeliveryLoad.Medium -> 0.65f
        DeliveryLoad.Heavy -> 1f
    }
    val visible = load != DeliveryLoad.Calm
    val appear = animateFloatAsState(
        targetValue = if (visible) 1f else 0.35f,
        animationSpec = tween(durationMillis = if (visible) 0 else 400),
        label = "carryDot"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .bounceClick(onOpen)
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer { this.alpha = appear.value * if (visible) 1f else 1f }
                .background(TextSecondary.copy(alpha = alpha), CircleShape)
        )
        if (load == DeliveryLoad.Heavy && count > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                count.toString(),
                style = Typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarryExplainSheet(count: Int, onDismiss: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = com.blink.dtn.ui.theme.GlassDialogContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
            Text(
                S.carryExplainTitle(lang),
                style = Typography.titleMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                S.carryExplainBody(count, lang),
                style = Typography.bodyMedium,
                color = TextSecondary
            )
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            S.updateHint(lang),
            color = TextSecondary,
            style = Typography.labelSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                S.get(lang),
                color = AccentLime,
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
fun CustomBottomBar(selectedTab: MainTab, onTabSelected: (MainTab) -> Unit) {
    val lang by AppLang.lang.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Lift above system gesture/nav buttons.
            .navigationBarsPadding()
            .padding(bottom = 8.dp, start = 10.dp, end = 10.dp, top = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(corner = 22.dp, strong = true)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(Icons.AutoMirrored.Filled.Chat, S.dialogs(lang), selectedTab == MainTab.Dialogs) {
                onTabSelected(MainTab.Dialogs)
            }
            if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
                BottomBarItem(Icons.Default.Radar, S.hub(lang), selectedTab == MainTab.Hub) {
                    onTabSelected(MainTab.Hub)
                }
                BottomBarItem(Icons.Default.Email, S.groupChat(lang), selectedTab == MainTab.Channels) {
                    onTabSelected(MainTab.Channels)
                }
            }
            BottomBarItem(Icons.Default.Person, S.profile(lang), selectedTab == MainTab.Profile) {
                onTabSelected(MainTab.Profile)
            }
        }
    }
}

@Composable
fun BottomBarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val contentColor = if (isSelected) BackgroundDark else TextSecondary
    val bgColor = if (isSelected) AccentLime else Color.Transparent

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .bounceClick { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(18.dp))
            if (isSelected) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    label,
                    color = contentColor,
                    style = Typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun QqButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    // Prefer clickable over detectTapGestures — works reliably inside verticalScroll + IME.
    Box(
        modifier = Modifier
            .glassPanel(corner = 16.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) BackgroundDark else TextSecondary,
        style = Typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentLime else DividerColor.copy(alpha = 0.35f))
            .bounceClick(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
fun PrivateTab(
    viewModel: BLinkViewModel,
    onOpenAddContact: () -> Unit = {}
) {
    val currentDialogId by viewModel.currentDialogId.collectAsState()

    when {
        currentDialogId != null -> ConversationScreen(
            viewModel = viewModel,
            contactId = currentDialogId!!,
            onBack = { viewModel.setCurrentDialog(null) }
        )
        else -> ChatListScreen(
            viewModel = viewModel,
            onOpenContacts = onOpenAddContact
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(viewModel: BLinkViewModel, onOpenContacts: () -> Unit = {}) {
    val lang by AppLang.lang.collectAsState()
    val dialogs by viewModel.dialogs.collectAsState()
    // No search header: the list is short by nature, and a raw-ID field disguised
    // as search is not how you add a person. ＋ is the single primary action.
    val privateDialogs = dialogs.filter {
        it.conversationId != "general" && !it.isArchived
    }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (privateDialogs.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = S.noDialogs(lang),
                        color = TextSecondary,
                        style = Typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (lang == "en") "Tap + to add a contact" else "Нажмите + чтобы добавить контакт",
                        color = AccentLime,
                        style = Typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.bounceClick { onOpenContacts() }
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(privateDialogs, key = { it.conversationId }) { dialog ->
                    DialogListRow(
                        dialog = dialog,
                        viewModel = viewModel,
                        lang = lang,
                        onOpen = {
                            focusManager.clearFocus()
                            viewModel.setCurrentDialog(dialog.conversationId)
                        },
                        onTogglePin = { viewModel.togglePinDialog(dialog.conversationId) },
                        onToggleArchive = {
                            viewModel.setDialogArchived(dialog.conversationId, !dialog.isArchived)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogListRow(
    dialog: Conversation,
    viewModel: BLinkViewModel,
    lang: String,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit
) {
    val profile by viewModel.getProfileFlow(dialog.conversationId).collectAsState(initial = null)
    val ownStatus by viewModel.getLastOwnStatusFlow(dialog.conversationId)
        .collectAsState(initial = null)
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = if (dialog.lastTimestamp > 0) formatter.format(Date(dialog.lastTimestamp)) else ""
    val title = peerListTitle(profile, dialog.displayName, dialog.conversationId)
    val trustBadge = profile?.trustBadge(lang)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onTogglePin()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onToggleArchive()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth(),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val pinBg = AccentLime.copy(alpha = 0.85f)
            val archiveBg = Color(0xFF3D6BCC)
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(pinBg)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = BackgroundDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (dialog.isPinned) S.unpin(lang) else S.pin(lang),
                            color = BackgroundDark,
                            style = Typography.labelMedium
                        )
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(archiveBg)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = if (dialog.isArchived) S.unarchive(lang) else S.archive(lang),
                            color = TextPrimary,
                            style = Typography.labelMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (dialog.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                else -> {}
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(BackgroundDark)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onOpen)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PeerAvatar(
                    avatarBlob = profile?.avatarBlob,
                    label = title,
                    size = 44.dp,
                    uid = dialog.conversationId
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dialog.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(12.dp)
                                    .padding(end = 0.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = title,
                            color = TextPrimary,
                            style = Typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (trustBadge != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = trustBadge,
                                color = TextSecondary,
                                style = Typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                    if (!dialog.lastMessage.isNullOrEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ownStatus?.let { status ->
                                val statusColor = DeliveryVoyageLabels.colorForStatus(status)
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = DeliveryVoyageLabels.labelForStatus(status, lang = lang),
                                    color = statusColor,
                                    style = Typography.labelSmall,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = dialog.lastMessage,
                                color = TextSecondary,
                                style = Typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (timeString.isNotEmpty()) {
                        Text(text = timeString, color = TextSecondary, style = Typography.labelSmall)
                    }
                    if (dialog.unreadCount > 0) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .height(18.dp)
                                .background(AccentLime, RoundedCornerShape(9.dp))
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dialog.unreadCount.toString(),
                                color = BackgroundDark,
                                style = Typography.labelSmall
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp)
                    .height(0.5.dp)
                    .background(DividerColor.copy(alpha = 0.4f))
            )
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
    val displayName = peerListTitle(profile, fallbackName, contactId)
    val isStranger = profile?.isStranger == true
    val isContact = profile == null || profile?.isContact == true
    val needsQrVerify = profile?.isVerified != true
    var messageText by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showPeerProfile by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var forwardQueue by remember { mutableStateOf<List<Message>?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val messageById = remember(messages) { messages.associateBy { it.id } }
    // Core chat = SMS chrome: no forward, no edit, no multi-select. The code below
    // stays in place (and still runs in non-core builds) but is unreachable here.
    val extendedChat = !com.blink.dtn.BuildConfig.QQ_CORE_ONLY

    BackHandler(enabled = selecting || editing != null || replyTo != null || forwardQueue != null) {
        when {
            forwardQueue != null -> forwardQueue = null
            selecting -> {
                selecting = false
                selectedIds = emptySet()
            }
            editing != null -> {
                editing = null
                messageText = ""
            }
            replyTo != null -> replyTo = null
        }
    }

    val verifyScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && scanned.isNotBlank()) {
            when (
                val parsed = com.blink.dtn.crypto.ContactQr.parse(
                    scanned,
                    viewModel.myNodeId,
                    allowBareNodeId = false
                )
            ) {
                is com.blink.dtn.crypto.ContactQr.Result.Ok -> {
                    val p = parsed.parsed
                    if (p.nodeId != contactId) {
                        Toast.makeText(
                            context,
                            if (lang == "en") "QR is for a different person"
                            else "QR другого человека — не этого диалога",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val avatar = p.avatarAvBase64?.let { av ->
                            decodeContactQrAvatar(org.json.JSONObject().put("av", av))
                        }
                        viewModel.addScannedContact(p.nodeId, p.nick, p.publicKeyBase64, avatar)
                        Toast.makeText(context, S.qrVerified(lang), Toast.LENGTH_SHORT).show()
                    }
                }
                com.blink.dtn.crypto.ContactQr.Result.KeyMismatch ->
                    Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
                com.blink.dtn.crypto.ContactQr.Result.Self ->
                    Toast.makeText(context, S.qrSelf(lang), Toast.LENGTH_SHORT).show()
                com.blink.dtn.crypto.ContactQr.Result.NotContact ->
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
                Toast.makeText(context, S.idCopied(lang), Toast.LENGTH_SHORT).show()
            }
        )
    }

    forwardQueue?.let { queued ->
        ForwardPickerSheet(
            dialogs = dialogs,
            excludeId = contactId,
            profiles = emptyMap(),
            onDismiss = { forwardQueue = null },
            onConfirm = { targets ->
                viewModel.forwardMessagesToPeers(queued.map { it.text }, targets)
                Toast.makeText(context, S.forwarded(lang), Toast.LENGTH_SHORT).show()
                selecting = false
                selectedIds = emptySet()
                forwardQueue = null
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
    
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .bounceClick {
                        if (selecting) {
                            selecting = false
                            selectedIds = emptySet()
                        } else onBack()
                    }
                    .padding(8.dp)
            ) {
                CustomBackIcon()
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .bounceClick { if (!selecting) showPeerProfile = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PeerAvatar(
                    avatarBlob = profile?.avatarBlob,
                    label = displayName,
                    size = 36.dp,
                    uid = contactId
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selecting) S.selectedCount(lang, selectedIds.size) else displayName,
                        style = Typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (!selecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile?.trustBadge(lang) ?: S.fromNetwork(lang),
                                style = Typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier
                                    .background(DividerColor, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            if (!selecting) {
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = if (lang == "en") "Menu" else "Меню",
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
                    QqButton(onClick = { viewModel.acceptContact(contactId) }) {
                        Text(S.accept(lang), color = TextPrimary, style = Typography.labelMedium)
                    }
                    QqButton(onClick = {
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
                QqButton(onClick = {
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

        if (selecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .glassPanel(corner = 12.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(S.copy(lang), color = AccentLime, style = Typography.labelSmall, modifier = Modifier.bounceClick {
                    val texts = messages.filter { it.id in selectedIds }.joinToString("\n") { it.text }
                    clipboardManager.setText(AnnotatedString(texts))
                    Toast.makeText(context, S.copied(lang), Toast.LENGTH_SHORT).show()
                })
                Text(S.forward(lang), color = AccentLime, style = Typography.labelSmall, modifier = Modifier.bounceClick {
                    val picked = messages.filter { it.id in selectedIds }
                    if (picked.isNotEmpty()) forwardQueue = picked
                })
                Text(S.deleteLocal(lang), color = DangerColor, style = Typography.labelSmall, modifier = Modifier.bounceClick {
                    viewModel.deleteMessagesLocally(selectedIds)
                    selectedIds = emptySet()
                    selecting = false
                })
                Text(S.cancel(lang), color = TextSecondary, style = Typography.labelSmall, modifier = Modifier.bounceClick {
                    selecting = false
                    selectedIds = emptySet()
                })
            }
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
                    val replyPreview = msg.replyToId?.let { messageById[it]?.text }
                    MessageBubble(
                        msg = msg,
                        myNodeId = viewModel.myNodeId,
                        selected = msg.id in selectedIds,
                        selecting = selecting,
                        replyPreview = replyPreview,
                        onToggleSelect = {
                            selectedIds = if (msg.id in selectedIds) selectedIds - msg.id else selectedIds + msg.id
                            if (selectedIds.isEmpty()) selecting = false
                        },
                        onLongPressSelect = if (extendedChat) {
                            {
                                selecting = true
                                selectedIds = selectedIds + msg.id
                            }
                        } else null,
                        onReply = {
                            editing = null
                            replyTo = msg
                        },
                        onForward = if (extendedChat) {
                            { forwardQueue = listOf(msg) }
                        } else null,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(msg.text))
                            Toast.makeText(context, S.copied(lang), Toast.LENGTH_SHORT).show()
                        },
                        onEdit = if (extendedChat) {
                            {
                                replyTo = null
                                editing = msg
                                messageText = msg.text
                            }
                        } else null,
                        onDeleteLocal = { viewModel.deleteMessageLocally(msg.id) },
                        onCancelSend = { viewModel.cancelOutgoingMessage(msg.id) },
                        onRetrySend = { viewModel.retryOutgoingMessage(msg.id) },
                        onReport = {
                            viewModel.reportMessage(msg)
                        }
                    )
                }
            }
        }

        editing?.let { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .glassPanel(corner = 10.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(S.edit(lang), color = AccentLime, style = Typography.labelSmall)
                    Text(target.text, color = TextSecondary, style = Typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text("×", color = TextSecondary, modifier = Modifier.bounceClick {
                    editing = null
                    messageText = ""
                }.padding(6.dp))
            }
        }

        replyTo?.let { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .glassPanel(corner = 10.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(S.reply(lang), color = AccentLime, style = Typography.labelSmall)
                    Text(target.text, color = TextSecondary, style = Typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text("×", color = TextSecondary, modifier = Modifier.bounceClick { replyTo = null }.padding(6.dp))
            }
        }
        
        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                viewModel.sendPrivatePhoto(uri, contactId, caption = messageText.trim())
                messageText = ""
            }
        }

        ChatInputArea(
            text = messageText,
            onTextChange = { if (it.length <= com.blink.dtn.ble.MeshLimits.MAX_TEXT_CHARS) messageText = it },
            sendLabel = if (editing != null) S.edit(lang) else S.send(lang),
            onPickPhoto = if (editing == null) {
                { photoPicker.launch("image/*") }
            } else null,
            onSend = {
                if (messageText.isNotBlank()) {
                    val editTarget = editing
                    if (editTarget != null) {
                        viewModel.editOwnMessage(editTarget.id, messageText)
                        editing = null
                        messageText = ""
                    } else {
                        val quoted = replyTo
                        val body = quoted?.let { "↪ ${it.text.take(40)}\n$messageText" } ?: messageText
                        viewModel.sendPrivateMessage(body, contactId, replyToId = quoted?.id)
                        messageText = ""
                        replyTo = null
                    }
                }
            }
        )
    }
}


@Composable
private fun ChannelChip(
    meta: com.blink.dtn.ble.MeshRoom.RoomMeta,
    selected: Boolean,
    lang: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .glassPanel(corner = 14.dp, strong = selected)
            .background(
                if (selected) AccentLime.copy(alpha = 0.22f)
                else Color.Transparent
            )
            .bounceClick(onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (lang == "en") meta.titleEn else meta.titleRu,
            color = if (selected) AccentLime else TextSecondary,
            style = Typography.labelSmall
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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        // Primary humanitarian groups first, then the rest
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(com.blink.dtn.ble.MeshRoom.PRIMARY) { meta ->
                ChannelChip(meta, selectedRoom == meta.id, lang) { viewModel.selectRoom(meta.id) }
            }
            item {
                Text(
                    "·",
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                )
            }
            items(com.blink.dtn.ble.MeshRoom.ALL.filter { room ->
                com.blink.dtn.ble.MeshRoom.PRIMARY.none { it.id == room.id }
            }) { meta ->
                ChannelChip(meta, selectedRoom == meta.id, lang) { viewModel.selectRoom(meta.id) }
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
                        onReport = { viewModel.reportMessage(msg) },
                        senderAvatarBlob = senderProfile?.avatarBlob
                    )
                }
            }
        }


        ChatInputArea(
            text = messageText,
            onTextChange = { if (it.length <= com.blink.dtn.ble.MeshLimits.MAX_TEXT_CHARS) messageText = it },
            onSend = {
                if (messageText.isNotBlank()) {
                    viewModel.sendPublicMessage(messageText, selectedRoom)
                    messageText = ""
                }
            }
        )
    }
}

/**
 * Composer: field · attach · send.
 *
 * No microphone — Qq has no voice messages and the button promised one.
 * The emoji sheet and its stickers are kept for non-core builds only; in Qq Core
 * the keyboard's own emoji are enough.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    sendLabel: String? = null,
    onPickPhoto: (() -> Unit)? = null,
    onEmoji: (() -> Unit)? = null
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    var showEmojiSheet by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank() || (sendLabel != null && sendLabel == S.edit(lang))
    val editing = sendLabel != null && sendLabel == S.edit(lang)
    val quickEmoji = remember {
        listOf("😀", "😂", "❤️", "👍", "🔥", "🎉", "🙏", "😎", "😢", "🤝", "💪", "✨")
    }

    if (showEmojiSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiSheet = false },
            containerColor = GlassDialogContainer
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
                Text(S.emoji(lang), color = TextPrimary, style = Typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    quickEmoji.take(6).forEach { e ->
                        Text(
                            e,
                            style = Typography.headlineSmall,
                            modifier = Modifier.bounceClick {
                                onTextChange(text + e)
                                showEmojiSheet = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    quickEmoji.drop(6).forEach { e ->
                        Text(
                            e,
                            style = Typography.headlineSmall,
                            modifier = Modifier.bounceClick {
                                onTextChange(text + e)
                                showEmojiSheet = false
                            }
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .bounceClick {
                        if (onEmoji != null) onEmoji()
                        else showEmojiSheet = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("😊", style = Typography.titleMedium)
            }
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = Typography.bodyLarge.copy(color = TextPrimary, fontSize = 16.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(TextPrimary),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(DividerColor.copy(alpha = 0.35f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(S.message(lang), color = TextSecondary, style = Typography.bodyMedium)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f)
        )
        if (onPickPhoto != null && !canSend) {
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .bounceClick { onPickPhoto() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = S.attach(lang),
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (canSend) AccentLime.copy(alpha = 0.9f) else Color.Transparent)
                .bounceClick {
                    when {
                        canSend -> onSend()
                        else -> Toast.makeText(context, S.typeToSend(lang), Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (editing) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                contentDescription = sendLabel ?: S.send(lang),
                tint = when {
                    editing -> AccentLime
                    canSend -> BackgroundDark
                    else -> TextSecondary
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    onReport: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongPressSelect: (() -> Unit)? = null,
    replyPreview: String? = null,
    senderAvatarBlob: ByteArray? = null
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val isMine = msg.senderId == myNodeId || msg.isMine
    // Core = plain text messenger: previously stored reactions are simply not rendered
    // and legacy nick cosmetics never tint the text.
    val reaction = if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
        null
    } else {
        val reactions by ReactionStore.map.collectAsState()
        reactions[msg.id]
    }
    val nickTint = if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
        TextPrimary
    } else {
        val gm by GamificationStore.snap.collectAsState()
        if (isMine) CosmeticApply.nickColor(gm.nickColorId) else TextPrimary
    }
    val canBlockSender = !isMine && onBlockUser != null
    val canReport = !isMine && onReport != null && msg.senderId.isNotBlank()
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = formatter.format(Date(msg.displayClockMs()))
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
        MessageActionSheet(
            msg = msg,
            isMine = isMine,
            canCancel = canCancel,
            canBlockSender = canBlockSender || canReport,
            onDismiss = { showActions = false },
            onReply = onReply,
            onForward = onForward,
            onCopy = onCopy,
            onEdit = onEdit,
            onSelect = onLongPressSelect,
            onDeleteLocal = onDeleteLocal,
            onCancelSend = onCancelSend,
            onBlockUser = onBlockUser,
            onReport = if (canReport) onReport else null,
            onReact = if (com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
                null
            } else {
                { emoji -> ReactionStore.set(context, msg.id, emoji) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (selected) AccentLime.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(
                onClick = {
                    when {
                        selecting -> onToggleSelect?.invoke()
                        else -> showVoyage = true
                    }
                },
                onLongClick = {
                    if (selecting) {
                        onToggleSelect?.invoke()
                    } else {
                        showActions = true
                    }
                }
            ),
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
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                val preview = replyPreview ?: msg.text.lines().firstOrNull()
                    ?.takeIf { it.startsWith("↪ ") || it.startsWith("↗") }
                    ?.removePrefix("↪ ")
                if (!replyPreview.isNullOrBlank()) {
                    Text(
                        replyPreview,
                        color = AccentLime.copy(alpha = 0.9f),
                        style = Typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .background(AccentLime.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                } else if (preview != null && msg.replyToId != null) {
                    Text(
                        preview,
                        color = AccentLime.copy(alpha = 0.9f),
                        style = Typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                if (msg.type == Message.TYPE_PRIVATE_IMAGE && !msg.mediaPath.isNullOrBlank()) {
                    val bmp = remember(msg.mediaPath) {
                        runCatching {
                            android.graphics.BitmapFactory.decodeFile(msg.mediaPath)
                                ?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = S.photo(lang),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        if (msg.text.isNotBlank() && msg.text != "📷") {
                            Spacer(modifier = Modifier.height(6.dp))
                            EmojiText(
                                text = msg.text,
                                style = Typography.bodyLarge,
                                color = nickTint
                            )
                        }
                    } else {
                        EmojiText(
                            text = msg.text.ifBlank { "📷" },
                            style = Typography.bodyLarge,
                            color = nickTint
                        )
                    }
                } else {
                    EmojiText(
                        text = msg.text,
                        style = Typography.bodyLarge,
                        color = nickTint
                    )
                }
                if (!reaction.isNullOrBlank()) {
                    Text(
                        reaction,
                        style = Typography.titleMedium,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(AccentLime.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 3.dp).align(Alignment.End)
                ) {
                    if (msg.editedAt > 0L) {
                        Text(S.edited(lang), style = Typography.labelSmall, color = TextSecondary)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
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
        val destFile = File(context.externalCacheDir, "Qq_setup.apk")
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
fun ProfileTab(
    viewModel: BLinkViewModel,
    onScanSuccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenHub: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var nameFieldValue by remember {
        mutableStateOf(TextFieldValue(viewModel.displayName()))
    }
    var nickFieldValue by remember {
        mutableStateOf(TextFieldValue(viewModel.myNick))
    }
    var savedName by remember { mutableStateOf(viewModel.displayName()) }
    var savedNick by remember { mutableStateOf(viewModel.myNick) }
    val profileDirty =
        nameFieldValue.text.trim() != savedName.trim() ||
            nickFieldValue.text.trim() != savedNick.trim()
    var showSavedFlash by remember { mutableStateOf(false) }
    val myProfile by viewModel.getProfileFlow(viewModel.myNodeId).collectAsState(initial = null)
    var contactQr by remember { mutableStateOf(viewModel.myContactQr) }
    var showInvite by remember { mutableStateOf(false) }
    var showQrZoom by remember { mutableStateOf(false) }

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
            when (val parsed = com.blink.dtn.crypto.ContactQr.parse(scanned, viewModel.myNodeId)) {
                is com.blink.dtn.crypto.ContactQr.Result.Ok -> {
                    val p = parsed.parsed
                    val avatar = p.avatarAvBase64?.let { av ->
                        decodeContactQrAvatar(org.json.JSONObject().put("av", av))
                    }
                    if (p.hasPinnedKey) {
                        viewModel.addScannedContact(p.nodeId, p.nick, p.publicKeyBase64, avatar)
                    } else {
                        viewModel.ensureContact(p.nodeId, p.nick)
                        viewModel.setCurrentDialog(p.nodeId)
                    }
                    onScanSuccess()
                }
                com.blink.dtn.crypto.ContactQr.Result.KeyMismatch ->
                    Toast.makeText(context, S.qrKeyMismatch(lang), Toast.LENGTH_LONG).show()
                com.blink.dtn.crypto.ContactQr.Result.Self ->
                    Toast.makeText(context, S.qrSelf(lang), Toast.LENGTH_SHORT).show()
                com.blink.dtn.crypto.ContactQr.Result.NotContact ->
                    Toast.makeText(context, S.qrNotContact(lang), Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showInvite) {
        InviteFriendsSheet(contactQrPayload = contactQr, onDismiss = { showInvite = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PeerAvatar(
            avatarBlob = myProfile?.avatarBlob,
            label = nameFieldValue.text.ifBlank {
                nickFieldValue.text.ifBlank { viewModel.myNodeId.take(4) }
            },
            size = 96.dp,
            uid = viewModel.myNodeId,
            modifier = Modifier.bounceClick { openAvatarPicker() }
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            S.authDisplayName(lang),
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.fillMaxWidth()
        )
        BasicTextField(
            value = nameFieldValue,
            onValueChange = { newVal ->
                if (newVal.text.length <= 20) {
                    nameFieldValue = newVal
                    showSavedFlash = false
                }
            },
            textStyle = Typography.headlineSmall.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (nameFieldValue.text.isEmpty()) {
                        Text(S.authDisplayNameHint(lang), color = TextSecondary, style = Typography.headlineSmall)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Always-editable @nick (tap-to-swap Text↔field lost focus immediately and only "jerked").
        BasicTextField(
            value = nickFieldValue,
            onValueChange = { newVal ->
                val cleaned = newVal.text.removePrefix("@").replace("@", "").take(20)
                val sel = newVal.selection.start.coerceIn(0, cleaned.length)
                nickFieldValue = TextFieldValue(text = cleaned, selection = TextRange(sel))
                showSavedFlash = false
            },
            textStyle = Typography.bodyMedium.copy(color = TextSecondary),
            cursorBrush = SolidColor(TextPrimary),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("@", color = TextSecondary, style = Typography.bodyMedium)
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (nickFieldValue.text.isEmpty()) {
                            Text(
                                nameFieldValue.text.trim().ifBlank { "user" },
                                color = TextSecondary.copy(alpha = 0.55f),
                                style = Typography.bodyMedium
                            )
                        }
                        innerTextField()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = S.tapCopyId(lang),
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier
                .padding(top = 6.dp)
                .bounceClick {
                    clipboardManager.setText(AnnotatedString(viewModel.myNodeId))
                    Toast.makeText(context, S.idCopied(lang), Toast.LENGTH_SHORT).show()
                }
        )
        Spacer(modifier = Modifier.height(8.dp))
        QqButton(
            enabled = profileDirty || showSavedFlash,
            onClick = {
                if (!profileDirty) return@QqButton
                keyboardController?.hide()
                viewModel.updateMyNameAndNick(nameFieldValue.text, nickFieldValue.text)
                val resolvedName = viewModel.displayName()
                val resolvedNick = viewModel.myNick
                nameFieldValue = TextFieldValue(
                    text = resolvedName,
                    selection = TextRange(resolvedName.length)
                )
                nickFieldValue = TextFieldValue(
                    text = resolvedNick,
                    selection = TextRange(resolvedNick.length)
                )
                savedName = resolvedName
                savedNick = resolvedNick
                showSavedFlash = true
                Toast.makeText(context, S.nameSaved(lang), Toast.LENGTH_SHORT).show()
            }
        ) {
            Icon(
                Icons.Filled.Check,
                null,
                tint = if (profileDirty || showSavedFlash) AccentLime else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (showSavedFlash && !profileDirty) S.saved(lang) else S.save(lang),
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        if (!com.blink.dtn.BuildConfig.QQ_CORE_ONLY) {
            val helpSnap by GamificationStore.snap.collectAsState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(corner = 14.dp)
                    .padding(14.dp)
            ) {
                Text(S.networkHelpStats(lang), color = TextPrimary, style = Typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(helpSnap.helped.toString(), color = AccentLime, style = Typography.titleLarge)
                        Text(S.packagesDelivered(lang), color = TextSecondary, style = Typography.labelSmall)
                    }
                    Column {
                        Text(helpSnap.received.toString(), color = AccentLime, style = Typography.titleLarge)
                        Text(S.messagesReceived(lang), color = TextSecondary, style = Typography.labelSmall)
                    }
                    Column {
                        Text(helpSnap.saved.toString(), color = AccentLime, style = Typography.titleLarge)
                        Text(S.livesSaved(lang), color = TextSecondary, style = Typography.labelSmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            SettingsNavRow(S.cosmetics(lang), onOpenHub)
            SettingsNavRow(S.inviteFriends(lang)) { showInvite = true }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }
        SettingsNavRow(S.settings(lang), onOpenSettings)
        SettingsNavRow(S.aboutProject(lang), onOpenAbout)
        SettingsNavRow(S.scanQr(lang)) {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setCameraId(0)
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            options.setCaptureActivity(CustomScannerActivity::class.java)
            scanLauncher.launch(options)
        }

        Spacer(modifier = Modifier.height(24.dp))
        val qrBitmap = remember(contactQr) {
            generateQrCode(contactQr, 512)?.asImageBitmap()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(S.showQr(lang), style = Typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .glassPanel(corner = 18.dp)
                        .bounceClick { showQrZoom = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Search, contentDescription = S.zoomQr(lang), tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (showQrZoom && qrBitmap != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showQrZoom = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.94f))
                ) {
                    Text(
                        S.closeScanner(lang),
                        color = AccentLime,
                        style = Typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(20.dp)
                            .bounceClick { showQrZoom = false }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .aspectRatio(1f)
                            .align(Alignment.Center)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = qrBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(16.dp)
                    .bounceClick { showQrZoom = true },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    bitmap = qrBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val revision by AppWallpaper.revision.collectAsState()
    val savedOpacity by AppWallpaper.opacity.collectAsState()
    val draftBitmap by AppWallpaper.draftBitmap.collectAsState()
    val draftOpacity by AppWallpaper.draftOpacity.collectAsState()
    val scope = rememberCoroutineScope()
    val savedPreview = remember(revision) { AppWallpaper.loadBitmap(context) }
    val preview = draftBitmap ?: savedPreview
    val displayOpacity = (draftOpacity ?: savedOpacity).coerceIn(0f, 1f)
    val wallpaperDirty = draftBitmap != null ||
        (draftOpacity != null && draftOpacity != savedOpacity)
    var loading by remember { mutableStateOf(false) }
    var showSavedFlash by remember { mutableStateOf(false) }
    var vpsDraft by remember {
        mutableStateOf(
            run {
                com.blink.dtn.net.VpsConfig.init(context)
                com.blink.dtn.net.VpsConfig.baseUrl.value
            }
        )
    }

    LaunchedEffect(showSavedFlash) {
        if (showSavedFlash) {
            delay(2000)
            showSavedFlash = false
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AppWallpaper.loadDraftFromUri(context, uri) }
            loading = false
            if (!ok) {
                Toast.makeText(context, S.wallpaperError(lang), Toast.LENGTH_SHORT).show()
            }
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

    fun saveWallpaper() {
        if (!wallpaperDirty) return
        val ok = AppWallpaper.commitDraft(context)
        if (ok) {
            showSavedFlash = true
            Toast.makeText(context, S.wallpaperSaved(lang), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, S.wallpaperError(lang), Toast.LENGTH_SHORT).show()
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
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            if (wallpaperDirty || showSavedFlash) {
                Box(
                    modifier = Modifier
                        .bounceClick { saveWallpaper() }
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = S.save(lang),
                        tint = if (wallpaperDirty) AccentLime else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
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
        Text(S.vpsUrl(lang), style = Typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(S.vpsUrlHint(lang), style = Typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        BasicTextField(
            value = vpsDraft,
            onValueChange = { vpsDraft = it },
            textStyle = Typography.bodyMedium.copy(color = TextPrimary),
            singleLine = true,
            cursorBrush = SolidColor(TextPrimary),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(corner = 12.dp)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (vpsDraft.isEmpty()) {
                        Text("https://…", color = TextSecondary, style = Typography.bodyMedium)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        QqButton(onClick = {
            com.blink.dtn.net.VpsConfig.setBaseUrl(context, vpsDraft)
            Toast.makeText(context, S.vpsSaved(lang), Toast.LENGTH_SHORT).show()
        }) {
            Text(S.save(lang), color = TextPrimary, style = Typography.titleMedium)
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
                        .graphicsLayer { alpha = displayOpacity }
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
                    "${(displayOpacity * 100).toInt()}%",
                    style = Typography.labelSmall,
                    color = AccentLime
                )
            }
            Slider(
                value = displayOpacity,
                onValueChange = { AppWallpaper.setDraftOpacity(it) },
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
        QqButton(onClick = { if (!loading) openGallery() }) {
            Text(
                if (loading) "…" else S.chooseWallpaper(lang),
                color = TextPrimary,
                style = Typography.titleMedium
            )
        }
        if (savedPreview != null || draftBitmap != null) {
            Spacer(modifier = Modifier.height(12.dp))
            QqButton(onClick = {
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

internal fun peerListTitle(
    profile: com.blink.dtn.db.UserProfile?,
    fallback: String?,
    conversationId: String? = null
): String {
    fun humanOrUnknown(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return S.unknownContact(AppLang.lang.value)
        // Never show raw node id in the main dialog row.
        if (conversationId != null && value == conversationId) {
            return S.unknownContact(AppLang.lang.value)
        }
        return value
    }
    if (profile != null) {
        val alias = profile.localAlias.trim()
        if (alias.isNotEmpty()) return alias
        val nick = profile.nickname.trim()
        if (profile.isStranger) {
            return nick.ifEmpty { S.stranger(AppLang.lang.value) }
        }
        if (nick.isNotEmpty() && nick != profile.userId) return nick
        return humanOrUnknown(fallback)
    }
    return humanOrUnknown(fallback)
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
                    QqButton(onClick = {
                        onAccept()
                        onDismiss()
                    }) {
                        Text(S.addToContacts(lang), color = TextPrimary, style = Typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                QqButton(onClick = onRename) {
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
            .then(
                if (compact) Modifier
                else Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
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
                    subject = "Qq feedback",
                    body = S.feedbackBody(lang)
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        var showErrorReport by remember { mutableStateOf(false) }
        QqButton(onClick = { showErrorReport = true }) {
            Text(S.errorReportButton(lang), color = TextPrimary)
        }
        if (showErrorReport) {
            ErrorReportDialog(
                lang = lang,
                onDismiss = { showErrorReport = false }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(S.errorReportHint(lang), style = quietStyle, color = TextSecondary)

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
