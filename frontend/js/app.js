const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const autoBtn = document.getElementById('auto-btn');
const stopBtn = document.getElementById('stop-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const messagesEl = document.getElementById('messages');

const CHAR_DELAY_MS = 18;
const SIMULATOR_GOAL = 'Чередовать советы по задаче и ненавязчивые проверки памяти агента (имя, задача, детали из истории)';

let activeRequestId = 0;
let autoDialogRequestId = 0;
let sessionId = null;
let autoDialogAbort = false;

function setControlsDisabled(isDisabled) {
  sendBtn.disabled = isDisabled;
  promptInput.disabled = isDisabled;
  autoBtn.disabled = isDisabled;
}

function setAutoDialogRunning(isRunning) {
  autoBtn.classList.toggle('hidden', isRunning);
  stopBtn.classList.toggle('hidden', !isRunning);
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

async function revealText(textEl, text, shouldAbort = () => false) {
  textEl.textContent = '';

  for (const char of text) {
    if (shouldAbort()) {
      textEl.textContent = text;
      scrollToBottom();
      return false;
    }

    textEl.textContent += char;
    scrollToBottom();
    await sleep(CHAR_DELAY_MS);
  }

  return true;
}

async function appendMessageGradually(role, text, shouldAbort = () => false) {
  const textEl = createMessageElement(role);
  return revealText(textEl, text, shouldAbort);
}

async function typeInInput(text) {
  promptInput.value = '';
  resizeInput();

  for (const char of text) {
    if (autoDialogAbort) {
      return false;
    }

    promptInput.value += char;
    resizeInput();
    await sleep(CHAR_DELAY_MS);
  }

  return true;
}

async function sendSimulatedUserMessage(text) {
  const typed = await typeInInput(text);
  if (!typed) {
    return false;
  }

  promptInput.value = '';
  resizeInput();
  appendMessage('user', text);
  return true;
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

async function fetchSimulatorNext(currentSessionId) {
  const payload = { goal: SIMULATOR_GOAL };
  if (currentSessionId) {
    payload.sessionId = currentSessionId;
  }

  const response = await fetch('/api/simulator/next', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const errorMessage = await parseErrorResponse(response);
    throw new Error(errorMessage);
  }

  return response.json();
}

async function fetchAgentReply(prompt, currentSessionId) {
  const payload = { prompt, sessionId: currentSessionId };

  const response = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const errorMessage = await parseErrorResponse(response);
    throw new Error(errorMessage);
  }

  return response.json();
}

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function runAutoDialog() {
  clearError();
  autoDialogAbort = false;
  sessionId = null;
  const requestId = ++autoDialogRequestId;
  setAutoDialogRunning(true);
  setControlsDisabled(true);

  try {
    while (!autoDialogAbort && requestId === autoDialogRequestId) {
      const simData = await fetchSimulatorNext(sessionId);

      if (autoDialogAbort || requestId !== autoDialogRequestId) {
        break;
      }

      sessionId = simData.sessionId || sessionId;

      if (!simData.userMessage) {
        continue;
      }

      const sent = await sendSimulatedUserMessage(simData.userMessage);
      if (!sent || autoDialogAbort || requestId !== autoDialogRequestId) {
        break;
      }

      setLoading(true);
      const agentData = await fetchAgentReply(simData.userMessage, sessionId);
      setLoading(false);

      sessionId = agentData.sessionId || sessionId;
      const assistantReply = agentData.response || 'Пустой ответ от агента.';
      await appendMessageGradually(
        'assistant',
        assistantReply,
        () => autoDialogAbort || requestId !== autoDialogRequestId,
      );

      if (autoDialogAbort || requestId !== autoDialogRequestId) {
        break;
      }
    }
  } catch (error) {
    showError(
      error.message.includes('Failed to fetch')
        ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
        : error.message
    );
  } finally {
    autoDialogAbort = false;
    setLoading(false);
    setAutoDialogRunning(false);
    setControlsDisabled(false);
    promptInput.value = '';
    resizeInput();
    promptInput.focus();
  }
}

function stopAutoDialog() {
  autoDialogAbort = true;
  autoDialogRequestId += 1;
  activeRequestId += 1;
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
autoBtn.addEventListener('click', runAutoDialog);
stopBtn.addEventListener('click', stopAutoDialog);

promptInput.addEventListener('input', resizeInput);

promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendPrompt();
  }
});

resizeInput();
promptInput.focus();
