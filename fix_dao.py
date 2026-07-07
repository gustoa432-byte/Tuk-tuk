import re

with open("/app/applet/app/src/main/java/com/blink/dtn/db/BLinkDao.kt", "r") as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if line.strip().startswith("suspend fun ") or line.strip().startswith("fun "):
        if "{" in line or "insertMessageWithConversation" in line:
            new_lines.append(line)
        else:
            new_lines.append(line.replace("suspend fun", "abstract suspend fun").replace("fun get", "abstract fun get"))
    else:
        new_lines.append(line)

with open("/app/applet/app/src/main/java/com/blink/dtn/db/BLinkDao.kt", "w") as f:
    f.writelines(new_lines)
