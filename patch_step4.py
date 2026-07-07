import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

old_chatlist_row = """                    items(privateDialogs) { dialog ->
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
                                Text(text = dialog.displayName ?: "Неизвестный контакт", color = TextPrimary, style = Typography.bodyLarge)
                                if (!dialog.lastMessage.isNullOrEmpty()) {
                                    Text(text = dialog.lastMessage, color = TextSecondary, style = Typography.bodyMedium)
                                }
                            }
                        }
                    }"""

new_chatlist_row = """                    items(privateDialogs) { dialog ->
                        val formatter = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
                        val timeString = if (dialog.lastTimestamp > 0) formatter.format(java.util.Date(dialog.lastTimestamp)) else ""

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .bounceClick { viewModel.setCurrentDialog(dialog.conversationId) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dialog.displayName ?: "Неизвестный контакт", 
                                    color = TextPrimary, 
                                    style = Typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (!dialog.lastMessage.isNullOrEmpty()) {
                                    Text(
                                        text = dialog.lastMessage, 
                                        color = TextSecondary, 
                                        style = Typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (timeString.isNotEmpty()) {
                                Text(text = timeString, color = TextSecondary, style = Typography.labelSmall)
                            }
                        }
                    }"""
content = content.replace(old_chatlist_row, new_chatlist_row)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
