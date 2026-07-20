# FAQ: MCP

## Какие MCP-серверы есть?

| Сервер | Назначение |
|--------|------------|
| mcp-study | учебный справочник |
| mcp-scheduler | напоминания |
| mcp-pipeline | search → summarize → save |
| mcp-git | ветка / файлы / diff (Day 31) |
| mcp-tickets | JSON-тикеты поддержки (Day 33) |

Конфиг генерируется в `data/mcp-sandbox/mcp-servers.generated.json`.

## Почему tools не видны?

1. Модуль MCP не собран (`mvn -pl mcp-servers/mcp-git,mcp-servers/mcp-tickets -am package`).
2. Backend запущен не из каталога `backend/` — PostProcessor не находит `../mcp-servers/.../target`.
3. Нужен перезапуск после первой сборки.

Проверка: `GET /api/mcp/tools` — должны быть `getCurrentBranch`, `listTickets`, `getTicket`.

## mcp-tickets

Тикеты лежат в JSON (`support/tickets` → копируются/указываются как `TICKETS_DIR`).
Tools: `listTickets`, `getTicket`, `createTicket`, `updateTicketStatus`.

Статусы: `open` (открыт), `in_progress` (в работе), `resolved` (решён).
Переходы: open→in_progress|resolved; in_progress→open|resolved; resolved→open|in_progress.
