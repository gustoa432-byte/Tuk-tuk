import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

# Replace ChatListScreen
cl_old = """@Composable
fun ChatListScreen(viewModel: BLinkViewModel) {
    val dialogs by viewModel.dialogs.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var searchId by remember { mutableStateOf("") }"""

cl_new = """@Composable
fun ChatListScreen(viewModel: BLinkViewModel) {
    val dialogs by viewModel.dialogs.collectAsState()
    val privateDialogs = dialogs.filter { it.conversationId != "general" }
    var showSearch by remember { mutableStateOf(false) }
    var searchId by remember { mutableStateOf("") }"""
content = content.replace(cl_old, cl_new)

# Replace dialogs with privateDialogs in LazyColumn
lc_old = """        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(dialogs) { conv ->
                ConversationItem(conv) {
                    viewModel.setCurrentDialog(conv.conversationId)
                }
            }
        }"""
lc_new = """        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(privateDialogs) { conv ->
                ConversationItem(conv) {
                    viewModel.setCurrentDialog(conv.conversationId)
                }
            }
        }"""
content = content.replace(lc_old, lc_new)

# Translate English texts in ChatListScreen
content = content.replace('Text("Search...",', 'Text("Поиск...",')
content = content.replace('Text("New Contact ID",', 'Text("ID контакта",')
content = content.replace('Text("Cancel",', 'Text("Отмена",')
content = content.replace('Text("Add",', 'Text("Добавить",')
content = content.replace('Text("No messages yet",', 'Text("Пока нет сообщений",')
content = content.replace('Text("You: ",', 'Text("Вы: ",')
content = content.replace('Text("Сообщение...", color = TextSecondary, style = Typography.bodyLarge)', 'Text("Сообщение...", color = TextSecondary, style = Typography.bodyLarge)')

# Update ShareTab
share_old = """@Composable
fun ShareTab() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Поделиться приложением", style = Typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Отправьте Tuk-Tuk тем, кто рядом, даже без интернета.",
            style = Typography.bodyMedium,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        TukTukButton(onClick = { shareApk(context) }) {
            Text("Отправить файл", color = TextPrimary, style = Typography.titleMedium)
        }
    }
}"""
share_new = """@Composable
fun ShareTab() {
    val context = LocalContext.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            Toast.makeText(context, "Scanned: " + result.contents, Toast.LENGTH_LONG).show()
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Поделиться приложением", style = Typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Отправьте Tuk-Tuk тем, кто рядом, даже без интернета.",
            style = Typography.bodyMedium,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        TukTukButton(onClick = { shareApk(context) }) {
            Text("Отправить файл", color = TextPrimary, style = Typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Или отсканируйте чужой QR:", style = Typography.titleSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        TukTukButton(onClick = { 
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Наведите камеру на QR код")
            options.setCameraId(0) // Use a specific camera of the device
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            scanLauncher.launch(options)
        }) {
            Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сканировать QR", color = TextPrimary, style = Typography.titleMedium)
        }
    }
}"""
content = content.replace(share_old, share_new)


# Update ProfileTab
profile_old = """@Composable
fun ProfileTab(viewModel: BLinkViewModel) {
    val clipboardManager = LocalClipboardManager.current
    var nickname by remember { mutableStateOf(viewModel.myNick) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
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
        Spacer(modifier = Modifier.height(48.dp))
        val qrBitmap = remember(viewModel.myNodeId) {
            generateQrCode(viewModel.myNodeId, 512)?.asImageBitmap()
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .bounceClick { clipboardManager.setText(AnnotatedString(viewModel.myNodeId)) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = viewModel.myNodeId, color = TextSecondary, style = Typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Share, contentDescription = "Copy", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}"""
profile_new = """@Composable
fun ProfileTab(viewModel: BLinkViewModel) {
    val clipboardManager = LocalClipboardManager.current
    var nickname by remember { mutableStateOf(viewModel.myNick) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
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
        Spacer(modifier = Modifier.height(48.dp))
        val qrBitmap = remember(viewModel.myNodeId) {
            generateQrCode(viewModel.myNodeId, 512)?.asImageBitmap()
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
        Text(
            "Ваш QR нужен для быстрого добавления контактов без интернета.",
            style = Typography.bodyMedium,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
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
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Share, contentDescription = "Copy", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}"""
content = content.replace(profile_old, profile_new)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
