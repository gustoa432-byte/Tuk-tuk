# Router contract checklist (Phase 6)

All user sends must go through `MessageRouter` first.

## Required order
1. Internet (VPS) when online + configured
2. Wi‑Fi Direct when group ready
3. BLE / people nearby
4. Store & Forward (local queue)

## Checks
- [x] UI has no transport picker for send path
- [x] `MessageRouter.tryAlternateTransports` / `decide` encodes the order
- [x] VPS bridge registers self and syncs directory (`/v1/directory`) into local contacts
- [x] Failed path does not show protocol errors to the user (human status only)
- [x] Network tab explains path in human words (`humanPathLabel`)

## Manual smoke
1. Send private with VPS configured → Router notes Internet path when reachable
2. Airplane / no VPS → path falls to people nearby / waiting
3. Online contact appears in dialogs after directory sync without QR
