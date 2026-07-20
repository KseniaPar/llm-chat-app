# Архитектура

## Слои backend

```text
Frontend (Vite)
    ↓ HTTP / JWT
Controllers (/api/*)
    ↓
ChatAgent / LocalLlmPrivateService / Rag* / Mcp*
    ↓
Stores (SQLite) · Ollama / OpenRouter · MCP STDIO servers
```

### Auth и сессии

- JWT auth (`/api/auth/register`, `/api/auth/login`)
- Сессии диалога привязаны к `user_id` (SQLite)

### Память агента

Три слоя в пакете `com.example.llmchat.memory`:

- **SHORT** — сообщения текущего диалога
- **WORKING** — факты задачи, summary
- **LONG** — профиль и устойчивые знания пользователя

Сборщик промпта: `ContextAssembler`.

### Task FSM

Пакет `com.example.llmchat.task`: состояния планирования / выполнения / валидации, пауза/продолжить, аудит переходов и инварианты (`invariants`).

### RAG

Пакет `com.example.llmchat.rag`:

- учебный корпус (`data/rag-corpus`) → индексы LOCAL / CLOUD
- **документация проекта** (`README.md` + `project/docs`) → индекс `RagStack.PROJECT` (`data/rag-project-index.db`)
- chunking (FIXED / STRUCTURE), эмбеддинги (Ollama / OpenRouter), retrieval + цитаты

### MCP

Клиент в backend подключает STDIO-серверы:

| Сервер | Роль |
|--------|------|
| `mcp-study` | справочник тем / экзамен |
| `mcp-scheduler` | напоминания и сводки |
| `mcp-pipeline` | search → summarize → saveToFile |
| `mcp-git` | текущая ветка, список файлов, diff |

Конфиг генерируется `McpSandboxEnvironmentPostProcessor` → `data/mcp-sandbox/mcp-servers.generated.json`.

### Local LLM / platform

- `LocalLlmPrivateService` — публичный учебный чат о православии (`/api/local-llm/service/*`)
- `DeveloperAssistantService` — tool-calling агент (`/help`, `/api/devassist/chat`): сам вызывает `retrieveProjectDocs` + MCP git
- `PrReviewService` — AI-ревью PR/diff (`/api/review/analyze`) + GitHub Action
- `PlatformServerService` — info / verify платформы
