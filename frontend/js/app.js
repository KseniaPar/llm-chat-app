const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const newDialogBtn = document.getElementById('new-dialog-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const messagesEl = document.getElementById('messages');
const statsPanel = document.getElementById('stats-panel');
const tokenCurrent = document.getElementById('token-current');
const tokenHistory = document.getElementById('token-history');
const tokenResponse = document.getElementById('token-response');
const tokenSession = document.getElementById('token-session');
const tokenCost = document.getElementById('token-cost');
const tokenContext = document.getElementById('token-context');
const tokenBar = document.getElementById('token-bar');
const tokenBarFill = document.getElementById('token-bar-fill');
const tokenWarning = document.getElementById('token-warning');
const authOverlay = document.getElementById('auth-overlay');
const appMain = document.getElementById('app-main');
const authForm = document.getElementById('auth-form');
const authUsername = document.getElementById('auth-username');
const authPassword = document.getElementById('auth-password');
const authError = document.getElementById('auth-error');
const authSubmit = document.getElementById('auth-submit');
const authTabLogin = document.getElementById('auth-tab-login');
const authTabRegister = document.getElementById('auth-tab-register');
const authUserLabel = document.getElementById('auth-user-label');
const logoutBtn = document.getElementById('logout-btn');
const memoryPanel = document.getElementById('memory-panel');
const memoryTabShort = document.getElementById('memory-tab-short');
const memoryTabWorking = document.getElementById('memory-tab-working');
const memoryTabLong = document.getElementById('memory-tab-long');
const memoryLogsEl = document.getElementById('memory-logs');
const profileForm = document.getElementById('profile-form');
const profileDisplayName = document.getElementById('profile-display-name');
const profileResponseStyle = document.getElementById('profile-response-style');
const profileResponseFormat = document.getElementById('profile-response-format');
const profileConstraints = document.getElementById('profile-constraints');
const profileActiveSummary = document.getElementById('profile-active-summary');
const profileStatus = document.getElementById('profile-status');
const taskPanel = document.getElementById('task-panel');
const taskPanelEmpty = document.getElementById('task-panel-empty');
const taskPanelContent = document.getElementById('task-panel-content');
const taskPhaseBadge = document.getElementById('task-phase-badge');
const taskTitleEl = document.getElementById('task-title');
const taskCurrentStep = document.getElementById('task-current-step');
const taskExpectedAction = document.getElementById('task-expected-action');
const taskPausedLabel = document.getElementById('task-paused-label');
const taskPauseBtn = document.getElementById('task-pause-btn');
const taskResumeBtn = document.getElementById('task-resume-btn');
const transitionsPanel = document.getElementById('transitions-panel');
const transitionsAllowedBadge = document.getElementById('transitions-allowed-badge');
const transitionsAllowedEl = document.getElementById('transitions-allowed');
const transitionsList = document.getElementById('transitions-list');
const transitionsEmpty = document.getElementById('transitions-empty');
const invariantsList = document.getElementById('invariants-list');
const invariantsCountBadge = document.getElementById('invariants-count-badge');

const CHAR_DELAY_MS = 18;
const SESSION_STORAGE_KEY = 'llm-chat-session-id';
const JWT_STORAGE_KEY = 'llm-chat-jwt';
const AUTH_USER_STORAGE_KEY = 'llm-chat-username';

let authMode = 'login';
let authToken = localStorage.getItem(JWT_STORAGE_KEY);
let authUsernameValue = localStorage.getItem(AUTH_USER_STORAGE_KEY);
let activeRequestId = 0;
let sessionId = null;

function getAuthToken() {
  const stored = localStorage.getItem(JWT_STORAGE_KEY);
  if (stored && stored !== 'null' && stored !== 'undefined') {
    authToken = stored;
    return stored;
  }
  authToken = null;
  return null;
}

function getAuthHeaders(extraHeaders = {}) {
  const headers = { ...extraHeaders };
  if (!headers['Content-Type'] && !headers['content-type']) {
    headers['Content-Type'] = 'application/json';
  }
  const token = getAuthToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

async function apiFetch(url, options = {}) {
  const headers = getAuthHeaders(options.headers || {});
  const response = await fetch(url, { ...options, headers });
  const token = getAuthToken();
  if ((response.status === 401 || response.status === 403) && token) {
    const message = await parseErrorResponse(response);
    forceReauth(message);
    throw new Error(message);
  }
  return response;
}

async function apiFetchOptional(url, options = {}) {
  const headers = getAuthHeaders(options.headers || {});
  return fetch(url, { ...options, headers });
}

function forceReauth(message) {
  logout();
  if (authError) {
    authError.textContent = message || 'Сессия истекла. Войдите снова.';
    authError.classList.remove('hidden');
  }
}

function showAuthOverlay() {
  authOverlay?.classList.remove('hidden');
  appMain?.classList.add('hidden');
}

function hideAuthOverlay() {
  authOverlay?.classList.add('hidden');
  appMain?.classList.remove('hidden');
  if (authUserLabel && authUsernameValue) {
    authUserLabel.textContent = authUsernameValue;
  }
}

function setAuthMode(mode) {
  authMode = mode;
  authTabLogin?.classList.toggle('auth-card__tab--active', mode === 'login');
  authTabRegister?.classList.toggle('auth-card__tab--active', mode === 'register');
  if (authSubmit) {
    authSubmit.textContent = mode === 'login' ? 'Войти' : 'Зарегистрироваться';
  }
  authError?.classList.add('hidden');
}

async function handleAuthSubmit(event) {
  event.preventDefault();
  authError?.classList.add('hidden');
  const username = authUsername?.value?.trim();
  const password = authPassword?.value;
  if (!username || !password) {
    if (authError) {
      authError.textContent = 'Заполните имя пользователя и пароль.';
      authError.classList.remove('hidden');
    }
    return;
  }
  try {
    const endpoint = authMode === 'login' ? '/api/auth/login' : '/api/auth/register';
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (!response.ok) {
      throw new Error(await parseErrorResponse(response));
    }
    const data = await response.json();
    authToken = data.token;
    authUsernameValue = data.username;
    localStorage.setItem(JWT_STORAGE_KEY, authToken);
    localStorage.setItem(AUTH_USER_STORAGE_KEY, authUsernameValue);
    hideAuthOverlay();
    await Promise.all([restoreSessionFromStorage(), loadProfile(), loadTaskState(), loadTransitions(), loadInvariants()]);
    promptInput?.focus();
  } catch (error) {
    if (authError) {
      authError.textContent = error.message;
      authError.classList.remove('hidden');
    }
  }
}

function logout() {
  authToken = null;
  authUsernameValue = null;
  localStorage.removeItem(JWT_STORAGE_KEY);
  localStorage.removeItem(AUTH_USER_STORAGE_KEY);
  localStorage.removeItem(SESSION_STORAGE_KEY);
  sessionId = null;
  showAuthOverlay();
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function renderMemoryPanel(snapshot, logs, personalizationLogs, profileSnapshot, taskStateLogs, invariantLogs) {
  if (!memoryPanel) return;
  memoryPanel.classList.remove('hidden');

  const shortMessages = snapshot?.shortTermInContext || [];
  memoryTabShort.innerHTML = shortMessages.length
    ? shortMessages
        .map(
          (m) =>
            `<div class="memory-panel__item"><span class="memory-panel__key">${escapeHtml(m.role)}:</span> ${escapeHtml(m.content)}</div>`,
        )
        .join('')
    : '<p>Нет сообщений</p>';

  const workingFacts = snapshot?.workingFactsInContext || {};
  const workingSummary = snapshot?.workingSummaryInContext;
  const workingEntries = Object.entries(workingFacts);
  memoryTabWorking.innerHTML =
    (workingSummary
      ? `<div class="memory-panel__item"><span class="memory-panel__key">summary:</span> ${escapeHtml(workingSummary)}</div>`
      : '') +
    (workingEntries.length
      ? workingEntries
          .map(
            ([key, value]) =>
              `<div class="memory-panel__item"><span class="memory-panel__key">${escapeHtml(key)}:</span> ${escapeHtml(value)}</div>`,
          )
          .join('')
      : workingSummary
        ? ''
        : '<p>Нет рабочих данных</p>');

  const longTerm = snapshot?.longTermInContext || {};
  const longHtml = Object.entries(longTerm)
    .map(([category, entries]) => {
      const rows = Object.entries(entries || {})
        .map(
          ([key, value]) =>
            `<div class="memory-panel__item"><span class="memory-panel__key">${escapeHtml(key)}:</span> ${escapeHtml(value)}</div>`,
        )
        .join('');
      return `<div><strong>${escapeHtml(category)}</strong>${rows || '<p>пусто</p>'}</div>`;
    })
    .join('');
  memoryTabLong.innerHTML = longHtml || '<p>Нет долговременных данных</p>';

  const allLogs = [...(invariantLogs || []), ...(taskStateLogs || []), ...(personalizationLogs || []), ...(logs || [])];
  if (memoryLogsEl) {
    if (profileSnapshot?.appliedToPrompt) {
      allLogs.unshift('Активный профиль применён к запросу');
    }
    memoryLogsEl.innerHTML = allLogs.length
      ? allLogs.map((line) => `<div>${escapeHtml(line)}</div>`).join('')
      : '';
  }
}

function renderTaskPanel(task) {
  if (!taskPanel) return;
  const active = task?.active;
  if (!active) {
    taskPanelEmpty?.classList.remove('hidden');
    taskPanelContent?.classList.add('hidden');
    return;
  }
  taskPanelEmpty?.classList.add('hidden');
  taskPanelContent?.classList.remove('hidden');
  if (taskPhaseBadge) {
    taskPhaseBadge.textContent = task.phaseLabel || task.phase || '—';
    taskPhaseBadge.className = `task-panel__phase task-panel__phase--${task.phase || 'planning'}`;
  }
  if (taskTitleEl) {
    taskTitleEl.textContent = task.taskTitle || '';
    taskTitleEl.classList.toggle('hidden', !task.taskTitle);
  }
  if (taskCurrentStep) taskCurrentStep.textContent = task.currentStep || '—';
  if (taskExpectedAction) taskExpectedAction.textContent = task.expectedAction || '—';
  const paused = !!task.paused;
  taskPausedLabel?.classList.toggle('hidden', !paused);
  taskPauseBtn?.classList.toggle('hidden', paused);
  taskResumeBtn?.classList.toggle('hidden', !paused);
}

async function loadTaskState() {
  if (!sessionId || !getAuthToken()) {
    renderTaskPanel(null);
    return;
  }
  try {
    const response = await apiFetch(`/api/agent/task?sessionId=${encodeURIComponent(sessionId)}`);
    if (!response.ok) return;
    const data = await response.json();
    renderTaskPanel(data.active ? data : null);
  } catch {
    // optional
  }
}

function formatTransitionTime(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch {
    return '—';
  }
}

function renderTransitionsPanel(allowedData, historyData) {
  if (!transitionsPanel) return;
  const allowed = allowedData?.allowedLabels || allowedData?.allowed || [];
  const items = historyData?.transitions || [];

  if (transitionsAllowedBadge) {
    transitionsAllowedBadge.textContent = String(allowed.length);
  }
  if (transitionsAllowedEl) {
    transitionsAllowedEl.innerHTML = allowed.length
      ? allowed.map((label) => `<span class="transitions-panel__chip">${escapeHtml(label)}</span>`).join('')
      : '<span class="transitions-panel__chip transitions-panel__chip--muted">нет активной задачи</span>';
  }

  transitionsEmpty?.classList.toggle('hidden', items.length > 0);
  if (transitionsList) {
    transitionsList.innerHTML = items.length
      ? items
          .map((item) => {
            const statusClass = item.accepted ? 'transitions-panel__item--ok' : 'transitions-panel__item--reject';
            const statusMark = item.accepted ? '✓' : '✗';
            const phaseLine = [item.fromPhase, item.toPhase].filter(Boolean).join(' → ') || '—';
            const codePrefix = !item.accepted && item.rejectionCode ? `${item.rejectionCode}: ` : '';
            const reason = item.rejectionReason
              ? `<p class="transitions-panel__reason">${escapeHtml(item.rejectionReason)}</p>`
              : '';
            return `<li class="transitions-panel__item ${statusClass}">
              <div class="transitions-panel__head">
                <span class="transitions-panel__status">${statusMark}</span>
                <span class="transitions-panel__type">${escapeHtml(item.transitionLabel || item.transitionType || '')}</span>
                <span class="transitions-panel__time">${formatTransitionTime(item.createdAt)}</span>
              </div>
              <p class="transitions-panel__phase">${escapeHtml(codePrefix + phaseLine)}</p>
              ${reason}
            </li>`;
          })
          .join('')
      : '';
  }
}

async function loadTransitions() {
  if (!sessionId || !getAuthToken()) {
    renderTransitionsPanel(null, null);
    return;
  }
  try {
    const [allowedRes, historyRes] = await Promise.all([
      apiFetchOptional(`/api/agent/task/transitions/allowed?sessionId=${encodeURIComponent(sessionId)}`),
      apiFetchOptional(`/api/agent/task/transitions?sessionId=${encodeURIComponent(sessionId)}&limit=30`),
    ]);
    if (allowedRes.status === 401 || allowedRes.status === 403 || historyRes.status === 401 || historyRes.status === 403) {
      renderTransitionsPanel(null, null);
      return;
    }
    const allowedData = allowedRes.ok ? await allowedRes.json() : null;
    const historyData = historyRes.ok ? await historyRes.json() : null;
    renderTransitionsPanel(allowedData, historyData);
  } catch {
    renderTransitionsPanel(null, null);
  }
}

function renderInvariantsPanel(rules) {
  if (!invariantsList) return;
  const items = rules || [];
  if (invariantsCountBadge) {
    invariantsCountBadge.textContent = String(items.length);
  }
  invariantsList.innerHTML = items.length
    ? items
        .map(
          (rule) => `<li class="invariants-panel__item">
          <div class="invariants-panel__head">
            <span class="invariants-panel__id">${escapeHtml(rule.id)}</span>
            ${rule.hardBlock ? '<span class="invariants-panel__tag">hard</span>' : ''}
          </div>
          <strong class="invariants-panel__name">${escapeHtml(rule.title)}</strong>
          <p class="invariants-panel__desc">${escapeHtml(rule.description || '')}</p>
        </li>`,
        )
        .join('')
    : '<li class="invariants-panel__empty">Нет правил</li>';
}

async function loadInvariants() {
  if (!getAuthToken()) return;
  try {
    const response = await apiFetch('/api/agent/invariants');
    if (!response.ok) return;
    renderInvariantsPanel(await response.json());
  } catch {
    // optional
  }
}

async function pauseTask() {
  const result = await postTaskPause();
  if (!result.ok) {
    showError(result.error);
  }
}

async function resumeTask() {
  const result = await postTaskResume();
  if (!result.ok) {
    showError(result.error);
  }
}

function renderProfileSummary(profile) {
  if (!profileActiveSummary) return;
  const parts = [];
  if (profile?.displayName) parts.push(`Имя: ${profile.displayName}`);
  if (profile?.responseStyle) parts.push(`Стиль: ${profile.responseStyle}`);
  if (profile?.responseFormat) parts.push(`Формат: ${profile.responseFormat}`);
  if (profile?.constraints) parts.push(`Ограничения: ${profile.constraints}`);
  if (!parts.length) {
    profileActiveSummary.classList.add('hidden');
    profileActiveSummary.textContent = '';
    return;
  }
  profileActiveSummary.textContent = `Активный профиль: ${parts.join(' · ')}`;
  profileActiveSummary.classList.remove('hidden');
}

function fillProfileForm(profile) {
  if (profileDisplayName) profileDisplayName.value = profile?.displayName || '';
  if (profileResponseStyle) profileResponseStyle.value = profile?.responseStyle || '';
  if (profileResponseFormat) profileResponseFormat.value = profile?.responseFormat || '';
  if (profileConstraints) profileConstraints.value = profile?.constraints || '';
  renderProfileSummary(profile);
}

function showProfileStatus(message, isError = false) {
  if (!profileStatus) return;
  profileStatus.textContent = message;
  profileStatus.classList.toggle('hidden', !message);
  profileStatus.style.color = isError ? '#b91c1c' : '';
}

async function loadProfile() {
  if (!getAuthToken()) return;
  try {
    const response = await apiFetch('/api/user/profile');
    if (!response.ok) return;
    const data = await response.json();
    fillProfileForm(data);
  } catch {
    // optional
  }
}

async function saveProfile(event) {
  event?.preventDefault();
  showProfileStatus('');
  if (!getAuthToken()) {
    showProfileStatus('Сначала войдите в аккаунт.', true);
    showAuthOverlay();
    return;
  }
  const payload = {
    displayName: profileDisplayName?.value?.trim() || null,
    responseStyle: profileResponseStyle?.value || null,
    responseFormat: profileResponseFormat?.value || null,
    constraints: profileConstraints?.value?.trim() || null,
  };
  try {
    const response = await apiFetch('/api/user/profile', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    if (!response.ok) throw new Error(await parseErrorResponse(response));
    const data = await response.json();
    fillProfileForm(data);
    showProfileStatus('Профиль сохранён');
  } catch (error) {
    showProfileStatus(error.message, true);
  }
}

async function loadMemoryPanel() {
  if (!sessionId) return;
  try {
    const response = await apiFetch(`/api/agent/memory?sessionId=${encodeURIComponent(sessionId)}`);
    if (!response.ok) return;
    const data = await response.json();
    renderMemoryPanel(
      {
        shortTermInContext: data.shortTerm?.messages || [],
        workingFactsInContext: data.working?.facts || {},
        workingSummaryInContext: data.working?.summary || null,
        longTermInContext: data.longTerm?.longTerm || {},
      },
      data.memoryLogs || [],
      [],
      null,
      [],
      [],
    );
  } catch {
    // optional
  }
}

function initMemoryTabs() {
  document.querySelectorAll('.memory-panel__tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      const target = tab.dataset.memoryTab;
      document.querySelectorAll('.memory-panel__tab').forEach((btn) => {
        btn.classList.toggle('memory-panel__tab--active', btn.dataset.memoryTab === target);
      });
      memoryTabShort?.classList.toggle('hidden', target !== 'short');
      memoryTabWorking?.classList.toggle('hidden', target !== 'working');
      memoryTabLong?.classList.toggle('hidden', target !== 'long');
    });
  });
}

async function postTaskPause() {
  if (!sessionId) {
    return { ok: false, error: 'Нет активной сессии' };
  }
  try {
    const response = await apiFetch('/api/agent/task/pause', {
      method: 'POST',
      body: JSON.stringify({ sessionId }),
    });
    if (!response.ok) {
      return { ok: false, error: await parseErrorResponse(response) };
    }
    const task = await response.json();
    renderTaskPanel(task);
    await loadTransitions();
    return { ok: true, task };
  } catch (error) {
    return { ok: false, error: error.message };
  }
}

async function postTaskResume() {
  if (!sessionId) {
    return { ok: false, error: 'Нет активной сессии' };
  }
  try {
    const response = await apiFetch('/api/agent/task/resume', {
      method: 'POST',
      body: JSON.stringify({ sessionId }),
    });
    if (!response.ok) {
      return { ok: false, error: await parseErrorResponse(response) };
    }
    const task = await response.json();
    renderTaskPanel(task);
    await loadTransitions();
    return { ok: true, task };
  } catch (error) {
    return { ok: false, error: error.message };
  }
}

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

async function restoreSessionFromStorage() {
  const savedSessionId = localStorage.getItem(SESSION_STORAGE_KEY);
  if (!savedSessionId) return;

  try {
    const response = await apiFetch(`/api/agent/history?sessionId=${encodeURIComponent(savedSessionId)}`);
    if (!response.ok) {
      persistSessionId(null);
      return;
    }
    const data = await response.json();
    sessionId = data.sessionId || savedSessionId;
    clearMessages();
    for (const message of data.messages || []) {
      if (message.role === 'summary' || message.role === 'facts') continue;
      const role = message.role === 'user' ? 'user' : 'assistant';
      appendMessage(role, message.content);
    }
    await loadMemoryPanel();
    await loadTaskState();
  } catch {
    sessionId = savedSessionId;
  }
}

function clearMessages() {
  messagesEl.innerHTML = `
    <div class="messages-empty">
      <div class="messages-empty__icon" aria-hidden="true">💬</div>
      <p>Начните диалог — агент запомнит контекст в трёх слоях памяти</p>
    </div>
  `;
}

function clearEmptyState() {
  const empty = messagesEl.querySelector('.messages-empty');
  if (empty) empty.remove();
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

function createMessageElement(role) {
  clearEmptyState();
  const messageEl = document.createElement('article');
  messageEl.className = `message message--${role}`;

  const avatar = role === 'user' ? 'Вы' : 'AI';
  const label = role === 'user' ? 'Вы' : 'Агент';

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

function formatTokens(value) {
  return value == null ? '—' : `~${Number(value).toLocaleString('ru-RU')}`;
}

function formatExactTokens(value) {
  return value == null ? '—' : Number(value).toLocaleString('ru-RU');
}

function formatCost(value) {
  if (value == null) return '—';
  const num = Number(value);
  return num < 0.0001 ? `$${num.toFixed(6)}` : `$${num.toFixed(4)}`;
}

function formatContextPercent(used, limit) {
  if (!limit || used <= 0) return '0%';
  const percent = (used / limit) * 100;
  if (percent >= 100) return '100%';
  if (percent >= 10) return `${Math.round(percent)}%`;
  if (percent >= 1) return `${percent.toFixed(1)}%`;
  return '<0.01%';
}

function contextBarWidth(used, limit) {
  if (!limit || used <= 0) return 0;
  return Math.max(Math.min(100, (used / limit) * 100), 0.5);
}

function updateTokenPanel(tokens) {
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

  tokenCost.textContent = formatCost(tokens.sessionCostUsd);
  tokenContext.textContent = `${formatExactTokens(promptActual)} / ${formatExactTokens(limit)} (${formatContextPercent(promptActual, limit)})`;

  tokenBar.classList.remove('hidden');
  tokenBarFill.style.width = `${contextBarWidth(promptActual, limit)}%`;
  tokenBarFill.classList.toggle('token-bar__fill--warn', tokens.nearContextLimit);
  tokenBarFill.classList.toggle('token-bar__fill--danger', tokens.contextOverflow);

  if (tokens.nearContextLimit || tokens.contextOverflow) {
    tokenWarning.textContent = tokens.contextOverflow
      ? 'Контекст переполнен — запрос не помещается в окно модели.'
      : 'Контекст почти заполнен.';
    tokenWarning.classList.remove('hidden');
  } else {
    tokenWarning.classList.add('hidden');
  }

  statsPanel.classList.remove('hidden');
}

function resetTokenPanel() {
  statsPanel.classList.add('hidden');
  tokenWarning.classList.add('hidden');
  tokenBar.classList.add('hidden');
}

async function parseErrorResponse(response) {
  try {
    const data = await response.json();
    return data.error || data.message || `Сервер вернул ошибку: ${response.status}`;
  } catch {
    try {
      const text = await response.text();
      if (text) return text;
    } catch {
      // ignore
    }
    if (response.status === 401 || response.status === 403) {
      return 'Требуется авторизация. Войдите снова.';
    }
    return `Сервер вернул ошибку: ${response.status}`;
  }
}

async function startNewDialog() {
  const previousSessionId = sessionId;
  sessionId = null;
  persistSessionId(null);
  clearMessages();
  resetTokenPanel();
  if (memoryTabShort) memoryTabShort.innerHTML = '';
  if (memoryTabWorking) memoryTabWorking.innerHTML = '';
  if (memoryTabLong) memoryTabLong.innerHTML = '';
  if (memoryLogsEl) memoryLogsEl.innerHTML = '';
  renderTaskPanel(null);

  if (previousSessionId) {
    try {
      await apiFetch('/api/agent/reset', {
        method: 'POST',
        body: JSON.stringify({ sessionId: previousSessionId }),
      });
    } catch {
      // не критично
    }
  }
}

async function sendMessage(prompt, options = {}) {
  const requestId = ++activeRequestId;
  setControlsDisabled(true);
  appendMessage('user', prompt);

  const payload = { prompt };
  if (sessionId) payload.sessionId = sessionId;

  try {
    setLoading(true);
    const response = await apiFetch('/api/agent/chat', {
      method: 'POST',
      body: JSON.stringify(payload),
    });

    if (requestId !== activeRequestId) return { ok: false, error: 'Запрос отменён' };
    if (!response.ok) throw new Error(await parseErrorResponse(response));

    const data = await response.json();
    sessionId = data.sessionId || sessionId;
    persistSessionId(sessionId);
    updateTokenPanel(data.tokens);

    if (data.memorySnapshot) {
      renderMemoryPanel(
        data.memorySnapshot,
        data.memoryLogs || [],
        data.personalizationLogs || [],
        data.profileSnapshot || null,
        data.taskStateLogs || [],
        data.invariantLogs || [],
      );
    } else {
      await loadMemoryPanel();
    }
    if (data.invariantsSnapshot?.rules?.length) {
      renderInvariantsPanel(data.invariantsSnapshot.rules);
    } else {
      await loadInvariants();
    }
    if (data.taskStateSnapshot) {
      renderTaskPanel({
        ...data.taskStateSnapshot,
        active: data.taskStateSnapshot.active,
      });
    } else {
      await loadTaskState();
    }
    await loadTransitions();

    setLoading(false);
    if (options.skipTyping) {
      appendMessage('assistant', data.response || 'Пустой ответ.');
    } else {
      await appendMessageGradually('assistant', data.response || 'Пустой ответ.');
    }
    return { ok: true, data };
  } catch (error) {
    if (requestId !== activeRequestId) return { ok: false, error: 'Запрос отменён' };
    const message = error.message.includes('Failed to fetch')
      ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
      : error.message;
    showError(message);
    return { ok: false, error: message };
  } finally {
    if (requestId === activeRequestId) {
      setLoading(false);
      setControlsDisabled(false);
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
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
logoutBtn?.addEventListener('click', logout);
authForm?.addEventListener('submit', handleAuthSubmit);
authTabLogin?.addEventListener('click', () => setAuthMode('login'));
authTabRegister?.addEventListener('click', () => setAuthMode('register'));
profileForm?.addEventListener('submit', saveProfile);
taskPauseBtn?.addEventListener('click', pauseTask);
taskResumeBtn?.addEventListener('click', resumeTask);
promptInput.addEventListener('input', resizeInput);
promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendPrompt();
  }
});

initMemoryTabs();
resizeInput();

if (getAuthToken()) {
  hideAuthOverlay();
  Promise.all([restoreSessionFromStorage(), loadProfile(), loadTaskState(), loadTransitions(), loadInvariants()]).finally(() =>
    promptInput.focus(),
  );
} else {
  showAuthOverlay();
}
