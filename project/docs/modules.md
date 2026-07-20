# Модули и пакеты

## Maven-модули

```text
llm-chat-app/
├── backend/                 # Spring Boot приложение
├── frontend/                # UI
└── mcp-servers/
    ├── mcp-study/
    ├── mcp-scheduler/
    ├── mcp-pipeline/
    ├── mcp-git/             # Day 31 — git branch / files / diff
    └── mcp-tickets/         # Day 33 — JSON support tickets
```

## Пакеты `com.example.llmchat`

| Пакет | Назначение |
|-------|------------|
| `agent` | `ChatAgent`, completion, стратегии контекста |
| `auth` | JWT, пользователи |
| `controller` | REST-контроллеры |
| `devassist` | Ассистент разработчика: tool-calling (RAG docs + MCP git) |
| `invariants` | Бизнес-правила состояний |
| `localllm` | Ollama, private service, topic guard |
| `mcp` | MCP-клиент, orchestration, pipeline, sandbox PostProcessor |
| `memory` | SHORT / WORKING / LONG + ContextAssembler |
| `personalization` | Профиль пользователя |
| `platform` | Info / verify платформы |
| `rag` | Индексация, retrieval, query, project docs index |
| `review` | Day 32 AI PR review (docs + diff → markdown) |
| `support` | Day 33 support assistant (FAQ RAG + mcp-tickets) |
| `task` | Task FSM, переходы, pause/resume |

## MCP tools (git) + DevAssist RAG tool

| Tool | Источник | Описание |
|------|----------|----------|
| `getCurrentBranch` | mcp-git | Текущая ветка |
| `listRepoFiles` | mcp-git | `git ls-files` с optional prefix / limit |
| `getWorkingTreeDiff` | mcp-git | `git diff --stat` и усечённый diff |
| `retrieveProjectDocs` | DevAssist (Spring AI) | RAG по README + `project/docs` |

Агент на `/dev.html` и `/help` **сам выбирает** эти инструменты (не эвристики по ключевым словам).

## Где искать RAG

Исходники RAG: `backend/src/main/java/com/example/llmchat/rag/`.

Индексы (относительно `backend/` при запуске):

- учебный local: `data/rag-index-local.db`
- учебный cloud: `data/rag-index.db`
- документация проекта: `data/rag-project-index.db`
