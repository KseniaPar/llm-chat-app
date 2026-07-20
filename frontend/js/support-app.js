const WELCOME_HTML = `
  <div class="support-app__welcome">
    <p class="support-app__welcome-title">Поддержка пользователей llm-chat-app</p>
    <p class="support-app__welcome-text">
      Ответы строятся по FAQ (<code>support/faq</code>) и контексту выбранного JSON-тикета (MCP mcp-tickets).
    </p>
    <div class="support-app__hints" role="group" aria-label="Примеры вопросов">
      <button type="button" class="support-app__hint" data-prompt="Почему не работает авторизация?">Авторизация</button>
      <button type="button" class="support-app__hint" data-prompt="Почему пустой индекс документации?">Индекс RAG</button>
      <button type="button" class="support-app__hint" data-prompt="Почему не виден MCP git?">MCP</button>
    </div>
  </div>`;

const messagesEl = document.getElementById('support-messages');
const promptEl = document.getElementById('support-prompt');
const formEl = document.getElementById('support-chat-form');
const sendBtn = document.getElementById('support-send-btn');
const newChatBtn = document.getElementById('support-new-chat-btn');
const errorEl = document.getElementById('support-error');
const metaEl = document.getElementById('support-meta');
const statusPill = document.getElementById('support-status-pill');
const tagline = document.getElementById('support-tagline');
const ticketListEl = document.getElementById('ticket-list');
const ticketLabelEl = document.getElementById('support-ticket-label');
const statFaq = document.getElementById('stat-faq');
const statDocs = document.getElementById('stat-docs');
const statTickets = document.getElementById('stat-tickets');
const statLast = document.getElementById('stat-last');

let ready = false;
let busy = false;
let selectedTicketId = null;

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
  messagesEl.querySelector('.support-app__welcome')?.remove();
}

function buildDetailsHtml(sources, toolCalls) {
  const hasSources = sources?.length > 0;
  const hasTools = toolCalls?.length > 0;
  if (!hasSources && !hasTools) return '';
  let inner = '';
  if (hasSources) {
    inner += '<p class="support-app__details-label">Источники FAQ</p><ul>'
      + sources.map((s) => `<li>${escapeHtml(s)}</li>`).join('') + '</ul>';
  }
  if (hasTools) {
    inner += '<p class="support-app__details-label">Инструменты</p><ul>'
      + toolCalls.map((t) => `<li>${escapeHtml(t.toolName)} · ${formatDuration(t.durationMs)}</li>`).join('')
      + '</ul>';
  }
  const label = [
    hasSources ? `${sources.length} FAQ` : '',
    hasTools ? `${toolCalls.length} tool(s)` : '',
  ].filter(Boolean).join(' · ');
  return `<details class="support-app__details"><summary>${escapeHtml(label)}</summary>${inner}</details>`;
}

function appendMessage(role, text, extras = {}) {
  clearWelcome();
  const isUser = role === 'user';
  const el = document.createElement('div');
  el.className = `support-app__msg support-app__msg--${isUser ? 'user' : 'bot'}`;
  const details = isUser ? '' : buildDetailsHtml(extras.sources, extras.toolCalls);
  el.innerHTML = `
    <div class="support-app__msg-avatar" aria-hidden="true">${isUser ? 'you' : 'sup'}</div>
    <div class="support-app__bubble">${escapeHtml(text)}${details}</div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function showTyping() {
  clearWelcome();
  const el = document.createElement('div');
  el.className = 'support-app__msg support-app__msg--bot';
  el.id = 'support-typing';
  el.innerHTML = `
    <div class="support-app__msg-avatar" aria-hidden="true">sup</div>
    <div class="support-app__bubble support-app__typing-label">Смотрю FAQ и тикет…</div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function hideTyping() {
  document.getElementById('support-typing')?.remove();
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

function renderTickets(payload) {
  const tickets = payload?.tickets || [];
  if (!tickets.length) {
    ticketListEl.innerHTML = '<p class="support-app__sidebar-note">Тикеты не найдены. Соберите mcp-tickets и перезапустите backend.</p>';
    return;
  }
  ticketListEl.innerHTML = tickets.map((t) => {
    const status = t.status || 'open';
    const options = [status, ...(t.allowedNext || [])]
      .filter((value, index, arr) => arr.indexOf(value) === index)
      .map((value) => `<option value="${escapeHtml(value)}"${value === status ? ' selected' : ''}>${escapeHtml(statusLabel(value))}</option>`)
      .join('');
    return `
    <div class="support-app__ticket${selectedTicketId === t.id ? ' support-app__ticket--active' : ''}" data-id="${escapeHtml(t.id)}">
      <button type="button" class="support-app__ticket-select" data-id="${escapeHtml(t.id)}">
        <div class="support-app__ticket-id">${escapeHtml(t.id)}</div>
        <div class="support-app__ticket-subject">${escapeHtml(t.subject || '')}</div>
      </button>
      <label class="support-app__ticket-status">
        <span class="support-app__ticket-status-label">статус</span>
        <select class="support-app__ticket-status-select" data-id="${escapeHtml(t.id)}" aria-label="Статус ${escapeHtml(t.id)}">
          ${options}
        </select>
      </label>
    </div>`;
  }).join('');

  ticketListEl.querySelectorAll('.support-app__ticket-select').forEach((btn) => {
    btn.addEventListener('click', () => {
      selectedTicketId = btn.dataset.id;
      ticketLabelEl.textContent = `Тикет: ${selectedTicketId}`;
      renderTickets(payload);
    });
  });

  ticketListEl.querySelectorAll('.support-app__ticket-status-select').forEach((select) => {
    select.addEventListener('change', async () => {
      const ticketId = select.dataset.id;
      const status = select.value;
      select.disabled = true;
      try {
        showError('');
        await api(`/api/support/tickets/${encodeURIComponent(ticketId)}/status`, {
          method: 'PATCH',
          body: JSON.stringify({ status }),
        });
        await loadTickets();
        await loadStatus();
      } catch (err) {
        showError(err.message);
        await loadTickets();
      } finally {
        select.disabled = false;
      }
    });
  });
}

const STATUS_LABELS = {
  open: 'открыт',
  in_progress: 'в работе',
  resolved: 'решён',
};

function statusLabel(status) {
  return STATUS_LABELS[status] || status;
}

function applyStatus(s) {
  statFaq.textContent = s.supportIndexReady ? 'готов' : 'пуст';
  statDocs.textContent = String(s.supportDocuments ?? '—');
  statTickets.textContent = s.ticketsToolAvailable
    ? `${s.ticketCount ?? 0} шт.`
    : 'offline';
  tagline.textContent = s.supportIndexReady
    ? `FAQ ${s.supportDocuments ?? 0} · тикеты ${s.ticketCount ?? 0}`
    : 'индекс FAQ не готов';

  ready = s.llmReady && s.supportIndexReady && s.ticketsToolAvailable;
  if (ready) {
    statusPill.textContent = 'готов';
    statusPill.className = 'support-app__pill support-app__pill--online';
    showError('');
  } else {
    statusPill.textContent = 'не готов';
    statusPill.className = 'support-app__pill support-app__pill--offline';
    const issues = [];
    if (!s.llmReady) issues.push('нет OPENROUTER_API_KEY');
    if (!s.supportIndexReady) issues.push('FAQ индекс пуст');
    if (!s.ticketsToolAvailable) issues.push('mcp-tickets не подключён');
    showError(issues.join('. ') || 'Сервис недоступен');
  }
}

async function loadStatus() {
  try {
    applyStatus(await api('/api/support/status'));
  } catch {
    ready = false;
    statusPill.textContent = 'offline';
    statusPill.className = 'support-app__pill support-app__pill--offline';
    showError('Backend недоступен на :8080');
  }
  setBusy(false);
}

async function loadTickets() {
  try {
    const data = await api('/api/support/tickets');
    renderTickets(data);
    if (!selectedTicketId && data?.tickets?.length) {
      selectedTicketId = data.tickets[0].id;
      ticketLabelEl.textContent = `Тикет: ${selectedTicketId}`;
      renderTickets(data);
    }
  } catch (err) {
    ticketListEl.innerHTML = `<p class="support-app__sidebar-note">${escapeHtml(err.message)}</p>`;
  }
}

async function sendMessage(question) {
  const text = question.trim();
  if (!text || busy || !ready) return;
  showError('');
  setBusy(true);
  appendMessage('user', text);
  showTyping();
  try {
    const data = await api('/api/support/chat', {
      method: 'POST',
      body: JSON.stringify({ question: text, ticketId: selectedTicketId }),
    });
    hideTyping();
    appendMessage('bot', data.answer || 'Пустой ответ.', {
      sources: data.sources,
      toolCalls: data.mcpToolCalls,
    });
    const duration = formatDuration(data.durationMs);
    statLast.textContent = duration;
    metaEl.textContent = [duration, data.ticketId || selectedTicketId].filter(Boolean).join(' · ');
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
  messagesEl.querySelectorAll('.support-app__hint').forEach((btn) => {
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
Promise.all([loadStatus(), loadTickets()]).then(() => promptEl.focus());
