import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

# 5. Remove ShareTab
sharetab_pattern = r"@Composable\s+fun ShareTab\(viewModel: BLinkViewModel, onScanSuccess: \(\) -> Unit\) \{.*?\}(?=\s*fun shareApk)"
content = re.sub(sharetab_pattern, "", content, flags=re.DOTALL)

# 6. Replace ProfileTab
old_profiletab = r"@Composable\s+fun ProfileTab\(viewModel: BLinkViewModel\) \{.*?\}(?=\s*fun generateQrCode)"
new_profiletab = """@Composable
fun ProfileTab(viewModel: BLinkViewModel, onScanSuccess: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var nickname by remember { mutableStateOf(viewModel.myNick) }
    
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scannedId = result.contents
            if (scannedId.isNotBlank()) {
                viewModel.setCurrentDialog(scannedId)
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
            options.setPrompt("Наведите камеру на QR код")
            options.setCameraId(0) // Use a specific camera of the device
            options.setBeepEnabled(false)
            options.setBarcodeImageEnabled(true)
            scanLauncher.launch(options)
        }) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сканировать QR", color = TextPrimary, style = Typography.titleMedium)
        }
    }
}"""
content = re.sub(old_profiletab, new_profiletab, content, flags=re.DOTALL)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
print("Step 2 done")
