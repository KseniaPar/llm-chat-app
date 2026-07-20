# Exam Prep Assistant (Day 35)

Аудио лекции → STT (OpenRouter Whisper) → транскрипт с таймкодами → RAG → экзаменационные ответы с цитатами.

## UI

- `/exam.html` — загрузка MP3/M4A, прогресс job, транскрипт, Q&A, кнопка «Сделать конспект».

## API

| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/exam/status` | STT/RAG статус |
| POST | `/api/exam/upload` | multipart `file`, опц. `title`, `subject` |
| GET | `/api/exam/jobs` | список задач |
| GET | `/api/exam/jobs/{id}` | задача + сегменты (когда READY) |
| POST | `/api/exam/chat` | `{ "question", "lectureTitle"? }` |
| POST | `/api/exam/conspect` | `{ "jobId" }` → `data/exam-notes/*.md` |

## Ключи

- `OPENROUTER_API_KEY` — STT (`openai/whisper-large-v3`) + chat + (опц.) clean/conspect LLM
- Ollama — embeddings для `RagStack.EXAM` (локально, как SUPPORT/PROJECT)

## Демо

1. Короткий MP3 из `C:\Users\user\Desktop\религиоведение\феноменология\` (для дедлайна — не целый 200 МБ файл).
2. Дождаться статуса `READY`, задать вопрос по лекции.
3. «Сделать конспект» → файл в `data/exam-notes/`.

## Pipeline

```
audio → ffmpeg chunks (~10 min, mono 16 kHz)
     → STT каждой части (OpenRouter Whisper)
     → merge segments с offset таймкодов
     → clean.md → RagStack.EXAM
     → cited Q&A + conspect
```
