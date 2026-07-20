# FAQ: Деплой и окружение

## Локальный запуск

1. Ollama + `nomic-embed-text` (для PROJECT/SUPPORT RAG).
2. Сборка MCP: `mvn -pl mcp-servers/mcp-study,mcp-servers/mcp-scheduler,mcp-servers/mcp-pipeline,mcp-servers/mcp-git,mcp-servers/mcp-tickets -am package`
3. Backend: `cd backend && mvn spring-boot:run` (нужен `OPENROUTER_API_KEY` для DevAssist/Support/Review).
4. Frontend: `cd frontend && npm run dev`

## VPS

Скрипты в `deploy/vps/`. Нужны переменные из `deploy/vps/env.example`, в том числе OpenRouter при использовании облачных ассистентов.

## Частые сбои

- Frontend `ERR_CONNECTION_REFUSED` на :5173 — не запущен Vite.
- Backend 8080 ок, ассистенты «не готов» — нет API key или пустой RAG-индекс.
