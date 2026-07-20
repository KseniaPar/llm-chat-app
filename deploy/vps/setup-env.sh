#!/usr/bin/env bash
# Генерирует deploy/vps/.env со случайными секретами.
# bash deploy/vps/setup-env.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
EXAMPLE="$SCRIPT_DIR/env.example"
APP_USER="${APP_USER:-llmchat}"

rand_b64() {
  openssl rand -base64 32 | tr -d '\n'
}

rand_hex() {
  openssl rand -hex 24
}

if [[ ! -f "$EXAMPLE" ]]; then
  echo "Не найден $EXAMPLE"
  exit 1
fi

if [[ -f "$ENV_FILE" ]]; then
  echo "Файл $ENV_FILE уже существует."
  read -r -p "Перезаписать секреты? [y/N] " answer
  if [[ ! "$answer" =~ ^[Yy]$ ]]; then
    echo "Отменено."
    exit 0
  fi
fi

JWT_SECRET="$(rand_b64)"
API_KEY="$(rand_hex)"
SITE_PASS="$(openssl rand -base64 12 | tr -d '/+=' | head -c 14)"

cp "$EXAMPLE" "$ENV_FILE"

# POSIX sed in-place
if sed --version >/dev/null 2>&1; then
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$JWT_SECRET|" "$ENV_FILE"
  sed -i "s|^LOCAL_LLM_API_KEY=.*|LOCAL_LLM_API_KEY=$API_KEY|" "$ENV_FILE"
  sed -i "s|^VITE_LOCAL_LLM_API_KEY=.*|VITE_LOCAL_LLM_API_KEY=$API_KEY|" "$ENV_FILE"
  sed -i "s|^SITE_BASIC_AUTH_PASSWORD=.*|SITE_BASIC_AUTH_PASSWORD=$SITE_PASS|" "$ENV_FILE"
else
  sed -i '' "s|^JWT_SECRET=.*|JWT_SECRET=$JWT_SECRET|" "$ENV_FILE"
  sed -i '' "s|^LOCAL_LLM_API_KEY=.*|LOCAL_LLM_API_KEY=$API_KEY|" "$ENV_FILE"
  sed -i '' "s|^VITE_LOCAL_LLM_API_KEY=.*|VITE_LOCAL_LLM_API_KEY=$API_KEY|" "$ENV_FILE"
  sed -i '' "s|^SITE_BASIC_AUTH_PASSWORD=.*|SITE_BASIC_AUTH_PASSWORD=$SITE_PASS|" "$ENV_FILE"
fi

chmod 600 "$ENV_FILE"
if id "$APP_USER" >/dev/null 2>&1; then
  chown "$APP_USER:$APP_USER" "$ENV_FILE" 2>/dev/null || true
fi

echo "==> Создан $ENV_FILE (права 600)"
echo "    Логин на сайт: prihod"
echo "    Пароль на сайт: $SITE_PASS  (сохраните — понадобится прихожанам)"
echo "    Отредактируйте при необходимости: NGINX_SERVER_NAME, LOCAL_LLM_MODEL"
echo "    Дальше: sudo bash deploy/vps/deploy-app.sh"
