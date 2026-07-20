# Схема данных

Обзор персистентности llm-chat-app (для ассистента разработчика / RAG PROJECT).

## Основная БД приложения

Путь: `backend/data/llm-chat.db` (`app.database.path`), SQLite.

Инициализация: `SchemaInitializer`.

| Таблица | Назначение |
|--------|------------|
| `users` | Учётки (`id`, `username`, `password_hash`) |
| `sessions` | Диалоговые сессии агента (`user_id`, `context_strategy`, `state_json`) |
| `short_term_messages` | SHORT-память: сообщения сессии (`role`, `content`, `segment`, `seq`) |
| `working_memory` | WORKING: факты задачи (`session_id`, `key`, `value`) |
| `working_summary` | WORKING: краткое summary сессии |
| `long_term_memory` | LONG: устойчивые факты пользователя (`category`, `key`, `value`) |
| `user_profiles` | Профиль персонализации (`display_name`, `response_style`, …) |
| `session_task_state` | FSM задачи: `phase`, `current_step`, `expected_action`, `paused` |
| `session_task_transitions` | Аудит переходов FSM |

## RAG-индексы

Отдельные SQLite-файлы эмбеддингов (не смешиваются с `llm-chat.db`):

| Индекс | Путь (типично) | Корпус |
|--------|----------------|--------|
| CLOUD | `data/rag-index.db` | учебный `data/rag-corpus` |
| LOCAL | `data/rag-local-index.db` | тот же корпус, локальные эмбеддинги |
| **PROJECT** | `data/rag-project-index.db` | `README.md` + `project/docs` |

Чанки: стратегия FIXED или STRUCTURE; метаданные source / section / score при retrieval.

## Прочие файловые хранилища

| Путь | Назначение |
|------|------------|
| `data/conversations.json` | опциональный JSON store диалогов агента |
| `data/mcp-sandbox/` | сгенерированный MCP servers config + sandbox |
| `data/pipeline/` | вывод `mcp-pipeline` (`saveToFile`) |
| `mcp-servers/mcp-study` SQLite | справочник тем / exam outline |

## Связь с API

- Auth / сессии / память / FSM — через `/api/auth`, `/api/agent`, `/api/profile`, …
- PROJECT RAG — `/api/rag/project/index`, `/api/devassist/*`
- Схема выше описывает **состояние платформы**, не учебный корпус православия.
