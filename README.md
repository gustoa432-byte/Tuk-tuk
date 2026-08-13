# Qq

Qq is a person-to-person messenger designed to keep messages moving when normal internet connectivity is unavailable.

An experimental Android app for 1:1 text messaging over Bluetooth Low Energy (BLE) and store-carry-forward (DTN) between nearby devices, with an optional internet transport when a network is available.

## What is Qq?

Qq is a small, specialized tool, not a platform:

- **1:1 text messages only.** There are no public feeds or group broadcast surfaces in the product interface.
- **Works without an account.** The app starts and can send and receive over BLE/DTN without any sign-in.
- **Optional internet transport.** Signing in to an Internet Gateway (email code or Telegram) is optional and only adds the internet delivery path.
- **Local-first.** Messages, contacts and delivery state live in a Room database on the device.

Qq is not a social platform and not a replacement for cloud messengers. It is a fallback channel for situations where the usual infrastructure is degraded or missing.

## How it works

```
Qq → 1:1 encrypted messages → BLE/DTN → nearby Qq devices can carry messages
                                      → optional Internet Gateway delivers when internet is available
```

1. You write a message to one contact. Qq encrypts it for that contact on your device.
2. If an Internet Gateway is configured and reachable, the ciphertext is handed to the gateway for store-and-forward.
3. Otherwise the message is queued locally and offered over BLE to nearby Qq devices.
4. A nearby device may accept the message and carry it further (store-carry-forward) until it reaches the recipient, or until an internet path becomes available.
5. The recipient's device decrypts the message and returns an end-to-end acknowledgement.

The user does not pick a transport manually; the app tries the available paths.

## Offline messaging

Offline mode uses BLE advertising, scanning and GATT connections between phones running Qq. No internet, SIM card or account is required for this path.

Limitations to be aware of:

- **BLE behaviour depends on Android version, device vendor, background restrictions and granted permissions.** Scanning and advertising can be throttled or stopped by the system.
- **Relaying requires physical proximity.** A message can only move to a device that comes within BLE range.
- **Delivery may be delayed.** Store-carry-forward delivery is not instant and is not guaranteed: if no suitable device is ever met, the message stays queued.
- Offline text messages are short by design (a per-message length limit is enforced on the mesh path).

## Internet Gateway

The Internet Gateway is an **optional** store-and-forward server (`server/vps-rs`). The app ships with a default gateway address, and a different one can be set in settings.

- The internet path is only used after an optional sign-in (email one-time code or Telegram): the gateway's send and receive endpoints require a token.
- For private messages the gateway receives and relays **ciphertext**; it does not hold the recipient's private key.
- It is a convenience hop, not a requirement: with no gateway the app still works over BLE/DTN.
- Availability, retention and uptime of any gateway instance depend on whoever operates it.

## Privacy

Only technically verifiable statements are made here:

- Messages between users are encrypted end-to-end; encryption happens on the sending device.
- The RSA private key is generated and kept on the device (Android Keystore-backed) and is not uploaded.
- For private messages, the Internet Gateway receives ciphertext.
- The offline BLE/DTN mode does not require internet access or an account.
- Telemetry upload is disabled by default (`QQ_ALLOW_TELEMETRY_UPLOAD = false`); diagnostics can be exported locally by the user and shared manually if they choose.

What Qq does **not** claim: it is not an anonymity tool, it does not hide that BLE traffic and timing are observable to someone nearby, and it cannot protect messages once a participant's device itself is compromised. See [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) and [`docs/SECURITY.md`](docs/SECURITY.md).

## Contact exchange

Identity is a keypair, not a login:

- The device generates an RSA keypair; the node ID is derived from the public key.
- Contacts are exchanged **fully offline via QR code**. The QR payload (`ContactQr`) carries a format version, the node ID, the public key, and optionally a nickname and avatar.
- Manual entry of a node ID is also possible.
- A nickname is a cosmetic label and can be reused by anyone; the node ID and public key are the identity that matters.

## Delivery

Delivery state is reported honestly, without pretending a message arrived:

| State | Meaning |
|-------|---------|
| queued | stored on your device, waiting for a path |
| sending | an attempt is in progress |
| carried by another Qq device | handed to a neighbouring device or gateway hop; **not** end-to-end delivery |
| delivered | an end-to-end acknowledgement from the recipient was received |
| failed / waiting for key | the attempt failed, or the recipient's public key is not known yet |

Your phone can also carry messages for other people. A small indicator shows roughly how many are currently being carried: 0, 1–3, 4–9, or 10+.

## Development status

Work in progress and experimental. Interfaces, storage format and wire protocol may change between versions, and behaviour varies across devices.

- Version history: [`docs/VERSIONS.md`](docs/VERSIONS.md) (version labels are not semver — read that file before comparing tags)
- Releases: https://github.com/gustoa432-byte/Tuk-tuk/releases/latest (repository URL predates the rename)
- Legacy and experimental code from earlier phases is still present in the repository but is disabled in the shipped product (see `QqLegacyQuarantine` and the `QQ_CORE_ONLY` build flag). Documents under `docs/` that describe those earlier concepts are kept for history and marked as historical.

## Architecture

Android app (Kotlin, Jetpack Compose) plus an optional Rust gateway.

- `app/` — Android client. Internal identifiers keep the historical names: the application ID is `com.blink.dtn` and the foreground mesh service class is `BLinkMeshService`. These are internal names, not product names.
- `app/.../ble/` — BLE advertising, scanning, GATT ingress, chunk reassembly, relay/TTL handling
- `app/.../crypto/` — RSA keys, node ID derivation, message encryption
- `app/.../db/` — Room entities (messages, contacts, delivery state)
- `app/.../router/` — chooses between the internet path and the BLE/DTN path
- `server/vps-rs/` — optional Internet Gateway (Rust, Axum, libSQL): store-and-forward plus sign-in
- `docs/` — build, signing, security, threat model and historical design notes

### Build

Requires Android SDK and JDK 17.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Output APKs are written to `app/build/outputs/apk/`. `assembleRelease` fails without signing configuration; see [`docs/SIGNING.md`](docs/SIGNING.md), [`docs/OFFICIAL_BUILD.md`](docs/OFFICIAL_BUILD.md) and [`docs/REPRODUCIBLE_BUILDS.md`](docs/REPRODUCIBLE_BUILDS.md). Gateway deployment: [`docs/ORACLE_DEPLOY.md`](docs/ORACLE_DEPLOY.md).

## По-русски (кратко)

Qq — мессенджер один-на-один, задача которого — доставлять сообщения, когда обычного интернета нет.

- Личные текстовые сообщения, без публичных лент.
- Работает без аккаунта: офлайн-путь через BLE/DTN между устройствами рядом.
- Опциональный Internet Gateway (сервер store-and-forward) добавляет доставку через интернет; для личных сообщений сервер получает шифротекст.
- Ключи создаются и остаются на устройстве; контакты добавляются QR-кодом полностью офлайн.
- Статус «доставлено» ставится только по сквозному подтверждению от получателя.
- Ограничения: поведение BLE зависит от устройства и версии Android, доставка может задерживаться и не гарантирована, релей требует физической близости устройств. Проект в разработке.

## License

MIT — see [`LICENSE`](LICENSE).
