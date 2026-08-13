# Privacy (Qq)

What the code actually does with data. No legal conclusions, no compliance claims — if something is missing or unfinished, it is marked as a gap.

Scope: the Qq Android client, plus what an operator of an Internet Gateway (`server/vps-rs`) can observe. The client and a gateway are separate things: a gateway can be run by anyone, and its retention and logging are decided by whoever runs it.

## On the device

- Messages, contacts, delivery state and local settings live in a Room database in app-private storage.
- The RSA keypair is generated on the device; the private key is kept in Android Keystore and is not uploaded. The node ID is derived from the public key.
- Contacts can be added fully offline by QR code. No address book upload, no contact discovery service is required.
- Blocking a contact is local to the device.
- Local diagnostics (message traces, error journal) are stored on the device and may contain peer identifiers and route information.

## No account needed

The app launches and works over BLE/DTN with no sign-in. There is no auth gate on start. Signing in (email one-time code or Telegram) is optional and only enables the internet delivery path.

## What a gateway operator can see

If you sign in and the internet path is used:

- **Metadata, always:** sender and recipient node IDs, timestamps, message sizes, and the fact that your device connected.
- **Text content:** relayed as ciphertext — the gateway does not hold the recipient's private key.
- **Images — the honest caveat:** images are intended to travel over BLE only. In earlier builds private images were sent to the gateway as unencrypted base64 JPEG. If you are on an older build, treat any image sent over the internet path as visible to the gateway operator.
- **Account data persists on the gateway:** the identifier you signed in with (email address or Telegram ID) and your public key, so that others can reach you over the internet path.
- Network-level data (IP address, connection times) is visible to any server you connect to, as with any internet service.

An observer physically nearby can see that BLE exchange is happening, along with timing and sizes. That is not hidden.

## Telemetry

Telemetry upload is **disabled by default** (`QQ_ALLOW_TELEMETRY_UPLOAD = false`). Diagnostics are user-initiated local exports: you export them and decide whether to share them. Exports can contain peer identifiers and route information, so review before sending.

## Known gaps (being worked on — do not assume these exist)

- **No in-app "delete all my data" action.** Clearing local data currently means uninstalling the app or clearing app data in Android settings.
- **No account-deletion API on the gateway.** Account data (sign-in identifier and public key) stays in the gateway database; there is no endpoint or in-app flow to remove it. Removal requires whoever operates that gateway to do it manually.
- Messages already handed to other devices or to a gateway cannot be recalled.
- Retention on a gateway is not defined by the client. Ask the operator of the gateway you use.

## Related

- [`SECURITY.md`](SECURITY.md) — reporting security issues, official-build integrity
- [`THREAT_MODEL.md`](THREAT_MODEL.md) — adversaries and non-goals
- [`../TERMS.md`](../TERMS.md) — terms of use (Russian)
