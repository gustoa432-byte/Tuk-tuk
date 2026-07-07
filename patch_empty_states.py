with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

# PrivateTab Empty State
empty_private = """
            if (privateDialogs.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("У вас пока нет диалогов.\\nНажмите 'Найти или начать диалог...', чтобы добавить контакт.", color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(privateDialogs) { dialog ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .bounceClick { viewModel.setCurrentDialog(dialog.conversationId) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = dialog.displayName ?: dialog.conversationId, color = TextPrimary, style = Typography.bodyLarge)
                                if (!dialog.lastMessage.isNullOrEmpty()) {
                                    Text(text = dialog.lastMessage, color = TextSecondary, style = Typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
"""
old_private_lazy = """            LazyColumn(modifier = Modifier.weight(1f)) {
                items(privateDialogs) { dialog ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .bounceClick { viewModel.setCurrentDialog(dialog.conversationId) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = dialog.displayName ?: dialog.conversationId, color = TextPrimary, style = Typography.bodyLarge)
                            if (!dialog.lastMessage.isNullOrEmpty()) {
                                Text(text = dialog.lastMessage, color = TextSecondary, style = Typography.bodyMedium)
                            }
                        }
                    }
                }
            }"""
content = content.replace(old_private_lazy, empty_private)


# PublicTab Empty State
empty_public = """
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("В общем чате пока тихо.\\nНапишите что-нибудь!", color = TextSecondary, style = Typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                items(messages.reversed()) { msg ->
                    MessageBubble(msg, viewModel.myNodeId, showSender = true, onSenderClick = onPrivateChatRequested)
                }
            }
        }
"""
old_public_lazy = """        LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { msg ->
                MessageBubble(msg, viewModel.myNodeId, showSender = true, onSenderClick = onPrivateChatRequested)
            }
        }"""
content = content.replace(old_public_lazy, empty_public)


# ConversationScreen Empty State
empty_conv = """
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Здесь пока нет сообщений.", color = TextSecondary, style = Typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                items(messages.reversed()) { msg ->
                    MessageBubble(msg, viewModel.myNodeId)
                }
            }
        }
"""
old_conv_lazy = """        LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { msg ->
                MessageBubble(msg, viewModel.myNodeId)
            }
        }"""
content = content.replace(old_conv_lazy, empty_conv)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
