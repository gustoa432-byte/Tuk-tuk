with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

content = content.replace(
    'val clipboardManager = LocalClipboardManager.current\n    var nickname',
    'val clipboardManager = LocalClipboardManager.current\n    val context = LocalContext.current\n    var nickname'
)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
