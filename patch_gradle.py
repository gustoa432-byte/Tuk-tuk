with open("/app/applet/app/build.gradle.kts", "r") as f:
    text = f.read()

if "room.schemaLocation" not in text:
    text += '\n\nksp {\n    arg("room.schemaLocation", "$projectDir/schemas")\n}\n'

with open("/app/applet/app/build.gradle.kts", "w") as f:
    f.write(text)
