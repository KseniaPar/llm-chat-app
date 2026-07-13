const WELCOME_HTML = `
  <div class="ai-server__welcome">
    <p class="ai-server__welcome-title">Вопросы о православной вере</p>
    <p class="ai-server__welcome-text">
      Спокойный разговор о вере, Церкви, таинствах, молитве и духовной жизни.
      Ответы формируются локально на вашем сервере.
    </p>
    <p class="ai-server__welcome-note">
      Это учебный собеседник, а не священник. По личным духовным вопросам
      лучше обратиться к приходскому священнослужителю.
    </p>
    <p class="ai-server__welcome-note">Короткие вопросы отвечают быстрее.</p>
    <div class="ai-server__hints" role="group" aria-label="Примеры вопросов">
      <button type="button" class="ai-server__hint" data-prompt="Что такое пост?">Пост</button>
      <button type="button" class="ai-server__hint" data-prompt="Сколько таинств в Церкви?">Таинства</button>
      <button type="button" class="ai-server__hint" data-prompt="Зачем нужна молитва?">Молитва</button>
    </div>
  </div>`;

const DISRESPECTFUL_PATTERNS = [
  /\bбог\s+не\s+существует\b/i,
  /\bнет\s+бога\b/i,
  /\bрелигия\s+(?:для|—\s*)?(?:слабаков|глупцов|отсталых)\b/i,
  /\bправослав(?:ие|н\w*)\s+(?:лох|бред|чушь|лажа|дичь)\b/i,
  /\b(?:священник|поп|батюшк)\w*\s+(?:лох|мошенник|вор)\b/i,
  /\bцерковь\s+(?:обман|развод|бизнес)\b/i,
];

const API_KEY = import.meta.env.VITE_LOCAL_LLM_API_KEY || '';

const messagesEl = document.getElementById('messages');
const promptEl = document.getElementById('prompt');
const formEl = document.getElementById('chat-form');
const sendBtn = document.getElementById('send-btn');
const newChatBtn = document.getElementById('new-chat-btn');
const errorEl = document.getElementById('error');
const metaEl = document.getElementById('meta');
const modelLabel = document.getElementById('model-label');
const statusPill = document.getElementById('status-pill');

let ready = false;
let busy = false;

function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function isDisrespectful(text) {
  const normalized = text.trim();
  return DISRESPECTFUL_PATTERNS.some((pattern) => pattern.test(normalized));
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
  messagesEl.querySelector('.ai-server__welcome')?.remove();
}

function appendMessage(role, text) {
  clearWelcome();
  const isUser = role === 'user';
  const el = document.createElement('div');
  el.className = `ai-server__msg ai-server__msg--${isUser ? 'user' : 'bot'}`;
  el.innerHTML = `
    <div class="ai-server__msg-avatar" aria-hidden="true">${isUser ? 'Вы' : '·'}</div>
    <div class="ai-server__bubble">${escapeHtml(text)}</div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
  return el;
}

function showTyping() {
  clearWelcome();
  const el = document.createElement('div');
  el.className = 'ai-server__msg ai-server__msg--bot';
  el.id = 'typing-indicator';
  el.innerHTML = `
    <div class="ai-server__msg-avatar" aria-hidden="true">·</div>
    <div class="ai-server__bubble ai-server__typing">
      <span></span><span></span><span></span>
    </div>`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function hideTyping() {
  document.getElementById('typing-indicator')?.remove();
}

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (API_KEY) headers['X-Local-Llm-Api-Key'] = API_KEY;
  const res = await fetch(path, { ...options, headers });
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = { error: text }; }
  }
  if (!res.ok) {
    const err = new Error(data?.error || `HTTP ${res.status}`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

async function loadStatus() {
  try {
    const info = await api('/api/local-llm/service/info');
    ready = info.online && info.modelAvailable;
    modelLabel.textContent = 'учебный собеседник · локально';
    statusPill.textContent = ready ? 'готов' : 'недоступен';
    statusPill.className = `ai-server__pill ai-server__pill--${ready ? 'online' : 'offline'}`;
    if (!ready) showError(info.message);
    else showError('');
  } catch {
    ready = false;
    modelLabel.textContent = 'сервис недоступен';
    statusPill.textContent = 'недоступен';
    statusPill.className = 'ai-server__pill ai-server__pill--offline';
    showError('Сервис временно недоступен. Проверьте, что backend запущен.');
  }
  setBusy(false);
}

async function sendMessage(prompt) {
  const text = prompt.trim();
  if (!text || busy || !ready) return;

  if (isDisrespectful(text)) {
    showError('Пожалуйста, задавайте вопросы уважительно и по теме православной веры.');
    return;
  }

  showError('');
  setBusy(true);
  appendMessage('user', text);
  showTyping();

  try {
    const data = await api('/api/local-llm/service/chat', {
      method: 'POST',
      body: JSON.stringify({ prompt: text }),
    });
    hideTyping();
    const answer = data.answer || 'Не удалось получить ответ. Попробуйте переформулировать вопрос.';
    appendMessage('bot', answer);
    if (data.durationMs > 0) {
      metaEl.textContent = `ответ сформирован локально · ${data.durationMs} мс`;
    } else {
      metaEl.textContent = 'вопрос вне темы собеседника';
    }
  } catch (err) {
    hideTyping();
    const msg = err.status === 429
      ? `${err.message} (повторите через ${err.data?.retryAfterSeconds ?? '?'} с)`
      : err.message;
    showError(msg);
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
  messagesEl.querySelectorAll('.ai-server__hint').forEach((btn) => {
    btn.addEventListener('click', () => sendMessage(btn.dataset.prompt || ''));
  });
}

function resizeInput() {
  promptEl.style.height = 'auto';
  promptEl.style.height = `${Math.min(promptEl.scrollHeight, 120)}px`;
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
