# Threat model (Qq)

BLE mesh / DTN messenger. Assumptions below describe the transport and crypto surface; the shipped product UI is 1:1 private messaging only (public-chat code paths are gated off in Core builds via `QQ_CORE_ONLY`).

Qq is experimental and promises neither absolute security nor guaranteed delivery. Data-handling detail: [`PRIVACY.md`](PRIVACY.md).

## Assets

- Private dialog plaintext (should stay E2E between endpoints).
- Device RSA identity / keystore private key.
- Contact trust bindings (QR-pinned peers).
- Local message history on device storage.

## Trust zones

| Zone | Expectation |
|------|-------------|
| **Dialogs (PRIVATE) — text** | End-to-end: text payload encrypted to the recipient public key. Intermediate phones and the gateway forward ciphertext. Delivery ACK confirms receipt at destination. |
| **Dialogs (PRIVATE) — images** | **Weaker, by implementation.** Images are intended for the BLE path only; earlier builds uploaded private images to the gateway as unencrypted base64 JPEG. Do not model images as confidential on the internet path. |
| **Internet Gateway** | Semi-trusted hop, optional. Sees metadata (node IDs, timestamps, sizes, IP) and holds account data (email / Telegram ID, public key). Operated by a third party whose retention and logging are outside the client's control. |
| **Chats (PUBLIC)** | **Not a product surface.** The code exists but is gated off in Core builds; confidentiality of that path is out of scope and was never claimed. Legacy only. |
| **Nickname** | Cosmetic label only — **not** a unique identity. Spoofable. Real identity is the node id derived from the RSA public key. |
| **QR contact** | Out-of-band trust: scanning pins / accepts a peer as CONTACT. Strangers can still message; UI marks them. |

## Adversaries

1. **Curious neighbor** — overhears BLE advertisements and exchange timing; should not read private dialog text.
2. **Malicious relay** — drops, delays, or floods packets; should not decrypt private text payloads without the recipient key. Note that a relay can always refuse to carry a message: delivery is not guaranteed.
3. **Physical device access** — can read Room DB / export traces if the phone is unlocked; OS keystore protects private key at rest (device-dependent).
4. **Gateway operator** — sees metadata and account data; sees image content on the internet path in older builds. Avoidable by not signing in (the BLE path does not need an account).
5. **Network (internet bridge / VK)** — optional and legacy paths; treat as lower trust than on-device E2E.

## Non-goals (today)

- Anonymity against a global passive adversary.
- Protection if the recipient device is compromised.
- Metadata privacy against a gateway operator.
- Confidentiality of images over the internet path (see trust zones above).
- Confidentiality of the legacy public-chat code paths (not a product surface).
- Production multi-hop **Wi‑Fi mesh** (Wi‑Fi Direct path was removed in 0.1.109).
- Perfect traffic-analysis resistance (sizes/timing of BLE writes are observable).
- Guaranteed or timely delivery of any message.

## Mitigations in code

- RSA keypair in Android Keystore; node id = hash of public key (`NodeIdentity`).
- PRIVATE encrypt-at-source in relay path; ACK updates `STATUS_DELIVERED_ACK` (e2e only). GATT success is `STATUS_STORED_IN_NEIGHBOR`.
- Mesh envelope encryption (`CryptoUtils`) for wire framing — **not** a substitute for dialog E2E; review passphrase / key derivation in external audits.
- Seen-set / TTL to limit replay loops (not a cryptographic anti-replay for all cases).

## User guidance (RU UI)

- Личные текстовые сообщения шифруются на устройстве. Публичных каналов в продукте нет.
- Картинки рассчитаны на путь по BLE; на интернет-пути не считайте их защищёнными.
- Ник можно подделать; сверяйте QR / id.
- Долгое нажатие на своё сообщение → отмена отправки; короткое нажатие → «путь» доставки.
- «Доставлено» появляется только по сквозному подтверждению; всё остальное — не доставка.
