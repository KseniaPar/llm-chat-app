# AI PR Review (Day 32)

Автоматическое ревью Pull Request:

1. GitHub Action собирает `git diff base...head` и список файлов.
2. Скрипт `scripts/pr-review.mjs` (или `POST /api/review/analyze`) подмешивает `README` + `project/docs` и фрагменты изменённых файлов.
3. OpenRouter генерирует markdown с секциями:
   - Потенциальные баги
   - Архитектурные проблемы
   - Рекомендации
4. Комментарий публикуется (или обновляется) в PR.

Secret: `OPENROUTER_API_KEY` в настройках репозитория.
