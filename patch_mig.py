with open("app/src/main/java/com/blink/dtn/db/BLinkDatabase.kt", "r") as f:
    text = f.read()

import re

new_mig = """
                    SELECT 
                        CASE 
                            WHEN type = 'PUBLIC' THEN 'general'
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderId END)
                        END as conversationId,
                        CASE 
                            WHEN type = 'PUBLIC' THEN NULL
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderId END)
                        END as peerId,
                        CASE 
                            WHEN type = 'PUBLIC' THEN 'General Chat'
                            ELSE (CASE WHEN is_mine = 1 THEN targetId ELSE senderNick END)
                        END as displayName,
                        text as lastMessage,
                        MAX(timestamp) as lastTimestamp,
                        0 as unreadCount
                    FROM messages
                    GROUP BY 1
"""
text = re.sub(r"SELECT\s+CASE[\s\S]+?HAVING timestamp = max\(timestamp\)", new_mig.strip(), text)

with open("app/src/main/java/com/blink/dtn/db/BLinkDatabase.kt", "w") as f:
    f.write(text)
