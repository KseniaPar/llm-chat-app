const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const newDialogBtn = document.getElementById('new-dialog-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const messagesEl = document.getElementById('messages');

const CHAR_DELAY_MS = 18;
const SESSION_STORAGE_KEY = 'llm-chat-session-id';

let activeRequestId = 0;
let sessionId = null;

function setControlsDisabled(isDisabled) {
  sendBtn.disabled = isDisabled;
  promptInput.disabled = isDisabled;
  newDialogBtn.disabled = isDisabled;
}

function setLoading(isLoading) {
  loadingEl.classList.toggle('hidden', !isLoading);
}

function showError(message) {
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
}

function persistSessionId(value) {
  if (value) {
    localStorage.setItem(SESSION_STORAGE_KEY, value);
  } else {
    localStorage.removeItem(SESSION_STORAGE_KEY);
  }
}

async function restoreSessionFromStorage() {
  const savedSessionId = localStorage.getItem(SESSION_STORAGE_KEY);
  if (!savedSessionId) {
    return;
  }

  try {
    const response = await fetch(
      `/api/agent/history?sessionId=${encodeURIComponent(savedSessionId)}`,
    );

    if (!response.ok) {
      persistSessionId(null);
      return;
    }

    const data = await response.json();
    sessionId = data.sessionId || savedSessionId;

    for (const message of data.messages || []) {
      const role = message.role === 'user' ? 'user' : 'assistant';
      appendMessage(role, message.content);
    }
  } catch {
    sessionId = savedSessionId;
  }
}

function clearError() {
  errorEl.textContent = '';
  errorEl.classList.add('hidden');
}

function clearMessages() {
  messagesEl.innerHTML = `
    <div class="messages-empty">
      <div class="messages-empty__icon" aria-hidden="true">💬</div>
      <p>Начните диалог — напишите сообщение и нажмите Enter</p>
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
  return { avatar: 'AI', label: 'Агент' };
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

async function parseErrorResponse(response) {
  try {
    const data = await response.json();
    return data.error || data.message || `Сервер вернул ошибку: ${response.status}`;
  } catch {
    return `Сервер вернул ошибку: ${response.status}`;
  }
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

    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (requestId !== activeRequestId) {
      return false;
    }

    if (!response.ok) {
      const errorMessage = await parseErrorResponse(response);
      throw new Error(errorMessage);
    }

    const data = await response.json();
    sessionId = data.sessionId || sessionId;
    persistSessionId(sessionId);
    const assistantReply = data.response || 'Пустой ответ от агента.';

    setLoading(false);
    await appendMessageGradually('assistant', assistantReply);
    return true;
  } catch (error) {
    if (requestId !== activeRequestId) {
      return false;
    }

    showError(
      error.message.includes('Failed to fetch')
        ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
        : error.message
    );
    return false;
  } finally {
    if (requestId === activeRequestId) {
      setLoading(false);
      setControlsDisabled(false);
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
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
      await fetch('/api/agent/reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
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

resizeInput();
restoreSessionFromStorage().finally(() => {
  promptInput.focus();
});
