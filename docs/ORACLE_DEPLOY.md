# TukTuk VPS — раскатка release (mesh + Oracle)

Host: `node.tuktuk.dev` / `157.228.136.239` · Ubuntu 24.04 · бинарь systemd, **не Docker**.

## Release-сборка

В `server/vps-rs/Cargo.toml` уже включён release-профиль:

```toml
[profile.release]
lto = true
codegen-units = 1
strip = true
```

На сервере:

```bash
cd /opt/tuktuk/server/vps-rs
source ~/.cargo/env
cargo build --release
# → target/release/tuktuk-vps
```

Локально на Windows MSVC/`link.exe` для libsql обычно нет — **собирай на VPS**.

## Файлы конфигурации

| Путь | Назначение |
|------|------------|
| `/etc/tuktuk.env` | секреты и bind (`chmod 600`) |
| `server/vps-rs/tuktuk.env.example` | шаблон (в git) |
| `server/vps-rs/tuktuk.service` | unit-шаблон (в git) |
| `/etc/systemd/system/tuktuk.service` | установленный unit |
| `/opt/tuktuk/tuktuk.db` | libSQL (mesh + `oracle_*`) |

Ключевые переменные:

```bash
TUKTUK_HOST=0.0.0.0
TUKTUK_PORT=8080
TUKTUK_DB=/opt/tuktuk/tuktuk.db
TUKTUK_JWT_SECRET=<openssl rand -hex 32>
# + SMTP / Telegram по необходимости
TUKTUK_OTP_DEV_LOG=false   # в проде со SMTP
```

`EnvironmentFile=/etc/tuktuk.env` прокидывает их в процесс. Смена `TUKTUK_JWT_SECRET` инвалидирует все JWT (нужен re-login на клиентах, чтобы появился claim `node_id`).

## Пошаговый флоу (первый раз или обновление)

### 0. SSH

```bash
ssh -i ~/.ssh/id_ed25519 root@157.228.136.239
```

### 1. Репозиторий (если ещё нет)

```bash
mkdir -p /opt/tuktuk
cd /opt/tuktuk
git clone https://github.com/gustoa432-byte/Tuk-tuk.git .
# или: git remote add … && git fetch
```

### 2. Env

```bash
cp /opt/tuktuk/server/vps-rs/tuktuk.env.example /etc/tuktuk.env
chmod 600 /etc/tuktuk.env
# отредактируй JWT / SMTP / TG
nano /etc/tuktuk.env
# если JWT ещё CHANGE_ME:
sed -i "s/^TUKTUK_JWT_SECRET=.*/TUKTUK_JWT_SECRET=$(openssl rand -hex 32)/" /etc/tuktuk.env
```

### 3. One-shot деплой (рекомендуется)

С ноутбука:

```bash
ssh -i ~/.ssh/id_ed25519 root@157.228.136.239 'bash -s' < scripts/deploy-vps.sh
```

Или на сервере:

```bash
bash /opt/tuktuk/scripts/deploy-vps.sh
```

Скрипт: `git pull master` → merge env keys → install systemd unit → `cargo build --release` → `systemctl restart tuktuk` → smoke (`/v1/health`, `/v1/oracle/sync` без JWT → 401).

### 4. Ручной путь (если без скрипта)

```bash
cd /opt/tuktuk && git fetch && git reset --hard origin/master
cp server/vps-rs/tuktuk.service /etc/systemd/system/tuktuk.service
# поправь EnvironmentFile при необходимости
systemctl daemon-reload
cd server/vps-rs && cargo build --release
systemctl enable --now tuktuk
systemctl restart tuktuk
systemctl status tuktuk --no-pager
```

### 5. Проверка Oracle

```bash
curl -sS http://127.0.0.1:8080/v1/health
# без JWT — 401:
curl -sS -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8080/v1/oracle/sync \
  -H 'Content-Type: application/json' -d '{"orbits":[]}'
curl -sS -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8080/v1/oracle/hint \
  -H 'Content-Type: application/json' -d '{"target_node":"X"}'
```

С валидным JWT (после `/auth/email/verify` или Telegram):

```bash
TOKEN='…'
curl -sS -X POST http://127.0.0.1:8080/v1/oracle/sync \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"orbits":[{"target_node":"PEERNODEID123456","meet_count":3,"last_meet_at":1715000000}]}'

curl -sS -X POST http://127.0.0.1:8080/v1/oracle/hint \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"target_node":"MAMANODEID000000"}'
```

### 6. Логи / откат

```bash
journalctl -u tuktuk -f
# откат кода:
cd /opt/tuktuk && git reset --hard <good-sha>
bash /opt/tuktuk/scripts/deploy-vps.sh
```

База `tuktuk.db` при обновлении **не** удаляется: `CREATE TABLE IF NOT EXISTS` добавит `oracle_*`.

## Firewall / доступ

- Порт `8080` уже открыт под Android VPS URL (`http://157.228.136.239:8080`).
- Снаружи не светить `/etc/tuktuk.env`.
- При желании — nginx TLS на `node.tuktuk.dev` → `127.0.0.1:8080` (отдельный шаг).

## Не используем Docker в v1

Текущий прод — **systemd + бинарь**. Контейнеры не нужны для этого эпика; при появлении требования можно добавить позже без смены API.
