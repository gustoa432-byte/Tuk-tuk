import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

old_infotab = r"@Composable\s+fun InfoTab\(\) \{.*?\}(?=\s*@Composable\s+fun DeveloperPanel)"
new_infotab = """@Composable
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
}"""

content = re.sub(old_infotab, new_infotab, content, flags=re.DOTALL)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
print("Step 3 done")
