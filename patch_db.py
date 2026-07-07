import re

with open("app/src/main/java/com/blink/dtn/db/BLinkDao.kt", "r") as f:
    content = f.read()

old_insert = """            updateConversationInternal(conv)
        }
        
        insertMessage(msg)
    }"""
new_insert = """            updateConversationInternal(conv)
        }
        
        insertMessage(msg)
        android.util.Log.d("DB_INSERT", "ConversationId=${msg.conversationId} MessageId=${msg.id} Status=${msg.status}")
    }"""
content = content.replace(old_insert, new_insert)

with open("app/src/main/java/com/blink/dtn/db/BLinkDao.kt", "w") as f:
    f.write(content)
