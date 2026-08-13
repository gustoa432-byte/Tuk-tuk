# Security policy

Qq is experimental software under development. It makes **no promise of absolute security** and is not an anonymity tool. Scope of this document: the Android client and the optional Internet Gateway.

## Reporting

Email: **tuktukfb@internet.ru** (mailbox predates the product rename and is still monitored)

Please include:

- Affected app version / git commit if known
- Device model + Android version
- Steps to reproduce
- Impact (e.g. decrypt private dialog, spoof identity, crash mesh)

We read all mail; replies may be delayed. Do not open public issues for unfixed critical crypto bugs if disclosure would harm users in the field.

## Ключ от сети / anti-fake APK (honest design)

**What we can and cannot do**

- Extracting or redistributing an APK cannot be made impossible. Anyone can install a fork.
- Mesh routing cannot stop forks from talking to each other.
- What we *can* do: (1) treat **Play / release signing cert** as the publisher identity for installable updates; (2) require **developer-signed** `SYSTEM_ANNOUNCEMENT` / `VERSION_ANNOUNCEMENT` packets so a forged build cannot claim “official Qq news” without the author private key.

**Official builds**

- Play Store (or a release keystore) signs the APK. Sideloaded peer updates are accepted only if `PackageManager` shows the **same signing certificate** as the currently installed app (`BuildIntegrity.apkMatchesInstalledSignature`).
- Debug builds intentionally show «Сборка: debug» in Profile and keep working.
- Release builds inject cert SHA-256 into `BuildConfig.EXPECTED_RELEASE_CERT_SHA256`; Profile then shows «Сборка: официальная подпись» when the installed APK matches.
- Short how-to: [OFFICIAL_BUILD.md](OFFICIAL_BUILD.md). Setup script: `scripts/setup-official-signing.sh`.

**Backup (сохрани офлайн)**

Не коммить в git:

- `app/release.keystore` / `*.keystore` / `*.jks`
- `keystore.properties`
- весь каталог `secrets/` (пароли, `author_private.pem`, бэкап)

Скопируй на офлайн носитель:

1. `app/release.keystore`
2. `keystore.properties` или `secrets/RELEASE_KEY_BACKUP.txt` (пароли + SHA-256 сертификата)
3. `secrets/author_private.pem`

Без бэкапа нельзя выпускать обновления с той же «официальной» подписью. Публичный ключ автора (`AUTHOR_PUBLIC_KEY` в коде) коммитить можно.

**Author key (`SecurityConfig.AUTHOR_PUBLIC_KEY`)**

- Used only for official-channel mesh announcements (not for chat E2E).
- While `AUTHOR_KEY_CONFIGURED` is false, unsigned/forged “official” announcements are **rejected**.
- Generate and install a real RSA-2048 public key (or run `./scripts/setup-official-signing.sh`):

```bash
mkdir -p secrets
openssl genrsa -out secrets/author_private.pem 2048
openssl rsa -in secrets/author_private.pem -pubout -outform DER | base64 -w0 > secrets/author_pub.b64
# paste into SecurityConfig.AUTHOR_PUBLIC_KEY, set AUTHOR_KEY_CONFIGURED = true
# cert SHA-256 is injected from the release keystore via BuildConfig when keystore.properties exists
# sign text: echo -n "$TEXT" | openssl dgst -sha256 -sign secrets/author_private.pem | base64 -w0
```

Keep `secrets/author_private.pem` offline; never ship it in the APK.

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
   - Upload is off by default (`QQ_ALLOW_TELEMETRY_UPLOAD = false`); exports are user-initiated and local
5. **Optional transports**
   - BLE mesh + optional Internet Gateway only (Wi‑Fi Direct / Event Anchor removed in 0.1.109)
   - Gateway image handling (see the image caveat below)
   - VK / cellular bridges — legacy code, not part of the product; treat as untrusted relays if enabled
6. **Updates**
   - Peer APK transfer signature check (`security/BuildIntegrity.kt`)
   - Author-signed announcements (`security/SecurityConfig.kt`)

## Scope notes

- The product is **1:1 private text messaging**. Public channel / public chat code still exists in the repository but is gated off in Core builds (`QQ_CORE_ONLY`), so public-chat confidentiality is not a product concern — do not report it as a product vulnerability, and do not rely on those paths.
- **Private text** is encrypted on the device; the Internet Gateway relays ciphertext for text and never holds the recipient's private key. Metadata (node IDs, timestamps, sizes) is visible to the gateway.
- **Image caveat (real, current):** images are intended for the BLE path only. In earlier builds private images were sent to the gateway as unencrypted base64 JPEG. Do not describe the gateway as "ciphertext only" without this exception. Details: [`PRIVACY.md`](PRIVACY.md).
- **Moderation reports** that carry decrypted message text are a gateway/operator feature, not normal client operation: they require sign-in and an explicit user action, and are being restricted further.
- Global ban-list synchronisation is **disabled** in Core builds; only local per-device blocking is effective.
- Known operational gap: after a device reboot or a Bluetooth off/on cycle the app may need to be reopened before mesh activity resumes.
- Reproducible builds: see [`REPRODUCIBLE_BUILDS.md`](REPRODUCIBLE_BUILDS.md).
- Threat model narrative: see [`THREAT_MODEL.md`](THREAT_MODEL.md). Data handling: [`PRIVACY.md`](PRIVACY.md).
