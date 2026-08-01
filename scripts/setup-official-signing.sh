#!/usr/bin/env bash
# Idempotent setup for TukTuk official release signing + author RSA keypair.
# Usage: ./scripts/setup-official-signing.sh
#        FORCE=1 ./scripts/setup-official-signing.sh   # overwrite existing secrets
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SECRETS_DIR="$ROOT/secrets"
KEYSTORE="$ROOT/app/release.keystore"
PROPS="$ROOT/keystore.properties"
BACKUP="$SECRETS_DIR/RELEASE_KEY_BACKUP.txt"
AUTHOR_PRIV="$SECRETS_DIR/author_private.pem"
AUTHOR_PUB_B64="$SECRETS_DIR/author_pub.b64"
ALIAS="tuktuk"

mkdir -p "$SECRETS_DIR"

gen_password() {
  openssl rand -hex 32
}

cert_sha256() {
  local store="$1" pass="$2" alias="$3"
  # Export DER cert bytes, hash with SHA-256 (matches PackageManager signing cert digest)
  keytool -exportcert -alias "$alias" -keystore "$store" -storepass "$pass" 2>/dev/null \
    | openssl dgst -sha256 -hex \
    | awk '{print $NF}'
}

write_keystore_bundle() {
  local store_pass="$1"
  # PKCS12 ignores distinct keypass — use one password for both.
  keytool -genkeypair \
    -storetype PKCS12 \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$store_pass" \
    -keypass "$store_pass" \
    -dname "CN=TukTuk Official, OU=TukTuk, O=TukTuk, L=Offline, ST=NA, C=RU"

  cat > "$PROPS" <<EOF
storeFile=app/release.keystore
storePassword=$store_pass
keyAlias=$ALIAS
keyPassword=$store_pass
EOF
  chmod 600 "$PROPS" "$KEYSTORE"

  local cert_sha
  cert_sha="$(cert_sha256 "$KEYSTORE" "$store_pass" "$ALIAS")"
  cat > "$BACKUP" <<EOF
TukTuk release signing — СОХРАНИ ОФЛАЙН
========================================
Дата: $(date -Iseconds)
Alias: $ALIAS
Store file: app/release.keystore
Store password: $store_pass
Key password: $store_pass
Cert SHA-256 (hex, lowercase, no colons): $cert_sha

Инструкции:
1. Скопируй этот файл + app/release.keystore + secrets/author_private.pem на офлайн носитель.
2. Не коммить keystore.properties, *.keystore, secrets/ в git.
3. Без этого бэкапа нельзя будет обновлять официальные APK той же подписью.
EOF
  chmod 600 "$BACKUP"
  echo "Wrote $KEYSTORE, $PROPS, $BACKUP"
  echo "Cert SHA-256: $cert_sha"
}

# ── Release keystore ──────────────────────────────────────────────────────────
if [[ -f "$KEYSTORE" || -f "$PROPS" ]]; then
  if [[ "${FORCE:-0}" != "1" ]]; then
    echo "Release keystore / keystore.properties already exist — skipping keystore creation."
    echo "  (Set FORCE=1 to regenerate; you will lose the old signing key.)"
  else
    echo "FORCE=1: regenerating release keystore…"
    rm -f "$KEYSTORE"
    write_keystore_bundle "$(gen_password)"
  fi
else
  echo "Creating release keystore…"
  write_keystore_bundle "$(gen_password)"
fi

# ── Author RSA keypair ────────────────────────────────────────────────────────
if [[ -f "$AUTHOR_PRIV" ]]; then
  if [[ "${FORCE:-0}" != "1" ]]; then
    echo "Author private key already exists — skipping."
    echo "  $AUTHOR_PRIV"
    if [[ ! -f "$AUTHOR_PUB_B64" ]]; then
      openssl rsa -in "$AUTHOR_PRIV" -pubout -outform DER 2>/dev/null \
        | base64 -w0 > "$AUTHOR_PUB_B64"
      echo "Regenerated public key: $AUTHOR_PUB_B64"
    fi
  else
    echo "FORCE=1: regenerating author RSA keypair…"
    openssl genrsa -out "$AUTHOR_PRIV" 2048
    chmod 600 "$AUTHOR_PRIV"
    openssl rsa -in "$AUTHOR_PRIV" -pubout -outform DER 2>/dev/null \
      | base64 -w0 > "$AUTHOR_PUB_B64"
    echo "Wrote $AUTHOR_PRIV and $AUTHOR_PUB_B64"
  fi
else
  echo "Generating author RSA-2048 keypair…"
  openssl genrsa -out "$AUTHOR_PRIV" 2048
  chmod 600 "$AUTHOR_PRIV"
  openssl rsa -in "$AUTHOR_PRIV" -pubout -outform DER 2>/dev/null \
    | base64 -w0 > "$AUTHOR_PUB_B64"
  echo "Wrote $AUTHOR_PRIV and $AUTHOR_PUB_B64"
fi

# Ensure pub b64 exists even if we skipped priv regeneration
if [[ -f "$AUTHOR_PRIV" && ! -f "$AUTHOR_PUB_B64" ]]; then
  openssl rsa -in "$AUTHOR_PRIV" -pubout -outform DER 2>/dev/null \
    | base64 -w0 > "$AUTHOR_PUB_B64"
fi

echo ""
echo "Done. Next steps:"
echo "  1. Paste contents of $AUTHOR_PUB_B64 into SecurityConfig.AUTHOR_PUBLIC_KEY"
echo "     and set AUTHOR_KEY_CONFIGURED = true (setup may have done this already)."
echo "  2. Backup offline: $BACKUP + $KEYSTORE + $AUTHOR_PRIV"
echo "  3. Build: ./gradlew :app:assembleRelease"
echo "  4. Public key (safe to commit) is in $AUTHOR_PUB_B64"
