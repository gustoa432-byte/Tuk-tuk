with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

old_block = """@OptIn(ExperimentalMaterial3Api::class)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {"""

new_block = """@OptIn(ExperimentalMaterial3Api::class)
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
                    ) {"""

content = content.replace(old_block, new_block)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
