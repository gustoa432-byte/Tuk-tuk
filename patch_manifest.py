import re

with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

activity_xml = """        <activity
            android:name=".ui.CustomScannerActivity"
            android:screenOrientation="portrait"
            android:stateNotNeeded="true"
            android:theme="@style/Theme.BLink" />"""

if "CustomScannerActivity" not in content:
    content = content.replace("</application>", activity_xml + "\n    </application>")
    with open("app/src/main/AndroidManifest.xml", "w") as f:
        f.write(content)
