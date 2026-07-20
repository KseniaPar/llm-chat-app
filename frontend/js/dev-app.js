const WELCOME_HTML = `
  <div class="dev-app__welcome">
    <p class="dev-app__welcome-title">Вопросы о репозитории llm-chat-app</p>
    <p class="dev-app__welcome-text">
      Ассистент опирается на документацию проекта и при необходимости
      смотрит файлы и изменения в git. Спросите о структуре, API или схеме данных.
    </p>
    <div class="dev-app__hints" role="group" aria-label="Примеры вопросов">
      <button type="button" class="dev-app__hint" data-prompt="Какие модули в monorepo?">Модули</button>
      <button type="button" class="dev-app__hint" data-prompt="Где лежит RAG?">RAG</button>
      <button type="button" class="dev-app__hint" data-prompt="Какие таблицы в SQLite схеме?">Схема БД</button>
      <button type="button" class="dev-app__hint" data-prompt="Какая сейчас git-ветка?">Ветка</button>
      <button type="button" class="dev-app__hint" data-prompt="Что изменено в working tree?">Изменения</button>
    </div>
  </div>`;

const messagesEl = document.getElementById('dev-messages');
const promptEl = document.getElementById('dev-prompt');
const formEl = document.getElementById('dev-chat-form');
const sendBtn = document.getElementById('dev-send-btn');
const newChatBtn = document.getElementById('dev-new-chat-btn');
const errorEl = document.getElementById('dev-error');
const metaEl = document.getElementById('dev-meta');
const statusPill = document.getElementById('dev-status-pill');
const tagline = document.getElementById('dev-tagline');

const statBranch = document.getElementById('stat-branch');
const statCommit = document.getElementById('stat-commit');
const statRag = document.getElementById('stat-rag');
const statDocs = document.getElementById('stat-docs');
const statChunks = document.getElementById('stat-chunks');
const statLast = document.getElementById('stat-last');

let ready = false;
let busy = false;

function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return '—';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} с`;
}

function showError(msg) {
  if (!msg) {
    errorEl.classList.add('hidden');
    errorEl.textContent = '';
    return;
  }
  errorEl.textContent = msg;
  errorEl.classList.remove('hidden');
}

function setBusy(isBusy) {
  busy = isBusy;
  sendBtn.disabled = isBusy || !ready;
  promptEl.disabled = isBusy || !ready;
  newChatBtn.disabled = isBusy;
}

function clearWelcome() {
  messagesEl.querySelector('.dev-app__welcome')?.remove();
}

function buildDetailsHtml(sources, toolCalls) {
  const hasSources = sources?.length > 0;
  const hasTools = toolCalls?.length > 0;
  if (!hasSources && !hasTools) return '';

  let inner = '';
  if (hasSources) {
    inner += '<p class="dev-app__details-label">Источники</p><ul>'
      + sources.map((s) => `<li>${escapeHtml(s)}</li>`).join('')
      + '</ul>';
  }
  if (hasTools) {
    inner += '<p class="dev-app__details-label">Доп. запросы</p><ul>'
      + toolCalls.map((t) => {
        const name = t.toolName || 'tool';
        return `<li>${escapeHtml(name)} · ${formatDuration(t.durationMs)}</li>`;
      }).join('')
      + '</ul>';
  }
  const label = [
    hasSources ? `${sources.length} источник(а)` : '',
    hasTools ? `${toolCalls.length} доп. запрос(а)` : '',
  ].filter(Boolean).join(' · ');

  return `<details class="dev-app__details"><summary>${escapeHtml(label)}</summary>${inner}</details>`;
}

function appendMessage(role, text, extras = {}) {
  clearWelcome();
  const isUser = role === 'user';
  const el = document.createElement('div');
  el.className = `dev-app__msg dev-app__msg--${isUser ? 'user' : 'bot'}`;
  const details = isUser ? '' : buildDetailsHtml(extras.sources, extras.toolCalls);
  el.innerHTML = `
    <div class="dev-app__msg-avatar" aria-hidden="true">${isUser ? 'you' : 'dev'}</div>
    <div class="dev-app__bubble">${escapeHtml(text)}${details}</div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function showTyping() {
  clearWelcome();
  const el = document.createElement('div');
  el.className = 'dev-app__msg dev-app__msg--bot';
  el.id = 'dev-typing';
  el.innerHTML = `
    <div class="dev-app__msg-avatar" aria-hidden="true">dev</div>
    <div class="dev-app__bubble dev-app__typing-label">
      Ищу в документации…
    </div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function hideTyping() {
  document.getElementById('dev-typing')?.remove();
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  });
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = { error: text }; }
  }
  if (!res.ok) {
    const err = new Error(data?.message || data?.error || `HTTP ${res.status}`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

function applyStatus(s) {
  statBranch.textContent = s.gitBranch || '—';
  statCommit.textContent = s.gitCommit || '—';
  statRag.textContent = s.projectIndexReady ? 'готов' : 'пуст';
  statDocs.textContent = String(s.projectDocuments ?? '—');
  statChunks.textContent = String(s.projectChunks ?? '—');

  ready = s.llmReady && s.projectIndexReady;
  tagline.textContent = s.projectIndexReady
    ? `${s.projectDocuments ?? 0} док. · ветка ${s.gitBranch || '—'}`
    : 'индекс документации не готов';

  if (ready) {
    statusPill.textContent = 'готов';
    statusPill.className = 'dev-app__pill dev-app__pill--online';
    showError('');
  } else {
    statusPill.textContent = 'не готов';
    statusPill.className = 'dev-app__pill dev-app__pill--offline';
    const issues = [];
    if (!s.llmReady) issues.push('нет ключа OpenRouter');
    if (!s.projectIndexReady) issues.push('индекс документации пуст');
    showError(issues.join('. ') || 'Сервис недоступен');
  }
}

async function loadStatus() {
  try {
    applyStatus(await api('/api/devassist/status'));
  } catch {
    ready = false;
    statusPill.textContent = 'offline';
    statusPill.className = 'dev-app__pill dev-app__pill--offline';
    showError('Backend недоступен. Запустите Spring Boot на :8080.');
  }
  setBusy(false);
}

async function sendMessage(question) {
  const text = question.trim();
  if (!text || busy || !ready) return;

  showError('');
  setBusy(true);
  appendMessage('user', text);
  showTyping();

  try {
    const data = await api('/api/devassist/chat', {
      method: 'POST',
      body: JSON.stringify({ question: text }),
    });
    hideTyping();
    appendMessage('bot', data.answer || 'Пустой ответ.', {
      sources: data.sources,
      toolCalls: data.mcpToolCalls,
    });
    const duration = formatDuration(data.durationMs);
    statLast.textContent = duration;
    metaEl.textContent = duration;
    if (data.gitBranch) {
      statBranch.textContent = data.gitBranch;
    }
  } catch (err) {
    hideTyping();
    showError(err.message);
  } finally {
    setBusy(false);
    promptEl.focus();
  }
}

function newChat() {
  messagesEl.innerHTML = WELCOME_HTML;
  bindHints();
  metaEl.textContent = '';
  showError('');
  promptEl.focus();
}

function bindHints() {
  messagesEl.querySelectorAll('.dev-app__hint').forEach((btn) => {
    btn.addEventListener('click', () => sendMessage(btn.dataset.prompt || ''));
  });
}

function resizeInput() {
  promptEl.style.height = 'auto';
  promptEl.style.height = `${Math.min(promptEl.scrollHeight, 140)}px`;
}

formEl.addEventListener('submit', (e) => {
  e.preventDefault();
  const text = promptEl.value;
  promptEl.value = '';
  resizeInput();
  sendMessage(text);
});

promptEl.addEventListener('input', resizeInput);
promptEl.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    formEl.requestSubmit();
  }
});

newChatBtn.addEventListener('click', newChat);
messagesEl.innerHTML = WELCOME_HTML;
bindHints();

setBusy(true);
loadStatus().then(() => promptEl.focus());
