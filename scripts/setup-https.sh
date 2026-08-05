#!/usr/bin/env bash
# Configure Nginx reverse-proxy + Let's Encrypt TLS for TukTuk VPS (Rust on :8080).
#
# Default domain uses free nip.io wildcard DNS (IP → hostname, no registrar):
#   157.228.136.239.nip.io  →  157.228.136.239
#
# Usage (on VPS as root), one line:
#   TUKTUK_EMAIL=ops@example.com bash /opt/tuktuk/scripts/setup-https.sh
#
# After success, Android VpsConfig default is:
#   https://157.228.136.239.nip.io
#
# Prerequisites: port 80/443 open; tuktuk listening on 127.0.0.1:8080 (script sets TUKTUK_HOST).
set -euo pipefail

DOMAIN="${TUKTUK_DOMAIN:-157.228.136.239.nip.io}"
EMAIL="${TUKTUK_EMAIL:-}"
UPSTREAM="${TUKTUK_UPSTREAM:-127.0.0.1:8080}"
SITE_NAME="${TUKTUK_NGINX_SITE:-tuktuk}"
NGINX_AVAIL="/etc/nginx/sites-available/${SITE_NAME}"
NGINX_ENABLED="/etc/nginx/sites-enabled/${SITE_NAME}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi

if [[ -z "${EMAIL}" ]]; then
  echo "Set TUKTUK_EMAIL=admin@example.com (Let's Encrypt registration)" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq nginx certbot python3-certbot-nginx >/dev/null

# Bind Rust to localhost — TLS terminates at Nginx.
ENV_FILE="${TUKTUK_ENV_FILE:-/etc/tuktuk.env}"
if [[ -f "${ENV_FILE}" ]]; then
  if grep -q '^TUKTUK_HOST=' "${ENV_FILE}"; then
    sed -i 's/^TUKTUK_HOST=.*/TUKTUK_HOST=127.0.0.1/' "${ENV_FILE}"
  else
    echo "TUKTUK_HOST=127.0.0.1" >>"${ENV_FILE}"
  fi
  # Keep port 8080 for upstream; public clients use https://${DOMAIN}
  systemctl restart tuktuk 2>/dev/null || true
  sleep 1
fi

# Verify nip.io (or custom DNS) resolves before ACME challenge.
RESOLVED="$(getent hosts "${DOMAIN}" | awk '{print $1}' | head -n1 || true)"
echo "==> ${DOMAIN} resolves to: ${RESOLVED:-unknown}"
if [[ -z "${RESOLVED}" ]]; then
  echo "WARN: could not resolve ${DOMAIN} — certbot may fail" >&2
fi

cat >"${NGINX_AVAIL}" <<EOF
# TukTuk — HTTP (certbot upgrades to HTTPS + redirect)
# Domain: ${DOMAIN} (nip.io or custom)
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name ${DOMAIN};

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        proxy_pass http://${UPSTREAM};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Authorization \$http_authorization;
        proxy_pass_header Authorization;
        client_max_body_size 8m;
        proxy_connect_timeout 10s;
        proxy_read_timeout 60s;
    }
}
EOF

ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}"
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx
systemctl reload nginx

echo "==> requesting Let's Encrypt cert for ${DOMAIN}"
certbot --nginx \
  -d "${DOMAIN}" \
  --non-interactive \
  --agree-tos \
  -m "${EMAIL}" \
  --redirect

nginx -t
systemctl reload nginx

echo
echo "OK HTTPS https://${DOMAIN}/"
echo "Health: curl -fsS https://${DOMAIN}/v1/health"
echo "Android default URL: https://${DOMAIN}"
echo "Rust upstream: http://${UPSTREAM} (TUKTUK_HOST=127.0.0.1)"
