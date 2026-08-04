# TukTuk VPS (Rust / Axum + libSQL)

Store-and-forward mesh bridge + email/Telegram auth + online BLE-key contact handshake.

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
| `TUKTUK_SMTP_FROM` | — | From: `TukTuk <noreply@…>` |
| `TUKTUK_TELEGRAM_BOT_TOKEN` | — | для `/auth/telegram` |
| `TUKTUK_OTP_DEV_LOG` | `true` | без SMTP пишет OTP в лог (+ `devCode` в JSON) |

## API

### Mesh (Android `VpsBridge`)

| Method | Path |
|--------|------|
| GET | `/v1/health` |
| POST | `/v1/register` |
| GET | `/v1/directory` |
| POST | `/v1/push` |
| GET | `/v1/pull?nodeId=&since=` |

### Auth

| Method | Path | Body |
|--------|------|------|
| POST | `/auth/email/send` | `{ "email": "…" }` |
| POST | `/auth/email/verify` | `{ "email", "otp", "publicBleKey" }` → JWT |
| POST | `/auth/telegram` | `{ "initData", "publicBleKey" }` → JWT |

### Contacts (скрытое BLE-рукопожатие)

| Method | Path | Headers | Body |
|--------|------|---------|------|
| POST | `/contacts/add` | `Authorization: Bearer <jwt>` | `{ "userId": "<uuid>" }` |

Ответ: `{ ok, userId, publicBleKey, authMethod, authId, nodeId }` — клиент сохраняет `publicBleKey` локально для офлайн BLE.

JWT claims: `sub` (account UUID), `node_id` (mesh device id = Base32(SHA-256(DER pub)[0..10])), `public_ble_key`, …

### Oracle (social orbit → courier hints)

| Method | Path | Headers | Body |
|--------|------|---------|------|
| POST | `/v1/oracle/sync` | `Authorization: Bearer <jwt>` | `{ "orbits": [{ "target_node", "meet_count", "last_meet_at" }] }` |
| POST | `/v1/oracle/hint` | `Authorization: Bearer <jwt>` | `{ "target_node": "…" }` |

- sync: `source_node` = JWT `node_id`; upsert edges if incoming `meet_count` is greater
- hint: 1st-degree couriers, decay score, top-3 → `{ "recommended_couriers": [{ "node_id", "score" }] }`

## Схема

```
users(id, auth_method, auth_id, public_ble_key, created_at)
contacts(user_id_1, user_id_2, created_at)
email_otps(email, code, expires_at)
nodes / envelopes  — mesh S&F
oracle_nodes(node_id, last_seen_at)
oracle_edges(source_node, target_node, meet_count, last_meet_at)
```
