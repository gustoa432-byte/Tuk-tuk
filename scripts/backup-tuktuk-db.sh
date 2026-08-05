#!/usr/bin/env bash
# Daily backup of TukTuk libSQL/SQLite DB.
#
# Install cron (root):
#   15 3 * * * /opt/tuktuk/scripts/backup-tuktuk-db.sh >>/var/log/tuktuk-backup.log 2>&1
#
# Or via deploy-vps.sh (installs the crontab line idempotently).
set -euo pipefail

DB_PATH="${TUKTUK_DB:-/opt/tuktuk/tuktuk.db}"
BACKUP_DIR="${TUKTUK_BACKUP_DIR:-/opt/tuktuk/backups}"
KEEP_DAYS="${TUKTUK_BACKUP_KEEP_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${BACKUP_DIR}/tuktuk-${STAMP}.db"

mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

if [[ ! -f "${DB_PATH}" ]]; then
  echo "DB missing: ${DB_PATH}" >&2
  exit 1
fi

if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "${DB_PATH}" ".backup '${DEST}'"
else
  # Fallback when sqlite3 CLI absent (libSQL file is still a SQLite3 DB).
  cp -a "${DB_PATH}" "${DEST}"
fi

chmod 600 "${DEST}"
# Also keep a rolling "latest" pointer
ln -sfn "${DEST}" "${BACKUP_DIR}/tuktuk-latest.db"

find "${BACKUP_DIR}" -type f -name 'tuktuk-*.db' -mtime "+${KEEP_DAYS}" -delete 2>/dev/null || true

echo "OK backup ${DEST} ($(du -h "${DEST}" | awk '{print $1}'))"
