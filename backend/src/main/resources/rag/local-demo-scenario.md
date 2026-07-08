# Day 28 — Сценарий демо: полностью локальный RAG + сравнение с облаком

## Архитектура

| Компонент | LOCAL (основной) | CLOUD (сравнение) |
|-----------|------------------|-------------------|
| Embedding | Ollama `nomic-embed-text` | OpenRouter `text-embedding-3-small` |
| Индекс | `data/rag-index-local.db` | `data/rag-index.db` |
| Генерация | Ollama `qwen2.5:14b` | OpenRouter `gpt-4o-mini` |

Основной RAG работает **без OpenRouter**. Облако используется только в режимах сравнения (если задан `OPENROUTER_API_KEY`).

## Подготовка

1. Ollama запущен
2. Модели:
   ```bash
   ollama pull qwen2.5:14b
   ollama pull nomic-embed-text
   ```
3. Backend `:8080`, frontend `:5173`
4. Локальный индекс:
   - автоматически при старте (`app.rag.local.auto-index-on-startup: true`), если пуст
   - или кнопка **«Построить локальный индекс (Ollama)»** в UI
   - или `POST /api/rag/local/index`
5. Облачный индекс `data/rag-index.db` — из Недели 6 (для сравнения)
6. `OPENROUTER_API_KEY` — опционально, только для CLOUD-ветки

## Шаги сценария (6)

| # | Сложность | Тема | Что показать |
|---|-----------|------|--------------|
| 1 | Простой | Крещение | Локальный retrieval + LOCAL/CLOUD генерация |
| 2 | Простой | Кол-во таинств | Короткий факт из документа |
| 3 | Средний | Евхаристия | Несколько чанков, цитаты и sources |
| 4 | Средний | Храм | Структурированный ответ по разделу |
| 5 | Сложный | 7 таинств | Длинный ответ, сравнение качества |
| 6 | Off-topic | Борщ | Режим «не знаю» при слабом контексте |

## API

```bash
# Метаданные и список шагов
GET /api/rag/local/demo

# Статус индексов
GET /api/rag/local/index/status

# Построить локальный индекс (Ollama embeddings)
POST /api/rag/local/index
{"strategy": "STRUCTURE"}

# Только локальный RAG
POST /api/rag/query
{"question": "...", "useRag": true, "llmProvider": "LOCAL"}

# Сравнение LOCAL vs CLOUD (отдельные retrieval + генерация)
POST /api/rag/query/llm/compare
{"question": "...", "useRag": true}

# Один шаг демо
POST /api/rag/local/demo/run/1

# Весь сценарий
POST /api/rag/local/demo/run
```

## UI

1. Открыть http://localhost:5173
2. Проверить статус LOCAL/CLOUD индексов
3. При необходимости — **«Построить локальный индекс»**
4. **«Только LOCAL»** — полностью офлайн RAG
5. **«Сравнить LOCAL vs CLOUD»** — две параллельные ветки
6. **«Сценарий демо»** → шаги по одному или весь сценарий

## Ожидаемый результат

- Шаги 1–5: ответы с источниками из «Основы православия.pdf»
- Шаг 6: confidence UNKNOWN / «не знаю»
- LOCAL: медленнее, но полностью локально (embed + index + chat)
- CLOUD: быстрее при наличии OpenRouter; отдельный облачный индекс
