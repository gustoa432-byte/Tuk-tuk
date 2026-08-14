# Deploy TukTuk Oracle / mesh VPS (vps-rs release binary + systemd)
#
# Usage ON the VPS as root:
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
UNIT_SRC="${REPO_DIR}/server/vps-rs/tuktuk.service"
UNIT_DST="/etc/systemd/system/${SERVICE}.service"
BIN="${REPO_DIR}/server/vps-rs/target/release/tuktuk-vps"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi

if [[ ! -d "${REPO_DIR}/.git" ]]; then
  echo "Missing git checkout at ${REPO_DIR}" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get install -y -qq curl git build-essential pkg-config libssl-dev >/dev/null

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
  umask 077
  if [[ -f "${EXAMPLE_ENV}" ]]; then
    cp "${EXAMPLE_ENV}" "${ENV_FILE}"
  else
    cat >"${ENV_FILE}" <<'EOF'
TUKTUK_HOST=0.0.0.0
TUKTUK_PORT=8080
TUKTUK_DB=/opt/tuktuk/tuktuk.db
TUKTUK_JWT_SECRET=CHANGE_ME
TUKTUK_OTP_DEV_LOG=false
EOF
  fi
  if grep -q 'CHANGE_ME' "${ENV_FILE}"; then
    SECRET="$(openssl rand -hex 32)"
    sed -i "s/^TUKTUK_JWT_SECRET=.*/TUKTUK_JWT_SECRET=${SECRET}/" "${ENV_FILE}"
  fi
else
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
# Hardening: never leave OTP codes in API responses on prod.
if grep -q '^TUKTUK_OTP_DEV_LOG=true' "${ENV_FILE}" 2>/dev/null; then
  sed -i 's/^TUKTUK_OTP_DEV_LOG=.*/TUKTUK_OTP_DEV_LOG=false/' "${ENV_FILE}"
  echo "    forced TUKTUK_OTP_DEV_LOG=false"
elif ! grep -q '^TUKTUK_OTP_DEV_LOG=' "${ENV_FILE}" 2>/dev/null; then
  echo "TUKTUK_OTP_DEV_LOG=false" >>"${ENV_FILE}"
fi
chmod 600 "${ENV_FILE}"
chown root:root "${ENV_FILE}"

# Ensure DB directory exists
DB_PATH="$(grep -E '^TUKTUK_DB=' "${ENV_FILE}" | cut -d= -f2- | tr -d '\r' || true)"
DB_PATH="${DB_PATH:-/opt/tuktuk/tuktuk.db}"
mkdir -p "$(dirname "${DB_PATH}")"

echo "==> systemd unit"
if [[ -f "${UNIT_SRC}" ]]; then
  # Rewrite paths if REPO_DIR is non-default
  sed \
    -e "s|/opt/tuktuk|${REPO_DIR}|g" \
    "${UNIT_SRC}" >"${UNIT_DST}"
  # Ensure EnvironmentFile points at ENV_FILE
  if ! grep -q "EnvironmentFile=" "${UNIT_DST}"; then
    sed -i "/\\[Service\\]/a EnvironmentFile=${ENV_FILE}" "${UNIT_DST}"
  else
    sed -i "s|^EnvironmentFile=.*|EnvironmentFile=${ENV_FILE}|" "${UNIT_DST}"
  fi
else
  cat >"${UNIT_DST}" <<EOF
[Unit]
Description=TukTuk VPS (mesh S&F + auth + Oracle)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${REPO_DIR}/server/vps-rs
EnvironmentFile=${ENV_FILE}
ExecStart=${BIN}
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
fi
systemctl daemon-reload
systemctl enable "${SERVICE}" >/dev/null

echo "==> cargo build --release (LTO)"
cd "${REPO_DIR}/server/vps-rs"
cargo build --release
test -x "${BIN}"

echo "==> restart ${SERVICE}"
systemctl restart "${SERVICE}"
sleep 2
systemctl --no-pager --full status "${SERVICE}" || true

PORT="$(grep -E '^TUKTUK_PORT=' "${ENV_FILE}" | cut -d= -f2 | tr -d '\r' || true)"
PORT="${PORT:-8080}"
BASE="http://127.0.0.1:${PORT}"

echo "==> smoke ${BASE}"
curl -fsS "${BASE}/v1/health"
echo

code_oracle_sync="$(curl -sS -o /tmp/tuktuk_oracle_sync.json -w '%{http_code}' \
  -X POST "${BASE}/v1/oracle/sync" \
  -H 'Content-Type: application/json' \
  -d '{"orbits":[]}')"
echo "POST /v1/oracle/sync (no JWT) -> ${code_oracle_sync} (expect 401)"
cat /tmp/tuktuk_oracle_sync.json 2>/dev/null || true
echo

code_oracle_hint="$(curl -sS -o /tmp/tuktuk_oracle_hint.json -w '%{http_code}' \
  -X POST "${BASE}/v1/oracle/hint" \
  -H 'Content-Type: application/json' \
  -d '{"target_node":"X"}')"
echo "POST /v1/oracle/hint (no JWT) -> ${code_oracle_hint} (expect 401)"
cat /tmp/tuktuk_oracle_hint.json 2>/dev/null || true
echo

# Do NOT send a real OTP: SMTP is live (Yandex) and example.com rejects mail (nullMX bounce).
# Invalid address proves the route is mounted without triggering lettre/SMTP.
code_send="$(curl -sS -o /tmp/tuktuk_auth_send.json -w '%{http_code}' \
  -X POST "${BASE}/auth/email/send" \
  -H 'Content-Type: application/json' \
  -d '{"email":"not-an-email"}')"
echo "POST /auth/email/send (invalid) -> ${code_send} (expect 400)"
cat /tmp/tuktuk_auth_send.json 2>/dev/null || true
echo

code_contacts="$(curl -sS -o /tmp/tuktuk_contacts.json -w '%{http_code}' \
  -X POST "${BASE}/contacts/add" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"00000000-0000-0000-0000-000000000000"}')"
echo "POST /contacts/add (no JWT) -> ${code_contacts} (expect 401)"
cat /tmp/tuktuk_contacts.json 2>/dev/null || true
echo

code_push="$(curl -sS -o /tmp/tuktuk_push.json -w '%{http_code}' \
  -X POST "${BASE}/v1/push" \
  -H 'Content-Type: application/json' \
  -d '{"envelopes":[]}')"
echo "POST /v1/push (no JWT) -> ${code_push} (expect 401)"
cat /tmp/tuktuk_push.json 2>/dev/null || true
echo

code_reg="$(curl -sS -o /tmp/tuktuk_reg.json -w '%{http_code}' \
  -X POST "${BASE}/v1/register" \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"x"}')"
echo "POST /v1/register (no JWT) -> ${code_reg} (expect 401)"
cat /tmp/tuktuk_reg.json 2>/dev/null || true
echo

code_phone="$(curl -sS -o /tmp/tuktuk_phone_lookup.json -w '%{http_code}' \
  -X POST "${BASE}/v1/users/phone/lookup" \
  -H 'Content-Type: application/json' \
  -d '{"hashes":[]}')"
echo "POST /v1/users/phone/lookup (no JWT) -> ${code_phone} (expect 401)"
cat /tmp/tuktuk_phone_lookup.json 2>/dev/null || true
echo

# Daily DB backup cron (idempotent)
BACKUP_SCRIPT="${REPO_DIR}/scripts/backup-tuktuk-db.sh"
if [[ -f "${BACKUP_SCRIPT}" ]]; then
  chmod +x "${BACKUP_SCRIPT}" || true
  apt-get install -y -qq sqlite3 >/dev/null || true
  CRON_LINE="15 3 * * * TUKTUK_DB=${DB_PATH} ${BACKUP_SCRIPT} >>/var/log/tuktuk-backup.log 2>&1"
  (crontab -l 2>/dev/null | grep -v 'backup-tuktuk-db.sh' || true; echo "${CRON_LINE}") | crontab -
  echo "==> backup cron installed (03:15 UTC daily)"
  # Smoke one backup now
  TUKTUK_DB="${DB_PATH}" bash "${BACKUP_SCRIPT}" || true
fi

if [[ "${code_oracle_sync}" == "404" || "${code_oracle_hint}" == "404" ]]; then
  echo "FAIL: /v1/oracle/* still 404 — old binary without Oracle?" >&2
  exit 1
fi
if [[ "${code_oracle_sync}" != "401" && "${code_oracle_sync}" != "400" ]]; then
  echo "WARN: unexpected oracle sync code ${code_oracle_sync}" >&2
fi
if [[ "${code_push}" != "401" && "${code_push}" != "400" ]]; then
  echo "WARN: unexpected push code ${code_push} (expected 401 without JWT)" >&2
fi

echo "OK deploy ${SERVICE} @ $(git -C "${REPO_DIR}" rev-parse --short HEAD)"
echo "Binary: ${BIN}"
echo "DB:     ${DB_PATH}"
echo "Env:    ${ENV_FILE}"
echo "HTTPS:  TUKTUK_EMAIL=you@example.com bash ${REPO_DIR}/scripts/setup-https.sh"
echo "        # default domain 157.228.136.239.nip.io"
