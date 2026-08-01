# TukTuk message router (Internet → Wi‑Fi Direct → BLE)

Product path from the Telegram-vision plan: TukTuk stays the brain; UX feels familiar; VPS is optional online hop.

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
