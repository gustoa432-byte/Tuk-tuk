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

Ответ: `{ ok, userId, publicBleKey, authMethod, authId }` — клиент сохраняет `publicBleKey` локально для офлайн BLE.

## Схема

```
users(id, auth_method, auth_id, public_ble_key, created_at)
contacts(user_id_1, user_id_2, created_at)
email_otps(email, code, expires_at)
nodes / envelopes  — mesh S&F
```
