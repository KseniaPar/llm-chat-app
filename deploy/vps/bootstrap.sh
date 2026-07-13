#!/usr/bin/env bash
# Полный цикл на свежем Ubuntu VPS (из корня репозитория).
# sudo bash deploy/vps/bootstrap.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Запустите от root: sudo bash deploy/vps/bootstrap.sh"
  exit 1
fi

cd "$APP_ROOT"

echo "==> 1/3 Подготовка сервера"
bash "$SCRIPT_DIR/install-server.sh"

echo "==> 2/3 Секреты"
if [[ ! -f "$SCRIPT_DIR/.env" ]]; then
  bash "$SCRIPT_DIR/setup-env.sh"
else
  echo "    .env уже есть — пропуск"
fi

echo "==> 3/3 Деплой приложения"
bash "$SCRIPT_DIR/deploy-app.sh"

echo ""
echo "==> Проверка"
bash "$SCRIPT_DIR/verify-vps.sh"
