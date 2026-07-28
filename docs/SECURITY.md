# Security policy

## Reporting

Email: **tuktukfb@internet.ru**

Please include:

- Affected app version / git commit if known
- Device model + Android version
- Steps to reproduce
- Impact (e.g. decrypt private dialog, spoof identity, crash mesh)

We read all mail; replies may be delayed. Do not open public issues for unfixed critical crypto bugs if disclosure would harm users in the field.

## External review checklist

Auditors should prioritize:

1. **Crypto**
   - `crypto/RsaUtils.kt` — key generation, encrypt/decrypt, padding
   - `crypto/CryptoUtils.kt` — mesh envelope (passphrase / AES-GCM)
   - `crypto/NodeIdentity.kt` — node id derivation, QR binding
2. **Identity & trust**
   - Contact / stranger / block flows (`UserProfile.trustStatus`)
   - QR scan acceptance path
   - Nickname never treated as authentic identity
3. **BLE ingress**
   - `ble/BleIngressHandler.kt` — packet accept, ACK generation, duplicate/seen handling
   - Chunk reassembly (`BleChunkReassembler`) — size limits, resource exhaustion
   - Relay TTL / flood control (`BleRelayEngine`)
4. **Telemetry side-channels**
   - MessageTrace / Observatory export contents (may include peer MACs, route crumbs)
5. **Optional transports**
   - Wi‑Fi Direct (`transport/WifiDirectTransport.kt`) — experimental; must not weaken dialog E2E
   - Cellular / VK bridges — treat as untrusted relays

## Scope notes

- Public chat confidentiality is **out of scope** by design.
- Reproducible builds: see `docs/REPRODUCIBLE_BUILDS.md`.
- Threat model narrative: see `docs/THREAT_MODEL.md`.
