import re

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'r') as f:
    content = f.read()

def replacer(match):
    tag = match.group(1)
    if 'BleTx' in tag or tag == 'TAG' or tag == '"BleTx"':
        if 'Exception writing characteristic' in match.group(0) or 'writeCharacteristic failed' in match.group(0) or 'Cascade cancelled chunk' in match.group(0) or 'Channel closed' in match.group(0):
            return match.group(0).replace(tag, '"BLE_TX"')
        if 'SecurityException starting' in match.group(0) or 'SecurityException stopping' in match.group(0) or 'startGattServer' in match.group(0) or 'startAdvertising' in match.group(0) or 'startScanning' in match.group(0) or 'stopScanning' in match.group(0):
            return match.group(0).replace(tag, '"ROUTE"')
        if 'DTN Relay: Processing message' in match.group(0):
            return match.group(0).replace(tag, '"ROUTE"')
        if 'Watchdog: timeout' in match.group(0):
            return match.group(0).replace(tag, '"BLE_TX"')
        if 'Successfully reassembled' in match.group(0) or 'Received packet' in match.group(0) or 'Error decoding' in match.group(0):
            return match.group(0).replace(tag, '"DTN"')
        if 'write request' in match.group(0):
            return match.group(0).replace(tag, '"BLE_RX"')
        if 'Advertise' in match.group(0) or 'Scan failed' in match.group(0):
            return match.group(0).replace(tag, '"ROUTE"')
    return match.group(0)

# Also fix safeEmit logs
content = re.sub(r'Log\.(d|i|w|e)\((TAG|"BleTx"|"BLE_TX"|"ROUTE"|"DTN"|"BLE_RX"), "(.*?)"\)', lambda m: m.group(0).replace('TAG', '"DTN"') if 'TAG' in m.group(0) else m.group(0), content)

with open('app/src/main/java/com/blink/dtn/ble/BleMeshManager.kt', 'w') as f:
    f.write(content)
