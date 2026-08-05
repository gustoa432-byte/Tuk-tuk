# Security policy

## Reporting

Email: **tuktukfb@internet.ru**

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
- What we *can* do: (1) treat **Play / release signing cert** as the publisher identity for installable updates; (2) require **developer-signed** `SYSTEM_ANNOUNCEMENT` / `VERSION_ANNOUNCEMENT` packets so a forged build cannot claim “official TukTuk news” without the author private key.

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
5. **Optional transports**
   - BLE mesh + optional VPS only (Wi‑Fi Direct / Event Anchor amputated in 0.1.109)
   - Cellular / VK bridges — treat as untrusted relays
6. **Updates**
   - Peer APK transfer signature check (`security/BuildIntegrity.kt`)
   - Author-signed announcements (`security/SecurityConfig.kt`)

## Scope notes

- Public chat confidentiality is **out of scope** by design.
- Reproducible builds: see `docs/REPRODUCIBLE_BUILDS.md`.
- Threat model narrative: see `docs/THREAT_MODEL.md`.
