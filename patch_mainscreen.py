import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

# 1. Add InfoTab and DeveloperPanel imports
imports = """import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Check
"""
if "import androidx.compose.foundation.verticalScroll" not in content:
    content = content.replace("import androidx.compose.foundation.rememberScrollState", imports)

# 2. Add pointer input for dev panel
imports2 = """import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import android.content.Intent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
"""
if "import com.journeyapps.barcodescanner.ScanContract" not in content:
    content = content.replace("import androidx.compose.ui.input.pointer.pointerInput", imports2)

# 3. Modify MainScreen top bar to add click listener and Dev Panel state
main_screen_old = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BLinkViewModel) {
    var selectedTab by remember { mutableStateOf(1) }
    val peerCount by viewModel.peerCount.collectAsState()
    val vkActive by viewModel.vkActive.collectAsState()
    val relayActive by viewModel.relayActive.collectAsState()
    val isConnected = vkActive || peerCount > 0

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tuk-Tuk", style = Typography.titleLarge, color = TextPrimary)"""

main_screen_new = """@OptIn(ExperimentalMaterial3Api::class)
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
                                        if (clickCount >= 4) { // 5 clicks total (1 first + 4 fast)
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
                        Text("Tuk-Tuk", style = Typography.titleLarge, color = TextPrimary)"""
content = content.replace(main_screen_old, main_screen_new)

# 4. Update CustomBottomBar call and selectedTab handling
tab_old = """        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> PrivateTab(viewModel)
                1 -> PublicTab(viewModel) { contactId ->
                    viewModel.setCurrentDialog(contactId)
                    selectedTab = 0
                }
                2 -> ShareTab()
                3 -> ProfileTab(viewModel)
            }
        }"""
tab_new = """        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> PrivateTab(viewModel)
                1 -> PublicTab(viewModel) { contactId ->
                    viewModel.setCurrentDialog(contactId)
                    selectedTab = 0
                }
                2 -> InfoTab()
                3 -> ShareTab()
                4 -> ProfileTab(viewModel)
            }
        }"""
content = content.replace(tab_old, tab_new)

bottom_bar_old = """        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(Icons.Default.Email, "Чат", selectedTab == 1) { onTabSelected(1) }
            BottomBarItem(Icons.Default.Person, "Диалог", selectedTab == 0) { onTabSelected(0) }
            BottomBarItem(Icons.Default.Share, "Поделиться", selectedTab == 2) { onTabSelected(2) }
            BottomBarItem(Icons.Default.Settings, "Профиль", selectedTab == 3) { onTabSelected(3) }
        }"""
bottom_bar_new = """        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(Icons.Default.Person, "Диалоги", selectedTab == 0) { onTabSelected(0) }
            BottomBarItem(Icons.Default.Email, "Общий чат", selectedTab == 1) { onTabSelected(1) }
            BottomBarItem(Icons.Default.Info, "Инфо", selectedTab == 2) { onTabSelected(2) }
            BottomBarItem(Icons.Default.Share, "QR", selectedTab == 3) { onTabSelected(3) }
            BottomBarItem(Icons.Default.Settings, "Профиль", selectedTab == 4) { onTabSelected(4) }
        }"""
content = content.replace(bottom_bar_old, bottom_bar_new)

# 5. Add InfoTab and DevPanel at the bottom
extra_code = """
@Composable
fun InfoTab() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Что такое Tuk-Tuk", style = Typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Во время отключения связи я однажды не смог сообщить близкому человеку, что со мной всё в порядке.\\n\\nПоэтому появился Tuk-Tuk — простой мессенджер, который работает без интернета.",
            style = Typography.bodyMedium,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Возможности", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        val features = listOf("Общий чат", "Приватные сообщения", "Передача через BLE Mesh", "Работает без интернета")
        features.forEach { feature ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(feature, style = Typography.bodyMedium, color = TextSecondary)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Поддержать развитие", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Ваши пожертвования помогают:\\n✔ тестировать приложение\\n✔ покупать оборудование (LoRa)\\n✔ улучшать маршрутизацию\\n✔ выпускать новые версии",
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
            "Нашли ошибку?\\nЕсть идея?\\nХотите предложить улучшение?\\nНапишите автору:",
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
"""
content += extra_code

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
