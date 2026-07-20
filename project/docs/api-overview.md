# Обзор REST API

## Auth

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/auth/register` | Регистрация |
| POST | `/api/auth/login` | Логин, JWT |

## Агент

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/agent/chat` | Чат со stateful-агентом; промпт `/help …` → ассистент разработчика |
| POST | `/api/agent/reset` | Сброс сессии |
| GET | `/api/agent/history` | История |
| GET | `/api/agent/memory` | Снимок памяти |
| POST | `/api/agent/task/pause` | Пауза задачи |
| POST | `/api/agent/task/resume` | Продолжение |

## RAG

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/rag/index` | Индексация учебного корпуса (cloud) |
| POST | `/api/rag/local/index` | Индексация local (Ollama) |
| GET | `/api/rag/local/index/status` | Статус local/cloud индексов |
| POST | `/api/rag/project/index` | Индексация README + `project/docs` |
| GET | `/api/rag/project/index/status` | Статус project-индекса |
| POST | `/api/rag/query` | Single-shot RAG Q&A |
| POST | `/api/rag/chat` | Multi-turn RAG чат |

## MCP

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/mcp/status` | Статус подключений |
| GET | `/api/mcp/tools` | Список tools (включая `getCurrentBranch`) |
| POST | `/api/mcp/reconnect` | Переподключение |
| POST | `/api/mcp/pipeline/run` | Демо pipeline |
| POST | `/api/mcp/orchestration/run` | Оркестрация study+pipeline+scheduler |

## Local LLM / platform

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/local-llm/service/info` | Статус приватного сервиса |
| POST | `/api/local-llm/service/chat` | Учебный чат (только православие) |
| POST | `/api/local-llm/service/verify` | Проверки сервиса |
| GET | `/api/platform/info` | Информация о платформе |
| POST | `/api/platform/verify` | Verify платформы |

## Ассистент разработчика (отдельный UI)

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/devassist/status` | Git, RAG project index, MCP, LLM |
| POST | `/api/devassist/chat` | Tool-calling: LLM сама вызывает RAG + mcp-git |

Frontend: `/dev.html` — не пересекается с учебным чатом (`/`).

## Профиль

| Метод | Путь | Описание |
|-------|------|----------|
| GET/PUT | `/api/user/profile` | Персонализация ответов |
