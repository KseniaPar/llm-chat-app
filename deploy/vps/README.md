# Деплой на выделенный сервер (VPS)

Локальный православный чат на **Ubuntu 22.04/24.04** без OpenRouter: Ollama + Spring Boot + nginx.

## Требования

| Параметр | Минимум |
|----------|---------|
| RAM | 8 GB (модель `qwen2.5:7b`) |
| CPU | 2 vCPU |
| Диск | 20 GB |
| ОС | Ubuntu 22.04 / 24.04 |

## Быстрый старт (3 команды)

На **свежем VPS** после `git clone`:

```bash
cd /opt/llm-chat          # или путь к репозиторию
sudo bash deploy/vps/bootstrap.sh
```

Скрипт сам: установит Java, Node, Ollama, nginx, ufw → сгенерирует секреты → соберёт и запустит приложение.

Откройте в браузере: `http://<IP-вашего-VPS>/`

## Пошагово (если нужен контроль)

```bash
# 1. Клонировать репозиторий
git clone -b day30 <url-репозитория> /opt/llm-chat
cd /opt/llm-chat

# 2. Один раз: подготовка сервера (root)
sudo bash deploy/vps/install-server.sh

# 3. Секреты (не root)
bash deploy/vps/setup-env.sh
# при необходимости: nano deploy/vps/.env

# 4. Сборка и запуск (root)
sudo bash deploy/vps/deploy-app.sh

# 5. Проверка
bash deploy/vps/verify-vps.sh
```

## HTTPS (Let's Encrypt)

Нужен домен, A-запись которого указывает на VPS:

```bash
sudo bash deploy/vps/enable-https.sh chat.example.org
# или с email: CERTBOT_EMAIL=you@mail.org sudo bash deploy/vps/enable-https.sh chat.example.org
```

## С Windows (загрузка на сервер)

Если репозиторий ещё не на VPS:

```powershell
.\deploy\vps\push-to-vps.ps1 -Server user@1.2.3.4 -RemotePath /opt/llm-chat
```

Дальше на сервере по SSH:

```bash
cd /opt/llm-chat
sudo bash deploy/vps/bootstrap.sh
```

## Что защищено

- **Backend** слушает только `127.0.0.1:8080` — с интернета недоступен напрямую
- **nginx** отдаёт только статику и два endpoint'а чата; остальные `/api/*` → 404
- **ufw**: открыты только 22, 80, 443
- **Секреты** в `deploy/vps/.env` (права `600`, не в git)
- **VpsStartupValidator** не даст запуститься с ключами `change-me-*`
- **Rate limit** nginx: ~15 запросов/мин на IP
- **Rate limit** приложения: 20 запросов/мин + лимит одновременных запросов
- **Демо-пользователь** агента выключен по умолчанию (`PLATFORM_DEMO_ENABLED=false`)

### Важно про API-ключ

Ключ `LOCAL_LLM_API_KEY` вшивается во frontend при сборке — это защита от случайного сканирования, не от целенаправленного доступа. Для закрытого сайта (приход, семья) этого достаточно вместе с rate limit.

## Обновление после изменений в коде

```bash
cd /opt/llm-chat
git pull
sudo bash deploy/vps/deploy-app.sh
```

## Полезные команды

```bash
# Логи приложения
journalctl -u llm-chat -f

# Статус
systemctl status llm-chat ollama nginx

# Перезапуск
sudo systemctl restart llm-chat

# Проверка чата
bash deploy/vps/verify-vps.sh
```

## Файлы

| Файл | Назначение |
|------|------------|
| `bootstrap.sh` | Всё в одном на новом VPS |
| `install-server.sh` | Java, Node, Ollama, nginx, ufw |
| `setup-env.sh` | Генерация `.env` со случайными секретами |
| `deploy-app.sh` | Сборка + nginx + systemd |
| `enable-https.sh` | Сертификат Let's Encrypt |
| `verify-vps.sh` | Проверка info + chat + закрытых API |
| `env.example` | Шаблон переменных (без секретов) |
| `.env` | **Ваши секреты — не коммитить** |

## Переменные `.env`

| Переменная | Описание |
|------------|----------|
| `JWT_SECRET` | Секрет JWT (генерируется автоматически) |
| `LOCAL_LLM_API_KEY` | Ключ чата (генерируется автоматически) |
| `LOCAL_LLM_MODEL` | Модель Ollama (`qwen2.5:7b`) |
| `NGINX_SERVER_NAME` | `_` или домен |
| `PLATFORM_DEMO_ENABLED` | `false` на продакшене |
