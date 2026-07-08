const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const newDialogBtn = document.getElementById('new-dialog-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const messagesEl = document.getElementById('messages');
const statusIndicator = document.getElementById('status-indicator');
const statusMessage = document.getElementById('status-message');
const localModelLabel = document.getElementById('local-model-label');

const CHAR_DELAY_MS = 12;
const SESSION_STORAGE_KEY = 'local-llm-chat-session-id';

let activeRequestId = 0;
let sessionId = null;
let ollamaReady = false;

function setControlsDisabled(isDisabled) {
  sendBtn.disabled = isDisabled || !ollamaReady;
  promptInput.disabled = isDisabled || !ollamaReady;
  newDialogBtn.disabled = isDisabled;
}

function setLoading(isLoading) {
  loadingEl.classList.toggle('hidden', !isLoading);
}

function showError(message) {
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
}

function clearError() {
  errorEl.textContent = '';
  errorEl.classList.add('hidden');
}

function persistSessionId(value) {
  if (value) {
    localStorage.setItem(SESSION_STORAGE_KEY, value);
  } else {
    localStorage.removeItem(SESSION_STORAGE_KEY);
  }
}

async function apiFetch(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: text };
    }
  }
  if (!response.ok) {
    throw new Error(data?.error || `HTTP ${response.status}`);
  }
  return data;
}

async function loadStatus() {
  try {
    const status = await apiFetch('/api/local-llm/status');
    ollamaReady = status.online && status.modelAvailable;
    statusIndicator.className = `status-dot ${ollamaReady ? 'status-dot--online' : 'status-dot--offline'}`;
    statusMessage.textContent = status.message;
    localModelLabel.textContent = ollamaReady
      ? `Локальная LLM · ${status.configuredModel}`
      : `Ollama offline · ${status.configuredModel}`;
    if (!ollamaReady) {
      showError(status.message);
    } else {
      clearError();
    }
    return status;
  } catch (error) {
    ollamaReady = false;
    statusIndicator.className = 'status-dot status-dot--offline';
    statusMessage.textContent = error.message;
    localModelLabel.textContent = 'Локальная LLM · статус недоступен';
    showError(error.message);
    throw error;
  } finally {
    setControlsDisabled(false);
  }
}

async function restoreSessionFromStorage() {
  const savedSessionId = localStorage.getItem(SESSION_STORAGE_KEY);
  if (!savedSessionId) {
    return;
  }

  try {
    const data = await apiFetch(`/api/local-llm/agent/history?sessionId=${encodeURIComponent(savedSessionId)}`);
    sessionId = data.sessionId || savedSessionId;
    for (const message of data.messages || []) {
      const role = message.role === 'user' ? 'user' : 'assistant';
      appendMessage(role, message.content);
    }
  } catch {
    persistSessionId(null);
    sessionId = null;
  }
}

function clearMessages() {
  messagesEl.innerHTML = `
    <div class="messages-empty">
      <div class="messages-empty__icon" aria-hidden="true">💬</div>
      <p>Напишите сообщение — ответ придёт от локальной модели через Ollama</p>
      <p class="messages-empty__hint">Облачные модели не используются</p>
    </div>
  `;
}

function clearEmptyState() {
  const empty = messagesEl.querySelector('.messages-empty');
  if (empty) {
    empty.remove();
  }
}

function resizeInput() {
  promptInput.style.height = 'auto';
  const maxHeight = 120;
  const nextHeight = Math.min(promptInput.scrollHeight, maxHeight);
  promptInput.style.height = `${nextHeight}px`;
  promptInput.style.overflowY = promptInput.scrollHeight > maxHeight ? 'auto' : 'hidden';
}

function scrollToBottom() {
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function getMessageMeta(role) {
  if (role === 'user') {
    return { avatar: 'Вы', label: 'Вы' };
  }
  return { avatar: '🦙', label: 'Ollama' };
}

function createMessageElement(role) {
  clearEmptyState();

  const messageEl = document.createElement('article');
  messageEl.className = `message message--${role}`;

  const { avatar, label } = getMessageMeta(role);

  const avatarEl = document.createElement('div');
  avatarEl.className = 'message__avatar';
  avatarEl.textContent = avatar;
  avatarEl.setAttribute('aria-hidden', 'true');

  const bubbleEl = document.createElement('div');
  bubbleEl.className = 'message__bubble';

  const labelEl = document.createElement('span');
  labelEl.className = 'message__label';
  labelEl.textContent = label;

  const textEl = document.createElement('div');
  textEl.className = 'message__text';

  bubbleEl.append(labelEl, textEl);
  messageEl.append(avatarEl, bubbleEl);
  messagesEl.appendChild(messageEl);

  return textEl;
}

function appendMessage(role, text) {
  const textEl = createMessageElement(role);
  textEl.textContent = text;
  scrollToBottom();
}

async function revealText(textEl, text) {
  textEl.textContent = '';
  for (const char of text) {
    textEl.textContent += char;
    scrollToBottom();
    await sleep(CHAR_DELAY_MS);
  }
}

async function appendMessageGradually(role, text) {
  const textEl = createMessageElement(role);
  await revealText(textEl, text);
}

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function sendMessage(prompt) {
  const requestId = ++activeRequestId;

  setControlsDisabled(true);
  appendMessage('user', prompt);

  const payload = { prompt };
  if (sessionId) {
    payload.sessionId = sessionId;
  }

  try {
    setLoading(true);

    const data = await apiFetch('/api/local-llm/agent/chat', {
      method: 'POST',
      body: JSON.stringify(payload),
    });

    if (requestId !== activeRequestId) {
      return false;
    }

    sessionId = data.sessionId || sessionId;
    persistSessionId(sessionId);
    const assistantReply = data.response || 'Пустой ответ от модели.';

    setLoading(false);
    await appendMessageGradually('assistant', assistantReply);

    if (data.durationMs != null) {
      localModelLabel.textContent = `Локальная LLM · ${data.model} · ${data.durationMs} ms`;
    }
    return true;
  } catch (error) {
    if (requestId !== activeRequestId) {
      return false;
    }

    showError(
      error.message.includes('Failed to fetch')
        ? 'Не удалось связаться с backend. Запустите Spring Boot на порту 8080.'
        : error.message,
    );
    return false;
  } finally {
    if (requestId === activeRequestId) {
      setLoading(false);
      setControlsDisabled(false);
    }
  }
}

async function startNewDialog() {
  clearError();
  activeRequestId += 1;

  const previousSessionId = sessionId;
  sessionId = null;
  persistSessionId(null);
  clearMessages();

  if (previousSessionId) {
    try {
      await apiFetch('/api/local-llm/agent/reset', {
        method: 'POST',
        body: JSON.stringify({ sessionId: previousSessionId }),
      });
    } catch {
      showError('Не удалось сбросить сессию на сервере. Новый диалог начнётся локально.');
    }
  }

  promptInput.value = '';
  resizeInput();
  promptInput.focus();
}

async function sendPrompt() {
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showError('Введите сообщение перед отправкой.');
    return;
  }
  if (!ollamaReady) {
    showError('Ollama недоступен. Запустите Ollama и загрузите модель.');
    await loadStatus();
    return;
  }

  clearError();
  promptInput.value = '';
  resizeInput();
  await sendMessage(prompt);
  promptInput.focus();
}

sendBtn.addEventListener('click', sendPrompt);
newDialogBtn.addEventListener('click', startNewDialog);

promptInput.addEventListener('input', resizeInput);

promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendPrompt();
  }
});

async function init() {
  setControlsDisabled(true);
  try {
    await loadStatus();
    await restoreSessionFromStorage();
  } finally {
    resizeInput();
    promptInput.focus();
  }
}

init();
