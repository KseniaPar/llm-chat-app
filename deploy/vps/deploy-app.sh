#!/usr/bin/env bash
# Деплой православного чата на VPS.
# sudo bash deploy/vps/deploy-app.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$APP_ROOT/deploy/vps/.env"
APP_USER="${APP_USER:-llmchat}"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Запустите от root: sudo bash deploy/vps/deploy-app.sh"
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Сначала: bash deploy/vps/setup-env.sh"
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

for placeholder in change-me-use-openssl-rand-base64-32 change-me-private-llm-key; do
  if grep -q "$placeholder" "$ENV_FILE"; then
    echo "В .env остались значения по умолчанию. Запустите: bash deploy/vps/setup-env.sh"
    exit 1
  fi
done

MODEL="${LOCAL_LLM_MODEL:-qwen2.5:7b}"
EMBED_MODEL="${RAG_EMBED_MODEL:-nomic-embed-text}"
NGINX_NAME="${NGINX_SERVER_NAME:-_}"
API_KEY="${LOCAL_LLM_API_KEY:-}"
VITE_KEY="${VITE_LOCAL_LLM_API_KEY:-$API_KEY}"

chmod 600 "$ENV_FILE"
chown "$APP_USER:$APP_USER" "$ENV_FILE" 2>/dev/null || true

echo "==> Сборка backend"
cd "$APP_ROOT"
mvn -q package -DskipTests

echo "==> Сборка frontend"
cd "$APP_ROOT/frontend"
npm ci
VITE_LOCAL_LLM_API_KEY="$VITE_KEY" npm run build

echo "==> Ollama: chat=$MODEL embed=$EMBED_MODEL"
systemctl start ollama
ollama pull "$MODEL"
ollama pull "$EMBED_MODEL"

echo "==> nginx"
cp "$APP_ROOT/deploy/vps/nginx-rate-limit.conf" /etc/nginx/conf.d/llm-chat-rate-limit.conf
sed -e "s|NGINX_SERVER_NAME_PLACEHOLDER|$NGINX_NAME|g" \
    -e "s|APP_ROOT_PLACEHOLDER|$APP_ROOT|g" \
    "$APP_ROOT/deploy/vps/nginx-llm-chat.conf" > /etc/nginx/sites-available/llm-chat
ln -sf /etc/nginx/sites-available/llm-chat /etc/nginx/sites-enabled/llm-chat
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo "==> systemd"
sed -e "s|APP_ROOT_PLACEHOLDER|$APP_ROOT|g" \
    "$APP_ROOT/deploy/vps/llm-chat.service" > /etc/systemd/system/llm-chat.service
systemctl daemon-reload
systemctl enable llm-chat ollama
systemctl restart llm-chat

chown -R "$APP_USER:$APP_USER" "$APP_ROOT" /var/log/llm-chat 2>/dev/null || true

echo "==> Ожидание старта (RAG-индекс может строиться несколько минут)…"
for i in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:8080/api/local-llm/service/info" >/dev/null 2>&1; then
    break
  fi
  sleep 5
done

PUBLIC_IP="$(curl -sf ifconfig.me 2>/dev/null || echo '<IP-VPS>')"
echo ""
echo "==> Готово: http://$PUBLIC_IP/"
if [[ "$NGINX_NAME" != "_" ]]; then
  echo "           http://$NGINX_NAME/"
fi
echo "    Логи: journalctl -u llm-chat -f"
echo "    HTTPS: sudo bash deploy/vps/enable-https.sh <домен>"
