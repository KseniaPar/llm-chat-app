# FAQ: RAG и индекс документации

## Что такое PROJECT RAG?

Индекс `RagStack.PROJECT` строится из `README.md` и `project/docs` и лежит в `data/rag-project-index.db`.
Его использует ассистент разработчика (`/dev.html`, `/help`).

## Почему «индекс пуст»?

- Backend ещё не успел сделать auto-index при старте.
- Нет модели эмбеддингов Ollama (`nomic-embed-text`).
- Источники `app.rag.project-sources` указывают не туда.

## Что сделать?

```bash
ollama pull nomic-embed-text
curl -X POST http://localhost:8080/api/rag/project/index
curl http://localhost:8080/api/rag/project/index/status
```

На `/dev.html` должны появиться документы и чанки > 0.

## SUPPORT RAG (Day 33)

Отдельный индекс FAQ поддержки: `support/faq` → `data/rag-support-index.db`.
Не путать с учебным корпусом православия и с PROJECT docs.
