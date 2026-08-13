# Qq message router (Internet → BLE)

The client decides the path; the VPS (Internet Gateway) is an optional online hop.

> Historical note: this document was written when a Wi‑Fi Direct hop was also planned. That path was removed in 0.1.109 — see `docs/SECURITY.md`.

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
