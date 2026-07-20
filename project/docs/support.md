# Support assistant (Day 33)

Мини-сервис поддержки пользователей:

1. FAQ в `support/faq/*.md` индексируется в `RagStack.SUPPORT`.
2. Тикеты — JSON в `support/tickets/` (при старте копируются в `data/tickets/`).
3. MCP `mcp-tickets`: `listTickets`, `getTicket`, `createTicket`, `updateTicketStatus`.
4. UI `/support.html` — выбор тикета, смена статуса (`open` / `in_progress` / `resolved`), вопрос с опорой на FAQ и данные тикета.

Переходы статусов: `open` → `in_progress|resolved`; `in_progress` → `open|resolved`; `resolved` → `open|in_progress`.
API: `PATCH /api/support/tickets/{id}/status` с телом `{"status":"in_progress"}`.

Демо: тикет `TKT-001` + вопрос «Почему не работает авторизация?»
