#!/usr/bin/env bash
# HTTPS через Let's Encrypt (нужен домен, указывающий на VPS).
# sudo bash deploy/vps/enable-https.sh chat.example.org
set -euo pipefail

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  echo "Использование: sudo bash deploy/vps/enable-https.sh <домен>"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y certbot python3-certbot-nginx

if [[ -f "$ENV_FILE" ]]; then
  if grep -q '^NGINX_SERVER_NAME=_' "$ENV_FILE"; then
    if sed --version >/dev/null 2>&1; then
      sed -i "s|^NGINX_SERVER_NAME=.*|NGINX_SERVER_NAME=$DOMAIN|" "$ENV_FILE"
    else
      sed -i '' "s|^NGINX_SERVER_NAME=.*|NGINX_SERVER_NAME=$DOMAIN|" "$ENV_FILE"
    fi
    echo "==> NGINX_SERVER_NAME=$DOMAIN в .env"
  fi
fi

NGINX_NAME="$DOMAIN"
sed -e "s|NGINX_SERVER_NAME_PLACEHOLDER|$NGINX_NAME|g" \
    -e "s|APP_ROOT_PLACEHOLDER|$APP_ROOT|g" \
    "$APP_ROOT/deploy/vps/nginx-llm-chat.conf" > /etc/nginx/sites-available/llm-chat
nginx -t
systemctl reload nginx

EMAIL="${CERTBOT_EMAIL:-}"
if [[ -z "$EMAIL" ]]; then
  echo "Укажите email для Let's Encrypt:"
  echo "  CERTBOT_EMAIL=you@mail.org sudo bash deploy/vps/enable-https.sh $DOMAIN"
  exit 1
fi

certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" --redirect

echo "==> HTTPS: https://$DOMAIN/"
