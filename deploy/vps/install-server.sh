#!/usr/bin/env bash
# Одноразовая подготовка Ubuntu VPS (22.04/24.04, рекомендуется 8 GB RAM).
# sudo bash deploy/vps/install-server.sh
set -euo pipefail

APP_ROOT="${APP_ROOT:-/opt/llm-chat}"
APP_USER="${APP_USER:-llmchat}"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Запустите от root: sudo bash deploy/vps/install-server.sh"
  exit 1
fi

echo "==> Обновление пакетов"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get upgrade -y

echo "==> Базовые пакеты"
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-17-jdk \
  nginx \
  git \
  curl \
  ufw \
  maven \
  openssl

echo "==> Node.js 20"
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs
fi

echo "==> Ollama"
if ! command -v ollama >/dev/null 2>&1; then
  curl -fsSL https://ollama.com/install.sh | sh
fi
systemctl enable ollama
systemctl start ollama

echo "==> Пользователь $APP_USER"
if ! id "$APP_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$APP_ROOT" --shell /usr/sbin/nologin "$APP_USER"
fi
mkdir -p "$APP_ROOT" /var/log/llm-chat
chown -R "$APP_USER:$APP_USER" "$APP_ROOT" /var/log/llm-chat

echo "==> Firewall (только SSH + HTTP + HTTPS; порт 8080 снаружи закрыт)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> Готово. Дальше (из корня репозитория):"
echo "  bash deploy/vps/setup-env.sh"
echo "  sudo bash deploy/vps/deploy-app.sh"
echo ""
echo "  Или одной командой на свежем VPS:"
echo "  sudo bash deploy/vps/bootstrap.sh"
