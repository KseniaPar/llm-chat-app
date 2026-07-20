#!/usr/bin/env bash
# Пароль на весь сайт (nginx Basic Auth) — только для своих.
# sudo bash deploy/vps/enable-site-password.sh
# или: sudo bash deploy/vps/enable-site-password.sh prihod МойПароль2026
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$APP_ROOT/deploy/vps/.env"
HTPASSWD="/etc/nginx/llm-chat-htpasswd"
NGINX_SITE="/etc/nginx/sites-available/llm-chat"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Запустите от root: sudo bash deploy/vps/enable-site-password.sh"
  exit 1
fi

SITE_USER="${1:-}"
SITE_PASS="${2:-}"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

SITE_USER="${SITE_USER:-${SITE_BASIC_AUTH_USER:-prihod}}"
SITE_PASS="${SITE_PASS:-${SITE_BASIC_AUTH_PASSWORD:-}}"

if [[ -z "$SITE_PASS" ]]; then
  read -r -s -p "Пароль для входа на сайт: " SITE_PASS
  echo ""
  read -r -s -p "Повторите пароль: " SITE_PASS2
  echo ""
  if [[ "$SITE_PASS" != "$SITE_PASS2" ]]; then
    echo "Пароли не совпали."
    exit 1
  fi
fi

if [[ ${#SITE_PASS} -lt 8 ]]; then
  echo "Пароль должен быть не короче 8 символов."
  exit 1
fi

DEBIAN_FRONTEND=noninteractive apt-get install -y apache2-utils

htpasswd -cb "$HTPASSWD" "$SITE_USER" "$SITE_PASS"
chmod 640 "$HTPASSWD"
chown root:www-data "$HTPASSWD"

if [[ ! -f "$NGINX_SITE" ]]; then
  echo "Сначала выполните: sudo bash deploy/vps/deploy-app.sh"
  exit 1
fi

if grep -q "llm-chat-htpasswd" "$NGINX_SITE"; then
  echo "==> Пароль обновлён для пользователя: $SITE_USER"
else
  sed -i '/server_name /a\
\
    auth_basic "Доступ для прихожан";\
    auth_basic_user_file /etc/nginx/llm-chat-htpasswd;' "$NGINX_SITE"
  echo "==> Пароль включён для пользователя: $SITE_USER"
fi

nginx -t
systemctl reload nginx

echo ""
echo "Сайт: http://$(curl -sf ifconfig.me 2>/dev/null || echo '<IP-VPS>')/"
echo "Логин: $SITE_USER"
echo "Пароль: (тот, что вы задали — передайте прихожанам лично)"
echo ""
echo "Чтобы сменить пароль позже — запустите этот скрипт снова."
