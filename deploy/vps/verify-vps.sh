#!/usr/bin/env bash
# Проверка чата на VPS.
# bash deploy/vps/verify-vps.sh [http://127.0.0.1]
set -euo pipefail

BASE="${1:-http://127.0.0.1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

API_KEY="${LOCAL_LLM_API_KEY:-}"
hdr=(-H "Content-Type: application/json")
if [[ -n "$API_KEY" ]]; then
  hdr+=(-H "X-Local-Llm-Api-Key: $API_KEY")
fi

echo "==> Service info"
curl -sf "${hdr[@]}" "$BASE/api/local-llm/service/info" | head -c 500
echo ""

echo "==> Chat (короткий вопрос, может занять 1–2 мин на CPU)"
curl -sf "${hdr[@]}" -X POST "$BASE/api/local-llm/service/chat" \
  -d '{"prompt":"Что такое пост?"}' | head -c 800
echo ""

echo "==> Закрытые API (должен быть 404)"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/platform/info" || true)"
if [[ "$code" == "404" ]]; then
  echo "    /api/platform/info -> 404 OK"
else
  echo "    /api/platform/info -> $code (ожидался 404)"
  exit 1
fi

echo "==> OK"
