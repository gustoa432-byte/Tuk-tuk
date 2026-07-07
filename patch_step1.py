import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

# 1. Update CustomBottomBar inside MainScreen when statement
old_when = """        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> PrivateTab(viewModel)
                1 -> PublicTab(viewModel) { contactId ->
                    viewModel.setCurrentDialog(contactId)
                    selectedTab = 0
                }
                2 -> InfoTab()
                3 -> ShareTab(viewModel, { selectedTab = 0 })
                4 -> ProfileTab(viewModel)
            }
        }"""
new_when = """        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> PrivateTab(viewModel)
                1 -> PublicTab(viewModel) { contactId ->
                    viewModel.setCurrentDialog(contactId)
                    selectedTab = 0
                }
                2 -> InfoTab()
                3 -> ProfileTab(viewModel, { selectedTab = 0 })
            }
        }"""
content = content.replace(old_when, new_when)

# 2. Update CustomBottomBar Items
old_bottombar = """        Row(
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
new_bottombar = """        Row(
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
        }"""
content = content.replace(old_bottombar, new_bottombar)

# 3. Update ChatListScreen (hide public key)
old_chatlist_text = 'Text(text = dialog.displayName ?: dialog.conversationId, color = TextPrimary, style = Typography.bodyLarge)'
new_chatlist_text = 'Text(text = dialog.displayName ?: "Неизвестный контакт", color = TextPrimary, style = Typography.bodyLarge)'
content = content.replace(old_chatlist_text, new_chatlist_text)

# 4. Update ConversationScreen (hide public key)
old_conv_sig = """@Composable
fun ConversationScreen(viewModel: BLinkViewModel, contactId: String, onBack: () -> Unit) {
    val messages by viewModel.currentDialogMessages.collectAsState()
    var messageText by remember { mutableStateOf("") }
    
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {"""
new_conv_sig = """@Composable
fun ConversationScreen(viewModel: BLinkViewModel, contactId: String, onBack: () -> Unit) {
    val messages by viewModel.currentDialogMessages.collectAsState()
    val dialogs by viewModel.dialogs.collectAsState()
    val displayName = dialogs.find { it.conversationId == contactId }?.displayName ?: "Неизвестный контакт"
    var messageText by remember { mutableStateOf("") }
    
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {"""
content = content.replace(old_conv_sig, new_conv_sig)

old_conv_contactId = 'Text(text = contactId, style = Typography.titleMedium, color = TextPrimary)'
new_conv_displayName = 'Text(text = displayName, style = Typography.titleMedium, color = TextPrimary)'
content = content.replace(old_conv_contactId, new_conv_displayName)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)

print("Step 1 done")
