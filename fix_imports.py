with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    lines = f.readlines()

seen = set()
new_lines = []
for line in lines:
    if line.startswith("import "):
        if line in seen:
            continue
        seen.add(line)
    new_lines.append(line)

with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "w") as f:
    f.writelines(new_lines)
