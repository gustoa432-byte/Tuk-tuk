#!/usr/bin/env python3
"""Minimal TukTuk VPS store-and-forward (MVP).

Run on your VPS:
  python3 server/vps/tuktuk_vps.py --host 0.0.0.0 --port 8080

Endpoints:
  POST /v1/register   {nodeId, nick, pubkey}
  POST /v1/push       {envelopes:[...]}
  GET  /v1/pull?nodeId=&since=
  GET  /v1/directory  → known nodes (contact sync)
  GET  /v1/health
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

LOCK = threading.RLock()
NODES: dict[str, dict[str, Any]] = {}
ENVELOPES: list[dict[str, Any]] = []
MAX_ENVELOPES = 5000


def trim() -> None:
    global ENVELOPES
    if len(ENVELOPES) > MAX_ENVELOPES:
        ENVELOPES = ENVELOPES[-MAX_ENVELOPES:]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _read_json(self) -> Any:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def _send(self, code: int, payload: Any) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Node-Id")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/v1/health":
            with LOCK:
                self._send(200, {"ok": True, "nodes": len(NODES), "envelopes": len(ENVELOPES)})
            return
        if parsed.path == "/v1/directory":
            with LOCK:
                self._send(
                    200,
                    {
                        "nodes": [
                            {
                                "nodeId": n["nodeId"],
                                "nick": n.get("nick") or "",
                                "pubkey": n.get("pubkey") or "",
                                "seenAt": n.get("seenAt") or 0,
                            }
                            for n in NODES.values()
                        ]
                    },
                )
            return
        if parsed.path == "/v1/pull":
            qs = parse_qs(parsed.query)
            node_id = (qs.get("nodeId") or [""])[0]
            since = int((qs.get("since") or ["0"])[0] or "0")
            with LOCK:
                out = [
                    e
                    for e in ENVELOPES
                    if e.get("ts", 0) > since
                    and e.get("from") != node_id
                    and (e.get("to") in ("*", "", None, node_id))
                ]
            self._send(200, {"envelopes": out})
            return
        self._send(404, {"error": "not_found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        data = self._read_json()
        if parsed.path == "/v1/register":
            node_id = data.get("nodeId") or self.headers.get("X-Node-Id") or ""
            if not node_id:
                self._send(400, {"error": "nodeId_required"})
                return
            with LOCK:
                NODES[node_id] = {
                    "nodeId": node_id,
                    "nick": data.get("nick") or "",
                    "pubkey": data.get("pubkey") or "",
                    "seenAt": int(time.time() * 1000),
                }
            self._send(200, {"ok": True})
            return
        if parsed.path == "/v1/push":
            envs = data.get("envelopes") or []
            accepted = 0
            with LOCK:
                known = {e.get("id") for e in ENVELOPES}
                for env in envs:
                    if not isinstance(env, dict):
                        continue
                    eid = env.get("id")
                    if not eid or eid in known:
                        continue
                    env.setdefault("ts", int(time.time() * 1000))
                    env.setdefault("kind", "mesh_bytes")
                    ENVELOPES.append(env)
                    known.add(eid)
                    accepted += 1
                trim()
            self._send(200, {"ok": True, "accepted": accepted})
            return
        self._send(404, {"error": "not_found"})


def main() -> None:
    ap = argparse.ArgumentParser(description="TukTuk VPS MVP bridge")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print("TukTuk VPS listening on http://%s:%s" % (args.host, args.port))
    httpd.serve_forever()


if __name__ == "__main__":
    main()
