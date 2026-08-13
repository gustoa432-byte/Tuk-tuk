package com.blink.dtn.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.DividerColor
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import com.blink.dtn.ui.theme.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsSection {
    Hub,
    Account,
    VpsSignIn,
    Privacy,
    Notifications,
    Appearance,
    NetworkConfig,
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
        SettingsSection.Account -> SettingsAccountSection(
            viewModel = viewModel,
            onBack = { section = SettingsSection.Hub },
            onOpenVpsSignIn = { section = SettingsSection.VpsSignIn }
        )
        SettingsSection.VpsSignIn -> {
            val context = LocalContext.current
            val lang by AppLang.lang.collectAsState()
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsBackRow(S.vpsSignIn(lang)) { section = SettingsSection.Account }
                Box(modifier = Modifier.weight(1f)) {
                    AuthOnboardingScreen { displayName, nick, provider ->
                        viewModel.completeOnboarding(displayName, nick, provider)
                        Toast.makeText(context, S.vpsSignInOk(lang), Toast.LENGTH_SHORT).show()
                        section = SettingsSection.Account
                    }
                }
            }
        }
        SettingsSection.Privacy -> SettingsSimpleSection(
            title = { S.settingsPrivacy(it) },
            body = { S.settingsPrivacyBody(it) },
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Notifications -> SettingsSimpleSection(
            title = { S.settingsNotifications(it) },
            body = { S.settingsNotificationsBody(it) },
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Appearance -> SettingsAppearanceSection(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.NetworkConfig -> SettingsNetworkConfigSection(
            viewModel = viewModel,
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.About -> AboutTukTukScreen(
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.settings(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        SettingsNavRow(S.settingsAccount(lang)) { onOpen(SettingsSection.Account) }
        SettingsNavRow(S.settingsPrivacy(lang)) { onOpen(SettingsSection.Privacy) }
        SettingsNavRow(S.settingsNotifications(lang)) { onOpen(SettingsSection.Notifications) }
        SettingsNavRow(S.settingsAppearance(lang)) { onOpen(SettingsSection.Appearance) }
        SettingsNavRow(S.settingsNetwork(lang)) { onOpen(SettingsSection.NetworkConfig) }
        SettingsNavRow(S.settingsAbout(lang)) { onOpen(SettingsSection.About) }
    }
}

@Composable
fun AboutTukTukScreen(onBack: () -> Unit) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.aboutProject(lang), onBack)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Qq", style = Typography.titleLarge, color = TextPrimary)
        Text(S.slogan(lang), style = Typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("${S.versionLabel(lang)}: $version", color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        // compact=true → no nested verticalScroll (was collapsing About)
        InfoContent(compact = true)
        Spacer(modifier = Modifier.height(12.dp))
        SettingsNavRow(S.openGithub(lang)) { openUrl(context, TUKTUK_GITHUB_URL) }
        SettingsNavRow(S.openSource(lang)) { openUrl(context, TUKTUK_GITHUB_URL) }
        SettingsNavRow(S.supportProject(lang)) { openUrl(context, "https://t.me/tuk_tuk_official") }
        SettingsNavRow(S.projectHistory(lang)) { openUrl(context, TUKTUK_GITHUB_URL) }
        Text(
            S.licenseHint(lang),
            color = TextSecondary,
            style = Typography.labelSmall,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun SettingsAccountSection(
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
        SettingsBackRow(S.settingsAccount(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(S.settingsAccountBody(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
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
        Text(S.yourId(lang), color = TextSecondary, style = Typography.labelSmall)
        Text(
            viewModel.myNodeId,
            color = TextPrimary,
            style = Typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(S.vpsSessionLabel(lang), color = TextSecondary, style = Typography.labelSmall)
        Text(
            if (signedIn) S.vpsSessionOn(lang) else S.vpsSessionOff(lang),
            color = if (signedIn) AccentLime else TextSecondary,
            style = Typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsNavRow(S.vpsSignIn(lang), onOpenVpsSignIn)
    }
}

@Composable
private fun SettingsSimpleSection(
    title: (String) -> String,
    body: (String) -> String,
    onBack: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SettingsBackRow(title(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(body(lang), color = TextSecondary, style = Typography.bodyMedium)
    }
}

@Composable
private fun SettingsNetworkConfigSection(viewModel: BLinkViewModel, onBack: () -> Unit) {
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
    LaunchedEffect(showSaved) {
        if (showSaved) {
            delay(2000)
            showSaved = false
        }
    }
    val dutyPreset by com.blink.dtn.ble.MeshDutyPrefs.preset.collectAsState()
    LaunchedEffect(Unit) { com.blink.dtn.ble.MeshDutyPrefs.init(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsBackRow(S.settingsNetwork(lang), onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(S.settingsNetworkHint(lang), color = TextSecondary, style = Typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(S.deliveryServer(lang), color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(6.dp))
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
        TukTukButton(onClick = {
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
        Spacer(modifier = Modifier.height(24.dp))
        Text(S.networkMode(lang), color = TextSecondary, style = Typography.labelSmall)
        Spacer(modifier = Modifier.height(8.dp))
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
            style = Typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun SettingsAppearanceSection(onBack: () -> Unit) {
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
        Slider(
            value = displayOpacity,
            onValueChange = { AppWallpaper.setDraftOpacity(it) },
            colors = SliderDefaults.colors(
                thumbColor = AccentLime,
                activeTrackColor = AccentLime
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TukTukButton(onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(mediaPermission)
                } else picker.launch("image/*")
            }) {
                Text(if (loading) "…" else S.chooseWallpaper(lang), color = TextPrimary)
            }
            if (wallpaperDirty) {
                TukTukButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AppWallpaper.commitDraft(context) }
                        Toast.makeText(context, S.saved(lang), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(S.save(lang), color = AccentLime)
                }
            }
            TukTukButton(onClick = {
                AppWallpaper.discardDraft()
                AppWallpaper.clear(context)
            }) {
                Text(S.resetWallpaper(lang), color = TextSecondary)
            }
        }
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
