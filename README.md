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
mvn -pl mcp-servers/mcp-study,mcp-servers/mcp-scheduler,mcp-servers/mcp-pipeline,mcp-servers/mcp-git,mcp-servers/mcp-tickets -am package

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

## AI Code Review (Day 32)

На каждый Pull Request GitHub Action собирает diff, читает `README` + `project/docs` и фрагменты изменённых файлов, затем публикует комментарий с секциями:

- Потенциальные баги
- Архитектурные проблемы
- Рекомендации

Нужен secret репозитория **`OPENROUTER_API_KEY`**. Workflow: [`.github/workflows/pr-review.yml`](.github/workflows/pr-review.yml).

Локально (тот же скрипт, что в CI):

```bash
git diff main...HEAD > pr.diff
git diff --name-only main...HEAD > changed-files.txt
OPENROUTER_API_KEY=... node scripts/pr-review.mjs --diff pr.diff --files changed-files.txt --out review.md
```

Или через backend API (нужен запущенный Spring Boot + индекс PROJECT RAG опционален):

```bash
curl -s http://localhost:8080/api/review/status
curl -s http://localhost:8080/api/review/analyze -H "Content-Type: application/json" -d "{\"title\":\"local review\",\"baseRef\":\"main\"}"
```

## Ассистент поддержки (Day 33)

UI: **http://localhost:5173/support.html**

- FAQ RAG: `support/faq` → `RagStack.SUPPORT`
- Тикеты: JSON в `support/tickets` (копируются в `data/tickets`) через MCP **mcp-tickets**
- Пример: выберите `TKT-001` и спросите «Почему не работает авторизация?»

```bash
mvn -pl mcp-servers/mcp-tickets -am package
# backend + ollama nomic-embed-text + OPENROUTER_API_KEY

curl -s http://localhost:8080/api/support/status
curl -s http://localhost:8080/api/support/chat -H "Content-Type: application/json" \
  -d "{\"ticketId\":\"TKT-001\",\"question\":\"Почему не работает авторизация?\"}"
```

## Документация

- [Архитектура](project/docs/architecture.md)
- [Обзор API](project/docs/api-overview.md)
- [Модули и пакеты](project/docs/modules.md)
- [Схема данных](project/docs/data-schema.md)
- [AI PR Review](project/docs/pr-review.md)
- [Деплой VPS](deploy/vps/README.md)
