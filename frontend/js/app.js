const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const newDialogBtn = document.getElementById('new-dialog-btn');
const scenarioShortBtn = document.getElementById('scenario-short-btn');
const scenarioLongBtn = document.getElementById('scenario-long-btn');
const scenarioOverflowBtn = document.getElementById('scenario-overflow-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const messagesEl = document.getElementById('messages');
const statsPanel = document.getElementById('stats-panel');
const scenarioTablePanel = document.getElementById('scenario-table-panel');
const tokenCurrent = document.getElementById('token-current');
const tokenHistory = document.getElementById('token-history');
const tokenResponse = document.getElementById('token-response');
const tokenSession = document.getElementById('token-session');
const tokenCost = document.getElementById('token-cost');
const tokenContext = document.getElementById('token-context');
const tokenBar = document.getElementById('token-bar');
const tokenBarFill = document.getElementById('token-bar-fill');
const tokenWarning = document.getElementById('token-warning');
const scenarioTable = document.getElementById('scenario-table');

const SCENARIO_BUTTONS = [scenarioShortBtn, scenarioLongBtn, scenarioOverflowBtn];

const CHAR_DELAY_MS = 18;
const SESSION_STORAGE_KEY = 'llm-chat-session-id';
const OVERFLOW_USER_PREVIEW = 200;

let activeRequestId = 0;
let sessionId = null;
let activeScenarioMeta = null;
let lastScenarioStep = null;

function setControlsDisabled(isDisabled) {
  sendBtn.disabled = isDisabled;
  promptInput.disabled = isDisabled;
  newDialogBtn.disabled = isDisabled;
  for (const btn of SCENARIO_BUTTONS) {
    btn.disabled = isDisabled;
  }
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
      <p>Начните диалог или выберите сценарий сверху</p>
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
  if (role === 'info') {
    return { avatar: 'Σ', label: 'Итог' };
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

function appendHtmlMessage(role, html) {
  const textEl = createMessageElement(role);
  textEl.innerHTML = html;
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

function formatTokens(value) {
  if (value == null) {
    return '—';
  }
  return `~${Number(value).toLocaleString('ru-RU')}`;
}

function formatExactTokens(value) {
  if (value == null) {
    return '—';
  }
  return Number(value).toLocaleString('ru-RU');
}

function formatCost(value) {
  if (value == null) {
    return '—';
  }
  const num = Number(value);
  if (num < 0.0001) {
    return `$${num.toFixed(6)}`;
  }
  return `$${num.toFixed(4)}`;
}

function formatContextPercent(used, limit) {
  if (!limit || used <= 0) {
    return '0%';
  }

  const percent = (used / limit) * 100;
  if (percent >= 100) {
    return '100%';
  }
  if (percent >= 10) {
    return `${Math.round(percent)}%`;
  }
  if (percent >= 1) {
    return `${percent.toFixed(1)}%`;
  }
  if (percent >= 0.01) {
    return `${percent.toFixed(2)}%`;
  }
  return '<0.01%';
}

function contextBarWidth(used, limit) {
  if (!limit || used <= 0) {
    return 0;
  }
  const percent = Math.min(100, (used / limit) * 100);
  return Math.max(percent, 0.5);
}

function updateTokenPanel(tokens, showPanel = true) {
  if (!tokens) {
    statsPanel.classList.add('hidden');
    return;
  }

  tokenCurrent.textContent = formatTokens(tokens.currentPromptTokens);
  tokenHistory.textContent = formatTokens(tokens.historyTokens);
  tokenResponse.textContent = formatExactTokens(tokens.responseTokens);
  tokenSession.textContent = formatExactTokens(tokens.sessionTotalTokens);

  const promptActual = tokens.promptTokensActual || tokens.requestTokensEstimate || 0;
  const limit = tokens.modelContextLimit || 1;
  const usedPercentLabel = formatContextPercent(promptActual, limit);
  const usedPercentBar = contextBarWidth(promptActual, limit);

  tokenCost.textContent = formatCost(tokens.sessionCostUsd);
  tokenContext.textContent = `${formatExactTokens(promptActual)} / ${formatExactTokens(limit)} (${usedPercentLabel})`;

  tokenBar.classList.remove('hidden');
  tokenBarFill.style.width = `${usedPercentBar}%`;
  tokenBarFill.classList.toggle('token-bar__fill--warn', tokens.nearContextLimit);
  tokenBarFill.classList.toggle('token-bar__fill--danger', tokens.contextOverflow);

  if (tokens.nearContextLimit || tokens.contextOverflow) {
    tokenWarning.textContent = tokens.contextOverflow
      ? 'Контекст переполнен — запрос не помещается в окно модели.'
      : 'Контекст почти заполнен — скоро старые сообщения перестанут помещаться в окно модели.';
    tokenWarning.classList.remove('hidden');
  } else {
    tokenWarning.classList.add('hidden');
    tokenWarning.textContent = '';
  }

  if (showPanel) {
    statsPanel.classList.remove('hidden');
  }
}

function tokenPanelFromStep(step, modelContextLimit, failed) {
  if (!step) {
    return null;
  }
  const limit = modelContextLimit || 1;
  const used = step.requestTokens || 0;
  return {
    currentPromptTokens: step.currentPromptTokens,
    historyTokens: step.historyTokens,
    requestTokensEstimate: step.requestTokens,
    promptTokensActual: step.requestTokens,
    responseTokens: step.responseTokens,
    sessionTotalTokens: step.sessionTotalTokens,
    sessionCostUsd: step.sessionCostUsd,
    modelContextLimit: limit,
    nearContextLimit: used >= limit * 0.85,
    contextOverflow: failed || used > limit,
  };
}

function resetTokenPanel() {
  tokenCurrent.textContent = '—';
  tokenHistory.textContent = '—';
  tokenResponse.textContent = '—';
  tokenSession.textContent = '—';
  tokenCost.textContent = '—';
  tokenContext.textContent = '—';
  tokenBar.classList.add('hidden');
  tokenBarFill.style.width = '0%';
  tokenWarning.classList.add('hidden');
  tokenWarning.textContent = '';
  statsPanel.classList.add('hidden');
}

function clearScenarioOutput() {
  if (scenarioTable) {
    scenarioTable.innerHTML = '';
  }
  statsPanel?.classList.add('hidden');
  scenarioTablePanel?.classList.add('hidden');
}

function initScenarioOutput() {
  initScenarioTable();
  statsPanel.classList.remove('hidden');
  scenarioTablePanel?.classList.remove('hidden');
}

async function startNewDialog() {
  activeRequestId += 1;
  clearError();

  const previousSessionId = sessionId;
  sessionId = null;
  persistSessionId(null);
  activeScenarioMeta = null;
  lastScenarioStep = null;

  clearMessages();
  clearScenarioOutput();
  resetTokenPanel();

  if (previousSessionId) {
    try {
      await fetch('/api/agent/reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: previousSessionId }),
      });
    } catch {
      // сброс на сервере не критичен для UI
    }
  }
}

async function parseErrorResponse(response) {
  try {
    const data = await response.json();
    if (data.openRouterError && data.error) {
      return data.error;
    }
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

    updateTokenPanel(data.tokens);

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

function truncateForDisplay(text, maxLength = OVERFLOW_USER_PREVIEW) {
  if (!text || text.length <= maxLength) {
    return text || '';
  }
  return `${text.slice(0, maxLength)}… (ещё ${text.length - maxLength} символов)`;
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function renderTokenTableRow(step) {
  return `
    <tr>
      <td>${step.turn}</td>
      <td>${step.currentPromptTokens}</td>
      <td>${step.historyTokens}</td>
      <td>${step.requestTokens}</td>
      <td>${step.responseTokens}</td>
      <td>${step.sessionTotalTokens}</td>
      <td>${formatCost(step.sessionCostUsd)}</td>
    </tr>
  `;
}

function initScenarioTable() {
  if (!scenarioTable) {
    return;
  }
  scenarioTable.innerHTML = `
    <div class="scenario-table-wrap">
      <table class="scenario-table">
        <thead>
          <tr>
            <th>Ход</th>
            <th>Запрос</th>
            <th>История</th>
            <th>Промпт</th>
            <th>Ответ</th>
            <th>Сессия</th>
            <th>Стоимость</th>
          </tr>
        </thead>
        <tbody></tbody>
      </table>
    </div>
  `;
}

function appendScenarioTableRow(step) {
  const tbody = scenarioTable?.querySelector('.scenario-table tbody');
  if (!tbody) {
    return;
  }
  tbody.insertAdjacentHTML('beforeend', renderTokenTableRow(step));
  scenarioTablePanel?.classList.remove('hidden');
}

function parseSseEvent(chunk) {
  const dataLines = chunk
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trim());

  if (!dataLines.length) {
    return null;
  }

  return JSON.parse(dataLines.join('\n'));
}

async function consumeScenarioStream(scenario, signal) {
  const response = await fetch(
    `/api/agent/token-scenario/stream?scenario=${encodeURIComponent(scenario)}`,
    {
      signal,
      headers: { Accept: 'text/event-stream' },
    },
  );

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response));
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });

    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const chunk = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const event = parseSseEvent(chunk);
      if (event) {
        await handleScenarioStreamEvent(event);
      }
      boundary = buffer.indexOf('\n\n');
    }
  }
}

async function handleScenarioStreamEvent(event) {
  switch (event.event) {
    case 'start':
      activeScenarioMeta = {
        id: event.id,
        modelContextLimit: event.modelContextLimit,
      };
      lastScenarioStep = null;
      clearMessages();
      clearScenarioOutput();
      initScenarioOutput();
      setLoading(false);
      break;

    case 'user': {
      const userText = activeScenarioMeta?.id === 'overflow'
        ? truncateForDisplay(event.content)
        : event.content;
      appendMessage('user', userText);
      break;
    }

    case 'turn':
      await appendMessageGradually('assistant', event.content || '');
      lastScenarioStep = event.step;
      updateTokenPanel(
        tokenPanelFromStep(
          event.step,
          activeScenarioMeta?.modelContextLimit,
          false,
        ),
        true,
      );
      appendScenarioTableRow(event.step);
      break;

    case 'done':
      if (event.liveApiError && activeScenarioMeta?.id !== 'overflow') {
        showError(`OpenRouter HTTP ${event.liveApiStatusCode || '—'}: ${event.liveApiError}`);
      }
      scenarioTablePanel?.classList.remove('hidden');
      if (lastScenarioStep) {
        updateTokenPanel(
          tokenPanelFromStep(
            lastScenarioStep,
            activeScenarioMeta?.modelContextLimit,
            Boolean(event.failed),
          ),
          true,
        );
      }
      break;

    default:
      break;
  }
}

async function loadTokenScenario(scenario) {
  clearError();
  activeRequestId += 1;
  const requestId = activeRequestId;
  sessionId = null;
  persistSessionId(null);
  activeScenarioMeta = null;
  lastScenarioStep = null;

  clearMessages();
  clearScenarioOutput();
  resetTokenPanel();

  setControlsDisabled(true);
  setLoading(true);

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15 * 60 * 1000);

  try {
    await consumeScenarioStream(scenario, controller.signal);
    if (requestId === activeRequestId) {
      clearError();
    }
  } catch (error) {
    if (requestId !== activeRequestId) {
      return;
    }

    showError(
      error.name === 'AbortError'
        ? 'Сценарий прерван по таймауту. Попробуйте снова.'
        : error.message,
    );
  } finally {
    clearTimeout(timeoutId);
    if (requestId === activeRequestId) {
      setLoading(false);
      setControlsDisabled(false);
    }
  }
}

sendBtn.addEventListener('click', sendPrompt);
newDialogBtn.addEventListener('click', startNewDialog);
scenarioShortBtn.addEventListener('click', () => loadTokenScenario('short'));
scenarioLongBtn.addEventListener('click', () => loadTokenScenario('long'));
scenarioOverflowBtn.addEventListener('click', () => loadTokenScenario('overflow'));

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
