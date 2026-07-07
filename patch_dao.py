import re

with open('app/src/main/java/com/blink/dtn/db/BLinkDao.kt', 'r') as f:
    content = f.read()

new_method = """
    @Query("SELECT * FROM messages WHERE status IN (0, 1) ORDER BY timestamp ASC")
    abstract suspend fun getQueuedMessages(): List<Message>
"""
if "getQueuedMessages" not in content:
    content = content.replace("abstract suspend fun getPendingMessages(): List<Message>", "abstract suspend fun getPendingMessages(): List<Message>\n" + new_method)
    
with open('app/src/main/java/com/blink/dtn/db/BLinkDao.kt', 'w') as f:
    f.write(content)
