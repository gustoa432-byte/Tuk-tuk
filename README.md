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

1. You write a message to one contact. Qq encrypts the text for that contact on your device.
2. If an Internet Gateway is configured, you are signed in and it is reachable, the encrypted text is handed to the gateway for store-and-forward.
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
- **After a device reboot, or after turning Bluetooth off and on again, the app may need to be reopened** before it resumes advertising, scanning and carrying messages. This is a known limitation and is being worked on — until it is fixed, do not assume the mesh keeps running unattended across those events.

## Internet Gateway

The Internet Gateway is an **optional** store-and-forward server (`server/vps-rs`). The app ships with a default gateway address, and a different one can be set in settings.

- The internet path is only used after an optional sign-in (email one-time code or Telegram): the gateway's send and receive endpoints require a token.
- **Private text** is relayed as **ciphertext**; the gateway does not hold the recipient's private key.
- The gateway always sees **metadata**: sender and recipient node IDs, timestamps and sizes. Encryption of the text does not hide who talked to whom and when.
- **Images are intended to travel over BLE only.** In earlier builds private images were sent to the gateway as unencrypted base64 JPEG; on an older build, treat an image sent over the internet path as visible to the gateway operator. See [`docs/PRIVACY.md`](docs/PRIVACY.md).
- It is a convenience hop, not a requirement: with no gateway the app still works over BLE/DTN.
- Availability, retention and uptime of any gateway instance depend on whoever operates it — a gateway can be run by anyone, and its logging and retention are the operator's decision, not the client's.

## Privacy

Only technically verifiable statements are made here:

- **Private text messages** are encrypted end-to-end; encryption happens on the sending device, and the Internet Gateway relays that text as ciphertext.
- The RSA private key is generated and kept on the device (Android Keystore-backed) and is not uploaded.
- **Images are a documented exception:** they are meant for the BLE path only, and earlier builds uploaded private images to the gateway unencrypted. Do not treat images as protected on the internet path.
- Metadata is not hidden: a gateway operator sees sender/recipient node IDs, timestamps and sizes; account data (email address or Telegram ID, plus your public key) persists in the gateway database.
- The offline BLE/DTN mode does not require internet access or an account.
- Telemetry upload is disabled by default (`QQ_ALLOW_TELEMETRY_UPLOAD = false`); diagnostics can be exported locally by the user and shared manually if they choose.
- There is currently **no in-app "delete all my data"** and no account-deletion endpoint on the gateway. Known gap, being worked on.

What Qq does **not** claim: it is not an anonymity tool, it makes no promise of absolute security, it does not hide that BLE traffic and timing are observable to someone nearby, and it cannot protect messages once a participant's device itself is compromised. Full detail: [`docs/PRIVACY.md`](docs/PRIVACY.md), [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md), [`docs/SECURITY.md`](docs/SECURITY.md). Terms of use (Russian): [`TERMS.md`](TERMS.md).

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

- The **single source of truth for the current version** is `versionName` / `versionCode` in `app/build.gradle.kts`. Docs reference it instead of repeating it; if a document and the build file disagree, the build file wins.
- Version history: [`docs/VERSIONS.md`](docs/VERSIONS.md) (version labels are not semver — read that file before comparing tags). Release steps: [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md).
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
- `docs/` — build, signing, security, privacy, threat model and historical design notes

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
- Опциональный Internet Gateway (сервер store-and-forward) добавляет доставку через интернет; личный **текст** сервер получает шифротекстом. Метаданные (кто, кому, когда, размер) при этом видны оператору шлюза.
- **Изображения** рассчитаны только на путь по BLE: в ранних сборках они уходили на шлюз незашифрованными — не считайте картинки защищёнными на интернет-пути.
- Ключи создаются и остаются на устройстве; контакты добавляются QR-кодом полностью офлайн.
- Статус «доставлено» ставится только по сквозному подтверждению от получателя.
- Ограничения: поведение BLE зависит от устройства и версии Android, доставка может задерживаться и не гарантирована, релей требует физической близости устройств, шлюз опционален. После перезагрузки устройства или выключения/включения Bluetooth приложение может потребоваться открыть заново (известное ограничение, в работе). Проект экспериментальный и в разработке.
- Что с данными: [`docs/PRIVACY.md`](docs/PRIVACY.md). Условия использования: [`TERMS.md`](TERMS.md).

## License

MIT — see [`LICENSE`](LICENSE).

### Notice on the project name

**TukTuk is the historical name of this project; Qq is the current product name.** They refer to the same software and the same authors.

The copyright line in [`LICENSE`](LICENSE) reads "TukTuk contributors" and has deliberately been left untouched: the rightsholder designation in a licence is the copyright owner's decision, not a rename to be applied mechanically. Treat "TukTuk contributors" as referring to the contributors of this project, now published as Qq.

The same applies to technical identifiers that still carry the old names and are **not** product branding: the application ID `com.blink.dtn`, `BLink*` class names, the `blink_prefs` preferences file, database names, keystore aliases, the certificate CN `TukTuk Official`, the gateway's `TUKTUK_*` environment variables, `/opt/tuktuk`, `tuktuk.service`, `tuktuk.db`, the host `node.tuktuk.dev`, and the repository URL (`Tuk-tuk`). Renaming any of these would change identity, break updates, or break deployments, so they stay as they are.
