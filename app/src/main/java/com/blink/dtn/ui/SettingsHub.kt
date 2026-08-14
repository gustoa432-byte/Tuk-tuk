package com.blink.dtn.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.blink.dtn.R
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.BackgroundDark
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings read like a plain messenger: language, sound, battery, look, and an
 * optional delivery server. Everything about the gateway account sits one level
 * below the server field, so a core user never meets it — but it still works.
 */
enum class SettingsSection {
    Hub,
    Sound,
    Battery,
    Appearance,
    DeliveryServer,
    GatewayAccount,
    VpsSignIn,
    About
}

@Composable
fun SettingsHub(
    onBack: () -> Unit,
    viewModel: BLinkViewModel
) {
    var section by remember { mutableStateOf(SettingsSection.Hub) }
    when (section) {
        SettingsSection.Hub -> SettingsHubList(
            onBack = onBack,
            onOpen = { section = it }
        )
        SettingsSection.Sound -> SettingsSoundSection(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Battery -> SettingsBatterySection(
            viewModel = viewModel,
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Appearance -> SettingsAppearanceSection(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.DeliveryServer -> SettingsDeliveryServerSection(
            onBack = { section = SettingsSection.Hub },
            onOpenAccount = { section = SettingsSection.GatewayAccount }
        )
        SettingsSection.GatewayAccount -> SettingsGatewayAccountSection(
            viewModel = viewModel,
            onBack = { section = SettingsSection.DeliveryServer },
            onOpenVpsSignIn = { section = SettingsSection.VpsSignIn }
        )
        SettingsSection.VpsSignIn -> {
            val context = LocalContext.current
            val lang by AppLang.lang.collectAsState()
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsBackRow(S.vpsSignIn(lang)) { section = SettingsSection.GatewayAccount }
                Box(modifier = Modifier.weight(1f)) {
                    AuthOnboardingScreen { displayName, nick, provider ->
                        viewModel.completeOnboarding(displayName, nick, provider)
                        Toast.makeText(context, S.vpsSignInOk(lang), Toast.LENGTH_SHORT).show()
                        section = SettingsSection.GatewayAccount
                    }
                }
            }
        }
        SettingsSection.About -> AboutQqScreen(
            onBack = { section = SettingsSection.Hub }
        )
    }
}

@Composable
private fun SettingsHubList(
    onBack: () -> Unit,
    onOpen: (SettingsSection) -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.settings(lang), onBack)
        Spacer(modifier = Modifier.height(16.dp))
        // Language is one tap — a whole sub-screen for it would be theatre.
        Text(S.langLabel(lang), color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ru" to "Русский", "en" to "English").forEach { (code, label) ->
                val selected = lang == code
                Box(
                    modifier = Modifier
                        .glassPanel(corner = 12.dp, strong = selected)
                        .background(if (selected) AccentLime.copy(alpha = 0.2f) else Color.Transparent)
                        .bounceClick { AppLang.set(context, code) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(label, color = if (selected) AccentLime else TextPrimary)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        SettingsNavRow(S.settingsSound(lang)) { onOpen(SettingsSection.Sound) }
        SettingsNavRow(S.networkMode(lang)) { onOpen(SettingsSection.Battery) }
        SettingsNavRow(S.settingsAppearance(lang)) { onOpen(SettingsSection.Appearance) }
        SettingsNavRow(S.deliveryServer(lang)) { onOpen(SettingsSection.DeliveryServer) }
        SettingsNavRow(S.settingsAbout(lang)) { onOpen(SettingsSection.About) }
    }
}

@Composable
private fun SettingsSoundSection(onBack: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { QqFeedbackPrefs.init(context) }
    val soundOn by QqFeedbackPrefs.sound.collectAsState()
    val vibrationOn by QqFeedbackPrefs.vibration.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.settingsSound(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(S.settingsSoundHint(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(20.dp))
        SettingsToggleRow(S.soundToggle(lang), soundOn, lang) {
            QqFeedbackPrefs.setSound(context, it)
        }
        SettingsToggleRow(S.vibrationToggle(lang), vibrationOn, lang) {
            QqFeedbackPrefs.setVibration(context, it)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    lang: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassPanel(corner = 14.dp)
            .bounceClick { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, style = Typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (checked) S.toggleOn(lang) else S.toggleOff(lang),
            color = if (checked) AccentLime else TextSecondary,
            style = Typography.labelLarge
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
fun AboutQqScreen(onBack: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    var showDeliveryHelp by remember { mutableStateOf(false) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }

    if (showDeliveryHelp) {
        DeliveryHelpDialog(onDismiss = { showDeliveryHelp = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.aboutProject(lang), onBack)
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.qq_logo),
            contentDescription = "Qq",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Qq", style = Typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("${S.versionLabel(lang)}: $version", color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        // compact=true → no nested verticalScroll (was collapsing About)
        InfoContent(compact = true)
        Spacer(modifier = Modifier.height(16.dp))
        Text(S.settingsPrivacy(lang), style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(S.settingsPrivacyBody(lang), style = Typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        SettingsNavRow(S.deliveryHelpTitle(lang)) { showDeliveryHelp = true }
        SettingsNavRow(S.openGithub(lang)) { openUrl(context, TUKTUK_GITHUB_URL) }
        SettingsNavRow(S.supportProject(lang)) { openUrl(context, "https://t.me/qqube_official") }
        Text(
            S.licenseHint(lang),
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/** Gateway account — one level below the server field, never on the main list. */
@Composable
private fun SettingsGatewayAccountSection(
    viewModel: BLinkViewModel,
    onBack: () -> Unit,
    onOpenVpsSignIn: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val signedIn = com.blink.dtn.auth.AuthSessionStore.hasVpsSession(context)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.settingsAdvanced(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(S.yourId(lang), color = TextSecondary, style = Typography.labelSmall)
        Text(
            viewModel.myNodeId,
            color = TextPrimary,
            style = Typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            if (signedIn) S.vpsSessionOn(lang) else S.vpsSessionOff(lang),
            color = if (signedIn) AccentLime else TextSecondary,
            style = Typography.bodySmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsNavRow(S.vpsSignIn(lang), onOpenVpsSignIn)
    }
}

@Composable
private fun SettingsDeliveryServerSection(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    var vpsDraft by remember {
        mutableStateOf(
            run {
                com.blink.dtn.net.VpsConfig.init(context)
                com.blink.dtn.net.VpsConfig.baseUrl.value
            }
        )
    }
    var showSaved by remember { mutableStateOf(false) }
    var usernameDraft by remember { mutableStateOf("") }
    var usernameBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signedIn = com.blink.dtn.auth.AuthSessionStore.hasVpsSession(context)
    val gatewayOn = com.blink.dtn.net.VpsConfig.isConfigured(context)
    LaunchedEffect(showSaved) {
        if (showSaved) {
            delay(2000)
            showSaved = false
        }
    }
    LaunchedEffect(signedIn, gatewayOn) {
        if (signedIn && gatewayOn) {
            com.blink.dtn.net.UsersApi(context).me().onSuccess { me ->
                if (me.username.isNotBlank() && usernameDraft.isBlank()) {
                    usernameDraft = me.username
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.deliveryServer(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(S.deliveryServerBody(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        BasicTextField(
            value = vpsDraft,
            onValueChange = { vpsDraft = it },
            singleLine = true,
            textStyle = Typography.bodyMedium.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(corner = 12.dp)
                        .padding(12.dp)
                ) {
                    if (vpsDraft.isEmpty()) {
                        Text(S.deliveryServerHint(lang), color = TextSecondary, style = Typography.bodySmall)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        QqButton(onClick = {
            com.blink.dtn.net.VpsConfig.setBaseUrl(context, vpsDraft.trim())
            showSaved = true
            Toast.makeText(context, S.deliveryServerSaved(lang), Toast.LENGTH_SHORT).show()
        }) {
            Text(S.save(lang), color = TextPrimary)
            if (showSaved) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Filled.Check, null, tint = AccentLime, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(S.usernameClaim(lang), color = TextPrimary, style = Typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(S.usernameClaimHint(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(12.dp))
        if (!gatewayOn) {
            Text(S.usernameNeedServer(lang), color = TextSecondary, style = Typography.bodySmall)
        } else if (!signedIn) {
            Text(S.usernameNeedSignIn(lang), color = TextSecondary, style = Typography.bodySmall)
        } else {
            BasicTextField(
                value = usernameDraft,
                onValueChange = { usernameDraft = it },
                singleLine = true,
                enabled = !usernameBusy,
                textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(corner = 12.dp)
                            .padding(12.dp)
                    ) {
                        if (usernameDraft.isEmpty()) {
                            Text(S.usernameHint(lang), color = TextSecondary, style = Typography.bodySmall)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            QqButton(onClick = {
                if (usernameBusy) return@QqButton
                usernameBusy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        com.blink.dtn.net.UsersApi(context).claim(usernameDraft)
                    }
                    usernameBusy = false
                    result.fold(
                        onSuccess = {
                            usernameDraft = it.username
                            Toast.makeText(context, S.usernameSaved(lang), Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { err ->
                            val text = when ((err as? com.blink.dtn.net.ApiException)?.message) {
                                "username_invalid" -> S.usernameInvalid(lang)
                                "username_taken" -> S.usernameTaken(lang)
                                "username_cooldown" -> S.usernameCooldown(lang)
                                else -> err.message ?: S.usernameInvalid(lang)
                            }
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }) {
                Text(S.save(lang), color = TextPrimary)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        SettingsNavRow(S.settingsAdvanced(lang), onOpenAccount)
    }
}

@Composable
private fun SettingsBatterySection(viewModel: BLinkViewModel, onBack: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val dutyPreset by com.blink.dtn.ble.MeshDutyPrefs.preset.collectAsState()
    LaunchedEffect(Unit) { com.blink.dtn.ble.MeshDutyPrefs.init(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.networkMode(lang), onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(S.networkModeHint(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.blink.dtn.ble.MeshDutyPreset.entries.forEach { p ->
                val selected = dutyPreset == p
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .glassPanel(corner = 12.dp, strong = selected)
                        .background(if (selected) AccentLime.copy(alpha = 0.22f) else Color.Transparent)
                        .bounceClick {
                            viewModel.setDutyPreset(p)
                            Toast.makeText(
                                context,
                                S.modeSet(lang, if (lang == "en") p.labelEn else p.labelRu),
                                Toast.LENGTH_SHORT
                            ).show()
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
                com.blink.dtn.ble.MeshDutyPreset.CROWD -> S.modeCrowd(lang)
                else -> S.modeBalance(lang)
            },
            color = TextSecondary,
            style = Typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun SettingsAppearanceSection(onBack: () -> Unit) {
    val context = LocalContext.current
    val lang by AppLang.lang.collectAsState()
    val revision by AppWallpaper.revision.collectAsState()
    val savedOpacity by AppWallpaper.opacity.collectAsState()
    val presetId by AppWallpaper.presetId.collectAsState()
    val draftBitmap by AppWallpaper.draftBitmap.collectAsState()
    val draftOpacity by AppWallpaper.draftOpacity.collectAsState()
    val scope = rememberCoroutineScope()
    val savedPreview = remember(revision) { AppWallpaper.loadBitmap(context) }
    val preview = draftBitmap ?: savedPreview
    val displayOpacity = (draftOpacity ?: savedOpacity).coerceIn(0f, 1f)
    val wallpaperDirty = draftBitmap != null ||
        (draftOpacity != null && draftOpacity != savedOpacity)
    var loading by remember { mutableStateOf(false) }
    val noneSelected = draftBitmap == null && presetId == null &&
        !AppWallpaper.hasCustom(context)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AppWallpaper.loadDraftFromUri(context, uri) }
            loading = false
            if (!ok) Toast.makeText(context, S.wallpaperError(lang), Toast.LENGTH_SHORT).show()
        }
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) picker.launch("image/*")
        else Toast.makeText(context, S.galleryDenied(lang), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.settingsAppearance(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(S.wallpaperPack(lang), color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            WallpaperPackTile(
                selected = noneSelected,
                onClick = { AppWallpaper.clear(context) },
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(S.wallpaperNone(lang), color = TextSecondary, style = Typography.labelSmall)
                }
            }
            AppWallpaper.PRESETS.forEach { preset ->
                WallpaperPackTile(
                    selected = draftBitmap == null && presetId == preset.id,
                    onClick = { AppWallpaper.applyPreset(context, preset.id) },
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        painter = painterResource(id = preset.resId),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        alpha = AppWallpaper.DEFAULT_OPACITY
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(S.wallpaperHint(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(S.wallpaper(lang), color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .glassPanel(corner = 14.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    alpha = displayOpacity
                )
            } else {
                Text(S.wallpaperNone(lang), color = TextSecondary)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(S.wallpaperOpacity(lang), color = TextSecondary, style = Typography.labelSmall)
        Text(
            "${(displayOpacity * 100).toInt()}%",
            color = AccentLime,
            style = Typography.labelSmall
        )
        Slider(
            value = displayOpacity,
            onValueChange = { AppWallpaper.setDraftOpacity(it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = AccentLime,
                activeTrackColor = AccentLime
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QqButton(onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(mediaPermission)
                } else picker.launch("image/*")
            }) {
                Text(if (loading) "…" else S.chooseWallpaper(lang), color = TextPrimary)
            }
            if (wallpaperDirty) {
                QqButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AppWallpaper.commitDraft(context) }
                        Toast.makeText(context, S.saved(lang), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(S.save(lang), color = AccentLime)
                }
            }
            QqButton(onClick = {
                AppWallpaper.discardDraft()
                AppWallpaper.clear(context)
            }) {
                Text(S.resetWallpaper(lang), color = TextSecondary)
            }
        }
    }
}

@Composable
private fun WallpaperPackTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) AccentLime else TextSecondary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            )
            .bounceClick(onClick)
    ) {
        content()
    }
}

@Composable
fun SettingsBackRow(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onBack)
            .padding(vertical = 4.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, style = Typography.titleLarge, color = TextPrimary)
    }
}

@Composable
fun SettingsNavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassPanel(corner = 14.dp)
            .bounceClick(onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, style = Typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}
