# TukTuk VPS MVP

Minimal store-and-forward for the online path of the message router.

```bash
python3 server/vps/tuktuk_vps.py --host 0.0.0.0 --port 8080
```

In the app: **Profile → Settings → VPS URL**, e.g. `http://YOUR_IP:8080` (HTTPS recommended in production).

## API

| Method | Path | Body / query |
|--------|------|----------------|
| POST | `/v1/register` | `{nodeId, nick, pubkey}` |
| POST | `/v1/push` | `{envelopes:[{id,from,to,payloadB64,ts,kind}]}` |
| GET | `/v1/pull?nodeId=&since=` | returns `{envelopes:[...]}` |
| GET | `/v1/health` | liveness |

In-memory queue (last 5000 envelopes). For production replace with Redis/Postgres.
