# Qq message router (Internet → BLE)

The client decides the path; the VPS (Internet Gateway) is an optional online hop. The gateway is used only when it is configured, the user has signed in, and it is reachable; without it the app still works over BLE/DTN. Private text is relayed as ciphertext, metadata is not hidden, and images are intended for the BLE path only — see [`PRIVACY.md`](PRIVACY.md).

> Historical note: this document was written when a Wi‑Fi Direct hop was also planned. That path was removed in 0.1.109 — see [`SECURITY.md`](SECURITY.md). UI details below reflect that era and may differ from the current app.

## Client

- [`MessageRouter`](../../app/src/main/java/com/blink/dtn/router/MessageRouter.kt) — chooses path, tracks active shipment
- [`VpsBridge`](../../app/src/main/java/com/blink/dtn/net/VpsBridge.kt) — register / push / pull
- Settings → **VPS URL**

## Server

See [`server/vps/README.md`](../vps/README.md).

## UI

- Network banner under the top bar
- Tab **Сеть**: stats, preferred route, current shipment tracker, neighbors
- Tap own bubble → delivery tracker with path strip
