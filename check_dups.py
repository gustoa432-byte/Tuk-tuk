with open("app/src/main/java/com/blink/dtn/ui/MainScreen.kt", "r") as f:
    lines = f.readlines()

imports = [l.strip() for l in lines if l.startswith("import ")]
from collections import Counter
counts = Counter(imports)
for k, v in counts.items():
    if v > 1:
        print(f"DUPLICATE: {k}")
