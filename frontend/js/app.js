const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const newDialogBtn = document.getElementById('new-dialog-btn');
const scenarioShortBtn = document.getElementById('scenario-short-btn');
const scenarioLongBtn = document.getElementById('scenario-long-btn');
const scenarioOverflowBtn = document.getElementById('scenario-overflow-btn');
const compareCompressionBtn = document.getElementById('compare-compression-btn');
const compareStrategiesBtn = document.getElementById('compare-strategies-btn');
const compressionToggleBtn = document.getElementById('compression-toggle-btn');
const strategyWindowBtn = document.getElementById('strategy-window-btn');
const strategyFactsBtn = document.getElementById('strategy-facts-btn');
const strategyBranchBtn = document.getElementById('strategy-branch-btn');
const branchControls = document.getElementById('branch-controls');
const branchCheckpointBtn = document.getElementById('branch-checkpoint-btn');
const branchCreateBtn = document.getElementById('branch-create-btn');
const branchTabs = document.getElementById('branch-tabs');
const strategyInfo = document.getElementById('strategy-info');
const factsPanel = document.getElementById('facts-panel');
const factsTable = document.getElementById('facts-table');
const comparePanelTitle = document.getElementById('compare-panel-title');
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
const compressionInfo = document.getElementById('compression-info');
const scenarioTable = document.getElementById('scenario-table');
const comparePanel = document.getElementById('compare-panel');
const comparePanelDesc = document.getElementById('compare-panel-desc');
const compareSummary = document.getElementById('compare-summary');
const compareRawCard = document.getElementById('compare-raw-card');
const compareCompressedCard = document.getElementById('compare-compressed-card');
const compareStrategySliding = document.getElementById('compare-strategy-sliding');
const compareStrategyFacts = document.getElementById('compare-strategy-facts');
const compareStrategyBranching = document.getElementById('compare-strategy-branching');

const SCENARIO_BUTTONS = [
  scenarioShortBtn,
  scenarioLongBtn,
  scenarioOverflowBtn,
  compareCompressionBtn,
  compareStrategiesBtn,
];

const STRATEGY_BUTTONS = [strategyWindowBtn, strategyFactsBtn, strategyBranchBtn];

const STRATEGY_LABELS = {
  SLIDING_WINDOW: 'Sliding Window',
  STICKY_FACTS: 'Sticky Facts',
  BRANCHING: 'Branching',
};

const CHAR_DELAY_MS = 18;
const SESSION_STORAGE_KEY = 'llm-chat-session-id';
const COMPRESSION_STORAGE_KEY = 'llm-chat-compression-enabled';
const STRATEGY_STORAGE_KEY = 'llm-chat-context-strategy';
const OVERFLOW_USER_PREVIEW = 200;

let activeRequestId = 0;
let sessionId = null;
let compressionEnabled = localStorage.getItem(COMPRESSION_STORAGE_KEY) !== 'false';
let contextStrategy = localStorage.getItem(STRATEGY_STORAGE_KEY) || 'SLIDING_WINDOW';
let activeBranchId = null;
let branches = [];
let forkMessageIndex = -1;
let activeScenarioMeta = null;
let lastScenarioStep = null;

function isDay10Strategy() {
  return Boolean(contextStrategy);
}

function setControlsDisabled(isDisabled) {
  sendBtn.disabled = isDisabled;
  promptInput.disabled = isDisabled;
  newDialogBtn.disabled = isDisabled;
  compressionToggleBtn.disabled = isDisabled || isDay10Strategy();
  for (const btn of SCENARIO_BUTTONS) {
    btn.disabled = isDisabled;
  }
  for (const btn of STRATEGY_BUTTONS) {
    if (btn) btn.disabled = isDisabled;
  }
  if (branchCheckpointBtn) branchCheckpointBtn.disabled = isDisabled || contextStrategy !== 'BRANCHING';
  if (branchCreateBtn) {
    branchCreateBtn.disabled = isDisabled || contextStrategy !== 'BRANCHING' || forkMessageIndex < 0 || branches.length > 0;
  }
}

function updateCompressionToggleUi() {
  compressionToggleBtn.textContent = compressionEnabled ? 'Сжатие: вкл' : 'Сжатие: выкл';
  compressionToggleBtn.classList.toggle('chat-header__action--active', compressionEnabled);
  compressionToggleBtn.classList.toggle('hidden', isDay10Strategy());
}

function updateStrategyUi() {
  for (const btn of STRATEGY_BUTTONS) {
    if (!btn) continue;
    btn.classList.toggle('chat-header__action--active', btn.dataset.strategy === contextStrategy);
  }
  branchControls?.classList.toggle('hidden', contextStrategy !== 'BRANCHING');
  factsPanel?.classList.toggle('hidden', contextStrategy !== 'STICKY_FACTS');
  updateCompressionToggleUi();
  renderBranchTabs();
}

function selectStrategy(strategy) {
  contextStrategy = strategy;
  localStorage.setItem(STRATEGY_STORAGE_KEY, strategy);
  activeBranchId = null;
  branches = [];
  forkMessageIndex = -1;
  updateStrategyUi();
}

function renderFactsPanel(facts) {
  if (!factsTable || contextStrategy !== 'STICKY_FACTS') {
    return;
  }
  const entries = Object.entries(facts || {});
  if (entries.length === 0) {
    factsTable.innerHTML = '<p class="facts-panel__empty">Факты появятся после первого сообщения</p>';
    factsPanel?.classList.remove('hidden');
    return;
  }
  factsTable.innerHTML = entries
    .map(
      ([key, value]) =>
        `<div class="facts-panel__row"><span class="facts-panel__key">${escapeHtml(key)}</span><span class="facts-panel__value">${escapeHtml(value)}</span></div>`,
    )
    .join('');
  factsPanel?.classList.remove('hidden');
}

function renderBranchTabs() {
  if (!branchTabs) return;
  if (contextStrategy !== 'BRANCHING' || branches.length === 0) {
    branchTabs.innerHTML = '';
    return;
  }
  branchTabs.innerHTML = branches
    .map(
      (branch) =>
        `<button type="button" class="branch-tabs__btn${branch.branchId === activeBranchId ? ' branch-tabs__btn--active' : ''}" data-branch-id="${escapeHtml(branch.branchId)}">${escapeHtml(branch.label)}</button>`,
    )
    .join('');
  branchTabs.querySelectorAll('.branch-tabs__btn').forEach((btn) => {
    btn.addEventListener('click', () => switchBranch(btn.dataset.branchId));
  });
}

async function reloadHistory() {
  if (!sessionId) return;
  try {
    const response = await fetch(`/api/agent/history?sessionId=${encodeURIComponent(sessionId)}`);
    if (!response.ok) return;
    const data = await response.json();
    clearMessages();
    branches = data.branches || [];
    activeBranchId = data.activeBranchId || activeBranchId;
    forkMessageIndex = data.forkMessageIndex ?? forkMessageIndex;
    for (const message of data.messages || []) {
      if (message.role === 'summary') {
        appendMessage('summary', `Summary: ${message.content}`);
        continue;
      }
      if (message.role === 'facts') {
        appendMessage('facts', message.content);
        continue;
      }
      const role = message.role === 'user' ? 'user' : 'assistant';
      appendMessage(role, message.content);
    }
    renderFactsPanel(data.facts);
    renderBranchTabs();
    if (branchCreateBtn) {
      branchCreateBtn.disabled = forkMessageIndex < 0 || branches.length > 0;
    }
  } catch {
    // ignore
  }
}

async function createBranchCheckpoint() {
  if (!sessionId) {
    showError('Сначала начните диалог.');
    return;
  }
  clearError();
  try {
    const response = await fetch('/api/agent/branch/checkpoint', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId }),
    });
    if (!response.ok) throw new Error(await parseErrorResponse(response));
    const data = await response.json();
    forkMessageIndex = data.forkMessageIndex;
    appendMessage('info', `Checkpoint создан на сообщении ${forkMessageIndex}`);
    if (branchCreateBtn) branchCreateBtn.disabled = false;
  } catch (error) {
    showError(error.message);
  }
}

async function createBranches() {
  if (!sessionId) return;
  clearError();
  try {
    const response = await fetch('/api/agent/branch/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId }),
    });
    if (!response.ok) throw new Error(await parseErrorResponse(response));
    const data = await response.json();
    branches = data.branches || [];
    activeBranchId = data.activeBranchId;
    appendMessage('info', `Созданы ветки: ${branches.map((b) => b.label).join(', ')}`);
    await reloadHistory();
  } catch (error) {
    showError(error.message);
  }
}

async function switchBranch(branchId) {
  if (!sessionId || !branchId) return;
  clearError();
  try {
    const response = await fetch('/api/agent/branch/switch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, branchId }),
    });
    if (!response.ok) throw new Error(await parseErrorResponse(response));
    activeBranchId = branchId;
    appendMessage('info', `Переключено на ветку: ${branches.find((b) => b.branchId === branchId)?.label || branchId}`);
    await reloadHistory();
  } catch (error) {
    showError(error.message);
  }
}

function toggleCompression() {
  compressionEnabled = !compressionEnabled;
  localStorage.setItem(COMPRESSION_STORAGE_KEY, String(compressionEnabled));
  updateCompressionToggleUi();
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
    contextStrategy = data.contextStrategy || contextStrategy;
    branches = data.branches || [];
    activeBranchId = data.activeBranchId || null;
    forkMessageIndex = data.forkMessageIndex ?? -1;

    for (const message of data.messages || []) {
      if (message.role === 'summary') {
        appendMessage('summary', `Summary: ${message.content}`);
        continue;
      }
      if (message.role === 'facts') {
        appendMessage('facts', message.content);
        continue;
      }
      const role = message.role === 'user' ? 'user' : 'assistant';
      appendMessage(role, message.content);
    }
    renderFactsPanel(data.facts);
    updateStrategyUi();
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
  if (role === 'summary') {
    return { avatar: 'Σ', label: 'Summary' };
  }
  if (role === 'facts') {
    return { avatar: 'F', label: 'Facts' };
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
  const limit = tokens.modelContextLimit > 0 ? tokens.modelContextLimit : 128000;
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

  if (tokens.compressionApplied) {
    compressionInfo.textContent = `История сжата: ${tokens.messagesSummarized} сообщ. → summary (~${formatExactTokens(tokens.summaryTokens)} токенов)`;
    compressionInfo.classList.remove('hidden');
  } else if (tokens.summaryPreview) {
    compressionInfo.textContent = `В контексте summary (~${formatExactTokens(tokens.summaryTokens)} токенов)`;
    compressionInfo.classList.remove('hidden');
  } else if (tokens.compressionEnabled) {
    compressionInfo.textContent = 'Сжатие включено — summary появится каждые 10 сообщений.';
    compressionInfo.classList.remove('hidden');
  } else {
    compressionInfo.classList.add('hidden');
    compressionInfo.textContent = '';
  }

  if (tokens.contextStrategy) {
    strategyInfo.textContent = `Стратегия: ${STRATEGY_LABELS[tokens.contextStrategy] || tokens.contextStrategy} · окно ${tokens.windowSize || '—'} · в контексте ${tokens.messagesInContext || '—'} сообщ. · в store ${tokens.messagesInStore || '—'}`;
    if (tokens.contextStrategy === 'STICKY_FACTS') {
      strategyInfo.textContent += ` · фактов: ${tokens.factsCount || 0} (~${formatExactTokens(tokens.factsTokens)} ток.)`;
    }
    strategyInfo.classList.remove('hidden');
  } else {
    strategyInfo.classList.add('hidden');
    strategyInfo.textContent = '';
  }

  if (showPanel) {
    statsPanel.classList.remove('hidden');
  }
}

function tokenPanelFromStep(step, modelContextLimit, failed) {
  if (!step) {
    return null;
  }
  const limit = modelContextLimit > 0 ? modelContextLimit : 128000;
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
  compressionInfo.classList.add('hidden');
  compressionInfo.textContent = '';
  strategyInfo.classList.add('hidden');
  strategyInfo.textContent = '';
  factsPanel?.classList.add('hidden');
  if (factsTable) factsTable.innerHTML = '';
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
  activeBranchId = null;
  branches = [];
  forkMessageIndex = -1;

  clearMessages();
  clearScenarioOutput();
  clearCompareOutput();
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

  const payload = {
    prompt,
    compressionEnabled: isDay10Strategy() ? false : compressionEnabled,
    contextStrategy,
  };
  if (sessionId) {
    payload.sessionId = sessionId;
  }
  if (contextStrategy === 'BRANCHING' && activeBranchId) {
    payload.branchId = activeBranchId;
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

    if (data.tokens?.contextStrategy === 'STICKY_FACTS' && sessionId) {
      try {
        const histResponse = await fetch(`/api/agent/history?sessionId=${encodeURIComponent(sessionId)}`);
        if (histResponse.ok) {
          const histData = await histResponse.json();
          renderFactsPanel(histData.facts);
        }
      } catch {
        // facts panel optional
      }
    }

    if (data.tokens?.compressionApplied) {
      appendMessage(
        'info',
        `История сжата: ${data.tokens.messagesSummarized} сообщений → summary (~${data.tokens.summaryTokens} токенов)`,
      );
    }

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

function clearCompareOutput() {
  comparePanel?.classList.add('hidden');
  compareSummary?.classList.add('hidden');
  compareRawCard?.classList.add('hidden');
  compareCompressedCard?.classList.add('hidden');
  compareStrategySliding?.classList.add('hidden');
  compareStrategyFacts?.classList.add('hidden');
  compareStrategyBranching?.classList.add('hidden');
  comparePanel?.querySelector('.compare-panel__content')?.classList.remove('compare-panel__content--strategies');
  if (compareSummary) compareSummary.innerHTML = '';
  if (compareRawCard) compareRawCard.innerHTML = '';
  if (compareCompressedCard) compareCompressedCard.innerHTML = '';
  if (compareStrategySliding) compareStrategySliding.innerHTML = '';
  if (compareStrategyFacts) compareStrategyFacts.innerHTML = '';
  if (compareStrategyBranching) compareStrategyBranching.innerHTML = '';
}

function renderCompareMetricRow(label, rawValue, compressedValue, deltaValue, formatter = (v) => v) {
  return `
    <tr>
      <td>${label}</td>
      <td>${formatter(rawValue)}</td>
      <td>${formatter(compressedValue)}</td>
      <td>${formatter(deltaValue)}</td>
    </tr>
  `;
}

function renderVariantCard(container, variant, probeTurn) {
  if (!container || !variant) {
    return;
  }

  const lastStep = variant.steps?.[variant.steps.length - 1];
  const failedClass = variant.failed ? ' compare-card--failed' : '';

  container.className = `compare-card${failedClass}`;
  container.innerHTML = `
    <h3>${escapeHtml(variant.title || variant.mode)}</h3>
    <p class="compare-card__desc">
      Сжатий: ${variant.compressionEvents?.length || 0}
      · история на последнем ходу: ~${lastStep?.historyTokens ?? '—'} токенов
    </p>
    <div class="compare-table-wrap">
      <table class="compare-table">
        <thead>
          <tr>
            <th>Метрика</th>
            <th>Значение</th>
            <th>Ход ${probeTurn}</th>
            <th>Итого</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>История (последний ход)</td>
            <td colspan="3">~${lastStep?.historyTokens ?? '—'} токенов</td>
          </tr>
          <tr>
            <td>Сессия (токены)</td>
            <td colspan="3">${formatExactTokens(lastStep?.sessionTotalTokens)}</td>
          </tr>
          <tr>
            <td>Стоимость сессии</td>
            <td colspan="3">${formatCost(lastStep?.sessionCostUsd)}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="compare-live compare-live--success">
      <h4>Ответ на probe-вопрос (ход ${probeTurn})</h4>
      <p class="compare-live__text">${escapeHtml(variant.probeAnswer || '—')}</p>
    </div>
  `;
  container.classList.remove('hidden');
}

function renderCompareSummary(compare) {
  if (!compareSummary || !compare) {
    return;
  }

  compareSummary.className = 'compare-card';
  compareSummary.innerHTML = `
    <h3>Итог сравнения</h3>
    <div class="compare-table-wrap">
      <table class="compare-table">
        <thead>
          <tr>
            <th>Метрика</th>
            <th>Без сжатия</th>
            <th>Со сжатием</th>
            <th>Экономия</th>
          </tr>
        </thead>
        <tbody>
          ${renderCompareMetricRow(
            'История (ход 20)',
            compare.raw.finalHistoryTokens,
            compare.compressed.finalHistoryTokens,
            compare.historyTokensSaved,
            formatExactTokens,
          )}
          ${renderCompareMetricRow(
            'Сессия (токены)',
            compare.raw.sessionTotalTokens,
            compare.compressed.sessionTotalTokens,
            compare.sessionTokensSaved,
            formatExactTokens,
          )}
          ${renderCompareMetricRow(
            'Стоимость сессии',
            compare.raw.sessionCostUsd,
            compare.compressed.sessionCostUsd,
            compare.sessionCostSavedUsd,
            formatCost,
          )}
          <tr>
            <td>Экономия истории</td>
            <td colspan="3">${compare.historySavingsPercent.toFixed(1)}%</td>
          </tr>
          <tr>
            <td>Экономия сессии</td>
            <td colspan="3">${compare.sessionSavingsPercent.toFixed(1)}%</td>
          </tr>
        </tbody>
      </table>
    </div>
  `;
  compareSummary.classList.remove('hidden');
}

function renderStrategyVariantCard(container, variant, probeTurn) {
  if (!container || !variant) {
    return;
  }

  const lastStep = variant.steps?.[variant.steps.length - 1];
  const failedClass = variant.failed ? ' compare-card--failed' : '';

  container.className = `compare-card${failedClass}`;
  container.innerHTML = `
    <h3>${escapeHtml(variant.title || variant.mode)}</h3>
    <p class="compare-card__desc">
      Фактов: ${variant.factsCount ?? 0}
      · история на последнем ходу: ~${lastStep?.historyTokens ?? '—'} токенов
    </p>
    <div class="compare-table-wrap">
      <table class="compare-table">
        <tbody>
          <tr>
            <td>Сессия (токены)</td>
            <td>${formatExactTokens(lastStep?.sessionTotalTokens)}</td>
          </tr>
          <tr>
            <td>Стоимость сессии</td>
            <td>${formatCost(lastStep?.sessionCostUsd)}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="compare-live compare-live--success">
      <h4>Probe (ход ${probeTurn})</h4>
      <p class="compare-live__text">${escapeHtml(variant.probeAnswer || '—')}</p>
    </div>
    <div class="compare-live">
      <h4>Финальный ответ</h4>
      <p class="compare-live__text">${escapeHtml(variant.finalAnswer || '—')}</p>
    </div>
  `;
  container.classList.remove('hidden');
}

function renderStrategyCompareSummary(compare) {
  if (!compareSummary || !compare?.variants) {
    return;
  }

  const [sliding, facts, branching] = compare.variants;
  compareSummary.className = 'compare-card';
  compareSummary.innerHTML = `
    <h3>Итог: 3 стратегии на сценарии «Сбор ТЗ»</h3>
    <div class="compare-table-wrap">
      <table class="compare-table">
        <thead>
          <tr>
            <th>Метрика</th>
            <th>Sliding Window</th>
            <th>Sticky Facts</th>
            <th>Branching</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>Токены сессии</td>
            <td>${formatExactTokens(sliding?.sessionTotalTokens)}</td>
            <td>${formatExactTokens(facts?.sessionTotalTokens)}</td>
            <td>${formatExactTokens(branching?.sessionTotalTokens)}</td>
          </tr>
          <tr>
            <td>Стоимость</td>
            <td>${formatCost(sliding?.sessionCostUsd)}</td>
            <td>${formatCost(facts?.sessionCostUsd)}</td>
            <td>${formatCost(branching?.sessionCostUsd)}</td>
          </tr>
          <tr>
            <td>Фактов (Facts)</td>
            <td>—</td>
            <td>${facts?.factsCount ?? 0}</td>
            <td>—</td>
          </tr>
          <tr>
            <td>Стабильность (эвристика)</td>
            <td>Низкая — ранние детали теряются</td>
            <td>Высокая — facts сохраняют решения</td>
            <td>Зависит от ветки</td>
          </tr>
        </tbody>
      </table>
    </div>
  `;
  compareSummary.classList.remove('hidden');
}

async function consumeCompressionCompareStream(signal) {
  const response = await fetch('/api/agent/compression-compare/stream', {
    signal,
    headers: { Accept: 'text/event-stream' },
  });

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
        await handleCompressionCompareEvent(event);
      }
      boundary = buffer.indexOf('\n\n');
    }
  }
}

async function handleCompressionCompareEvent(event) {
  switch (event.event) {
    case 'compare_start':
      activeScenarioMeta = {
        id: 'compression',
        modelContextLimit: event.modelContextLimit,
      };
      if (comparePanelTitle) comparePanelTitle.textContent = 'Сравнение сжатия истории';
      comparePanel?.classList.remove('hidden');
      scenarioTablePanel?.classList.add('hidden');
      if (comparePanelDesc) {
        comparePanelDesc.textContent = event.description || 'Сравнение двух прогонов одного диалога';
      }
      clearCompareOutput();
      comparePanel?.classList.remove('hidden');
      setLoading(true);
      break;

    case 'variant_start':
      appendMessage('info', `Запуск: ${event.title}`);
      break;

    case 'user':
      appendMessage('user', event.content);
      break;

    case 'compressed':
      appendMessage(
        'info',
        `Сжатие на ходу ${event.turn}: ${event.messagesSummarized} сообщ. → summary (~${event.summaryTokens} токенов)`,
      );
      break;

    case 'turn':
      await appendMessageGradually('assistant', event.content || '');
      if (event.step) {
        updateTokenPanel(
          tokenPanelFromStep(event.step, activeScenarioMeta?.modelContextLimit, false),
          true,
        );
      }
      break;

    case 'variant_done':
      if (event.variantResult?.mode === 'raw') {
        renderVariantCard(compareRawCard, event.variantResult, event.variantResult.steps?.length ? 15 : 15);
      } else if (event.variantResult?.mode === 'compressed') {
        renderVariantCard(compareCompressedCard, event.variantResult, 15);
      }
      break;

    case 'compare_done':
      renderCompareSummary(event.compareResult);
      renderVariantCard(compareRawCard, event.compareResult?.raw, event.compareResult?.probeTurn || 15);
      renderVariantCard(
        compareCompressedCard,
        event.compareResult?.compressed,
        event.compareResult?.probeTurn || 15,
      );
      setLoading(false);
      break;

    default:
      break;
  }
}

async function loadCompressionCompare() {
  clearError();
  activeRequestId += 1;
  const requestId = activeRequestId;
  sessionId = null;
  persistSessionId(null);
  activeScenarioMeta = null;
  lastScenarioStep = null;

  clearMessages();
  clearScenarioOutput();
  clearCompareOutput();
  resetTokenPanel();

  setControlsDisabled(true);
  setLoading(true);
  statsPanel.classList.remove('hidden');

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 30 * 60 * 1000);

  try {
    await consumeCompressionCompareStream(controller.signal);
    if (requestId === activeRequestId) {
      clearError();
    }
  } catch (error) {
    if (requestId !== activeRequestId) {
      return;
    }
    showError(
      error.name === 'AbortError'
        ? 'Сравнение прервано по таймауту. Попробуйте снова.'
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
  clearCompareOutput();
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

async function consumeStrategyCompareStream(signal) {
  const response = await fetch('/api/agent/strategy-compare/stream', {
    signal,
    headers: { Accept: 'text/event-stream' },
  });

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
        await handleStrategyCompareEvent(event);
      }
      boundary = buffer.indexOf('\n\n');
    }
  }
}

async function handleStrategyCompareEvent(event) {
  switch (event.event) {
    case 'strategy_compare_start':
      activeScenarioMeta = {
        id: 'strategies',
        modelContextLimit: event.modelContextLimit,
      };
      comparePanel?.classList.remove('hidden');
      scenarioTablePanel?.classList.add('hidden');
      if (comparePanelTitle) comparePanelTitle.textContent = 'Сравнение стратегий контекста';
      if (comparePanelDesc) {
        comparePanelDesc.textContent = event.description || 'Сценарий «Сбор ТЗ» на 3 стратегиях';
      }
      clearCompareOutput();
      comparePanel?.querySelector('.compare-panel__content')?.classList.add('compare-panel__content--strategies');
      comparePanel?.classList.remove('hidden');
      setLoading(true);
      break;

    case 'strategy_start':
      appendMessage('info', `[${event.title}] Запуск прогона`);
      break;

    case 'user':
      appendMessage('user', event.content);
      break;

    case 'facts_updated':
      if (event.facts) {
        const factLines = Object.entries(event.facts)
          .map(([k, v]) => `${k}: ${v}`)
          .join('; ');
        appendMessage('info', `Facts обновлены (ход ${event.turn}): ${factLines}`);
      }
      break;

    case 'branch_created':
      appendMessage(
        'info',
        `Ветки созданы на ходу ${event.turn}: ${(event.branches || []).map((b) => b.label).join(', ')}`,
      );
      break;

    case 'turn':
      await appendMessageGradually('assistant', event.content || '');
      if (event.step) {
        updateTokenPanel(
          tokenPanelFromStep(event.step, activeScenarioMeta?.modelContextLimit, false),
          true,
        );
      }
      break;

    case 'strategy_variant_done': {
      const variant = event.strategyVariantResult;
      const probeTurn = 10;
      if (variant?.mode === 'sliding_window') {
        renderStrategyVariantCard(compareStrategySliding, variant, probeTurn);
      } else if (variant?.mode === 'sticky_facts') {
        renderStrategyVariantCard(compareStrategyFacts, variant, probeTurn);
      } else if (variant?.mode === 'branching') {
        renderStrategyVariantCard(compareStrategyBranching, variant, probeTurn);
      }
      break;
    }

    case 'strategy_compare_done':
      renderStrategyCompareSummary(event.strategyCompareResult);
      if (event.strategyCompareResult?.variants) {
        for (const variant of event.strategyCompareResult.variants) {
          const container =
            variant.mode === 'sliding_window'
              ? compareStrategySliding
              : variant.mode === 'sticky_facts'
                ? compareStrategyFacts
                : compareStrategyBranching;
          renderStrategyVariantCard(container, variant, event.strategyCompareResult.probeTurn || 10);
        }
      }
      setLoading(false);
      break;

    default:
      break;
  }
}

async function loadStrategyCompare() {
  clearError();
  activeRequestId += 1;
  const requestId = activeRequestId;
  sessionId = null;
  persistSessionId(null);
  activeScenarioMeta = null;
  lastScenarioStep = null;

  clearMessages();
  clearScenarioOutput();
  clearCompareOutput();
  resetTokenPanel();

  setControlsDisabled(true);
  setLoading(true);
  statsPanel.classList.remove('hidden');

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 45 * 60 * 1000);

  try {
    await consumeStrategyCompareStream(controller.signal);
    if (requestId === activeRequestId) {
      clearError();
    }
  } catch (error) {
    if (requestId !== activeRequestId) {
      return;
    }
    showError(
      error.name === 'AbortError'
        ? 'Сравнение прервано по таймауту. Попробуйте снова.'
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
compressionToggleBtn.addEventListener('click', toggleCompression);
scenarioShortBtn.addEventListener('click', () => loadTokenScenario('short'));
scenarioLongBtn.addEventListener('click', () => loadTokenScenario('long'));
scenarioOverflowBtn.addEventListener('click', () => loadTokenScenario('overflow'));
compareCompressionBtn.addEventListener('click', loadCompressionCompare);
compareStrategiesBtn.addEventListener('click', loadStrategyCompare);
branchCheckpointBtn?.addEventListener('click', createBranchCheckpoint);
branchCreateBtn?.addEventListener('click', createBranches);
for (const btn of STRATEGY_BUTTONS) {
  btn?.addEventListener('click', () => selectStrategy(btn.dataset.strategy));
}

promptInput.addEventListener('input', resizeInput);

promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendPrompt();
  }
});

resizeInput();
updateStrategyUi();
restoreSessionFromStorage().finally(() => {
  promptInput.focus();
});
