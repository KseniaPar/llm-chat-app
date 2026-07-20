# File assistant (Day 34)

Goal-driven ассистент для чтения, поиска и записи файлов репозитория через MCP **mcp-files**.

## Возможности

| Tool | Описание |
|------|----------|
| `listFiles` | Список файлов по префиксу |
| `searchFiles` | Поиск regex/literal по проекту |
| `readFile` | Чтение текстового файла |
| `writeFile` | Запись в allowlist-пути + unified diff |

## Безопасность записи

Разрешено писать только в:

- `project/docs/**`
- `docs/**`
- `adr/**`
- `README.md`
- `CHANGELOG.md`

Запрещено: `.git`, `target`, `node_modules`, секреты.

## API

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/files/status` | MCP files, repo root, allowlist |
| POST | `/api/files/goal` | `{ goal, dryRun? }` — выполнить цель |

Ответ включает `appliedPaths`, `writes[]` с `unifiedDiff`, `mcpToolCalls`.

## UI

`/files.html` — цель на уровне задачи, timeline инструментов, панель diff.

## Демо-сценарии

1. **Отчёт по использованию** — найти `SupportController` / `DevAssistController` → `project/docs/usage-assistants.md`
2. **Обновить API docs** — по `@RestController` обновить `project/docs/api-overview.md`

## Сборка

```bash
mvn -pl mcp-servers/mcp-files,mcp-servers/mcp-git -am package -DskipTests
# перезапуск backend из backend/
```
