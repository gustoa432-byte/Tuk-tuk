# Threat model (TukTuk)

Humanitarian BLE mesh messenger. Assumptions below match the product as shipped.

## Assets

- Private dialog plaintext (should stay E2E between endpoints).
- Device RSA identity / keystore private key.
- Contact trust bindings (QR-pinned peers).
- Local message history on device storage.

## Trust zones

| Zone | Expectation |
|------|-------------|
| **Dialogs (PRIVATE)** | End-to-end: payload encrypted to recipient public key. Intermediate phones forward ciphertext. Delivery ACK confirms receipt at destination. |
| **Chats (PUBLIC)** | Open megaphone: any nearby mesh peer can read. Not confidential. |
| **Nickname** | Cosmetic label only — **not** a unique identity. Spoofable. Real identity is the node id derived from the RSA public key. |
| **QR contact** | Out-of-band trust: scanning pins / accepts a peer as CONTACT. Strangers can still message; UI marks them. |

## Adversaries

1. **Curious neighbor** — overhears BLE advertisements / public chat; should not read private dialogs.
2. **Malicious relay** — drops, delays, or floods packets; should not decrypt PRIVATE payloads without the recipient key.
3. **Physical device access** — can read Room DB / export traces if the phone is unlocked; OS keystore protects private key at rest (device-dependent).
4. **Network (internet bridge / VK)** — experimental / optional paths; treat as lower trust than on-device E2E.

## Non-goals (today)

- Anonymity against a global passive adversary.
- Protection if the recipient device is compromised.
- Production multi-hop **Wi‑Fi mesh** (Wi‑Fi Direct path is experimental).
- Perfect traffic-analysis resistance (sizes/timing of BLE writes are observable).

## Mitigations in code

- RSA keypair in Android Keystore; node id = hash of public key (`NodeIdentity`).
- PRIVATE encrypt-at-source in relay path; ACK updates `STATUS_DELIVERED`.
- Mesh envelope encryption (`CryptoUtils`) for wire framing — **not** a substitute for dialog E2E; review passphrase / key derivation in external audits.
- Seen-set / TTL to limit replay loops (not a cryptographic anti-replay for all cases).

## User guidance (RU UI)

- Личные диалоги — для чувствительного. Общий чат — как громкоговоритель.
- Ник можно подделать; сверяйте QR / id.
- Долгое нажатие на своё сообщение → отмена отправки; короткое нажатие → «путь» доставки.
