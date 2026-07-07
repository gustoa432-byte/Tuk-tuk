with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

# Pass viewModel to ShareTab
content = content.replace("3 -> ShareTab()", "3 -> ShareTab(viewModel, { selectedTab = 0 })")

old_sharetab_sig = "fun ShareTab() {"
new_sharetab_sig = "fun ShareTab(viewModel: BLinkViewModel, onScanSuccess: () -> Unit) {"
content = content.replace(old_sharetab_sig, new_sharetab_sig)

old_scan_toast = 'Toast.makeText(context, "Scanned: " + result.contents, Toast.LENGTH_LONG).show()'
new_scan_logic = """val scannedId = result.contents
            if (scannedId.isNotBlank()) {
                viewModel.setCurrentDialog(scannedId)
                onScanSuccess()
            }"""
content = content.replace(old_scan_toast, new_scan_logic)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
