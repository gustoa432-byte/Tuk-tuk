#!/usr/bin/env python3
"""LEGACY — DO NOT RUN.

This insecure Python store-and-forward (no JWT, open directory, CORS *) is
quarantined. Production mesh uses Rust:

  cd server/vps-rs && cargo run --release
  # or: bash scripts/deploy-vps.sh

Set TUKTUK_ALLOW_LEGACY_PYTHON=1 only for local archaeology.
"""

from __future__ import annotations

import os
import sys

if __name__ == "__main__":
    if os.environ.get("TUKTUK_ALLOW_LEGACY_PYTHON") != "1":
        print(
            "FATAL: server/vps/tuktuk_vps.py is quarantined (insecure MVP).\n"
            "Use server/vps-rs (Rust) via scripts/deploy-vps.sh.\n"
            "Override only with TUKTUK_ALLOW_LEGACY_PYTHON=1.",
            file=sys.stderr,
        )
        sys.exit(2)
    print("Legacy Python VPS body removed — refuse to serve.", file=sys.stderr)
    sys.exit(2)
