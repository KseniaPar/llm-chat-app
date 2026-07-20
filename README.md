# llm-chat-app

Образовательная платформа со stateful LLM-агентом: память, FSM задач, MCP-инструменты, RAG и локальный (Ollama) чат.

## Модули monorepo

| Модуль | Назначение |
|--------|------------|
| `backend/` | Spring Boot API: агент, auth, RAG, MCP-клиент, local LLM / platform |
| `frontend/` | Vite + Vanilla JS UI |
| `mcp-servers/` | STDIO MCP-серверы: study, scheduler, pipeline, **git** |
| `project/docs/` | Документация проекта для ассистента разработчика (Day 31) |
| `deploy/vps/` | Скрипты деплоя на VPS |

## Быстрый старт (локально)

```bash
# 1. Ollama + модели (эмбеддинги для PROJECT RAG)
ollama pull nomic-embed-text

# 2. MCP-серверы
mvn -pl mcp-servers/mcp-study,mcp-servers/mcp-scheduler,mcp-servers/mcp-pipeline,mcp-servers/mcp-git -am package

# 3. Backend (из каталога backend) — нужен OPENROUTER_API_KEY для DevAssist
mvn spring-boot:run

# 4. Frontend
cd frontend && npm install && npm run dev
```

Backend: `http://localhost:8080` · Frontend: `http://localhost:5173`

## Ассистент разработчика (Day 31)

Отдельный UI: **http://localhost:5173/dev.html** (не смешивается с учебным чатом).

**Tool-calling агент** (OpenRouter): сам выбирает инструменты

| Tool | Зачем |
|------|--------|
| `retrieveProjectDocs` | RAG по README + `project/docs` |
| `getCurrentBranch` | MCP git — текущая ветка |
| `listRepoFiles` | MCP git — список файлов |
| `getWorkingTreeDiff` | MCP git — working tree diff |

Нужен `OPENROUTER_API_KEY`. Эмбеддинги PROJECT-индекса — через Ollama (`nomic-embed-text`).

API:

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/devassist/status` | Git-ветка, RAG-индекс, MCP, LLM |
| POST | `/api/devassist/chat` | `{ "question": "…" }` — агент с tools |

Команда **`/help …`** в `POST /api/agent/chat` использует тот же сервис.

### Демо-вопросы

```bash
curl -s http://localhost:8080/api/devassist/chat -H "Content-Type: application/json" -d "{\"question\":\"Какие модули в monorepo?\"}"
curl -s http://localhost:8080/api/devassist/chat -H "Content-Type: application/json" -d "{\"question\":\"Какая сейчас git-ветка?\"}"
curl -s http://localhost:8080/api/devassist/chat -H "Content-Type: application/json" -d "{\"question\":\"Что изменено в working tree?\"}"
```

В ответе смотрите `mcpToolCalls` — агент должен сам вызвать tools (не только текст).

## Документация

- [Архитектура](project/docs/architecture.md)
- [Обзор API](project/docs/api-overview.md)
- [Модули и пакеты](project/docs/modules.md)
- [Схема данных](project/docs/data-schema.md)
- [Деплой VPS](deploy/vps/README.md)
