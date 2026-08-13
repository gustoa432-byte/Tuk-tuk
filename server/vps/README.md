# Qq VPS MVP (Internet Gateway)

Minimal store-and-forward for the online path of the message router. Paths, file and env names (`/opt/tuktuk`, `tuktuk.db`, `TUKTUK_*`) are internal technical names kept as-is.

## Recommended: Rust + libSQL (persistent)

```bash
# On VPS (preferred):
/opt/tuktuk/scripts/deploy-vps.sh

# Or local build:
cd server/vps-rs
cargo run --release
# TUKTUK_PORT=8080 TUKTUK_DB=./tuktuk.db cargo run --release
```

Env template: [`../vps-rs/tuktuk.env.example`](../vps-rs/tuktuk.env.example) → install as `/etc/tuktuk.env` (`chmod 600`).  
SSH hardening: [`../../docs/VPS_HARDENING.md`](../../docs/VPS_HARDENING.md).

See [`../vps-rs/README.md`](../vps-rs/README.md). Tables `nodes` / `envelopes` / `users` / `contacts` survive process restarts.

## Legacy: Python in-memory

```bash
python3 server/vps/tuktuk_vps.py --host 0.0.0.0 --port 8080
```

In-memory queue (last 5000 envelopes) — **lost on restart**. Prefer `vps-rs` on a real VPS.

In the app: **Profile → Settings → VPS URL**, e.g. `http://YOUR_IP:8080` (HTTPS recommended in production).

## API (both implementations)

| Method | Path | Body / query |
|--------|------|----------------|
| POST | `/v1/register` | `{nodeId, nick, pubkey}` |
| GET | `/v1/directory` | → `{nodes:[...]}` |
| POST | `/v1/push` | `{envelopes:[{id,from,to,payloadB64,ts,kind}]}` |
| GET | `/v1/pull?nodeId=&since=` | returns `{envelopes:[...]}` |
| GET | `/v1/health` | liveness |
