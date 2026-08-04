# TukTuk VPS (Rust / Axum + libSQL)

Персистный store-and-forward для онлайн-пути роутера. Wire-совместим с Android `VpsBridge`
и прежним Python MVP (`server/vps/tuktuk_vps.py`).

## Стек

- **Axum** — HTTP
- **Tokio** — async runtime
- **libSQL** — локальный файл `tuktuk.db` (без внешнего СУБД-сервера)
- **serde / serde_json** — JSON

## Запуск

```bash
cd server/vps-rs
cargo run --release
```

Переменные окружения:

| Env | Default | Описание |
|-----|---------|----------|
| `TUKTUK_HOST` | `0.0.0.0` | bind host |
| `TUKTUK_PORT` | `8080` | bind port |
| `TUKTUK_DB` | `tuktuk.db` | путь к файлу libSQL |

В приложении: **Профиль → Настройки → VPS URL**, например `http://YOUR_IP:8080`.

## API

| Method | Path | Body / query |
|--------|------|----------------|
| POST | `/v1/register` | `{nodeId, nick, pubkey}` |
| GET | `/v1/directory` | → `{nodes:[{nodeId,nick,pubkey,seenAt}]}` |
| POST | `/v1/push` | `{envelopes:[{id,from,to,payloadB64,ts,kind}]}` |
| GET | `/v1/pull?nodeId=&since=` | → `{envelopes:[...]}` |
| GET | `/v1/health` | `{ok, nodes, envelopes}` |

## Схема БД

```sql
nodes(node_id PK, nick, pubkey, seen_at)
envelopes(id PK, sender_id, receiver_id, payload, kind, created_at)
```

`receiver_id` NULL / пустой / `*` = broadcast (как `to: "*"` у клиента).
