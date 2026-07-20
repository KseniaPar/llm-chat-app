const WELCOME_HTML = `
  <div class="files-app__welcome">
    <p class="files-app__welcome-title">Ассистент для работы с файлами проекта</p>
    <p class="files-app__welcome-text">
      Задайте цель — ассистент сам найдёт файлы через <code>searchFiles</code>,
      прочитает их и при необходимости запишет отчёт в <code>project/docs/</code>.
    </p>
    <div class="files-app__hints" role="group" aria-label="Примеры целей">
      <button type="button" class="files-app__hint" data-goal="Найди все упоминания SupportController и DevAssistController в backend и frontend. Запиши отчёт с путями и строками в project/docs/usage-assistants.md">
        Отчёт по ассистентам
      </button>
      <button type="button" class="files-app__hint" data-goal="По аннотациям @RestController в backend/src/main/java обнови раздел Support и Files в project/docs/api-overview.md — добавь недостающие эндпоинты">
        Обновить API docs
      </button>
    </div>
  </div>`;

const messagesEl = document.getElementById('files-messages');
const goalEl = document.getElementById('files-goal');
const formEl = document.getElementById('files-goal-form');
const sendBtn = document.getElementById('files-send-btn');
const newBtn = document.getElementById('files-new-btn');
const dryRunEl = document.getElementById('files-dry-run');
const errorEl = document.getElementById('files-error');
const metaEl = document.getElementById('files-meta');
const statusPill = document.getElementById('files-status-pill');
const tagline = document.getElementById('files-tagline');
const statRepo = document.getElementById('stat-repo');
const statMcp = document.getElementById('stat-mcp');
const statLast = document.getElementById('stat-last');
const appliedListEl = document.getElementById('applied-list');
const diffPanelEl = document.getElementById('diff-panel');

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
  goalEl.disabled = isBusy || !ready;
  dryRunEl.disabled = isBusy;
  newBtn.disabled = isBusy;
}

function clearWelcome() {
  messagesEl.querySelector('.files-app__welcome')?.remove();
}

function buildToolDetails(toolCalls) {
  if (!toolCalls?.length) return '';
  const items = toolCalls.map((t) =>
    `<li><code>${escapeHtml(t.toolName)}</code> · ${formatDuration(t.durationMs)}</li>`).join('');
  return `<details class="files-app__details"><summary>${toolCalls.length} tool(s)</summary><ul>${items}</ul></details>`;
}

function appendMessage(role, text, extras = {}) {
  clearWelcome();
  const isUser = role === 'user';
  const el = document.createElement('div');
  el.className = `files-app__msg files-app__msg--${isUser ? 'user' : 'bot'}`;
  const details = isUser ? '' : buildToolDetails(extras.toolCalls);
  el.innerHTML = `
    <div class="files-app__msg-avatar" aria-hidden="true">${isUser ? 'you' : 'fs'}</div>
    <div class="files-app__bubble">${escapeHtml(text)}${details}</div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function showTyping() {
  clearWelcome();
  const el = document.createElement('div');
  el.className = 'files-app__msg files-app__msg--bot';
  el.id = 'files-typing';
  el.innerHTML = `
    <div class="files-app__msg-avatar" aria-hidden="true">fs</div>
    <div class="files-app__bubble files-app__typing-label">Ищу и читаю файлы…</div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function hideTyping() {
  document.getElementById('files-typing')?.remove();
}

function renderWrites(writes, appliedPaths) {
  if (!appliedPaths?.length) {
    appliedListEl.innerHTML = '<li class="files-app__applied-empty">пока нет</li>';
  } else {
    appliedListEl.innerHTML = appliedPaths
      .map((p) => `<li><code>${escapeHtml(p)}</code></li>`)
      .join('');
  }
  const diffs = (writes || [])
    .filter((w) => w.unifiedDiff)
    .map((w) => `# ${w.path}${w.dryRun ? ' (dry run)' : ''}\n${w.unifiedDiff}`)
    .join('\n\n');
  diffPanelEl.textContent = diffs || '—';
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
    throw err;
  }
  return data;
}

function applyStatus(s) {
  statRepo.textContent = s.repoRoot || '—';
  statRepo.title = s.repoRoot || '';
  statMcp.textContent = s.filesToolAvailable ? 'online' : 'offline';
  tagline.textContent = s.filesToolAvailable ? 'mcp-files · read/search/write' : 'mcp-files не подключён';
  ready = s.llmReady && s.filesToolAvailable;
  if (ready) {
    statusPill.textContent = 'готов';
    statusPill.className = 'files-app__pill files-app__pill--online';
    showError('');
  } else {
    statusPill.textContent = 'не готов';
    statusPill.className = 'files-app__pill files-app__pill--offline';
    const issues = [];
    if (!s.llmReady) issues.push('нет OPENROUTER_API_KEY');
    if (!s.filesToolAvailable) issues.push('mcp-files не подключён');
    showError(issues.join('. ') || 'Сервис недоступен');
  }
}

async function loadStatus() {
  try {
    applyStatus(await api('/api/files/status'));
  } catch {
    ready = false;
    statusPill.textContent = 'offline';
    statusPill.className = 'files-app__pill files-app__pill--offline';
    showError('Backend недоступен на :8080');
  }
  setBusy(false);
}

async function runGoal(goal) {
  const text = goal.trim();
  if (!text || busy || !ready) return;
  showError('');
  setBusy(true);
  appendMessage('user', text);
  showTyping();
  try {
    const data = await api('/api/files/goal', {
      method: 'POST',
      body: JSON.stringify({ goal: text, dryRun: dryRunEl.checked }),
    });
    hideTyping();
    appendMessage('bot', data.answer || 'Пустой ответ.', { toolCalls: data.mcpToolCalls });
    renderWrites(data.writes, data.appliedPaths);
    const duration = formatDuration(data.durationMs);
    statLast.textContent = duration;
    metaEl.textContent = [
      duration,
      data.dryRun ? 'dry run' : `${data.appliedPaths?.length || 0} файл(ов)`,
    ].filter(Boolean).join(' · ');
  } catch (err) {
    hideTyping();
    showError(err.message);
  } finally {
    setBusy(false);
    goalEl.focus();
  }
}

function newTask() {
  messagesEl.innerHTML = WELCOME_HTML;
  bindHints();
  metaEl.textContent = '';
  renderWrites([], []);
  showError('');
  goalEl.focus();
}

function bindHints() {
  messagesEl.querySelectorAll('.files-app__hint').forEach((btn) => {
    btn.addEventListener('click', () => {
      goalEl.value = btn.dataset.goal || '';
      runGoal(goalEl.value);
    });
  });
}

formEl.addEventListener('submit', (e) => {
  e.preventDefault();
  const text = goalEl.value;
  goalEl.value = '';
  runGoal(text);
});

newBtn.addEventListener('click', newTask);
messagesEl.innerHTML = WELCOME_HTML;
bindHints();
setBusy(true);
loadStatus().then(() => goalEl.focus());
