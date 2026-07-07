import re

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    content = f.read()

old_chatlist_row = """                            if (timeString.isNotEmpty()) {
                                Text(text = timeString, color = TextSecondary, style = Typography.labelSmall)
                            }
                        }"""

new_chatlist_row = """                            Column(horizontalAlignment = Alignment.End) {
                                if (timeString.isNotEmpty()) {
                                    Text(text = timeString, color = TextSecondary, style = Typography.labelSmall)
                                }
                                if (dialog.unreadCount > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(TextPrimary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = dialog.unreadCount.toString(), color = BackgroundDark, style = Typography.labelSmall)
                                    }
                                }
                            }
                        }"""
content = content.replace(old_chatlist_row, new_chatlist_row)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.write(content)
