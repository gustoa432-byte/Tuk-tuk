#!/usr/bin/env bash
# Configure Nginx reverse-proxy + Let's Encrypt TLS for TukTuk VPS (Rust on :8080).
#
# Usage (on VPS as root):
#   TUKTUK_DOMAIN=node.tuktuk.dev TUKTUK_EMAIL=you@example.com \
#     bash /opt/tuktuk/scripts/setup-https.sh
#
# After success, point Android VpsConfig at:
#   https://node.tuktuk.dev
#
# Prerequisites: DNS A/AAAA for DOMAIN → this host; port 80/443 open; tuktuk on 127.0.0.1:8080.
set -euo pipefail

DOMAIN="${TUKTUK_DOMAIN:-node.tuktuk.dev}"
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

# Bind Rust to localhost only once TLS terminates at Nginx (optional hardening).
ENV_FILE="${TUKTUK_ENV_FILE:-/etc/tuktuk.env}"
if [[ -f "${ENV_FILE}" ]]; then
  if grep -q '^TUKTUK_HOST=' "${ENV_FILE}"; then
    sed -i 's/^TUKTUK_HOST=.*/TUKTUK_HOST=127.0.0.1/' "${ENV_FILE}"
  else
    echo "TUKTUK_HOST=127.0.0.1" >>"${ENV_FILE}"
  fi
  systemctl restart tuktuk 2>/dev/null || true
  sleep 1
fi

cat >"${NGINX_AVAIL}" <<EOF
# TukTuk — HTTP (certbot will upgrade to HTTPS)
server {
    listen 80;
    listen [::]:80;
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
        proxy_read_timeout 60s;
    }
}
EOF

ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}"
# Remove default site if it steals :80
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
echo "Rust upstream: ${UPSTREAM} (prefer TUKTUK_HOST=127.0.0.1)"
