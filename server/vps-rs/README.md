# Qq VPS / Internet Gateway (Rust / Axum + libSQL)

Store-and-forward mesh bridge + email/Telegram auth + online BLE-key contact handshake.

> **This server is optional.** The Qq client works fully offline over BLE/DTN with no account; a gateway only adds an internet delivery path. Whoever runs an instance decides its retention, logging and terms — the client cannot speak for that.
>
> What an operator can see: metadata for every envelope (sender/recipient `node_id`, timestamps, sizes) plus account rows (email address or Telegram ID, public key). Private **text** arrives as ciphertext. **Images:** intended to travel over BLE only — earlier client builds pushed private images here as unencrypted base64 JPEG, so historical rows may contain viewable image data. See [`../../docs/PRIVACY.md`](../../docs/PRIVACY.md).
>
> There is currently **no account-deletion endpoint**; removing a user's row is a manual operator action. Known gap.
>
> Paths, DB and env names (`/opt/tuktuk`, `tuktuk.db`, `TUKTUK_*`, `node.tuktuk.dev`) are internal technical names kept from the project's former name and are not renamed with the product.

## Стек

- Axum + Tokio
- libSQL (`tuktuk.db`)
- JWT (`jsonwebtoken`), SMTP (`lettre`), Telegram WebApp HMAC

## Запуск

```bash
cd server/vps-rs
cargo run --release
```

Прод-раскатка (VPS + systemd + Oracle smoke): см. [`docs/ORACLE_DEPLOY.md`](../../docs/ORACLE_DEPLOY.md).

```bash
# с ноутбука:
ssh -i ~/.ssh/id_ed25519 root@157.228.136.239 'bash -s' < scripts/deploy-vps.sh
```

### Env

| Env | Default | Описание |
|-----|---------|----------|
| `TUKTUK_HOST` | `0.0.0.0` | bind |
| `TUKTUK_PORT` | `8080` | bind |
| `TUKTUK_DB` | `tuktuk.db` | путь к libSQL |
| `TUKTUK_JWT_SECRET` | ephemeral UUID | секрет JWT (задай в проде) |
| `TUKTUK_SMTP_HOST` | — | SMTP host |
| `TUKTUK_SMTP_PORT` | `587` | STARTTLS |
| `TUKTUK_SMTP_USER` / `TUKTUK_SMTP_PASS` | — | SMTP auth |
| `TUKTUK_SMTP_FROM` | — | From: `Qq <noreply@…>` |
| `TUKTUK_TELEGRAM_BOT_TOKEN` | — | для `/auth/telegram` |
| `TUKTUK_OTP_DEV_LOG` | `true` | без SMTP пишет OTP в лог (+ `devCode` в JSON) |

## API

### Mesh (Android `VpsBridge`)

| Method | Path |
|--------|------|
| GET | `/v1/health` | — |
| POST | `/v1/register` | Bearer JWT (`node_id` claim) |
| GET | `/v1/directory` | — |
| POST | `/v1/push` | Bearer JWT (`from` = JWT node_id) |
| GET | `/v1/pull?nodeId=&since=` | Bearer JWT |

### Auth

| Method | Path | Body |
|--------|------|------|
| POST | `/auth/email/send` | `{ "email": "…" }` |
| POST | `/auth/email/verify` | `{ "email", "otp", "publicBleKey" }` → JWT |
| POST | `/auth/telegram` | `{ "initData", "publicBleKey" }` → JWT |
| POST | `/auth/refresh` | Bearer JWT + `{ "publicBleKey" }` → JWT с `node_id` (тихий апгрейд) |

### Contacts (скрытое BLE-рукопожатие)

| Method | Path | Headers | Body |
|--------|------|---------|------|
| POST | `/contacts/add` | `Authorization: Bearer <jwt>` | `{ "userId": "<uuid>" }` |

Ответ: `{ ok, userId, publicBleKey, authMethod, authId, nodeId }` — клиент сохраняет `publicBleKey` локально для офлайн BLE.

JWT claims: `sub` (account UUID), `node_id` (mesh device id = Base32(SHA-256(DER pub)[0..10])), `public_ble_key`, …

### Moderation (server-side / operator feature — not part of the product UI)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/v1/moderation/report` | Bearer JWT | `{ "reported_node_id", "decrypted_message_content" }` |
| GET | `/v1/moderation/blacklist` | — | JSON array of banned `node_id` |

Honest status of these two endpoints:

- `report` accepts **decrypted** message text. Nothing is uploaded automatically: it requires a signed-in account and an explicit user action, and the client side of this path is being restricted. Do not describe it as normal operation. An operator who enables it is choosing to store user-submitted plaintext (`reports` table) and is responsible for that.
- The **global ban-list is not a product feature**: blacklist synchronisation is disabled in the client's Core build (`QQ_CORE_ONLY`). Only local per-device blocking is effective. The endpoint remains here for server-side/experimental use.

Banned JWT `node_id` → **403** `node_banned` on `/v1/push`, `/v1/pull`, `/v1/register`, `/v1/oracle/sync`.

Tables: `banned_nodes(node_id, reason, banned_at)`, `reports(id, reporter_jwt, reported_node_id, message_content, created_at)`.

### Oracle (social orbit → courier hints) — legacy / experimental, not used by the product UI

| Method | Path | Headers | Body |
|--------|------|---------|------|
| POST | `/v1/oracle/sync` | `Authorization: Bearer <jwt>` | `{ "orbits": [{ "target_node", "meet_count", "last_meet_at" }] }` |
| POST | `/v1/oracle/hint` | `Authorization: Bearer <jwt>` | `{ "target_node": "…" }` |

- sync: `source_node` = JWT `node_id`; upsert edges if incoming `meet_count` is greater
- hint: 1st-degree couriers, decay score, top-3 → `{ "recommended_couriers": [{ "node_id", "score" }] }`
- retention: daily prune of `oracle_edges` with `last_meet_at` older than **30 days**

TLS: `scripts/setup-https.sh` (Nginx + Let's Encrypt). DB backup: `scripts/backup-tuktuk-db.sh` (cron via deploy).

## Схема

```
users(id, auth_method, auth_id, public_ble_key, created_at)
contacts(user_id_1, user_id_2, created_at)
email_otps(email, code, expires_at)
nodes / envelopes  — mesh S&F
oracle_nodes(node_id, last_seen_at)
oracle_edges(source_node, target_node, meet_count, last_meet_at)
```
