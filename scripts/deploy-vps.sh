#!/usr/bin/env bash
# Deploy TukTuk vps-rs to the production VPS (or any host with the same layout).
#
# Usage (on the VPS as root):
#   /opt/tuktuk/scripts/deploy-vps.sh
#
# From a laptop (SSH key auth):
#   ssh -i ~/.ssh/id_ed25519 root@157.228.136.239 'bash -s' < scripts/deploy-vps.sh
#
# Env overrides:
#   TUKTUK_REPO_DIR=/opt/tuktuk
#   TUKTUK_BRANCH=master
#   TUKTUK_SERVICE=tuktuk
set -euo pipefail

REPO_DIR="${TUKTUK_REPO_DIR:-/opt/tuktuk}"
BRANCH="${TUKTUK_BRANCH:-master}"
SERVICE="${TUKTUK_SERVICE:-tuktuk}"
ENV_FILE="${TUKTUK_ENV_FILE:-/etc/tuktuk.env}"
EXAMPLE_ENV="${REPO_DIR}/server/vps-rs/tuktuk.env.example"
UNIT_DST="/etc/systemd/system/${SERVICE}.service"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi

if [[ ! -d "${REPO_DIR}/.git" ]]; then
  echo "Missing git checkout at ${REPO_DIR}" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
# Ensure build deps exist (idempotent).
apt-get install -y -qq curl git build-essential pkg-config libssl-dev >/dev/null

# Rust toolchain
if [[ ! -x "${HOME}/.cargo/bin/cargo" ]]; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
fi
# shellcheck disable=SC1091
source "${HOME}/.cargo/env"

echo "==> sync ${REPO_DIR} @ ${BRANCH}"
cd "${REPO_DIR}"
git fetch origin
git checkout "${BRANCH}"
git reset --hard "origin/${BRANCH}"
git log -1 --oneline

echo "==> ensure ${ENV_FILE} (chmod 600)"
if [[ ! -f "${ENV_FILE}" ]]; then
  if [[ -f "${EXAMPLE_ENV}" ]]; then
    umask 077
    cp "${EXAMPLE_ENV}" "${ENV_FILE}"
    # Strip comment-only guidance lines that are not KEY=VAL for systemd? keep as-is;
    # systemd ignores lines starting with #.
  else
    umask 077
    cat >"${ENV_FILE}" <<'EOF'
TUKTUK_HOST=0.0.0.0
TUKTUK_PORT=8080
TUKTUK_DB=/opt/tuktuk/tuktuk.db
TUKTUK_JWT_SECRET=CHANGE_ME
TUKTUK_OTP_DEV_LOG=true
EOF
  fi
  # Generate JWT if still placeholder
  if grep -q 'CHANGE_ME' "${ENV_FILE}"; then
    SECRET="$(openssl rand -hex 32)"
    sed -i "s/^TUKTUK_JWT_SECRET=.*/TUKTUK_JWT_SECRET=${SECRET}/" "${ENV_FILE}"
  fi
else
  # Merge missing keys from example without overwriting existing secrets
  if [[ -f "${EXAMPLE_ENV}" ]]; then
    while IFS= read -r line; do
      [[ "${line}" =~ ^[[:space:]]*# ]] && continue
      [[ -z "${line//[[:space:]]/}" ]] && continue
      key="${line%%=*}"
      [[ -z "${key}" ]] && continue
      if ! grep -q "^${key}=" "${ENV_FILE}"; then
        echo "${line}" >>"${ENV_FILE}"
        echo "    + added ${key}"
      fi
    done <"${EXAMPLE_ENV}"
  fi
fi
chmod 600 "${ENV_FILE}"
chown root:root "${ENV_FILE}"

echo "==> systemd unit"
cat >"${UNIT_DST}" <<EOF
[Unit]
Description=TukTuk VPS store-and-forward (Axum + libSQL)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${REPO_DIR}/server/vps-rs
EnvironmentFile=${ENV_FILE}
ExecStart=${REPO_DIR}/server/vps-rs/target/release/tuktuk-vps
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable "${SERVICE}" >/dev/null

echo "==> cargo build --release"
cd "${REPO_DIR}/server/vps-rs"
cargo build --release

echo "==> restart ${SERVICE}"
systemctl restart "${SERVICE}"
sleep 1
systemctl --no-pager --full status "${SERVICE}" || true

PORT="$(grep -E '^TUKTUK_PORT=' "${ENV_FILE}" | cut -d= -f2 | tr -d '\r' || true)"
PORT="${PORT:-8080}"
BASE="http://127.0.0.1:${PORT}"

echo "==> smoke ${BASE}"
curl -fsS "${BASE}/v1/health"
echo
code_send="$(curl -sS -o /tmp/tuktuk_auth_send.json -w '%{http_code}' \
  -X POST "${BASE}/auth/email/send" \
  -H 'Content-Type: application/json' \
  -d '{"email":"deploy-smoke@example.com"}')"
echo "POST /auth/email/send -> ${code_send}"
cat /tmp/tuktuk_auth_send.json 2>/dev/null || true
echo
code_verify="$(curl -sS -o /tmp/tuktuk_auth_verify.json -w '%{http_code}' \
  -X POST "${BASE}/auth/email/verify" \
  -H 'Content-Type: application/json' \
  -d '{"email":"deploy-smoke@example.com","otp":"000000","publicBleKey":"smoke"}')"
echo "POST /auth/email/verify (bad otp) -> ${code_verify} (expect 401)"
cat /tmp/tuktuk_auth_verify.json 2>/dev/null || true
echo
code_contacts="$(curl -sS -o /tmp/tuktuk_contacts.json -w '%{http_code}' \
  -X POST "${BASE}/contacts/add" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"00000000-0000-0000-0000-000000000000"}')"
echo "POST /contacts/add (no JWT) -> ${code_contacts} (expect 401)"
cat /tmp/tuktuk_contacts.json 2>/dev/null || true
echo
code_tg="$(curl -sS -o /tmp/tuktuk_tg.json -w '%{http_code}' \
  -X POST "${BASE}/auth/telegram" \
  -H 'Content-Type: application/json' \
  -d '{"initData":"x","publicBleKey":"smoke"}')"
echo "POST /auth/telegram -> ${code_tg} (401/500 until token+valid initData)"
cat /tmp/tuktuk_tg.json 2>/dev/null || true
echo

if [[ "${code_send}" == "404" || "${code_contacts}" == "404" ]]; then
  echo "FAIL: auth/contacts still 404 — wrong binary or old build?" >&2
  exit 1
fi

echo "OK deploy ${SERVICE} @ $(git -C "${REPO_DIR}" rev-parse --short HEAD)"
